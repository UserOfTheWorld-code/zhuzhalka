package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.UUID

data class PublicSource(
    val id: String,
    val displayName: String,
    val subscriptionUrl: String,
)

enum class PublicSourceImportStatus {
    SUCCESS,
    NO_UNIQUE_SERVERS,
    UNKNOWN_SOURCE,
    INVALID_URL,
    DISABLED,
    REFRESH_FAILED,
    STORAGE_FAILED,
}

data class PublicSourceImportResult(
    val status: PublicSourceImportStatus,
    val subscriptionId: String? = null,
    val configCount: Int = 0,
    val added: Boolean = false,
    val duplicateCount: Int = 0,
)

/** Owns the curated catalog and explicit import action; MMKV and AngConfigManager own storage and parsing. */
object PublicSourceCatalog {
    val sources: List<PublicSource> = listOf(
        PublicSource(
            id = "au1rxx",
            displayName = "Au1rxx",
            subscriptionUrl = "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/v2ray-base64.txt",
        ),
        PublicSource(
            id = "eternity",
            displayName = "Eternity",
            subscriptionUrl = "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/Eternity",
        ),
        PublicSource(
            id = "vpnbook",
            displayName = "VPNBook",
            subscriptionUrl = OfficialVpnBookSource.URL,
        ),
        PublicSource(
            id = "radikal",
            displayName = "0xRadikal · Top100",
            subscriptionUrl = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt",
        ),
        PublicSource(
            id = "matin",
            displayName = "Matin · Super",
            subscriptionUrl = "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt",
        ),
        PublicSource(
            id = "nodesradar",
            displayName = "NodesRadar",
            subscriptionUrl = "https://radarhub.top/v2ray.txt",
        ),
    )

    private val importMutex = Mutex()
    private val importer = PublicSourceImporter(
        sources = sources,
        storage = MmkvPublicSourceStorage,
        refresh = AngConfigManager::updateConfigViaSub,
        reportFailure = { operation, id, error ->
            // Network exceptions can contain a subscription token. Keep the exception type only.
            LogUtil.e(
                AppConfig.TAG,
                "PublicSourceCatalog $operation failed for subscription ${id.orEmpty()}",
                IllegalStateException(error.javaClass.simpleName),
            )
        },
    )

    /** Called only in response to a user action. Creating the catalog performs no storage or network I/O. */
    suspend fun addAndRefresh(sourceId: String): PublicSourceImportResult = withContext(Dispatchers.IO) {
        importMutex.withLock { importer.addAndRefresh(sourceId) }
    }

    suspend fun addCustomAndRefresh(name: String, url: String): PublicSourceImportResult = withContext(Dispatchers.IO) {
        importMutex.withLock { importer.addCustomAndRefresh(name, url) }
    }
}

internal interface PublicSourceStorage {
    fun subscriptions(): List<SubscriptionCache>
    fun subscription(id: String): SubscriptionItem?
    fun subscriptionIds(): List<String>
    fun save(id: String, item: SubscriptionItem)
    fun remove(id: String)
    fun profileCount(id: String): Int
}

private object MmkvPublicSourceStorage : PublicSourceStorage {
    override fun subscriptions() = MmkvManager.decodeSubscriptions()
    override fun subscription(id: String) = MmkvManager.decodeSubscription(id)
    override fun subscriptionIds() = MmkvManager.decodeSubsList()
    override fun save(id: String, item: SubscriptionItem) = MmkvManager.encodeSubscription(id, item)
    override fun remove(id: String) = MmkvManager.removeNewSubscription(id)
    override fun profileCount(id: String) = MmkvManager.decodeServerList(id)
        .count { MmkvManager.decodeServerConfig(it) != null }
}

