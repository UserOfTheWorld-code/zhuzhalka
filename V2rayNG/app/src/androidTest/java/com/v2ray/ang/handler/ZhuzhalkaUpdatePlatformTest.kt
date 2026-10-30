package com.v2ray.ang.handler

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises PackageManager signatures and the Android provider without network or installer hooks. */
@RunWith(AndroidJUnit4::class)
class ZhuzhalkaUpdatePlatformTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun ownApkPassesPackageAndSignerChecksThenRejectsNonIncreasingActualVersion() = runBlocking {
        withOwnApk { release, file ->
            val failure = verificationFailure(release, file)
            assertEquals(ZhuzhalkaUpdateFailure.NOT_NEWER, failure)
        }
    }

    @Test
    fun changedApkBytesAreRejectedBeforePackageParsing() = runBlocking {
        withOwnApk { release, file ->
            withContext(Dispatchers.IO) {
                RandomAccessFile(file, "rw").use { apk ->
                    val original = apk.read()
                    assertTrue(original >= 0)
                    apk.seek(0)
                    apk.write(original xor 1)
                }
            }
            assertEquals(ZhuzhalkaUpdateFailure.CHECKSUM_MISMATCH, verificationFailure(release, file))
        }
    }

    @Test
    fun differentlySignedReleaseFixtureCannotReplaceInstalledDebugApp() = runBlocking {
        val fixturePath = InstrumentationRegistry.getArguments().getString("signatureFixture")
        assumeTrue("Pass a separately signed release APK through the signatureFixture instrumentation argument", fixturePath != null)
        val source = withContext(Dispatchers.IO) {
            val directory = context.externalCacheDir
            assumeTrue("Target app external cache is unavailable", directory != null)
            val file = File(fixturePath!!).canonicalFile
            assumeTrue("Fixture must be the named file in target app external cache",
                file.parentFile == directory!!.canonicalFile && file.name == "update-signature-fixture.apk" && file.isFile)
            file
        }
        withApk(source) { release, file ->
            assertEquals(ZhuzhalkaUpdateFailure.SIGNATURE_MISMATCH, verificationFailure(release, file))
        }
    }

    @Test
    fun updateProviderSharesOnlyItsNarrowCacheDirectory(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, ZhuzhalkaUpdateManager.CACHE_DIRECTORY)
            assertTrue(directory.isDirectory || directory.mkdirs())
            val allowed = File(directory, "update-platform-scope-test.apk")
            val outside = File(context.cacheDir, "updater-platform-outside-scope.txt")
            try {
                allowed.writeText("provider fixture")
                outside.writeText("outside fixture")
                val authority = "${context.packageName}.updates"
                val uri = FileProvider.getUriForFile(context, authority, allowed)
                assertEquals("content", uri.scheme)
                assertEquals(authority, uri.authority)
                context.contentResolver.openInputStream(uri).use { stream ->
                    assertNotNull(stream)
                    assertEquals("provider fixture", stream!!.bufferedReader().readText())
                }
                assertThrows(IllegalArgumentException::class.java) {
                    FileProvider.getUriForFile(context, authority, outside)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    FileProvider.getUriForFile(context, authority, File(directory, "../${outside.name}"))
                }
            } finally {
                allowed.delete()
                outside.delete()
            }
        }
    }

    private suspend fun withOwnApk(block: suspend (ZhuzhalkaRelease, File) -> Unit) =
        withApk(File(context.applicationInfo.sourceDir), block)

    private suspend fun withApk(source: File, block: suspend (ZhuzhalkaRelease, File) -> Unit) {
        val fixture = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val directory = File(context.cacheDir, ZhuzhalkaUpdateManager.CACHE_DIRECTORY)
            assertTrue(directory.isDirectory || directory.mkdirs())
            val id = 9_000_000_000_000L
            val file = File(directory, "update-$id-${hash.take(16)}.apk")
            source.copyTo(file, overwrite = true)
            val name = "Zhuzhalka_9999.0.0-test.1_universal.apk"
            ZhuzhalkaRelease(
                "v9999.0.0-test.1", "9999.0.0-test.1", "Instrumentation fixture", true,
                ZhuzhalkaReleaseAsset(id, name,
                    "https://github.com/UserOfTheWorld-code/zhuzhalka/releases/download/v9999.0.0-test.1/$name",
                    file.length(), hash), null,
            ) to file
        }
        try {
            block(fixture.first, fixture.second)
        } finally {
            withContext(Dispatchers.IO) { fixture.second.delete() }
        }
    }

    private suspend fun verificationFailure(release: ZhuzhalkaRelease, file: File): ZhuzhalkaUpdateFailure {
        try {
            ZhuzhalkaUpdateManager.verifyDownloaded(context, release, file)
            fail("Fixture must not be installable as an update")
        } catch (error: ZhuzhalkaUpdateException) {
            return error.reason
        }
        error("Unexpected verification result")
    }
}
