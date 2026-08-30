package io.github.alexyoj123.hapercontroler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexyoj123.hapercontroler.core.Capability
import io.github.alexyoj123.hapercontroler.core.ConnectionState
import io.github.alexyoj123.hapercontroler.core.RemoteKey
import io.github.alexyoj123.hapercontroler.hid.HidState

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
    // Se observa el estado del HID, no `hid.isConnected`: si no, la pantalla
    // no se redibuja cuando el teclado Bluetooth se conecta y los botones
    // quedan grises aunque el remoto ya este vivo.
    val hidState by vm.hidState.collectAsState()

    val conectado = connection is ConnectionState.Connected
    val hidConectado = hidState is HidState.Connected
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

        // ---------------------------------------------------- encendido
        // Solo, arriba de todo: es donde está en cualquier control remoto de
        // verdad, no mezclado con Atrás/Inicio/Menú.
        BotonEncendido(onClick = { vm.key(RemoteKey.POWER) }, habilitado = vivo)

        // --------------------------------------------------------- voz
        MicButtonGoogle(
            escuchando = listening,
            onClick = { vm.startListening() },
            habilitado = vivo && !listening,
        )
        Text(
            if (listening) "Escuchando…" else "Tocá para hablar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

        // ------------------------------- volumen · D-pad/touchpad · canal
        // Ubicación universal: volumen a la izquierda, canal a la derecha.
        // En el medio, deslizá para elegir entre el D-pad y el touchpad —
        // son las dos formas de mover el foco, no hace falta verlas juntas.
        val volumen by vm.volumePercent.collectAsState()
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BalancinVertical(
                    arriba = "VOL\n+",
                    abajo = "VOL\n−",
                    onArriba = { vm.volumeUp() },
                    onAbajo = { vm.volumeDown() },
                    habilitado = vivo,
                    nivelPorcentaje = volumen,
                )
                TeclaRedonda("🔇", { vm.key(RemoteKey.MUTE) }, habilitado = vivo)
            }

            val pagerState = rememberPagerState(pageCount = { 2 })
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .width(232.dp)
                        .height(232.dp),
                ) { pagina ->
                    when (pagina) {
                        0 -> DpadCircular(vm, habilitado = vivo)
                        else -> TouchpadSurface(
                            vm,
                            height = 232.dp,
                            pista = "Deslizá para mover · tocá para clic",
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(2) { i ->
                        val activo = pagerState.currentPage == i
                        Box(
                            Modifier
                                .size(if (activo) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (activo) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                ),
                        )
                    }
                }
                Text(
                    if (pagerState.currentPage == 0) "D-pad" else "Touchpad",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BalancinVertical(
                arriba = "CH\n+",
                abajo = "CH\n−",
                onArriba = { vm.key(RemoteKey.CHANNEL_UP) },
                onAbajo = { vm.key(RemoteKey.CHANNEL_DOWN) },
                habilitado = vivo,
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeclaRedonda("◀◀", { vm.key(RemoteKey.REWIND) }, habilitado = vivo)
            TeclaRedonda("▶‖", { vm.key(RemoteKey.PLAY_PAUSE) }, habilitado = vivo)
            TeclaRedonda("▶▶", { vm.key(RemoteKey.FAST_FORWARD) }, habilitado = vivo)
        }

        // ------------------------------------------ atrás · inicio · menú
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
            OutlinedButton(
                onClick = { vm.key(RemoteKey.MENU) },
                enabled = vivo,
                modifier = Modifier.weight(1f),
            ) { Text("Menú") }
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

/**
 * D-pad circular con anillo degradado — la otra mitad del carrusel de la
 * pagina anterior, junto con el touchpad.
 */
@Composable
private fun DpadCircular(vm: AppViewModel, habilitado: Boolean) {
    val anillo = Brush.sweepGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.primary,
        ),
    )
    Box(
        modifier = Modifier
            .size(220.dp)
            .border(3.dp, anillo, CircleShape)
            .padding(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        TeclaCuadrada(
            "▲",
            { vm.key(RemoteKey.UP) },
            habilitado = habilitado,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        TeclaCuadrada(
            "▼",
            { vm.key(RemoteKey.DOWN) },
            habilitado = habilitado,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        TeclaCuadrada(
            "◀",
            { vm.key(RemoteKey.LEFT) },
            habilitado = habilitado,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        TeclaCuadrada(
            "▶",
            { vm.key(RemoteKey.RIGHT) },
            habilitado = habilitado,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        TeclaCuadrada(
            "OK",
            { vm.key(RemoteKey.OK) },
            destacado = true,
            habilitado = habilitado,
        )
    }
}