/** Injectable deterministic workflow so duplicate handling and failure preservation need no Android runtime. */
internal class PublicSourceImporter(
    private val sources: List<PublicSource>,
    private val storage: PublicSourceStorage,
    private val refresh: (SubscriptionCache) -> SubscriptionUpdateResult,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val reportFailure: (String, String?, Exception) -> Unit = { _, _, _ -> },
) {
    fun addAndRefresh(sourceId: String): PublicSourceImportResult {
        val source = sources.firstOrNull { it.id == sourceId }
            ?: return PublicSourceImportResult(PublicSourceImportStatus.UNKNOWN_SOURCE)
        return addCustomAndRefresh(source.displayName, source.subscriptionUrl)
    }

    fun addCustomAndRefresh(name: String, url: String): PublicSourceImportResult {
        val normalizedUrl = url.trim()
        if (!isHttpsSubscription(normalizedUrl)) {
            return PublicSourceImportResult(PublicSourceImportStatus.INVALID_URL)
        }

        var subscriptionId: String? = null
        var created = false
        var refreshing = false
        try {
            val existing = storage.subscriptions().firstOrNull { it.subscription.url == normalizedUrl }
            val target = existing ?: run {
                val id = newId()
                check(id.isNotBlank() && storage.subscription(id) == null && id !in storage.subscriptionIds()) {
                    "New subscription ID is unavailable"
                }
                subscriptionId = id
                created = true
                // The caller supplies the localized default name; a host is a safe fallback without tokens.
                val item = SubscriptionItem(
                    remarks = name.trim().ifBlank { URI(normalizedUrl).host },
                    url = normalizedUrl,
                )
                storage.save(id, item)
                check(storage.subscription(id) == item && id in storage.subscriptionIds()) {
                    "Subscription write could not be verified"
                }
                SubscriptionCache(id, item)
            }
            subscriptionId = target.guid
            if (!target.subscription.enabled) {
                return PublicSourceImportResult(PublicSourceImportStatus.DISABLED, target.guid)
            }

            refreshing = true
            // Preserve existing names, filters, authentication and enabled state, including on repeat imports.
            val result = refresh(target.copy(subscription = target.subscription.copy()))
            val noUniqueServers = result.successCount > 0 && result.configCount == 0 && result.duplicateCount > 0
            if (result.successCount <= 0 || (result.configCount <= 0 && !noUniqueServers) || result.failureCount > 0) {
                reportFailure("refresh", target.guid, IllegalStateException("No verified profiles returned"))
                if (created && !rollback(target.guid)) {
                    return PublicSourceImportResult(PublicSourceImportStatus.STORAGE_FAILED, target.guid)
                }
                return PublicSourceImportResult(
                    PublicSourceImportStatus.REFRESH_FAILED,
                    subscriptionId = target.guid.takeUnless { created },
                )
            }

            refreshing = false
            check(storage.subscription(target.guid)?.url == normalizedUrl &&
                target.guid in storage.subscriptionIds() &&
                storage.profileCount(target.guid) >= result.configCount
            ) { "Imported profiles could not be verified" }
            if (noUniqueServers) {
                check(storage.profileCount(target.guid) == 0) { "Duplicate-only source still has indexed profiles" }
            }
            return PublicSourceImportResult(
                if (noUniqueServers) PublicSourceImportStatus.NO_UNIQUE_SERVERS else PublicSourceImportStatus.SUCCESS,
                subscriptionId = target.guid,
                configCount = result.configCount,
                added = created,
                duplicateCount = result.duplicateCount,
            )
        } catch (error: CancellationException) {
            if (created) subscriptionId?.let(::rollback)
            throw error
        } catch (error: Exception) {
            reportFailure(if (refreshing) "refresh" else "storage", subscriptionId, error)
            val rolledBack = !created || subscriptionId?.let(::rollback) == true
            return PublicSourceImportResult(
                status = if (refreshing && rolledBack) {
                    PublicSourceImportStatus.REFRESH_FAILED
                } else {
                    PublicSourceImportStatus.STORAGE_FAILED
                },
                subscriptionId = subscriptionId.takeUnless { created && rolledBack },
            )
        }
    }

    private fun rollback(id: String): Boolean = try {
        storage.remove(id)
        check(storage.subscription(id) == null && id !in storage.subscriptionIds() && storage.profileCount(id) == 0) {
            "Subscription rollback could not be verified"
        }
        true
    } catch (error: Exception) {
        reportFailure("rollback", id, error)
        false
    }

    private fun isHttpsSubscription(url: String): Boolean = try {
        val parsed = URI(url)
        parsed.scheme.equals("https", ignoreCase = true) &&
            !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
            (parsed.port == -1 || parsed.port in 1..65535)
    } catch (_: Exception) {
        false
    }
}
