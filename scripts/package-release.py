#!/usr/bin/env python3
"""Package the first beta's APKs and complete source, without machine files."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import zipfile

VERSION = "0.1.0"
ROOT = Path(__file__).resolve().parents[1]
ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


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

    for abi in ("arm64-v8a", "universal"):
        name = f"Zhuzhalka_{VERSION}_{abi}.apk"
        apk = ROOT / "V2rayNG/app/build/outputs/apk/playstore/debug" / name
        if not apk.is_file() or not zipfile.is_zipfile(apk):
            raise RuntimeError(f"Missing or invalid APK: {apk}")
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
            "source_commit": revision,
            "submodules": submodules,
            "native_sha256": {relative: digest(ROOT / relative) for relative in native},
            "signing": "Android debug signing; CI and local certificates may differ.",
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
