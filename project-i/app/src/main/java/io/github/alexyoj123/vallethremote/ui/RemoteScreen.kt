package io.github.alexyoj123.vallethremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexyoj123.vallethremote.core.Capability
import io.github.alexyoj123.vallethremote.core.ConnectionState
import io.github.alexyoj123.vallethremote.core.RemoteKey

/**
 * Pantalla principal del remoto. Todo boton que aparece aca hace algo de
 * verdad en el dispositivo conectado: los que no, no se dibujan.
 */
@Composable
fun RemoteScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val connection by vm.connection.collectAsState()
    val apps by vm.apps.collectAsState()
    val listening by vm.listening.collectAsState()
    val voiceStatus by vm.voiceStatus.collectAsState()

    val conectado = connection is ConnectionState.Connected
    val hidConectado = vm.hid.isConnected
    val vivo = conectado || hidConectado
    val caps = vm.capabilities

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        if (!vivo) {
            AvisoCard(
                titulo = when (connection) {
                    is ConnectionState.NeedsPairing -> (connection as ConnectionState.NeedsPairing).title
                    is ConnectionState.Failed -> "No se pudo conectar"
                    else -> "Todavía no hay un dispositivo conectado"
                },
                detalle = when (val c = connection) {
                    is ConnectionState.Failed -> c.reason
                    is ConnectionState.NeedsPairing -> "Seguí estos pasos y volvé a intentar."
                    else -> "Abrí la pestaña «Equipos» y elegí tu TV. Se reconecta sola la próxima vez."
                },
                pasos = (connection as? ConnectionState.NeedsPairing)?.steps.orEmpty(),
            )
        }

        // --------------------------------------------------------- voz
        Button(
            onClick = { vm.startListening() },
            enabled = vivo && !listening,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (listening) "Escuchando…" else "🎙  Hablar")
        }
        voiceStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // -------------------------------------------------- accesos apps
        if (apps.isNotEmpty() && Capability.APP_LAUNCH in caps) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(apps.take(12)) { app ->
                    AssistChip(
                        onClick = { vm.launch(app) },
                        label = { Text(app.name, maxLines = 1) },
                    )
                }
            }
        }

        // ------------------------------------------------------- D-pad
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TeclaCuadrada("▲", { vm.key(RemoteKey.UP) }, habilitado = vivo)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TeclaCuadrada("◀", { vm.key(RemoteKey.LEFT) }, habilitado = vivo)
                TeclaCuadrada("OK", { vm.key(RemoteKey.OK) }, destacado = true, habilitado = vivo)
                TeclaCuadrada("▶", { vm.key(RemoteKey.RIGHT) }, habilitado = vivo)
            }
            TeclaCuadrada("▼", { vm.key(RemoteKey.DOWN) }, habilitado = vivo)
        }

        Spacer(Modifier.height(4.dp))

        // ------------------------------------------- volumen / navegacion
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeclaRedonda("VOL +", { vm.key(RemoteKey.VOLUME_UP) }, habilitado = vivo)
            TeclaRedonda("VOL −", { vm.key(RemoteKey.VOLUME_DOWN) }, habilitado = vivo)
            TeclaRedonda("🔇", { vm.key(RemoteKey.MUTE) }, habilitado = vivo)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeclaRedonda("◀◀", { vm.key(RemoteKey.REWIND) }, habilitado = vivo)
            TeclaRedonda("▶‖", { vm.key(RemoteKey.PLAY_PAUSE) }, habilitado = vivo)
            TeclaRedonda("▶▶", { vm.key(RemoteKey.FAST_FORWARD) }, habilitado = vivo)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { vm.key(RemoteKey.BACK) },
                enabled = vivo,
                modifier = Modifier.weight(1f),
            ) { Text("Atrás") }
            OutlinedButton(
                onClick = { vm.key(RemoteKey.HOME) },
                enabled = vivo,
                modifier = Modifier.weight(1f),
            ) { Text("Inicio") }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { vm.key(RemoteKey.MENU) },
                enabled = vivo,
                modifier = Modifier.weight(1f),
            ) { Text("Menú") }
            OutlinedButton(
                onClick = { vm.key(RemoteKey.POWER) },
                enabled = vivo,
                modifier = Modifier.weight(1f),
            ) { Text("Apagar") }
        }

        // Wake-on-LAN solo si el dispositivo REALMENTE lo soporta y ya
        // conocemos su MAC. Si no, ni aparece el boton.
        if (Capability.WAKE_ON_LAN in caps && vm.repo.activeDriver.value?.device?.macAddress != null) {
            Button(onClick = { vm.wake() }, modifier = Modifier.fillMaxWidth()) {
                Text("Encender la TV (Wake-on-LAN)")
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
