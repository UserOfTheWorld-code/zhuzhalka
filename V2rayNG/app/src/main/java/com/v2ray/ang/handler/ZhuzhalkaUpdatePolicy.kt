package com.v2ray.ang.handler

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest

data class ZhuzhalkaReleaseAsset(
    val id: Long,
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

data class ZhuzhalkaRelease(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val isPrerelease: Boolean,
    val asset: ZhuzhalkaReleaseAsset,
    val checksumAsset: ZhuzhalkaReleaseAsset?,
)

data class ZhuzhalkaDownloadProgress(val bytesDownloaded: Long, val totalBytes: Long)

data class ZhuzhalkaVerifiedUpdate(
    val file: File,
    val versionName: String,
    val versionCode: Long,
)

enum class ZhuzhalkaUpdateFailure {
    NETWORK, INVALID_METADATA, NO_COMPATIBLE_ASSET, MISSING_CHECKSUM, TOO_LARGE,
    CHECKSUM_MISMATCH, INVALID_APK, PACKAGE_MISMATCH, SIGNATURE_MISMATCH, NOT_NEWER, STORAGE,
}

class ZhuzhalkaUpdateException(val reason: ZhuzhalkaUpdateFailure) : Exception(reason.name)

/** Pure rules for this application's release channel, separate from upstream v2rayNG updates. */
internal object ZhuzhalkaUpdatePolicy {
    const val PACKAGE_NAME = "app.zhuzhalka.vpn"
    const val RELEASES_URL = "https://api.github.com/repos/UserOfTheWorld-code/zhuzhalka/releases?per_page=100"
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MAX_METADATA_BYTES = 4L * 1024 * 1024
    const val MAX_CHECKSUM_BYTES = 128L * 1024
    private const val REPOSITORY_PATH = "/UserOfTheWorld-code/zhuzhalka"
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private val versionPattern = Regex(
        "^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
    )

    fun compareVersions(first: String, second: String): Int? {
        val left = parseVersion(first) ?: return null
        val right = parseVersion(second) ?: return null
        return left.compareTo(right)
    }

    fun selectRelease(metadata: String, installedVersion: String): ZhuzhalkaRelease? {
        val installed = parseVersion(installedVersion)
            ?: throw ZhuzhalkaUpdateException(ZhuzhalkaUpdateFailure.INVALID_METADATA)
        val root = try {
            JsonParser.parseString(metadata)
        } catch (_: Exception) {
            throw ZhuzhalkaUpdateException(ZhuzhalkaUpdateFailure.INVALID_METADATA)
        }
        if (!root.isJsonArray) throw ZhuzhalkaUpdateException(ZhuzhalkaUpdateFailure.INVALID_METADATA)
        val releases = root.asJsonArray.mapNotNull { element ->
            val release = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (release.boolean("draft") != false) return@mapNotNull null
            val tag = release.string("tag_name") ?: return@mapNotNull null
            val version = parseVersion(tag) ?: return@mapNotNull null
            if (version <= installed) return@mapNotNull null
            Triple(version, tag, release)
        }.sortedByDescending { it.first }
        val (_, tag, release) = releases.firstOrNull() ?: return null
        val versionName = tag.removePrefix("v").removePrefix("V")
        val coreVersion = versionName.substringBefore('-').substringBefore('+')
        val assets = release.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw ZhuzhalkaUpdateException(ZhuzhalkaUpdateFailure.INVALID_METADATA)
        val expectedNames = setOf("Zhuzhalka_${versionName}_universal.apk", "Zhuzhalka_${coreVersion}_universal.apk")
        val apkCandidates = assets.filter { it.isJsonObject && it.asJsonObject.string("name") in expectedNames }
        if (apkCandidates.size != 1) throw ZhuzhalkaUpdateException(ZhuzhalkaUpdateFailure.NO_COMPATIBLE_ASSET)
        val apk = parseAsset(apkCandidates.single().asJsonObject, tag, MAX_APK_BYTES)
        val checksumCandidates = assets.filter {
            it.isJsonObject && it.asJsonObject.string("name") in setOf("SHA256SUMS", "SHA256SUMS.txt")
        }
        val checksum = if (checksumCandidates.size == 1) {
            parseAsset(checksumCandidates.single().asJsonObject, tag, MAX_CHECKSUM_BYTES)
        } else null
        if (apk.sha256 == null && checksum == null) {
            throw ZhuzhalkaUpdateException(ZhuzhalkaUpdateFailure.MISSING_CHECKSUM)
        }
        return ZhuzhalkaRelease(
            tagName = tag,
            versionName = versionName,
            releaseNotes = release.string("body").orEmpty().take(8192),
            isPrerelease = release.boolean("prerelease") ?: true,
            asset = apk,
            checksumAsset = checksum,
        )
    }

    fun validAssetUrl(url: String, tag: String, name: String): Boolean {
        val uri = secureUri(url) ?: return false
        return uri.host == "github.com" && uri.rawQuery == null &&
            uri.rawPath == "$REPOSITORY_PATH/releases/download/$tag/$name"
    }

    fun validRedirectUrl(url: String, tag: String, name: String): Boolean {
        val uri = secureUri(url) ?: return false
        return validAssetUrl(url, tag, name) || uri.host in setOf(
            "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"
        )
    }

    fun checksumFromManifest(text: String, assetName: String): String? {
        val matches = text.lineSequence().mapNotNull { line ->
            val match = Regex("^([0-9a-fA-F]{64}) [ *](.+)$").matchEntire(line.trimEnd()) ?: return@mapNotNull null
            if (match.groupValues[2] == assetName) match.groupValues[1].lowercase() else null
        }.toList()
        // Duplicate entries are ambiguous even if they currently carry the same digest.
        return matches.singleOrNull()
    }

    fun hashMatches(expected: String, actual: String): Boolean =
        sha256Pattern.matches(expected) && sha256Pattern.matches(actual) &&
            MessageDigest.isEqual(expected.lowercase().toByteArray(Charsets.US_ASCII), actual.lowercase().toByteArray(Charsets.US_ASCII))

    fun compatiblePackage(
        expectedPackage: String,
        installedVersionCode: Long,
        installedSigners: Set<String>,
        archivePackage: String,
        archiveVersionCode: Long,
        archiveSigners: Set<String>,
    ): ZhuzhalkaUpdateFailure? = when {
        archivePackage != expectedPackage -> ZhuzhalkaUpdateFailure.PACKAGE_MISMATCH
        installedSigners.isEmpty() || archiveSigners.isEmpty() -> ZhuzhalkaUpdateFailure.INVALID_APK
        installedSigners != archiveSigners -> ZhuzhalkaUpdateFailure.SIGNATURE_MISMATCH
        archiveVersionCode <= installedVersionCode -> ZhuzhalkaUpdateFailure.NOT_NEWER
        else -> null
    }

    private fun parseAsset(asset: JsonObject, tag: String, maximumBytes: Long): ZhuzhalkaReleaseAsset {
        val id = asset.long("id")?.takeIf { it > 0 }
        val name = asset.string("name")
        val url = asset.string("browser_download_url")
        val size = asset.long("size")
        if (id == null || name == null || url == null || size == null || size <= 0 ||
            asset.string("state") != "uploaded" || !validAssetUrl(url, tag, name)) {
            throw ZhuzhalkaUpdateException(ZhuzhalkaUpdateFailure.INVALID_METADATA)
        }
        if (size > maximumBytes) throw ZhuzhalkaUpdateException(ZhuzhalkaUpdateFailure.TOO_LARGE)
        val digest = asset.string("digest")
        val hash = digest?.removePrefix("sha256:")
        if (digest != null && (!digest.startsWith("sha256:") || hash == null || !sha256Pattern.matches(hash))) {
            throw ZhuzhalkaUpdateException(ZhuzhalkaUpdateFailure.INVALID_METADATA)
        }
        return ZhuzhalkaReleaseAsset(id, name, url, size, hash?.lowercase())
    }

    private fun secureUri(url: String): URI? = try {
        URI(url).takeIf {
            it.scheme == "https" && it.host != null && it.rawUserInfo == null && it.rawFragment == null &&
                it.port in setOf(-1, 443) && !it.rawPath.orEmpty().contains("..")
        }
    } catch (_: Exception) { null }

    private fun parseVersion(raw: String): Version? {
        if (raw.length > 256) return null
        val match = versionPattern.matchEntire(raw) ?: return null
        val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.')
        if (prerelease?.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') } == true) return null
        return Version((1..3).map { BigInteger(match.groupValues[it]) }, prerelease)
    }

    private data class Version(val core: List<BigInteger>, val prerelease: List<String>?) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            core.indices.forEach { index -> core[index].compareTo(other.core[index]).takeIf { it != 0 }?.let { return it } }
            val left = prerelease ?: return if (other.prerelease == null) 0 else 1
            val right = other.prerelease ?: return -1
            for (index in 0 until minOf(left.size, right.size)) {
                val l = left[index]
                val r = right[index]
                val comparison = when {
                    l.all(Char::isDigit) && r.all(Char::isDigit) -> BigInteger(l).compareTo(BigInteger(r))
                    l.all(Char::isDigit) -> -1
                    r.all(Char::isDigit) -> 1
                    else -> l.compareTo(r)
                }
                if (comparison != 0) return comparison
            }
            return left.size.compareTo(right.size)
        }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString

    private fun JsonObject.boolean(name: String): Boolean? = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
    }?.asBoolean

    private fun JsonObject.long(name: String): Long? = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isNumber
    }?.let { element: JsonElement -> element.asString.toLongOrNull() }
}
