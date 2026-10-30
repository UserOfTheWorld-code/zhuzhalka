package com.v2ray.ang.handler

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class InstalledApkSnapshot(
    val source: File,
    val splits: List<File>,
    val versionCode: Long,
    val versionName: String,
)

/** File-only exporter; its Android caller supplies the installed package snapshot on IO. */
internal class InstalledApkExporter(
    private val cacheDirectory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val openInput: (File) -> InputStream = { FileInputStream(it) },
    private val onCleanupFailure: (IOException) -> Unit = {},
) {
    suspend fun export(snapshot: InstalledApkSnapshot): File = exportMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (snapshot.splits.isNotEmpty()) throw SplitApkSharingUnsupportedException()
        val source = snapshot.source
        val expectedBytes = source.length()
        if (!source.isFile || expectedBytes <= 0L) throw IOException("Installed APK is missing or empty")
        if (snapshot.versionCode < 0L) throw IOException("Installed APK version is invalid")

        val directory = File(cacheDirectory, DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create app-share cache")
        if (directory.canonicalFile != File(cacheDirectory.canonicalFile, DIRECTORY)) {
            throw IOException("Invalid app-share cache directory")
        }
        cleanupExpired(directory)
        val version = snapshot.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(40).ifEmpty { "installed" }
        val name = "Zhuzhalka-${snapshot.versionCode}-$version-${UUID.randomUUID()}"
        val temporary = File(directory, "$name.part")
        val destination = File(directory, "$name.apk")
        if (!temporary.createNewFile()) throw IOException("Cannot create APK copy")
        var renamed = false
        var completed = false
        try {
            openInput(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        currentCoroutineContext().ensureActive()
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        if (copied > expectedBytes) throw IOException("Installed APK changed during copying")
                        output.write(buffer, 0, count)
                    }
                    if (copied != expectedBytes) throw IOException("Installed APK copy is incomplete")
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            if (source.length() != expectedBytes || temporary.length() != expectedBytes) {
                throw IOException("Installed APK changed during copying")
            }
            // A same-directory rename publishes only a complete file, never a partially written APK.
            if (destination.exists() || !temporary.renameTo(destination)) throw IOException("Cannot publish APK copy")
            renamed = true
            currentCoroutineContext().ensureActive()
            if (!destination.isFile || destination.length() != expectedBytes) throw IOException("APK copy is incomplete")
            completed = true
            destination
        } finally {
            if (!completed) {
                if (temporary.exists() && !temporary.delete()) {
                    onCleanupFailure(IOException("Cannot remove incomplete APK copy"))
                }
                if (renamed && destination.exists() && !destination.delete()) {
                    onCleanupFailure(IOException("Cannot remove unpublished APK copy"))
                }
            }
        }
    }

    private suspend fun cleanupExpired(directory: File) {
        val now = nowMillis()
        // Copies may be read after the chooser closes. Keep them for at least 48 hours.
        // The process-wide mutex also keeps another export's in-progress file out of cleanup.
        directory.listFiles()?.asSequence()?.take(MAX_CLEANUP_SCAN)?.forEach { file ->
            currentCoroutineContext().ensureActive()
            val modified = file.lastModified()
            if (file.isFile && OWNED_FILE.matches(file.name) && modified > 0L &&
                now >= modified && now - modified > RETENTION_MILLIS &&
                file.canonicalFile.parentFile == directory.canonicalFile && !file.delete()
            ) {
                onCleanupFailure(IOException("Cannot remove expired APK copy"))
            }
        }
    }

    companion object {
        const val DIRECTORY = "shared-apks"
        private val RETENTION_MILLIS = TimeUnit.HOURS.toMillis(48)
        private const val MAX_CLEANUP_SCAN = 128
        private val OWNED_FILE = Regex("Zhuzhalka-[0-9]+-[A-Za-z0-9._-]{1,40}-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(?:apk|part)")
        private val exportMutex = Mutex()
    }
}
