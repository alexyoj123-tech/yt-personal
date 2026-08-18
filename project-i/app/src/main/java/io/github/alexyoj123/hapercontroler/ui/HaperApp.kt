package io.github.alexyoj123.hapercontroler.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.github.alexyoj123.hapercontroler.core.ConnectionState

private enum class Tab(val label: String) {
    REMOTO("Remoto"),
    TRACKPAD("Mouse"),
    APPS("Apps"),
    DESPLIEGUE("Enviar"),
    EQUIPOS("Equipos"),
    DIAG("Diag"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaperApp(vm: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.REMOTO) }
    val snackbar = remember { SnackbarHostState() }

    val notice by vm.notice.collectAsState()
    val connection by vm.connection.collectAsState()
    val pendingApk by vm.pendingApk.collectAsState()

    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }

    // Un APK compartido desde otra app manda directo a la pestana de envio.
    LaunchedEffect(pendingApk) {
        if (pendingApk != null) tab = Tab.DESPLIEGUE
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Wordmark()
                        Text(
                            text = estadoCorto(connection, vm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                for (entry in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Contenido(tab, vm, padding)
    }
}

@Composable
private fun Contenido(tab: Tab, vm: AppViewModel, padding: PaddingValues) {
    val modifier = Modifier.padding(padding)
    when (tab) {
        Tab.REMOTO -> RemoteScreen(vm, modifier)
        Tab.TRACKPAD -> TrackpadScreen(vm, modifier)
        Tab.APPS -> MyAppsScreen(vm, modifier)
        Tab.DESPLIEGUE -> DeployScreen(vm, modifier)
        Tab.EQUIPOS -> DevicesScreen(vm, modifier)
        Tab.DIAG -> DiagnosticsScreen(vm, modifier)
    }
}

private fun estadoCorto(state: ConnectionState, vm: AppViewModel): String {
    val device = vm.repo.activeDriver.value?.device
    return when (state) {
        is ConnectionState.Connected -> "Conectado a ${device?.displayName ?: "la TV"}"
        is ConnectionState.Connecting -> "Conectando…"
        is ConnectionState.NeedsPairing -> state.title
        is ConnectionState.Failed -> state.reason
        ConnectionState.Disconnected -> "Sin dispositivo — abrí «Equipos»"
    }
}
