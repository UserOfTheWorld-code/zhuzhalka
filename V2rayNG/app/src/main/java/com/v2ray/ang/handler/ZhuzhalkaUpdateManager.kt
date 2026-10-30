package com.v2ray.ang.handler

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Owns verified APK downloads for Zhuzhalka; upstream UpdateCheckerManager remains unchanged. */
object ZhuzhalkaUpdateManager {
    const val CACHE_DIRECTORY = "zhuzhalka-updates"
    private val downloadMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun checkForUpdate(context: Context): ZhuzhalkaRelease? = operation("check") {
        requireSupportedPackage(context)
        val installed = installedPackage(context)
        val version = installed.versionName ?: BuildConfig.VERSION_NAME
        val metadata = readBytes(
            url = ZhuzhalkaUpdatePolicy.RELEASES_URL,
            maximum = ZhuzhalkaUpdatePolicy.MAX_METADATA_BYTES,
            release = null,
            asset = null,
        ).toString(Charsets.UTF_8)
        val release = ZhuzhalkaUpdatePolicy.selectRelease(metadata, version) ?: return@operation null
        // Resolve a published checksum once, so permission-return verification can work offline.
        release.copy(asset = release.asset.copy(sha256 = expectedHash(release)))
    }

    /** Callback runs on IO. The caller owns its coroutine and moves UI state updates to Main. */
    suspend fun downloadAndVerify(
        context: Context,
        release: ZhuzhalkaRelease,
        onProgress: (ZhuzhalkaDownloadProgress) -> Unit = {},
    ): ZhuzhalkaVerifiedUpdate = operation("download") {
        requireSupportedPackage(context)
        downloadMutex.withLock {
            val asset = release.asset
            validateAsset(release, asset, ZhuzhalkaUpdatePolicy.MAX_APK_BYTES)
            val expectedHash = expectedHash(release)
            val directory = File(context.cacheDir, CACHE_DIRECTORY)
            if (!directory.isDirectory && !directory.mkdirs()) fail(ZhuzhalkaUpdateFailure.STORAGE)
            if (directory.canonicalFile.parentFile != context.cacheDir.canonicalFile) fail(ZhuzhalkaUpdateFailure.STORAGE)
            // Cache is disposable. Only remove our incomplete files and APKs older than a day.
            directory.listFiles()?.filter {
                it.isFile && it.name.startsWith("update-") &&
                    (it.name.endsWith(".part") || (it.name.endsWith(".apk") && System.currentTimeMillis() - it.lastModified() > TimeUnit.DAYS.toMillis(1)))
            }?.forEach { if (!it.delete()) LogUtil.w(AppConfig.TAG, "Zhuzhalka updater stale cache cleanup failed") }
            val temporary = try {
                File.createTempFile("update-", ".part", directory)
            } catch (_: IOException) { fail(ZhuzhalkaUpdateFailure.STORAGE) }
            var completedFile: File? = null
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                withResponse(asset.url, release, asset) { response ->
                    val body = response.body ?: fail(ZhuzhalkaUpdateFailure.NETWORK)
                    val advertisedLength = body.contentLength()
                    if (advertisedLength > ZhuzhalkaUpdatePolicy.MAX_APK_BYTES) fail(ZhuzhalkaUpdateFailure.TOO_LARGE)
                    if (advertisedLength >= 0 && advertisedLength != asset.size) fail(ZhuzhalkaUpdateFailure.INVALID_METADATA)
                    val output = try { FileOutputStream(temporary) } catch (_: IOException) { fail(ZhuzhalkaUpdateFailure.STORAGE) }
                    output.use { sink ->
                        body.byteStream().use { source ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastPercent = -1L
                            onProgress(ZhuzhalkaDownloadProgress(0, asset.size))
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = source.read(buffer)
                                if (read == -1) break
                                downloaded += read
                                if (downloaded > ZhuzhalkaUpdatePolicy.MAX_APK_BYTES || downloaded > asset.size) fail(ZhuzhalkaUpdateFailure.TOO_LARGE)
                                try { sink.write(buffer, 0, read) } catch (_: IOException) { fail(ZhuzhalkaUpdateFailure.STORAGE) }
                                digest.update(buffer, 0, read)
                                val percent = downloaded * 100 / asset.size
                                if (percent != lastPercent) {
                                    onProgress(ZhuzhalkaDownloadProgress(downloaded, asset.size))
                                    lastPercent = percent
                                }
                            }
                            if (downloaded != asset.size) fail(ZhuzhalkaUpdateFailure.NETWORK)
                        }
                        try { sink.fd.sync() } catch (_: IOException) { fail(ZhuzhalkaUpdateFailure.STORAGE) }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!ZhuzhalkaUpdatePolicy.hashMatches(expectedHash, digest.digest().hex())) fail(ZhuzhalkaUpdateFailure.CHECKSUM_MISMATCH)
                val archive = verifyPackage(context, temporary)
                val destination = File(directory, "update-${asset.id}-${expectedHash.take(16)}.apk")
                if (destination.exists() && !destination.delete()) fail(ZhuzhalkaUpdateFailure.STORAGE)
                if (!temporary.renameTo(destination)) fail(ZhuzhalkaUpdateFailure.STORAGE)
                completedFile = destination
                currentCoroutineContext().ensureActive()
                ZhuzhalkaVerifiedUpdate(destination, archive.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(archive))
            } catch (error: Exception) {
                completedFile?.let { if (!it.delete()) LogUtil.w(AppConfig.TAG, "Zhuzhalka updater cancelled cache cleanup failed") }
                throw error
            } finally {
                if (temporary.exists() && !temporary.delete()) LogUtil.w(AppConfig.TAG, "Zhuzhalka updater partial cache cleanup failed")
            }
        }
    }

    /** Rechecks bytes, package, version and signers immediately before the UI opens the installer. */
    suspend fun verifyDownloaded(
        context: Context,
        release: ZhuzhalkaRelease,
        file: File,
    ): ZhuzhalkaVerifiedUpdate = operation("verify") {
        requireSupportedPackage(context)
        validateAsset(release, release.asset, ZhuzhalkaUpdatePolicy.MAX_APK_BYTES)
        val expectedHash = expectedHash(release)
        val directory = File(context.cacheDir, CACHE_DIRECTORY).canonicalFile
        val expectedName = "update-${release.asset.id}-${expectedHash.take(16)}.apk"
        if (directory.parentFile != context.cacheDir.canonicalFile || file.canonicalFile.parentFile != directory ||
            file.name != expectedName || !file.isFile || file.length() != release.asset.size) {
            fail(ZhuzhalkaUpdateFailure.INVALID_APK)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count == -1) break
                total += count
                if (total > release.asset.size) fail(ZhuzhalkaUpdateFailure.TOO_LARGE)
                digest.update(buffer, 0, count)
            }
            if (total != release.asset.size) fail(ZhuzhalkaUpdateFailure.INVALID_APK)
        }
        if (!ZhuzhalkaUpdatePolicy.hashMatches(expectedHash, digest.digest().hex())) fail(ZhuzhalkaUpdateFailure.CHECKSUM_MISMATCH)
        val archive = verifyPackage(context, file)
        ZhuzhalkaVerifiedUpdate(file, archive.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(archive))
    }

    private suspend fun expectedHash(release: ZhuzhalkaRelease): String {
        release.asset.sha256?.let {
            if (!ZhuzhalkaUpdatePolicy.hashMatches(it, it)) fail(ZhuzhalkaUpdateFailure.INVALID_METADATA)
            return it
        }
        val checksum = release.checksumAsset ?: fail(ZhuzhalkaUpdateFailure.MISSING_CHECKSUM)
        validateAsset(release, checksum, ZhuzhalkaUpdatePolicy.MAX_CHECKSUM_BYTES)
        if (checksum.name !in setOf("SHA256SUMS", "SHA256SUMS.txt")) fail(ZhuzhalkaUpdateFailure.INVALID_METADATA)
        val bytes = readBytes(checksum.url, ZhuzhalkaUpdatePolicy.MAX_CHECKSUM_BYTES, release, checksum)
        if (bytes.size.toLong() != checksum.size) fail(ZhuzhalkaUpdateFailure.INVALID_METADATA)
        checksum.sha256?.let {
            if (!ZhuzhalkaUpdatePolicy.hashMatches(it, MessageDigest.getInstance("SHA-256").digest(bytes).hex())) fail(ZhuzhalkaUpdateFailure.CHECKSUM_MISMATCH)
        }
        return ZhuzhalkaUpdatePolicy.checksumFromManifest(bytes.toString(Charsets.UTF_8), release.asset.name)
            ?: fail(ZhuzhalkaUpdateFailure.MISSING_CHECKSUM)
    }

    private suspend fun readBytes(
        url: String,
        maximum: Long,
        release: ZhuzhalkaRelease?,
        asset: ZhuzhalkaReleaseAsset?,
    ): ByteArray = withResponse(url, release, asset) { response ->
        val body = response.body ?: fail(ZhuzhalkaUpdateFailure.NETWORK)
        if (body.contentLength() > maximum) fail(ZhuzhalkaUpdateFailure.TOO_LARGE)
        val output = ByteArrayOutputStream()
        body.byteStream().use { source ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                if (output.size().toLong() + read > maximum) fail(ZhuzhalkaUpdateFailure.TOO_LARGE)
                output.write(buffer, 0, read)
            }
        }
        output.toByteArray()
    }

    private suspend fun <T> withResponse(
        initialUrl: String,
        release: ZhuzhalkaRelease?,
        asset: ZhuzhalkaReleaseAsset?,
        consume: suspend (Response) -> T,
    ): T {
        var url = initialUrl
        repeat(6) { redirect ->
            val permitted = if (release == null || asset == null) {
                redirect == 0 && url == ZhuzhalkaUpdatePolicy.RELEASES_URL
            } else if (redirect == 0) {
                ZhuzhalkaUpdatePolicy.validAssetUrl(url, release.tagName, asset.name)
            } else {
                ZhuzhalkaUpdatePolicy.validRedirectUrl(url, release.tagName, asset.name)
            }
            if (!permitted) fail(ZhuzhalkaUpdateFailure.INVALID_METADATA)
            val request = Request.Builder().url(url)
                .header("User-Agent", "Zhuzhalka/${BuildConfig.VERSION_NAME}")
                .header("Accept", if (release == null) "application/vnd.github+json" else "application/octet-stream")
                .build()
            val call = client.newCall(request)
            var nextUrl: String? = null
            // A cancelled owner closes blocking headers AND body reads immediately, not just between chunks.
            val result = coroutineScope {
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        when (response.code) {
                            301, 302, 303, 307, 308 -> {
                                val location = response.header("Location") ?: fail(ZhuzhalkaUpdateFailure.NETWORK)
                                nextUrl = try { URI(url).resolve(location).toString() } catch (_: Exception) { fail(ZhuzhalkaUpdateFailure.INVALID_METADATA) }
                                null
                            }
                            200 -> ResponseValue(consume(response))
                            else -> fail(ZhuzhalkaUpdateFailure.NETWORK)
                        }
                    }
                } finally { cancellation.cancel() }
            }
            if (result != null) return result.value
            url = nextUrl ?: fail(ZhuzhalkaUpdateFailure.NETWORK)
        }
        fail(ZhuzhalkaUpdateFailure.NETWORK)
    }

    private fun verifyPackage(context: Context, file: File): PackageInfo {
        val installed = installedPackage(context)
        val archive = try { packageInfo(context.packageManager, file.absolutePath, archive = true) }
            catch (_: Exception) { fail(ZhuzhalkaUpdateFailure.INVALID_APK) }
            ?: fail(ZhuzhalkaUpdateFailure.INVALID_APK)
        ZhuzhalkaUpdatePolicy.compatiblePackage(
            context.packageName, PackageInfoCompat.getLongVersionCode(installed), currentSigners(installed),
            archive.packageName, PackageInfoCompat.getLongVersionCode(archive), currentSigners(archive),
        )?.let { fail(it) }
        return archive
    }

    private fun installedPackage(context: Context): PackageInfo =
        packageInfo(context.packageManager, context.packageName, archive = false)
            ?: fail(ZhuzhalkaUpdateFailure.INVALID_APK)

    private fun packageInfo(manager: PackageManager, value: String, archive: Boolean): PackageInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            return if (archive) manager.getPackageArchiveInfo(value, flags) else manager.getPackageInfo(value, flags)
        }
        return legacyPackageInfo(manager, value, archive)
    }

    @Suppress("DEPRECATION")
    private fun legacyPackageInfo(manager: PackageManager, value: String, archive: Boolean): PackageInfo? {
        // Android 24-32 lacks PackageInfoFlags; Android 24-27 also lacks SigningInfo.
        // Remove this compatibility function when minSdk reaches 33.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (archive) manager.getPackageArchiveInfo(value, flags) else manager.getPackageInfo(value, flags)
    }

    private fun currentSigners(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()
    } else {
        legacySigners(info)
    }

    @Suppress("DEPRECATION")
    private fun legacySigners(info: PackageInfo): Set<String> {
        // Android 24-27 has no SigningInfo. Remove this fallback when minSdk reaches 28.
        return info.signatures?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()
    }

    private fun validateAsset(release: ZhuzhalkaRelease, asset: ZhuzhalkaReleaseAsset, maximum: Long) {
        if (asset.id <= 0 || asset.size <= 0 || !ZhuzhalkaUpdatePolicy.validAssetUrl(asset.url, release.tagName, asset.name)) fail(ZhuzhalkaUpdateFailure.INVALID_METADATA)
        if (asset.size > maximum) fail(ZhuzhalkaUpdateFailure.TOO_LARGE)
    }

    private fun requireSupportedPackage(context: Context) {
        if (context.packageName != ZhuzhalkaUpdatePolicy.PACKAGE_NAME) fail(ZhuzhalkaUpdateFailure.NO_COMPATIBLE_ASSET)
    }

    private suspend fun <T> operation(name: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val safeError = error as? ZhuzhalkaUpdateException ?: ZhuzhalkaUpdateException(
                if (error is IOException) ZhuzhalkaUpdateFailure.NETWORK else ZhuzhalkaUpdateFailure.INVALID_METADATA
            )
            // Network exceptions may contain a signed CDN URL; only the sanitized reason is logged.
            LogUtil.e(AppConfig.TAG, "Zhuzhalka updater $name failed: ${safeError.reason}", safeError)
            throw safeError
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun fail(reason: ZhuzhalkaUpdateFailure): Nothing = throw ZhuzhalkaUpdateException(reason)
    private data class ResponseValue<T>(val value: T)
}
