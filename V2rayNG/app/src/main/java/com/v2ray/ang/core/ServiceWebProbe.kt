package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.entities.ProfileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class ProbeService(val url: String) {
    WHATSAPP("https://web.whatsapp.com/"),
    TELEGRAM("https://web.telegram.org/"),
    X("https://x.com/")
}

data class ServiceProbeResult(val delays: Map<ProbeService, Long>) {
    val passed: Boolean get() = ProbeService.entries.all { (delays[it] ?: -1L) >= 0 }
}

/** One-off outbound tests; this never changes the device VPN or selected profile. */
object ServiceWebProbe {
    // Shared across checks in this process: cancellation must not allow a new batch to
    // exceed the limit while a previous non-interruptible JNI measurement is finishing.
    private val nativeProbePermits = Semaphore(3)

    /** Observe Android's route for this process, not a UI copy of native service state. */
    suspend fun hasVpnRoute(context: Context): Boolean = withContext(Dispatchers.IO) {
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    suspend fun check(context: Context, guid: String, deadline: Long = Long.MAX_VALUE): ServiceProbeResult = withContext(Dispatchers.IO) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return@withContext ServiceProbeResult(emptyMap())
        // Arbitrary JSON and policy groups may route directly; only individual proxy profiles qualify.
        if (!canProbeProfile(profile)) {
            return@withContext ServiceProbeResult(emptyMap())
        }
        CoreNativeManager.initCoreEnv(context)
        val config = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!config.status) return@withContext ServiceProbeResult(emptyMap())

        // Initialize the native environment before starting any sibling measurements.
        // Each existing AAR call verifies destination TLS and accepts HTTP 200/204 only.
        // Its two 12-second HTTP attempts cannot be interrupted by coroutine cancellation;
        // the scope therefore waits for them to finish before releasing their permits.
        probeServicesConcurrently(deadline, SystemClock::elapsedRealtime, nativeProbePermits) { service ->
            CoreNativeManager.measureOutboundDelay(config.content, service.url)
        }
    }

    internal fun canProbeProfile(profile: ProfileItem): Boolean =
        profile.configType in setOf(EConfigType.VLESS, EConfigType.VMESS,
            EConfigType.TROJAN, EConfigType.SHADOWSOCKS, EConfigType.HYSTERIA2,
            EConfigType.WIREGUARD) &&
            // CoreOutboundBuilder enforces certificate pinning even if the legacy insecure flag is set.
            (profile.insecure != true || !profile.pinnedCA256.isNullOrEmpty())
}

/** Scheduling only; the caller owns native setup and the shared limit, and each service runs once. */
internal suspend fun probeServicesConcurrently(
    deadline: Long,
    now: () -> Long,
    permits: Semaphore,
    measure: suspend (ProbeService) -> Long,
): ServiceProbeResult = coroutineScope {
    val measurements = ProbeService.entries.map { service ->
        async {
            permits.withPermit {
                currentCoroutineContext().ensureActive()
                // A queued request may outlive the search budget while another call holds a permit.
                if (now() >= deadline) return@withPermit null
                val delay = measure(service)
                currentCoroutineContext().ensureActive()
                service to delay
            }
        }
    }
    ServiceProbeResult(measurements.awaitAll().filterNotNull().toMap())
}
