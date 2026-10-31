#!/usr/bin/env python3
"""Reassemble and verify a fixed-repository, commit-pinned public release bundle."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import time
import urllib.request
import zipfile

REPOSITORY = "UserOfTheWorld-code/zhuzhalka"
VERSION = "0.3.0-alpha.4"
VERSION_CODE = 5000303
CHUNK_BYTES = 4 * 1024 * 1024
MANIFEST_BYTES = 256 * 1024
ASSETS = (
    f"Zhuzhalka_{VERSION}_arm64-v8a.apk",
    f"Zhuzhalka_{VERSION}_universal.apk",
    f"Zhuzhalka_{VERSION}_sources.zip",
    "SHA256SUMS",
)
LIMITS = (256 * 1024 * 1024, 256 * 1024 * 1024, 128 * 1024 * 1024, 64 * 1024)
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("release_package", ROOT / "scripts/package-release.py")
PACKAGE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(PACKAGE)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def exact_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate manifest field")
        result[key] = value
    return result


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def valid_hash(value, length=64) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{" + str(length) + "}", value) is not None


def bounded_integer(value, maximum: int) -> bool:
    return type(value) is int and 1 <= value <= maximum


def validate_manifest(raw: bytes, source_commit: str, certificate_pin: str):
    require(len(raw) <= MANIFEST_BYTES, "Manifest too large")
    manifest = json.loads(raw.decode("utf-8"), object_pairs_hook=exact_object)
    require(isinstance(manifest, dict) and set(manifest) == {
        "schema", "version", "version_code", "source_commit", "signing_certificate_sha256", "assets"
    }, "Unexpected manifest fields")
    require(type(manifest["schema"]) is int and manifest["schema"] == 1, "Unexpected manifest schema")
    require(manifest["version"] == VERSION and type(manifest["version_code"]) is int and
            manifest["version_code"] == VERSION_CODE, "Unexpected release version")
    require(manifest["source_commit"] == source_commit, "Bundle belongs to another source commit")
    require(manifest["signing_certificate_sha256"] == certificate_pin, "Unexpected signing identity")
    assets = manifest["assets"]
    require(isinstance(assets, list) and len(assets) == len(ASSETS), "Expected exactly four assets")
    for asset_index, (asset, name, maximum) in enumerate(zip(assets, ASSETS, LIMITS)):
        require(isinstance(asset, dict) and set(asset) == {"name", "size", "sha256", "chunks"}, "Unexpected asset fields")
        require(asset["name"] == name, "Unexpected asset name or order")
        require(bounded_integer(asset["size"], maximum) and valid_hash(asset["sha256"]), "Invalid asset size/hash")
        chunks = asset["chunks"]
        count = (asset["size"] + CHUNK_BYTES - 1) // CHUNK_BYTES
        require(isinstance(chunks, list) and len(chunks) == count, "Unexpected chunk count")
        for index, chunk in enumerate(chunks):
            require(isinstance(chunk, dict) and set(chunk) == {"name", "size", "sha256"}, "Unexpected chunk fields")
            require(chunk["name"] == f"chunks/{asset_index:02d}-{index:04d}.bin", "Unsafe or unexpected chunk name")
            expected_size = min(CHUNK_BYTES, asset["size"] - index * CHUNK_BYTES)
            require(type(chunk["size"]) is int and chunk["size"] == expected_size and valid_hash(chunk["sha256"]),
                    "Invalid chunk size/hash")
    return assets


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise ValueError("Bundle download redirects are not allowed")


class Downloader:
    def __init__(self):
        self.opener = urllib.request.build_opener(NoRedirects())
        self.deadline = time.monotonic() + 20 * 60

    def __call__(self, url: str, maximum: int) -> bytes:
        require(time.monotonic() < self.deadline, "Bundle download deadline reached")
        request = urllib.request.Request(url, headers={"Accept": "application/octet-stream", "Accept-Encoding": "identity"})
        with self.opener.open(request, timeout=60) as response:
            require(response.status == 200 and response.url == url, "Unexpected bundle download response")
            require(response.headers.get("Content-Encoding", "identity") == "identity", "Encoded bundle response rejected")
            length = response.headers.get("Content-Length")
            require(length is None or (length.isdecimal() and int(length) <= maximum), "Bundle response too large")
            data = bytearray()
            while True:
                require(time.monotonic() < self.deadline, "Bundle download deadline reached")
                part = response.read(min(64 * 1024, maximum + 1 - len(data)))
                if not part:
                    return bytes(data)
                data.extend(part)
                require(len(data) <= maximum, "Bundle response too large")


def verify_outputs(directory: Path, source_commit: str, certificate_pin: str) -> None:
    require((PACKAGE.VERSION, PACKAGE.VERSION_CODE) == (VERSION, VERSION_CODE), "Packaging version mismatch")
    for name in ASSETS[:2]:
        PACKAGE.verify_apk(directory / name, certificate_pin)
    checksums = (directory / "SHA256SUMS").read_text(encoding="ascii").splitlines()
    require(len(checksums) == 3, "Unexpected checksum count")
    seen = set()
    for line in checksums:
        parts = line.split("  ")
        require(len(parts) == 2 and valid_hash(parts[0]) and parts[1] in ASSETS[:3] and parts[1] not in seen,
                "Invalid checksum entry")
        seen.add(parts[1])
        require(PACKAGE.digest(directory / parts[1]) == parts[0], "Release checksum mismatch")
    with zipfile.ZipFile(directory / ASSETS[2]) as archive:
        name = f"Zhuzhalka_{VERSION}_sources/BUILD_INFO.json"
        entries = [entry for entry in archive.infolist() if entry.filename == name]
        require(len(entries) == 1 and entries[0].file_size <= 64 * 1024, "Invalid source BUILD_INFO")
        info = json.loads(archive.read(entries[0]), object_pairs_hook=exact_object)
        require(info.get("source_commit") == source_commit and info.get("version") == VERSION and
                type(info.get("version_code")) is int and info["version_code"] == VERSION_CODE and
                info.get("signing_certificate_sha256") == certificate_pin, "Source archive provenance mismatch")


def fetch(bundle_commit: str, source_commit: str, output: Path, download=None) -> None:
    require(valid_hash(bundle_commit, 40) and valid_hash(source_commit, 40), "Expected exact lowercase commit SHAs")
    certificate_pin = (ROOT / "scripts/release-signing-cert.sha256").read_text(encoding="ascii").strip()
    require(valid_hash(certificate_pin), "Invalid signing certificate pin")
    output = output.absolute()
    require(not output.exists() and not output.is_symlink(), "Output directory must not exist")
    require(output.parent.is_dir(), "Output parent must exist")
    base = f"https://raw.githubusercontent.com/{REPOSITORY}/{bundle_commit}/"
    download = download or Downloader()
    assets = validate_manifest(download(base + "manifest.json", MANIFEST_BYTES), source_commit, certificate_pin)
    # Only this newly created directory is touched. On any failure, no partial release directory remains.
    output.mkdir()
    try:
        staging = output / ".staging"
        staging.mkdir()
        for asset in assets:
            whole_hash = hashlib.sha256()
            size = 0
            with (staging / asset["name"]).open("xb") as target:
                for chunk in asset["chunks"]:
                    data = download(base + chunk["name"], chunk["size"])
                    require(len(data) == chunk["size"] and sha256(data) == chunk["sha256"], "Chunk integrity mismatch")
                    target.write(data)
                    whole_hash.update(data)
                    size += len(data)
            require(size == asset["size"] and whole_hash.hexdigest() == asset["sha256"], "Asset integrity mismatch")
        verify_outputs(staging, source_commit, certificate_pin)
        for name in ASSETS:
            (staging / name).rename(output / name)
        staging.rmdir()
    except BaseException:
        shutil.rmtree(output)
        raise
    print(f"Verified {VERSION}: four assets from source {source_commit}; private signing key not required.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle-commit", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "Unexpected repository")
    require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and
            os.environ.get("GITHUB_REF") == "refs/heads/master", "Manual master workflow required")
    source = os.environ.get("GITHUB_SHA", "")
    require(PACKAGE.git("rev-parse", "HEAD").decode().strip() == source, "Checkout differs from source commit")
    fetch(args.bundle_commit, source, args.output)
