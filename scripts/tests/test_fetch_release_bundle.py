import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "fetch-release-bundle.py"
spec = importlib.util.spec_from_file_location("fetch_bundle", SCRIPT)
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class ReleaseBundleTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name) / "verified"
        self.commit = "a" * 40
        self.bundle_commit = "b" * 40
        self.pin = (bundle.ROOT / "scripts/release-signing-cert.sha256").read_text().strip()
        self.base = f"https://raw.githubusercontent.com/{bundle.REPOSITORY}/{self.bundle_commit}/"
        self.downloaded = []
        self.apk_verifier = patch.object(bundle.PACKAGE, "verify_apk")
        self.verify_apk = self.apk_verifier.start()
        self.addCleanup(self.apk_verifier.stop)
        self.build_fixture()

    def build_fixture(self, source_commit=None, checksum_corrupt=False):
        archive_bytes = io.BytesIO()
        with zipfile.ZipFile(archive_bytes, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(f"Zhuzhalka_{bundle.VERSION}_sources/BUILD_INFO.json", json.dumps({
                "version": bundle.VERSION, "version_code": bundle.VERSION_CODE,
                "source_commit": source_commit or self.commit, "signing_certificate_sha256": self.pin,
            }))
        self.files = [b"signed-arm64-fixture", b"signed-universal-fixture", archive_bytes.getvalue()]
        checksums = "".join(f"{bundle.sha256(data)}  {name}\n" for data, name in zip(self.files, bundle.ASSETS))
        self.files.append((checksums if not checksum_corrupt else checksums.replace(bundle.ASSETS[0], "unexpected.apk")).encode())
        self.manifest = {"schema": 1, "version": bundle.VERSION, "version_code": bundle.VERSION_CODE,
                         "source_commit": self.commit, "signing_certificate_sha256": self.pin, "assets": []}
        self.parts = {}
        for index, (name, data) in enumerate(zip(bundle.ASSETS, self.files)):
            part = f"chunks/{index:02d}-0000.bin"
            self.parts[part] = data
            self.manifest["assets"].append({"name": name, "size": len(data), "sha256": bundle.sha256(data),
                "chunks": [{"name": part, "size": len(data), "sha256": bundle.sha256(data)}]})

    def download(self, url, maximum):
        self.assertTrue(url.startswith(self.base))
        name = url[len(self.base):]
        self.downloaded.append(name)
        data = json.dumps(self.manifest).encode() if name == "manifest.json" else self.parts[name]
        self.assertLessEqual(len(data), maximum)
        return data

    def fetch(self):
        bundle.fetch(self.bundle_commit, self.commit, self.output, self.download)

    def test_complete_verified_release_has_exact_assets_and_both_apks_verified(self):
        self.fetch()
        self.assertEqual(set(bundle.ASSETS), {path.name for path in self.output.iterdir()})
        for name, data in zip(bundle.ASSETS, self.files):
            self.assertEqual(data, (self.output / name).read_bytes())
        self.assertEqual(2, self.verify_apk.call_count)
        self.assertEqual(list(bundle.ASSETS[:2]), [call.args[0].name for call in self.verify_apk.call_args_list])
        self.assertTrue(all(call.args[1] == self.pin for call in self.verify_apk.call_args_list))

    def test_manifest_rejects_wrong_source_version_certificate_names_paths_and_sizes(self):
        original = copy.deepcopy(self.manifest)
        changes = [
            lambda m: m.update(source_commit="c" * 40),
            lambda m: m.update(version="0.1.0"),
            lambda m: m.update(version_code=True),
            lambda m: m.update(schema=True),
            lambda m: m.update(signing_certificate_sha256="0" * 64),
            lambda m: m["assets"].pop(),
            lambda m: m["assets"][0].update(name="../outside.apk"),
            lambda m: m["assets"][0]["chunks"][0].update(name="../../outside.bin"),
            lambda m: m["assets"][0]["chunks"][0].update(name="https://other.invalid/part"),
            lambda m: m["assets"][0].update(size=256 * 1024 * 1024 + 1),
            lambda m: m["assets"][2].update(size=128 * 1024 * 1024 + 1),
            lambda m: m["assets"][0]["chunks"][0].update(size=0),
            lambda m: m["assets"][0]["chunks"][0].update(size=True),
            lambda m: m["assets"][0]["chunks"][0].update(sha256="not-a-hash"),
        ]
        for change in changes:
            with self.subTest(change=changes.index(change)):
                self.manifest = copy.deepcopy(original)
                change(self.manifest)
                with self.assertRaises(ValueError):
                    self.fetch()
                self.assertFalse(self.output.exists())
        self.verify_apk.assert_not_called()
        self.assertTrue(all(name == "manifest.json" for name in self.downloaded))

    def test_chunk_hash_failure_removes_all_partial_staging(self):
        self.parts["chunks/01-0000.bin"] = b"x" * len(self.files[1])
        with self.assertRaisesRegex(ValueError, "Chunk integrity"):
            self.fetch()
        self.assertFalse(self.output.exists())
        self.verify_apk.assert_not_called()

    def test_full_asset_hash_is_independently_checked(self):
        self.manifest["assets"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "Asset integrity"):
            self.fetch()
        self.assertFalse(self.output.exists())

    def test_short_chunk_is_rejected_without_partial_output(self):
        self.parts["chunks/00-0000.bin"] = self.parts["chunks/00-0000.bin"][:-1]
        with self.assertRaisesRegex(ValueError, "Chunk integrity"):
            self.fetch()
        self.assertFalse(self.output.exists())

    def test_invalid_source_archive_provenance_is_rejected(self):
        self.build_fixture(source_commit="c" * 40)
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.fetch()
        self.assertFalse(self.output.exists())

    def test_checksum_file_must_name_exactly_the_three_release_files(self):
        self.build_fixture(checksum_corrupt=True)
        with self.assertRaisesRegex(ValueError, "checksum entry"):
            self.fetch()
        self.assertFalse(self.output.exists())

    def test_failed_real_apk_verification_cannot_leave_publishable_files(self):
        self.verify_apk.side_effect = RuntimeError("Wrong signing certificate")
        with self.assertRaises(RuntimeError):
            self.fetch()
        self.assertFalse(self.output.exists())

    def test_existing_output_is_never_modified(self):
        self.output.mkdir()
        sentinel = self.output / "keep"
        sentinel.write_text("user data")
        with self.assertRaisesRegex(ValueError, "must not exist"):
            self.fetch()
        self.assertEqual("user data", sentinel.read_text())
        self.assertEqual([], self.downloaded)

    def test_only_exact_commit_input_can_form_a_download_url(self):
        for commit in ["master", "a" * 39, "a" * 41, "../master", "A" * 40]:
            with self.subTest(commit=commit), self.assertRaises(ValueError):
                bundle.fetch(commit, self.commit, self.output, self.download)
        self.assertEqual([], self.downloaded)

    def test_duplicate_manifest_fields_are_rejected(self):
        raw = json.dumps(self.manifest).replace('"schema": 1', '"schema": 1, "schema": 1')
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            bundle.validate_manifest(raw.encode(), self.commit, self.pin)

    def test_nonfinal_chunks_must_be_exactly_four_mib(self):
        asset = self.manifest["assets"][0]
        asset["size"] = bundle.CHUNK_BYTES + 1
        asset["chunks"] = [
            {"name": "chunks/00-0000.bin", "size": bundle.CHUNK_BYTES, "sha256": "0" * 64},
            {"name": "chunks/00-0001.bin", "size": 1, "sha256": "0" * 64},
        ]
        bundle.validate_manifest(json.dumps(self.manifest).encode(), self.commit, self.pin)
        asset["chunks"][0]["size"] -= 1
        with self.assertRaisesRegex(ValueError, "chunk size"):
            bundle.validate_manifest(json.dumps(self.manifest).encode(), self.commit, self.pin)


if __name__ == "__main__":
    unittest.main()
