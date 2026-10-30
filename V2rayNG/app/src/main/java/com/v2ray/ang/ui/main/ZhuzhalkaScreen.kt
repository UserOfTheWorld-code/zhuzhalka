package com.v2ray.ang.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.PublicSourceCatalog

@Composable
fun ZhuzhalkaScreen(
    viewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onSubscriptions: () -> Unit,
    onAdvanced: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val work by viewModel.zhuzhalkaState.collectAsStateWithLifecycle()
    val servers by viewModel.serversForGroup(state.selectedGroupId).collectAsStateWithLifecycle()
    var showServers by rememberSaveable { mutableStateOf(false) }
    var showSubscription by rememberSaveable { mutableStateOf(false) }
    var subscriptionUrl by rememberSaveable { mutableStateOf("") }
    val selected = servers.firstOrNull { it.guid == state.selectedGuid }
    val groupName = state.groups.firstOrNull { it.id == state.selectedGroupId }?.remarks.orEmpty()
    val canEdit = !work.busy && !state.isRunning

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_zhuzhalka), null, Modifier.size(42.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { heading() })
                TextButton(onClick = onAdvanced, enabled = !work.busy) { Text(stringResource(R.string.buzz_more)) }
            }
            Text(stringResource(if (state.isRunning) R.string.buzz_connected else R.string.buzz_disconnected),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { onAction(MainAction.ToggleService) },
                enabled = state.isRunning || (!work.busy && selected != null),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCD45), contentColor = Color(0xFF241E32)),
                modifier = Modifier.size(164.dp)
            ) {
                Text(stringResource(if (state.isRunning) R.string.buzz_disconnect else R.string.buzz_connect),
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                    maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = { showServers = true }, enabled = canEdit && state.groups.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(selected?.profile?.remarks?.takeIf { it.isNotBlank() } ?: stringResource(R.string.buzz_choose_server),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (groupName.isNotBlank()) Text(groupName, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (work.probe?.passed == true && work.testedGuid == state.selectedGuid) {
                Text(stringResource(R.string.buzz_services_ok), style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center)
            }
            Button(onClick = { onAction(MainAction.AutoPick) }, enabled = canEdit,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.buzz_auto))
            }
            Text(stringResource(R.string.buzz_auto_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (state.isRunning) Text(stringResource(R.string.buzz_stop_to_edit), style = MaterialTheme.typography.bodySmall)
            if (work.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (work.cancelling) R.string.buzz_cancelling else if (work.total > 0) R.string.buzz_progress else R.string.buzz_loading,
                    work.attempted, work.total), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onAction(MainAction.CancelAutoPick) }, enabled = !work.cancelling) {
                    Text(stringResource(R.string.action_cancel))
                }
            } else work.messageRes?.let { message ->
                Text(stringResource(message, work.importedCount), style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center)
            }
            Text(stringResource(R.string.buzz_public_lists), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().semantics { heading() })
            PublicSourceCatalog.sources.forEach { source ->
                val cached = state.subscriptionButtons.firstOrNull { it.publicSourceId == source.id }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        if (cached == null) onAction(MainAction.LoadPublicSource(source.id))
                        else { onAction(MainAction.SelectGroup(cached.groupId)); showServers = true }
                    }, enabled = canEdit,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text(source.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onAction(MainAction.LoadPublicSource(source.id, selectAfterLoad = false)) }, enabled = !work.busy) {
                        Text(stringResource(R.string.buzz_refresh))
                    }
                }
            }
            state.subscriptionButtons.filter { it.publicSourceId == null }.forEach { subscription ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onAction(MainAction.SelectGroup(subscription.groupId)); showServers = true },
                        enabled = canEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text(subscription.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onAction(MainAction.LoadSavedSubscription(subscription.groupId)) }, enabled = !work.busy) {
                        Text(stringResource(R.string.buzz_refresh))
                    }
                }
            }
            OutlinedButton(onClick = { showSubscription = true }, enabled = canEdit,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(stringResource(R.string.buzz_own))
            }
            TextButton(onClick = onSubscriptions, enabled = !work.busy) { Text(stringResource(R.string.buzz_manage)) }
            Text(stringResource(R.string.buzz_public_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Text(stringResource(R.string.buzz_credit), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showSubscription) AlertDialog(
        onDismissRequest = { showSubscription = false },
        modifier = Modifier.imePadding().consumeWindowInsets(WindowInsets.navigationBars),
        title = { Text(stringResource(R.string.buzz_own)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.buzz_url_hint))
                OutlinedTextField(value = subscriptionUrl, onValueChange = { subscriptionUrl = it },
                    label = { Text(stringResource(R.string.buzz_url)) }, singleLine = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = {
            onAction(MainAction.LoadOwnSubscription(subscriptionUrl.trim()))
            subscriptionUrl = ""
            showSubscription = false
        }, enabled = subscriptionUrl.isNotBlank()) { Text(stringResource(R.string.buzz_add)) } },
        dismissButton = { TextButton(onClick = { showSubscription = false }) { Text(stringResource(R.string.action_cancel)) } }
    )

    if (showServers) AlertDialog(
        onDismissRequest = { showServers = false },
        title = { Text(stringResource(R.string.buzz_choose_server)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(state.groups, key = { "group:" + it.id }) { group ->
                    TextButton(onClick = { onAction(MainAction.SelectGroup(group.id)) }) { Text(group.remarks) }
                }
                items(servers, key = { it.guid }) { server ->
                    Text(server.profile.remarks, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().selectable(selected = server.guid == state.selectedGuid,
                            role = Role.RadioButton, onClick = {
                                onAction(MainAction.SelectServer(server.guid))
                                showServers = false
                            }).padding(vertical = 14.dp, horizontal = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { showServers = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
}
