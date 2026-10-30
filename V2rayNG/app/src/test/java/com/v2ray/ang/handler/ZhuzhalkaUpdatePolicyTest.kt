package com.v2ray.ang.handler

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZhuzhalkaUpdatePolicyTest {
    private val hash = "a".repeat(64)
    private val base = "https://github.com/UserOfTheWorld-code/zhuzhalka/releases/download"

    @Test
    fun semverHandlesPrereleasesNumericPartsAndBuildMetadataWithoutIntegerOverflow() {
        val ordered = listOf("0.2.0-alpha", "0.2.0-alpha.2", "0.2.0-alpha.10", "0.2.0-beta.1", "0.2.0-rc.1", "0.2.0", "0.10.0", "999999999999999999999.0.0")
        ordered.zipWithNext().forEach { (first, second) ->
            assertTrue(ZhuzhalkaUpdatePolicy.compareVersions(first, second)!! < 0)
            assertTrue(ZhuzhalkaUpdatePolicy.compareVersions(second, first)!! > 0)
        }
        assertEquals(0, ZhuzhalkaUpdatePolicy.compareVersions("v0.2.0+first", "0.2.0+second"))
        assertTrue(ZhuzhalkaUpdatePolicy.compareVersions("0.2.0-1", "0.2.0-alpha")!! < 0)
    }

    @Test
    fun malformedOrAmbiguousVersionStringsAreNotGuessed() {
        listOf("", "latest", "0.2", "0.02.0", "0.2.0-beta.01", "0.2.0-", "0.2.0+", "0.2.0\n", "1".repeat(300) + ".0.0").forEach {
            assertNull(ZhuzhalkaUpdatePolicy.compareVersions(it, "0.1.0"))
        }
    }

    @Test
    fun selectsHighestSemanticVersionIncludingPrereleasesAndIgnoresDrafts() {
        val draft = release("v9.0.0").apply { addProperty("draft", true) }
        val selected = select(release("v0.2.0-beta.1"), draft, release("v0.1.9"), release("nightly"))
        assertNotNull(selected)
        assertEquals("v0.2.0-beta.1", selected!!.tagName)
        assertTrue(selected.isPrerelease)
        assertEquals("Zhuzhalka_0.2.0_universal.apk", selected.asset.name)
    }

    @Test
    fun doesNotOfferCurrentOlderOrPrereleaseOfInstalledStableVersion() {
        assertNull(select(release("v0.1.0"), release("v0.1.0-beta.2"), release("v0.0.9")))
        assertNull(ZhuzhalkaUpdatePolicy.selectRelease("[]", "0.1.0"))
    }

    @Test
    fun installedPrereleaseCanUpgradeToNewerPrereleaseOrStable() {
        val metadata = JsonArray().apply { add(release("v0.2.0-beta.10")); add(release("v0.2.0-beta.2")) }
        assertEquals("v0.2.0-beta.10", ZhuzhalkaUpdatePolicy.selectRelease(metadata.toString(), "0.2.0-beta.2")!!.tagName)
        assertEquals("v0.2.0", ZhuzhalkaUpdatePolicy.selectRelease(JsonArray().apply { add(release("v0.2.0")) }.toString(), "0.2.0-beta.10")!!.tagName)
    }

    @Test
    fun rejectsNonReleaseJsonAndInvalidInstalledVersion() {
        listOf("{\"message\":\"rate limited\"}", "not json", "null").forEach {
            assertFailure(ZhuzhalkaUpdateFailure.INVALID_METADATA) { ZhuzhalkaUpdatePolicy.selectRelease(it, "0.1.0") }
        }
        assertFailure(ZhuzhalkaUpdateFailure.INVALID_METADATA) { ZhuzhalkaUpdatePolicy.selectRelease("[]", "unknown") }
    }

    @Test
    fun choosesUniversalNotAbiSpecificOrFdroidApk() {
        val current = release("v0.2.0")
        current.getAsJsonArray("assets").add(asset("v0.2.0", "Zhuzhalka_0.2.0_arm64-v8a.apk"))
        current.getAsJsonArray("assets").add(asset("v0.2.0", "Zhuzhalka_0.2.0-fdroid_universal.apk"))
        assertEquals("Zhuzhalka_0.2.0_universal.apk", select(current)!!.asset.name)
    }

    @Test
    fun missingUniversalDoesNotSilentlyUseWrongAbiOrOlderRelease() {
        val current = release("v0.3.0").apply {
            add("assets", JsonArray().apply { add(asset("v0.3.0", "Zhuzhalka_0.3.0_arm64-v8a.apk")) })
        }
        assertFailure(ZhuzhalkaUpdateFailure.NO_COMPATIBLE_ASSET) { select(current, release("v0.2.0")) }
    }

    @Test
    fun duplicateUniversalAssetsAreRejected() {
        val current = release("v0.2.0")
        current.getAsJsonArray("assets").add(asset("v0.2.0", "Zhuzhalka_0.2.0_universal.apk"))
        assertFailure(ZhuzhalkaUpdateFailure.NO_COMPATIBLE_ASSET) { select(current) }
    }

    @Test
    fun metadataAssetMustBelongToSameRepositoryTagAndFilename() {
        val name = "Zhuzhalka_0.2.0_universal.apk"
        val urls = listOf(
            "http://github.com/UserOfTheWorld-code/zhuzhalka/releases/download/v0.2.0/$name",
            "$base/v0.3.0/$name", "$base/v0.2.0/another.apk",
            "https://github.com/another/repo/releases/download/v0.2.0/$name",
            "https://github.com.evil.example/UserOfTheWorld-code/zhuzhalka/releases/download/v0.2.0/$name",
            "https://user@github.com/UserOfTheWorld-code/zhuzhalka/releases/download/v0.2.0/$name",
            "$base/v0.2.0/$name?download=1", "$base/v0.2.0/$name#fragment",
            "https://github.com:444/UserOfTheWorld-code/zhuzhalka/releases/download/v0.2.0/$name",
        )
        urls.forEach { url ->
            assertFalse(ZhuzhalkaUpdatePolicy.validAssetUrl(url, "v0.2.0", name))
            val current = release("v0.2.0")
            current.getAsJsonArray("assets")[0].asJsonObject.addProperty("browser_download_url", url)
            assertFailure(ZhuzhalkaUpdateFailure.INVALID_METADATA) { select(current) }
        }
    }

    @Test
    fun onlyHttpsGithubAssetCdnRedirectsAreAccepted() {
        val good = "https://release-assets.githubusercontent.com/github-production-release-asset/1/file?sig=example"
        assertTrue(ZhuzhalkaUpdatePolicy.validRedirectUrl(good, "v0.2.0", "app.apk"))
        listOf("http://release-assets.githubusercontent.com/a", "https://release-assets.githubusercontent.com.evil.test/a", "https://evil.test/a", "https://localhost/a", "file:///tmp/app.apk", "https://user@objects.githubusercontent.com/a", "https://objects.githubusercontent.com/../a", "https://objects.githubusercontent.com/a#frag").forEach {
            assertFalse(ZhuzhalkaUpdatePolicy.validRedirectUrl(it, "v0.2.0", "app.apk"))
        }
        assertFalse(ZhuzhalkaUpdatePolicy.validAssetUrl(good, "v0.2.0", "app.apk"))
    }

    @Test
    fun rejectsOversizedZeroNegativeAndNonIntegralAssets() {
        val tooLarge = release("v0.2.0")
        tooLarge.getAsJsonArray("assets")[0].asJsonObject.addProperty("size", ZhuzhalkaUpdatePolicy.MAX_APK_BYTES + 1)
        assertFailure(ZhuzhalkaUpdateFailure.TOO_LARGE) { select(tooLarge) }
        listOf(0, -1, 1.5).forEach { size ->
            val current = release("v0.2.0")
            current.getAsJsonArray("assets")[0].asJsonObject.addProperty("size", size)
            assertFailure(ZhuzhalkaUpdateFailure.INVALID_METADATA) { select(current) }
        }
    }

    @Test
    fun uploadingOrMalformedDigestAssetsAreRejected() {
        val unfinished = release("v0.2.0")
        unfinished.getAsJsonArray("assets")[0].asJsonObject.addProperty("state", "starter")
        assertFailure(ZhuzhalkaUpdateFailure.INVALID_METADATA) { select(unfinished) }
        listOf("md5:$hash", "sha256:bad", "sha256:" + "z".repeat(64)).forEach { digest ->
            val current = release("v0.2.0")
            current.getAsJsonArray("assets")[0].asJsonObject.addProperty("digest", digest)
            assertFailure(ZhuzhalkaUpdateFailure.INVALID_METADATA) { select(current) }
        }
    }

    @Test
    fun fallbackRequiresPublishedChecksumAssetFromSameRelease() {
        val current = release("v0.2.0")
        current.getAsJsonArray("assets")[0].asJsonObject.remove("digest")
        assertFailure(ZhuzhalkaUpdateFailure.MISSING_CHECKSUM) { select(current) }
        current.getAsJsonArray("assets").add(asset("v0.2.0", "SHA256SUMS").apply { addProperty("size", 200) })
        val selected = select(current)!!
        assertNull(selected.asset.sha256)
        assertEquals("SHA256SUMS", selected.checksumAsset!!.name)
    }

    @Test
    fun checksumManifestMatchesExactBasenameAndRejectsDuplicates() {
        val name = "Zhuzhalka_0.2.0_universal.apk"
        assertEquals(hash, ZhuzhalkaUpdatePolicy.checksumFromManifest("$hash  $name\n", name))
        assertEquals(hash, ZhuzhalkaUpdatePolicy.checksumFromManifest("${hash.uppercase()} *$name\r\n", name))
        assertNull(ZhuzhalkaUpdatePolicy.checksumFromManifest("$hash  ../$name\n", name))
        assertNull(ZhuzhalkaUpdatePolicy.checksumFromManifest("$hash  another.apk\n", name))
        assertNull(ZhuzhalkaUpdatePolicy.checksumFromManifest("$hash  $name\n$hash  $name\n", name))
        assertNull(ZhuzhalkaUpdatePolicy.checksumFromManifest("bad  $name", name))
    }

    @Test
    fun digestComparisonRejectsTamperingAndMalformedHashes() {
        assertTrue(ZhuzhalkaUpdatePolicy.hashMatches(hash.uppercase(), hash))
        assertFalse(ZhuzhalkaUpdatePolicy.hashMatches(hash, "b".repeat(64)))
        assertFalse(ZhuzhalkaUpdatePolicy.hashMatches("", ""))
        assertFalse(ZhuzhalkaUpdatePolicy.hashMatches("z".repeat(64), "z".repeat(64)))
    }

    @Test
    fun actualApkVersionCodeMustIncreaseRegardlessOfReleaseTag() {
        assertNull(compatibility(4_000_001, 5_000_200))
        assertEquals(ZhuzhalkaUpdateFailure.NOT_NEWER, compatibility(5_000_200, 5_000_200))
        assertEquals(ZhuzhalkaUpdateFailure.NOT_NEWER, compatibility(5_000_200, 4_000_300))
    }

    @Test
    fun wrongPackageOrSigningKeyCannotBeInstalledAsAnUpdate() {
        assertEquals(ZhuzhalkaUpdateFailure.PACKAGE_MISMATCH, compatibility(1, 2, archivePackage = "app.zhuzhalka.vpn.fdroid"))
        assertEquals(ZhuzhalkaUpdateFailure.SIGNATURE_MISMATCH, compatibility(1, 2, archiveSigners = setOf("other")))
        assertEquals(ZhuzhalkaUpdateFailure.INVALID_APK, compatibility(1, 2, archiveSigners = emptySet()))
        assertEquals(ZhuzhalkaUpdateFailure.SIGNATURE_MISMATCH, compatibility(1, 2, archiveSigners = setOf(hash, "additional")))
    }

    @Test
    fun releaseNotesAreBoundedAndReturnedAsPlainText() {
        val current = release("v0.2.0").apply { addProperty("body", "<b>notes</b>" + "x".repeat(9000)) }
        val notes = select(current)!!.releaseNotes
        assertEquals(8192, notes.length)
        assertTrue(notes.startsWith("<b>notes</b>"))
    }

    private fun select(vararg releases: JsonObject): ZhuzhalkaRelease? = ZhuzhalkaUpdatePolicy.selectRelease(
        JsonArray().apply { releases.forEach { add(it) } }.toString(), "0.1.0"
    )

    private fun release(tag: String): JsonObject = JsonObject().apply {
        addProperty("tag_name", tag)
        addProperty("draft", false)
        addProperty("prerelease", tag.contains('-'))
        addProperty("body", "Release notes")
        val version = tag.removePrefix("v").substringBefore('-')
        add("assets", JsonArray().apply { add(asset(tag, "Zhuzhalka_${version}_universal.apk")) })
    }

    private fun asset(tag: String, name: String): JsonObject = JsonObject().apply {
        addProperty("id", 123)
        addProperty("name", name)
        addProperty("browser_download_url", "$base/$tag/$name")
        addProperty("size", 100_000_000)
        addProperty("state", "uploaded")
        addProperty("digest", "sha256:$hash")
    }

    private fun compatibility(installed: Long, archive: Long, archivePackage: String = ZhuzhalkaUpdatePolicy.PACKAGE_NAME, archiveSigners: Set<String> = setOf(hash)) =
        ZhuzhalkaUpdatePolicy.compatiblePackage(ZhuzhalkaUpdatePolicy.PACKAGE_NAME, installed, setOf(hash), archivePackage, archive, archiveSigners)

    private fun assertFailure(reason: ZhuzhalkaUpdateFailure, block: () -> Unit) {
        assertEquals(reason, assertThrows(ZhuzhalkaUpdateException::class.java) { block() }.reason)
    }
}
