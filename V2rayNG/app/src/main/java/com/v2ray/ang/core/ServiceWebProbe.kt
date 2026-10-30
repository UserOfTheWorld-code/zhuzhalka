package com.v2ray.ang.core

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.entities.ProfileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    suspend fun check(context: Context, guid: String, deadline: Long = Long.MAX_VALUE): ServiceProbeResult = withContext(Dispatchers.IO) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return@withContext ServiceProbeResult(emptyMap())
        // Arbitrary JSON and policy groups may route directly; only individual proxy profiles qualify.
        if (!canProbeProfile(profile)) {
            return@withContext ServiceProbeResult(emptyMap())
        }
        CoreNativeManager.initCoreEnv(context)
        val config = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!config.status) return@withContext ServiceProbeResult(emptyMap())
        val results = linkedMapOf<ProbeService, Long>()
        for (service in ProbeService.entries) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline) break
            // The pinned native core dials through this configuration, verifies destination TLS,
            // and accepts HTTP 200/204 only. Each call has two bounded 12-second attempts.
            results[service] = CoreNativeManager.measureOutboundDelay(config.content, service.url)
            currentCoroutineContext().ensureActive()
            if (results.getValue(service) < 0) break
        }
        ServiceProbeResult(results)
    }

    internal fun canProbeProfile(profile: ProfileItem): Boolean =
        profile.configType in setOf(EConfigType.VLESS, EConfigType.VMESS,
            EConfigType.TROJAN, EConfigType.SHADOWSOCKS, EConfigType.HYSTERIA2,
            EConfigType.WIREGUARD) &&
            // CoreOutboundBuilder enforces certificate pinning even if the legacy insecure flag is set.
            (profile.insecure != true || !profile.pinnedCA256.isNullOrEmpty())
}
