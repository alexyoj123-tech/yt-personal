package io.github.alexyoj123.hapercontroler.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

    // Fondo con profundidad: un aura sutil de la marca sobre el fondo solido,
    // en vez del negro plano de la primera version.
    val fondo = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.05f),
            MaterialTheme.colorScheme.background,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(fondo),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
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
                        containerColor = Color.Transparent,
                    ),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                    for (entry in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = {},
                            label = { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            // Transicion fluida entre pestanas: se desvanece y se desliza un
            // poquito en vez de cortar en seco de una pantalla a otra.
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 14 })
                        .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 14 })
                },
                label = "tab",
            ) { pestanaActual ->
                Contenido(pestanaActual, vm, padding)
            }
        }
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
