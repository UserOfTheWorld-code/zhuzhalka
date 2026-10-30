package com.v2ray.ang.handler

import android.content.Context
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface AppShareRepository {
    suspend fun prepareApk(): File
}

class SplitApkSharingUnsupportedException : IOException("Split installation cannot be shared as one APK")

/** Owns copies of this installed application, independently of verified update downloads. */
class AndroidAppShareRepository(context: Context) : AppShareRepository {
    private val applicationContext = context.applicationContext

    override suspend fun prepareApk(): File = withContext(Dispatchers.IO) {
        try {
            val applicationInfo = applicationContext.applicationInfo
            val sourcePath = applicationInfo.sourceDir
                ?: throw IOException("Installed application has no base APK")
            val snapshot = InstalledApkSnapshot(
                source = File(sourcePath),
                splits = applicationInfo.splitSourceDirs?.map(::File).orEmpty(),
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                versionName = BuildConfig.VERSION_NAME,
            )
            InstalledApkExporter(
                cacheDirectory = applicationContext.cacheDir,
                onCleanupFailure = { error ->
                    LogUtil.e(AppConfig.TAG, "App sharing: expired APK cleanup failed", error)
                },
            ).export(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            LogUtil.e(AppConfig.TAG, "App sharing: prepare installed APK failed", error)
            throw error
        }
    }
}

/** The .share authority exposes only shared-apks/, never profile data or update downloads. */
class AppShareProvider : FileProvider()
