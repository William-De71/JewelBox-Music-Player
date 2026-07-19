package com.jewelbox.player.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jewelbox.player.R
import com.jewelbox.player.data.net.DiscoveredServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.server_address_label), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.url,
                onValueChange = vm::onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.server_address_hint)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = vm::test,
                    enabled = state.url.isNotBlank() && state.test !is TestStatus.Testing,
                ) {
                    if (state.test is TestStatus.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.test))
                }

                Button(
                    onClick = { vm.save(); onBack() },
                    enabled = state.url.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }

            Spacer(Modifier.height(16.dp))
            TestStatusLine(state.test)
            if (state.saved) {
                Text(
                    stringResource(R.string.address_saved),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            DiscoverySection(
                state = state,
                onToggle = vm::toggleDiscovery,
                onSelect = vm::selectServer,
            )
        }
    }
}

@Composable
private fun DiscoverySection(
    state: SettingsUiState,
    onToggle: () -> Unit,
    onSelect: (DiscoveredServer) -> Unit,
) {
    Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    OutlinedButton(onClick = onToggle) {
        if (state.discovering) {
            CircularProgressIndicator(
                modifier = Modifier.width(18.dp).height(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(if (state.discovering) R.string.discovery_stop else R.string.discovery_scan))
    }

    if (state.discoveryFailed) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.discovery_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (state.discovering && state.found.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.discovery_scanning),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    state.found.forEach { server ->
        Spacer(Modifier.height(12.dp))
        DiscoveredServerRow(
            server = server,
            isKnown = server.serverId.isNotBlank() && server.serverId == state.knownServerId,
            validating = state.validating == server.serviceName,
            failed = state.validationFailed == server.serviceName,
            onSelect = { onSelect(server) },
        )
    }
}

@Composable
private fun DiscoveredServerRow(
    server: DiscoveredServer,
    isKnown: Boolean,
    validating: Boolean,
    failed: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !validating, onClick = onSelect)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(server.serviceName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        "${server.host}:${server.port}",
                        server.version.takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.discovery_version, it) },
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (validating) {
                CircularProgressIndicator(
                    modifier = Modifier.width(18.dp).height(18.dp),
                    strokeWidth = 2.dp,
                )
            } else if (isKnown) {
                AssistChip(onClick = onSelect, label = { Text(stringResource(R.string.discovery_your_server)) })
            }
        }
        if (failed) {
            Text(
                stringResource(R.string.discovery_not_jewelbox),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TestStatusLine(status: TestStatus) {
    when (status) {
        is TestStatus.Connected -> Text(
            stringResource(R.string.server_connected),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
        is TestStatus.UnexpectedResponse -> Text(
            stringResource(R.string.server_test_failed, stringResource(R.string.unexpected_response)),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        is TestStatus.Failed -> Text(
            stringResource(
                R.string.server_test_failed,
                status.detail ?: stringResource(R.string.connection_failed),
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> Unit
    }
}
