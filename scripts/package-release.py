#!/usr/bin/env python3
"""Package signed release APKs and complete source, without machine files."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import zipfile

VERSION = "0.3.0-alpha.3"
VERSION_CODE = 5000302
ROOT = Path(__file__).resolve().parents[1]
ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_apk(path: Path, certificate_pin: str) -> None:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("Set ANDROID_HOME to verify release APK signatures and manifests.")
    build_tools = Path(sdk) / "build-tools" / os.environ.get("ANDROID_BUILD_TOOLS", "37.0.0")
    signer = build_tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    aapt = build_tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
    signature = subprocess.check_output([str(signer), "verify", "--print-certs", str(path)], text=True)
    certificates = set(re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", signature))
    if {value.lower() for value in certificates} != {certificate_pin}:
        raise RuntimeError(f"APK is not signed exclusively by the permanent release identity: {path.name}")
    manifest = subprocess.check_output([str(aapt), "dump", "badging", str(path)], text=True)
    expected = f"package: name='app.zhuzhalka.vpn' versionCode='{VERSION_CODE}' versionName='{VERSION}'"
    if not manifest.startswith(expected) or "application-debuggable" in manifest:
        raise RuntimeError(f"APK must be the non-debuggable release with the expected package/version: {path.name}")


def package(output: Path) -> None:
    # CI starts from a clean checkout. Never describe modified tracked source as
    # the published commit; ignored build outputs are added explicitly below.
    git("diff", "--exit-code", "--quiet", "HEAD", "--ignore-submodules=none")
    revision = git("rev-parse", "HEAD").decode().strip()
    submodules = git("submodule", "status", "--recursive").decode().splitlines()
    if not submodules or any(line[:1] != " " for line in submodules):
        raise RuntimeError("Initialize every submodule at its recorded commit first.")
    if os.environ.get("GITHUB_SHA", revision) != revision:
        raise RuntimeError("The checkout does not match the workflow's source commit.")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise RuntimeError(f"Output directory must be empty: {output}")
    certificate_pin = (ROOT / "scripts/release-signing-cert.sha256").read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", certificate_pin):
        raise RuntimeError("Invalid permanent signing certificate pin.")

    for abi in ("arm64-v8a", "universal"):
        name = f"Zhuzhalka_{VERSION}_{abi}.apk"
        apk = ROOT / "V2rayNG/app/build/outputs/apk/playstore/release" / name
        if not apk.is_file() or not zipfile.is_zipfile(apk):
            raise RuntimeError(f"Missing or invalid APK: {apk}")
        verify_apk(apk, certificate_pin)
        shutil.copyfile(apk, output / name)

    entries = {}
    for record in git("ls-files", "--stage", "--recurse-submodules", "-z").split(b"\0"):
        if record:
            metadata, encoded_path = record.split(b"\t", 1)
            mode, _, stage = metadata.decode().split()
            if stage != "0" or mode == "160000":
                raise RuntimeError("Source index is unmerged or a submodule is missing.")
            entries[os.fsdecode(encoded_path)] = int(mode, 8)
    native = ["V2rayNG/app/libs/libv2ray.aar"] + [
        f"V2rayNG/app/libs/{abi}/{library}"
        for abi in ABIS
        for library in ("libhev-socks5-tunnel.so", "libhevsockstun.so")
    ]
    for relative in native:
        if not (ROOT / relative).is_file() or (ROOT / relative).stat().st_size == 0:
            raise RuntimeError(f"Missing native build input: {relative}")
        entries[relative] = 0o100644

    timestamp = max(315532800, int(git("show", "-s", "--format=%ct", "HEAD")))
    date_time = time.gmtime(timestamp)[:6]
    prefix = f"Zhuzhalka_{VERSION}_sources/"
    archive = output / f"Zhuzhalka_{VERSION}_sources.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for relative, mode in sorted(entries.items()):
            path = ROOT / relative
            info = zipfile.ZipInfo(prefix + relative, date_time)
            info.create_system = 3
            info.external_attr = mode << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            # Preserve Unix links, including Git for Windows' text link stubs.
            data = os.fsencode(os.readlink(path)) if path.is_symlink() else path.read_bytes()
            bundle.writestr(info, data)
        manifest = {
            "version": VERSION,
            "version_code": VERSION_CODE,
            "source_commit": revision,
            "submodules": submodules,
            "native_sha256": {relative: digest(ROOT / relative) for relative in native},
            "signing": "Permanent private release key; key material is never included in this archive.",
            "signing_certificate_sha256": certificate_pin,
            "build_without_git": "Configure Android SDK, then run Gradle from V2rayNG; native inputs are included.",
        }
        bundle.writestr(prefix + "BUILD_INFO.json", json.dumps(manifest, indent=2) + "\n")
    with zipfile.ZipFile(archive) as bundle:
        if bundle.testzip() is not None:
            raise RuntimeError("Source archive failed its integrity check.")

    checksums = "".join(f"{digest(path)}  {path.name}\n" for path in sorted(output.iterdir()))
    (output / "SHA256SUMS").write_text(checksums, encoding="ascii", newline="\n")
    print(checksums, end="")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    package(parser.parse_args().output.resolve())
