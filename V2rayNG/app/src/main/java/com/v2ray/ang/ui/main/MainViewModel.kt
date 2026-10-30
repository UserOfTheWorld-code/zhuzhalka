package com.v2ray.ang.ui.main

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RecentSuccessfulServer
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.handler.PublicSourceCatalog
import com.v2ray.ang.handler.PublicSourceImportResult
import com.v2ray.ang.handler.PublicSourceImportStatus
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.RecentSuccessfulServers
import com.v2ray.ang.handler.CommunityRanking
import com.v2ray.ang.handler.CommunityRepository
import com.v2ray.ang.handler.CommunitySnapshot
import com.v2ray.ang.handler.CommunityVote
import com.v2ray.ang.core.ServiceWebProbe
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

private fun applyTestDelayResults(
    servers: List<ServersCache>,
    updates: Map<String, Long>,
): List<ServersCache> = servers.map { server ->
    val delayMillis = updates[server.guid]
    if (delayMillis == null || delayMillis == server.testDelayMillis) {
        server
    } else {
        server.copy(testDelayMillis = delayMillis)
    }
}

private fun applyTestDelayResultsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, Long>,
): List<ServerRowUiModel> = rows.map { row ->
    val delayMillis = updates[row.guid]
    if (delayMillis == null || delayMillis == row.testDelayMillis) {
        row
    } else {
        row.copy(testDelayMillis = delayMillis)
    }
}

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _zhuzhalkaState = MutableStateFlow(ZhuzhalkaState())
    val zhuzhalkaState = _zhuzhalkaState.asStateFlow()
    private var sourceJob: Job? = null
    private val communityRepository = CommunityRepository.android()
    private val _communityState = MutableStateFlow(CommunityUiState())
    internal val communityState = _communityState.asStateFlow()
    private var communityJob: Job? = null
    private var communityOperationId = 0L
    private var communityVotes: List<CommunityVote> = emptyList()

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupUiFlows = ConcurrentHashMap<String, MutableStateFlow<ServerGroupUiState>>()
    private val groupServerFlows = ConcurrentHashMap<String, StateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var testResultFlushJob: Job? = null
    private val pendingTestResults = linkedMapOf<String, Long>()

    private val testRequests = MainTestRequests()
    private var bulkTestJob: Job? = null

    private val initialPageReady = CompletableDeferred<Unit>()

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        setupGroupTab()
        runCommunityOperation { communityRepository.snapshot() }
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
            MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
            MainServiceEvent.StateStartSuccess -> {
                toastSuccess(R.string.toast_services_success)
                updateRunningState(true)
            }

            is MainServiceEvent.StateStartFailure -> {
                if (!event.message.isNullOrBlank()) {
                    toastError(event.message)
                } else {
                    toastError(R.string.toast_services_failure)
                }
                updateRunningState(false)
            }

            MainServiceEvent.StateStopSuccess -> updateRunningState(false)
            is MainServiceEvent.MeasureDelayResult -> {
                if (!uiState.value.isRunning || !testRequests.completeCurrent(event.requestId)) return
                _uiState.update { it.copy(isTesting = testRequests.isTesting, status = MainStatus.ConnectionTest(event.result)) }
            }

            is MainServiceEvent.MeasureConfigSuccess -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                queueTestResult(event.result, request)
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                if (event.requestId == testRequests.bulk?.id) {
                    _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                val scheduledFlush = testResultFlushJob
                testResultFlushJob = viewModelScope.launch {
                    scheduledFlush?.cancelAndJoin()
                    if (testRequests.bulk?.id != request.id) return@launch
                    flushPendingTestResults(request)
                    onTestsFinished(request.id)
                }
            }

            is MainServiceEvent.MeasureDelayCancelled -> {
                if (testRequests.completeCurrent(event.requestId)) resetTestStatus()
            }

            is MainServiceEvent.MeasureConfigCancelled -> {
                if (testRequests.completeBulk(event.requestId) != null) {
                    cancelPendingTestResults()
                    resetTestStatus()
                }
            }
        }
    }

    private fun queueTestResult(result: RealPingResult, request: MainTestRequests.Bulk) {
        pendingTestResults[result.guid] = result.delayMillis
        if (testResultFlushJob?.isActive == true) return

        testResultFlushJob = viewModelScope.launch {
            while (pendingTestResults.isNotEmpty()) {
                delay(TEST_RESULT_FLUSH_INTERVAL_MS)
                flushPendingTestResults(request)
            }
        }
    }

    private suspend fun flushPendingTestResults(request: MainTestRequests.Bulk) {
        if (pendingTestResults.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return

        val updates = cacheMutex.withLock {
            val drained = pendingTestResults.toMap()
            pendingTestResults.clear()
            groupDataCache[request.groupId]?.let { cached ->
                groupDataCache[request.groupId] = applyTestDelayResults(cached, drained)
            }
            drained
        }
        if (updates.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return
        mutableServerGroupState(request.groupId).update { current ->
            current.copy(
                servers = applyTestDelayResults(current.servers, updates),
                rows = applyTestDelayResultsToRows(current.rows, updates),
            )
        }
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            val delay = dataSource.getString(R.string.server_test_delay_value, result.delayMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else {
            val detail = result.errorMessage.ifBlank {
                dataSource.getString(R.string.connection_test_empty_message)
            }
            dataSource.getString(R.string.connection_test_error, detail)
        }

        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
            return status
        }

        val unknown = dataSource.getString(R.string.value_unknown)
        return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupServerFlows.computeIfAbsent(groupId) {
            val groupState = mutableServerGroupState(groupId)
            groupState
                .map { it.servers }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = groupState.value.servers,
                )
        }

    internal fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> =
        mutableServerGroupState(groupId).asStateFlow()

    private fun mutableServerGroupState(groupId: String): MutableStateFlow<ServerGroupUiState> =
        groupUiFlows.computeIfAbsent(groupId) { MutableStateFlow(ServerGroupUiState()) }

    private fun currentServers(): List<ServersCache> =
        mutableServerGroupState(uiState.value.selectedGroupId).value.servers

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            is MainAction.LoadPublicSource -> loadZhuzhalkaSource(action.sourceId, null, selectAfterLoad = action.selectAfterLoad)
            is MainAction.LoadOwnSubscription -> loadZhuzhalkaSource(null, action.url)
            is MainAction.LoadSavedSubscription -> loadZhuzhalkaSource(null, null, action.groupId, selectAfterLoad = false)
            MainAction.AutoPick -> autoPickZhuzhalka()
            MainAction.DisconnectAndAutoPick -> autoPickZhuzhalka(disconnectFirst = true)
            MainAction.CancelAutoPick -> {
                if (sourceJob?.isActive == true) {
                    _zhuzhalkaState.update { it.copy(cancelling = true) }
                    sourceJob?.cancel()
                }
            }
            is MainAction.SetCommunityEnabled -> if (!action.enabled || (!_zhuzhalkaState.value.busy && !_communityState.value.syncing)) {
                runCommunityOperation { communityRepository.setEnabled(action.enabled) }
            }
            MainAction.SyncCommunity -> if (!_zhuzhalkaState.value.busy && !_communityState.value.syncing) {
                runCommunityOperation { communityRepository.snapshot() }
            }
            is MainAction.ShareCommunitySubscription -> if (!_zhuzhalkaState.value.busy && !_communityState.value.syncing) runCommunityOperation {
                val subscription = withContext(ioDispatcher) { dataSource.getSubscriptionItem(action.groupId)?.copy() }
                val offer = requireNotNull(communityOfferForConfirmedSubscription(subscription,
                    action.expectedUrl, getString(R.string.buzz_own), System.currentTimeMillis()))
                communityRepository.share(offer)
            }
            MainAction.StopCommunitySharing -> if (!_zhuzhalkaState.value.busy && !_communityState.value.syncing) {
                runCommunityOperation { communityRepository.stopSharing() }
            }
            is MainAction.LoadCommunityList -> {
                // A received offer grants no permission to fetch. Only this explicit UI action does.
                val offer = _communityState.value.offers.firstOrNull { it.url == action.url && it.name == action.name }
                if (offer != null && _communityState.value.enabled && !_zhuzhalkaState.value.busy && !uiState.value.isRunning) {
                    loadZhuzhalkaSource(null, offer.url, customName = offer.name)
                }
            }
            MainAction.Initialize -> initialize()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing()
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.UpdateSubscriptions -> importConfigViaSub(uiState.value.selectedGroupId)
            is MainAction.UpdateGroupSubscription -> importConfigViaSub(action.groupId)
            MainAction.ExportAll -> exportAllAsync()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> importBatchConfig(action.configText)
            MainAction.LocateHandled -> consumeLocateTarget()
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent,
            is MainAction.OpenSubscriptionLink -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    // ---------- Initialization ----------
    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L
            )
        }

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): List<ServersCache> {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            cacheMutex.withLock { groupDataCache[groupId] = servers }
            servers
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>): List<ServersCache> {
        val keyword = keywordFilter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    private fun updateGroupUi(groupId: String, servers: List<ServersCache>) {
        val filteredServers = applyKeywordFilter(servers)
        mutableServerGroupState(groupId).value = ServerGroupUiState(
            servers = filteredServers,
            rows = buildServerRows(groupId, filteredServers)
        )
    }

    private fun buildServerRows(groupId: String, servers: List<ServersCache>): List<ServerRowUiModel> {
        val subscriptionRemarks = if (groupId.isEmpty()) {
            servers.asSequence()
                .map { it.profile.subscriptionId }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { subscriptionId ->
                    dataSource.getSubscriptionItem(subscriptionId)?.remarks.orEmpty()
                }
        } else {
            emptyMap()
        }
        return servers.map { server ->
            buildServerRowUiModel(
                server = server,
                subscriptionRemarks = subscriptionRemarks[server.profile.subscriptionId].orEmpty()
            )
        }
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val groups = dataSource.getSubscriptions().map {
                    GroupMapItem(
                        id = it.guid,
                        remarks = it.subscription.remarks,
                        usageInfo = it.subscription.usageInfo?.let { info ->
                            SubscriptionInfoParser.withWebPageFallback(info, it.subscription.url)
                        },
                    )
                }
                val selectedGroup = resolveSelectedGroup(groups)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupUiFlows.keys.removeAll { it !in validIds }
                groupServerFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer(),
                        subscriptionButtons = dataSource.getSubscriptions().filter { it.guid.isNotEmpty() && it.subscription.url.isNotBlank() }.map { sub ->
                            SubscriptionButton(sub.guid, sub.subscription.remarks,
                                PublicSourceCatalog.sources.firstOrNull { it.subscriptionUrl == sub.subscription.url }?.id,
                                sub.subscription.lastUpdated, sub.subscription.url)
                        },
                    )
                }
                groups.forEach { mutableServerGroupState(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    suspend fun prepareSimpleVpnStart(): Boolean {
        if (_zhuzhalkaState.value.busy || uiState.value.isRunning) return false
        _zhuzhalkaState.update { it.copy(busy = true, messageRes = null, testedGuid = null, probe = null) }
        return try {
            val issue = withContext(ioDispatcher) { simpleConnectionIssue(dataSource, dataSource.getSelectServer()) }
            if (issue != null) {
                _zhuzhalkaState.update { it.copy(messageRes = issue) }
                return false
            }
            withContext(ioDispatcher) { SettingsManager.prepareSimpleVpnMode() }
            !uiState.value.isRunning
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Simple VPN mode preparation failed", IllegalStateException(error.javaClass.simpleName))
            _zhuzhalkaState.update { it.copy(messageRes = R.string.toast_services_failure) }
            false
        } finally {
            _zhuzhalkaState.update { it.copy(busy = false) }
        }
    }

    private fun loadZhuzhalkaSource(sourceId: String?, url: String?, groupId: String? = null, selectAfterLoad: Boolean = true, customName: String? = null) {
        if (_zhuzhalkaState.value.busy) return
        sourceJob = viewModelScope.launch {
            _zhuzhalkaState.value = ZhuzhalkaState(busy = true, operationSource = sourceId)
            try {
                val saved = groupId?.let { withContext(ioDispatcher) { dataSource.getSubscriptionItem(it) } }
                val result = if (sourceId != null) PublicSourceCatalog.addAndRefresh(sourceId)
                    else PublicSourceCatalog.addCustomAndRefresh(saved?.remarks ?: customName ?: getString(R.string.buzz_own), saved?.url ?: url.orEmpty())
                _zhuzhalkaState.update { it.copy(messageRes = importResultMessage(result), importedCount = result.configCount) }
                if ((result.status == PublicSourceImportStatus.SUCCESS || result.status == PublicSourceImportStatus.NO_UNIQUE_SERVERS) && result.subscriptionId != null) {
                    if (selectAfterLoad && result.configCount > 0 && !uiState.value.isRunning) withContext(ioDispatcher) {
                        val first = loadEligibleAutoCandidates(dataSource, groupIds = setOf(result.subscriptionId)).firstOrNull()?.guid
                        if (first == null) _zhuzhalkaState.update { it.copy(messageRes = R.string.buzz_no_compatible) }
                        if (first != null && !uiState.value.isRunning) {
                            applyZhuzhalkaSelection(dataSource, result.subscriptionId, first) { group, guid ->
                                _uiState.update { it.copy(selectedGroupId = group, selectedGuid = guid) }
                            }
                        }
                    }
                    setupGroupTab(forceRefresh = true).join()
                }
            } catch (cancelled: CancellationException) {
                _zhuzhalkaState.update { it.copy(messageRes = R.string.buzz_cancelled) }
                setupGroupTab(forceRefresh = true)
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Catalog operation failed", IllegalStateException(error.javaClass.simpleName))
                _zhuzhalkaState.update { it.copy(messageRes = R.string.buzz_load_failed) }
            } finally {
                _zhuzhalkaState.update { it.copy(busy = false, cancelling = false) }
            }
        }
    }

    private fun autoPickZhuzhalka(disconnectFirst: Boolean = false) {
        if (_zhuzhalkaState.value.busy || (uiState.value.isRunning && !disconnectFirst)) return
        sourceJob = viewModelScope.launch {
            _zhuzhalkaState.value = ZhuzhalkaState(busy = true, autoPicking = true,
                waitingForDisconnect = uiState.value.isRunning, messageRes = R.string.buzz_loading)
            try {
                val stopped = awaitAutoPickDisconnect(
                    running = uiState.value.isRunning,
                    disconnectConfirmed = disconnectFirst,
                    runningUpdates = uiState.map { it.isRunning },
                    requestStop = { dataSource.sendMsg2Service(AppConfig.MSG_STATE_STOP, "") },
                    hasVpnRoute = { ServiceWebProbe.hasVpnRoute(app) },
                )
                if (!stopped) {
                    _zhuzhalkaState.update { it.copy(messageRes = R.string.buzz_stop_failed) }
                    return@launch
                }
                _zhuzhalkaState.update { it.copy(waitingForDisconnect = false) }
                // Keep the search opportunity while removing unnecessary work. The budget now
                // includes catalog downloads; blocking HTTP/JNI calls can finish after it expires.
                val deadline = SystemClock.elapsedRealtime() + 180_000L
                var recent = withContext(ioDispatcher) {
                    RecentSuccessfulServers.retain(MmkvManager.decodeRecentSuccessfulServers(), System.currentTimeMillis())
                        .filter { resolveAutoCandidate(dataSource, it.guid) != null }
                }
                persistRecentSuccessfulServers(recent)
                val cachedCandidates = withContext(ioDispatcher) { recent.mapNotNull { resolveAutoCandidate(dataSource, it.guid) } }
                val found = pickProgressively(
                    recent = cachedCandidates,
                    loadSaved = {
                        rankCommunityCandidates(withContext(ioDispatcher) {
                            loadEligibleAutoCandidates(dataSource, dataSource.getSelectServer())
                        })
                    },
                    sourceIds = PublicSourceCatalog.sources.map { it.id },
                    refreshSource = { sourceId, _ ->
                        _zhuzhalkaState.update { it.copy(attempted = 0, total = 0, testedGuid = null, probe = null) }
                        currentCoroutineContext().ensureActive()
                        _zhuzhalkaState.update { it.copy(operationSource = sourceId) }
                        val imported = PublicSourceCatalog.addAndRefresh(sourceId)
                        val refreshed = withContext(ioDispatcher) {
                            imported.subscriptionId?.let { groupId ->
                                loadEligibleAutoCandidates(dataSource, groupIds = setOf(groupId))
                            }.orEmpty()
                        }
                        rankCommunityCandidates(refreshed)
                    },
                    resolve = { candidate ->
                        withContext(ioDispatcher) {
                            resolveAutoCandidate(dataSource, candidate.guid)?.let { dataSource.decodeServerConfig(it.guid) }
                        }
                    },
                    nowMillis = SystemClock::elapsedRealtime,
                    deadlineMillis = deadline,
                    canContinue = { !uiState.value.isRunning },
                    attempt = attempt@{ candidate, attempted, total, probeDeadline ->
                        val resolved = withContext(ioDispatcher) { resolveAutoCandidate(dataSource, candidate.guid) }
                        if (resolved == null) {
                            recent = recent.filterNot { it.guid == candidate.guid }
                            persistRecentSuccessfulServers(recent)
                            false
                        } else {
                            _zhuzhalkaState.update { it.copy(attempted = attempted, total = total,
                                testedGuid = null, probe = null, operationSource = null, messageRes = R.string.buzz_checking) }
                            val testedProfile = withContext(ioDispatcher) { dataSource.decodeServerConfig(resolved.guid) }
                                ?: return@attempt false
                            val result = ServiceWebProbe.check(app, resolved.guid, probeDeadline)
                            currentCoroutineContext().ensureActive()
                            if (withContext(ioDispatcher) { resolveTestedAutoCandidate(dataSource, resolved.guid, testedProfile) } == null) {
                                return@attempt false
                            }
                            _zhuzhalkaState.update { it.copy(testedGuid = resolved.guid, probe = result) }
                            if (_communityState.value.enabled) {
                                try {
                                    val key = withContext(ioDispatcher) { communityEndpointKey(dataSource, resolved, testedProfile) }
                                    if (key != null) communityRepository.record(CommunityVote(key, "", System.currentTimeMillis(),
                                        result.passed, if (result.passed) CommunityRanking.roundLatencyMillis(result.delays.values.max()) else null))
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) { logCommunityFailure(error) }
                            }
                            if (!result.passed) {
                                recent = recent.filterNot { it.guid == resolved.guid }
                                persistRecentSuccessfulServers(recent)
                                false
                            } else {
                                val selected = withContext(ioDispatcher) {
                                    // Refreshes may replace either the GUID or its connection settings during JNI.
                                    val current = resolveTestedAutoCandidate(dataSource, resolved.guid, testedProfile)
                                    !uiState.value.isRunning && current != null &&
                                        applyZhuzhalkaSelection(dataSource, current.groupId, current.guid) { group, guid ->
                                            _uiState.update { it.copy(selectedGroupId = group, selectedGuid = guid) }
                                        }
                                }
                                if (selected) setupGroupTab(forceRefresh = true).join()
                                val accepted = selected && uiState.value.selectedGuid == resolved.guid
                                if (!accepted) _zhuzhalkaState.update { it.copy(testedGuid = null, probe = null) }
                                if (accepted) {
                                    recent = withContext(ioDispatcher) {
                                        RecentSuccessfulServers.remember(recent.filter { resolveAutoCandidate(dataSource, it.guid) != null },
                                            resolved.guid, System.currentTimeMillis())
                                    }
                                    persistRecentSuccessfulServers(recent)
                                }
                                accepted
                            }
                        }
                    },
                )
                if (!found) setupGroupTab(forceRefresh = true).join()
                _zhuzhalkaState.update { it.copy(messageRes = if (found) R.string.buzz_found else R.string.buzz_not_found) }
            } catch (cancelled: CancellationException) {
                _zhuzhalkaState.update { it.copy(messageRes = R.string.buzz_cancelled) }
                setupGroupTab(forceRefresh = true)
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Auto selection failed", IllegalStateException(error.javaClass.simpleName))
                _zhuzhalkaState.update { it.copy(messageRes =
                    if (it.waitingForDisconnect) R.string.buzz_stop_failed else R.string.buzz_load_failed) }
            } finally {
                _zhuzhalkaState.update { it.copy(busy = false, autoPicking = false, cancelling = false, waitingForDisconnect = false) }
                // Never supersede a pending opt-out or an explicit sharing operation.
                if (_communityState.value.enabled && communityJob?.isActive != true) {
                    runCommunityOperation { communityRepository.snapshot() }
                }
            }
        }
    }

    private suspend fun rankCommunityCandidates(candidates: List<AutoCandidate>): List<AutoCandidate> {
        if (!_communityState.value.enabled || communityVotes.isEmpty()) return candidates
        val votes = communityVotes
        val selected = uiState.value.selectedGuid
        val keys = withContext(ioDispatcher) {
            snapshotCommunityKeys(dataSource, candidates)
        }
        return withContext(defaultDispatcher) {
            rankCommunityCandidates(candidates, keys, votes, System.currentTimeMillis(), selected)
        }
    }

    private fun runCommunityOperation(change: suspend () -> CommunitySnapshot) {
        val operationId = ++communityOperationId
        val previous = communityJob
        previous?.cancel()
        communityJob = viewModelScope.launch {
            previous?.join()
            _communityState.update { it.copy(syncing = true, failed = false) }
            try {
                val snapshot = change()
                currentCoroutineContext().ensureActive()
                if (operationId != communityOperationId) return@launch
                communityVotes = snapshot.votes
                _communityState.value = communityUiState(snapshot, syncing = snapshot.enabled)
                if (snapshot.enabled) {
                    val exchanged = communityRepository.sync()
                    val current = communityRepository.snapshot()
                    currentCoroutineContext().ensureActive()
                    if (operationId != communityOperationId) return@launch
                    communityVotes = current.votes
                    _communityState.value = communityUiState(current,
                        failed = exchanged.readableRelays == 0 || (exchanged.publicationAttempted && exchanged.publishedRelays == 0))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                logCommunityFailure(error)
                if (operationId == communityOperationId) _communityState.update { it.copy(ready = true, failed = true) }
            } finally {
                if (operationId == communityOperationId) _communityState.update { it.copy(syncing = false) }
            }
        }
    }

    private fun logCommunityFailure(error: Exception) {
        LogUtil.e(AppConfig.TAG, "Community operation failed", IllegalStateException(error.javaClass.simpleName))
    }

    private suspend fun persistRecentSuccessfulServers(entries: List<RecentSuccessfulServer>) {
        withContext(ioDispatcher) {
            try {
                MmkvManager.encodeRecentSuccessfulServers(entries)
            } catch (error: Exception) {
                // Cache failure must not prevent selecting a server that just passed all web checks.
                LogUtil.e(AppConfig.TAG, "Recent successful server cache write failed", IllegalStateException(error.javaClass.simpleName))
            }
        }
    }

    private fun importBatchConfig(configText: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val (count, countSub) = dataSource.importBatchConfig(
                        configText, uiState.value.selectedGroupId, true
                    )
                    when {
                        count > 0 -> {
                            toast(dataSource.getString(R.string.title_import_config_count, count))
                            setupGroupTab(forceRefresh = true)
                        }

                        countSub > 0 -> setupGroupTab(forceRefresh = true)
                        else -> toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun importConfigViaSub(subId: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val result = if (subId.isEmpty()) {
                        dataSource.updateConfigViaSubAll()
                    } else {
                        val item = dataSource.getSubscriptionItem(subId) ?: return@withContext
                        dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
                    }
                    when {
                        result.successCount + result.failureCount + result.skipCount == 0 ->
                            toast(R.string.title_update_subscription_no_subscription)

                        result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                            toast(
                                getQuantityString(
                                    R.plurals.title_update_config_count,
                                    result.configCount,
                                    result.configCount,
                                )
                            )

                        else ->
                            toast(dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                    }
                    if (result.configCount > 0) {
                        setupGroupTab(forceRefresh = true)
                        refreshSelectedGuid()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            val guids = currentServers().map { it.guid }
                            guids.forEach { dataSource.removeServer(it) }
                            guids.size
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    duplicates.forEach { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, duplicates.size))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServerGroupState(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            val snapshot = cacheMutex.withLock { groupDataCache.toMap() }
            ensureActive()
            snapshot.forEach { (groupId, servers) ->
                ensureActive()
                updateGroupUi(groupId, servers)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    fun moveServer(groupId: String, fromPosition: Int, toPosition: Int) {
        val groupState = mutableServerGroupState(groupId).value
        val servers = groupState.servers.toMutableList()
        if (!servers.moveItem(fromPosition, toPosition)) return
        val rows = groupState.rows.toMutableList()
        rows.moveItem(fromPosition, toPosition)
        val guids = servers.map { it.guid }
        mutableServerGroupState(groupId).value = ServerGroupUiState(servers, rows)
        // A drag emits several moves; serialize writes so an older order cannot overwrite a newer one.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        serverOrderPersistenceJobs[groupId] = viewModelScope.launch(ioDispatcher) {
            previousPersistenceJob?.join()
            dataSource.encodeServerList(guids, groupId)
            cacheMutex.withLock { groupDataCache[groupId] = servers }
        }
    }

    // ---------- Testing ----------
    fun cancelAllPing() {
        bulkTestJob?.cancel()
        bulkTestJob = null
        testRequests.cancelBulk()
        testRequests.invalidateCurrent()
        cancelPendingTestResults()
        resetTestStatus()
        dataSource.cancelAllPing()
    }

    private fun resetTestStatus() {
        _uiState.update {
            it.copy(
                isTesting = testRequests.isTesting,
                status = if (testRequests.isTesting) MainStatus.Testing
                else if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    fun testAllRealPing(onlyTcp: Boolean = false) {
        cancelAllPing()
        val groupId = uiState.value.selectedGroupId
        val servers = currentServers()
        if (servers.isEmpty()) {
            return
        }
        val serverGuids = servers.map { it.guid }
        mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.testDelayMillis == 0L) server
                    else server.copy(testDelayMillis = 0L)
                },
                rows = current.rows.map { row ->
                    if (row.testDelayMillis == 0L) row
                    else row.copy(testDelayMillis = 0L)
                }
            )
        }
        val request = testRequests.beginBulk(groupId)
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = groupId,
            serverGuids = if (keywordFilter.isNotEmpty()) serverGuids else emptyList(),
            onlyTcp = onlyTcp
        )
        _uiState.update {
            it.copy(
                isTesting = true,
                status = MainStatus.Testing
            )
        }
        bulkTestJob = viewModelScope.launch {
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(serverGuids)
                val resetGuids = serverGuids.toHashSet()
                cacheMutex.withLock {
                    groupDataCache[groupId]?.let { cached ->
                        groupDataCache[groupId] = cached.map { server ->
                            if (server.guid !in resetGuids || server.testDelayMillis == 0L) server
                            else server.copy(testDelayMillis = 0L)
                        }
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
    }

    private fun cancelPendingTestResults() {
        testResultFlushJob?.cancel()
        testResultFlushJob = null
        pendingTestResults.clear()
    }

    fun testCurrentServerRealPing() {
        if (!uiState.value.isRunning) return
        val requestId = testRequests.beginCurrent()
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        dataSource.testCurrentServerRealPing(requestId)
    }

    private fun onTestsFinished(requestId: String) {
        if (testRequests.completeBulk(requestId) == null) return
        resetTestStatus()
        viewModelScope.launch(ioDispatcher) {
            cacheMutex.withLock { groupDataCache.clear() }
            reloadAllGroups(_uiState.value.groups.map { it.id })
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            val target = LocateTarget(groupId, selected)
            _uiState.update {
                it.copy(selectedGroupId = groupId, locateTarget = target)
            }
        }
    }

    private fun consumeLocateTarget() {
        _uiState.update { it.copy(locateTarget = null) }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        if (running && _zhuzhalkaState.value.autoPicking && !_zhuzhalkaState.value.waitingForDisconnect) sourceJob?.cancel()
        if (!running || clearTestingText) testRequests.invalidateCurrent()
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                isTesting = testRequests.isTesting,
                status = if (!clearTestingText && state.isRunning == running) state.status
                else if (running) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    override fun onCleared() {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private companion object {
        const val TEST_RESULT_FLUSH_INTERVAL_MS = 500L
    }
}
