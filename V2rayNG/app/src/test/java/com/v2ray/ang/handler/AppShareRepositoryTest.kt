package com.v2ray.ang.handler

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AppShareRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: File

    private val cacheDirectory get() = File(temporaryDirectory, "cache")
    private val shareDirectory get() = File(cacheDirectory, InstalledApkExporter.DIRECTORY)

    @Test
    fun copiesEveryByteAcrossBufferBoundariesAndRetainsTheSource() = runBlocking {
        val bytes = ByteArray(192 * 1024 + 37) { (it * 31).toByte() }
        val snapshot = installed(bytes)

        val result = InstalledApkExporter(cacheDirectory).export(snapshot)

        assertEquals(shareDirectory.canonicalFile, result.canonicalFile.parentFile)
        assertTrue(result.name.endsWith(".apk"))
        assertEquals(bytes.size.toLong(), result.length())
        assertArrayEquals(bytes, result.readBytes())
        assertArrayEquals(sha256(snapshot.source), sha256(result))
        assertArrayEquals(bytes, snapshot.source.readBytes())
        assertEquals(listOf(result), shareDirectory.listFiles()!!.toList())
    }

    @Test
    fun repeatedExportsUseDifferentFilesAndKeepTheEarlierReceiverCopy() = runBlocking {
        val snapshot = installed()
        val exporter = InstalledApkExporter(cacheDirectory)
        val first = exporter.export(snapshot)
        val second = exporter.export(snapshot)

        assertNotEquals(first, second)
        assertArrayEquals(snapshot.source.readBytes(), first.readBytes())
        assertArrayEquals(first.readBytes(), second.readBytes())
        assertEquals(2, shareDirectory.listFiles()!!.size)
    }

    @Test
    fun splitInstallationIsRejectedBeforeReadingOrCreatingACopy() {
        val snapshot = installed().copy(splits = listOf(File(temporaryDirectory, "config.arm64.apk")))
        var inputOpened = false
        val exporter = InstalledApkExporter(cacheDirectory, openInput = {
            inputOpened = true
            FileInputStream(it)
        })

        assertThrows(SplitApkSharingUnsupportedException::class.java) {
            runBlocking { exporter.export(snapshot) }
        }
        assertFalse(inputOpened)
        assertFalse(cacheDirectory.exists())
    }

    @Test
    fun emptyOrMissingSourceCannotPublishAnApk() {
        val empty = installed(byteArrayOf())
        assertThrows(IOException::class.java) {
            runBlocking { InstalledApkExporter(cacheDirectory).export(empty) }
        }
        assertTrue(empty.source.delete())
        assertThrows(IOException::class.java) {
            runBlocking { InstalledApkExporter(cacheDirectory).export(empty) }
        }
        assertFalse(cacheDirectory.exists())
    }

    @Test
    fun cancellationBetweenReadsClosesTheInputAndDeletesOnlyThePartialCopy() {
        val snapshot = installed(ByteArray(128 * 1024) { 5 })
        val parent = Job()
        var closed = false
        var reads = 0
        val exporter = InstalledApkExporter(cacheDirectory, openInput = { file ->
            object : FilterInputStream(FileInputStream(file)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (++reads == 2) parent.cancel()
                    return count
                }

                override fun close() {
                    closed = true
                    super.close()
                }
            }
        })

        assertThrows(CancellationException::class.java) {
            runBlocking(parent) { exporter.export(snapshot) }
        }
        assertEquals(2, reads)
        assertTrue(closed)
        assertTrue(snapshot.source.isFile)
        assertTrue(shareDirectory.listFiles()!!.isEmpty())
    }

    @Test
    fun cancelledCallerDoesNotTouchTheFilesystem() {
        val snapshot = installed()
        val parent = Job().apply { cancel() }

        assertThrows(CancellationException::class.java) {
            runBlocking(parent) { InstalledApkExporter(cacheDirectory).export(snapshot) }
        }
        assertFalse(cacheDirectory.exists())
    }

    @Test
    fun midCopyReadFailureClosesTheInputAndLeavesNoShareableApk() {
        val snapshot = installed(ByteArray(128 * 1024))
        var closed = false
        var reads = 0
        val exporter = InstalledApkExporter(cacheDirectory, openInput = { file ->
            object : FilterInputStream(FileInputStream(file)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (++reads == 2) throw IOException("Simulated read failure")
                    return super.read(buffer, offset, length)
                }

                override fun close() {
                    closed = true
                    super.close()
                }
            }
        })

        assertThrows(IOException::class.java) { runBlocking { exporter.export(snapshot) } }
        assertTrue(closed)
        assertTrue(shareDirectory.listFiles()!!.isEmpty())
    }

    @Test
    fun failingToOpenSourceRemovesTheCreatedPartialFile() {
        val snapshot = installed()
        val exporter = InstalledApkExporter(cacheDirectory, openInput = { throw IOException("Cannot open input") })

        assertThrows(IOException::class.java) { runBlocking { exporter.export(snapshot) } }
        assertTrue(shareDirectory.listFiles()!!.isEmpty())
    }

    @Test
    fun shortOrGrowingInputNeverPublishesAnIncompleteOrChangedApk() {
        val snapshot = installed(ByteArray(16))
        listOf(0, 15, 17).forEach { count ->
            val exporter = InstalledApkExporter(cacheDirectory, openInput = { ByteArrayInputStream(ByteArray(count)) })
            assertThrows(IOException::class.java) { runBlocking { exporter.export(snapshot) } }
            assertTrue(shareDirectory.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun versionTextCannotEscapeTheCacheOrIntroduceNonAsciiNames() = runBlocking {
        val snapshot = installed().copy(versionName = "../../Жужалка : /".repeat(10))
        val result = InstalledApkExporter(cacheDirectory).export(snapshot)

        assertEquals(shareDirectory.canonicalFile, result.canonicalFile.parentFile)
        assertTrue(result.name.all { it.code in 32..126 })
        assertTrue(Regex("[A-Za-z0-9._-]+\\.apk").matches(result.name))
        assertTrue(result.name.length < 110)
    }

    @Test
    fun negativeVersionIsRejectedAndMissingVersionNameUsesASafeLabel() {
        val snapshot = installed()
        assertThrows(IOException::class.java) {
            runBlocking { InstalledApkExporter(cacheDirectory).export(snapshot.copy(versionCode = -1)) }
        }
        assertFalse(cacheDirectory.exists())

        val result = runBlocking {
            InstalledApkExporter(cacheDirectory).export(snapshot.copy(versionName = ""))
        }
        assertTrue(result.name.startsWith("Zhuzhalka-5000302-installed-"))
    }

    @Test
    fun blockedCacheDirectoryFailsWithoutReplacingTheExistingFile() {
        val snapshot = installed()
        assertTrue(cacheDirectory.mkdirs())
        shareDirectory.writeText("keep")

        assertThrows(IOException::class.java) {
            runBlocking { InstalledApkExporter(cacheDirectory).export(snapshot) }
        }
        assertEquals("keep", shareDirectory.readText())
    }

    @Test
    fun cleanupOnlyRemovesOwnedFilesStrictlyOlderThanFortyEightHours() = runBlocking {
        val snapshot = installed()
        assertTrue(shareDirectory.mkdirs())
        val now = 1_800_000_000_000L
        val retention = TimeUnit.HOURS.toMillis(48)
        val expiredApk = cached("apk", now - retention - 1)
        val expiredPart = cached("part", now - retention - 1)
        val boundary = cached("apk", now - retention)
        val recent = cached("apk", now - 1)
        val future = cached("apk", now + 1)
        val unrelated = File(shareDirectory, "other-data.apk").apply {
            writeText("keep")
            assertTrue(setLastModified(now - retention - 1))
        }
        val subdirectory = File(shareDirectory, "nested").apply { mkdirs() }
        val nestedFile = File(subdirectory, expiredApk.name).apply { writeText("keep") }

        val result = InstalledApkExporter(cacheDirectory, nowMillis = { now }).export(snapshot)

        assertFalse(expiredApk.exists())
        assertFalse(expiredPart.exists())
        listOf(boundary, recent, future, unrelated, nestedFile, result).forEach { assertTrue(it.isFile) }
    }

    @Test
    fun cleanupHasABoundedScanAndDoesNotRemoveTheNewCopy() = runBlocking {
        val snapshot = installed()
        assertTrue(shareDirectory.mkdirs())
        val now = 1_800_000_000_000L
        repeat(140) { cached("apk", now - TimeUnit.HOURS.toMillis(49)) }

        val result = InstalledApkExporter(cacheDirectory, nowMillis = { now }).export(snapshot)

        assertTrue(result.isFile)
        assertEquals(13, shareDirectory.listFiles()!!.size)
    }

    private fun installed(bytes: ByteArray = byteArrayOf(1, 4, 9)): InstalledApkSnapshot {
        val source = File(temporaryDirectory, "installed.apk").apply { writeBytes(bytes) }
        return InstalledApkSnapshot(source, emptyList(), 5000302L, "0.3.0-alpha.3")
    }

    private fun cached(extension: String, modified: Long): File =
        File(shareDirectory, "Zhuzhalka-5000302-0.3.0-alpha.3-${UUID.randomUUID()}.$extension").apply {
            writeText("old APK")
            assertTrue(setLastModified(modified))
        }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
