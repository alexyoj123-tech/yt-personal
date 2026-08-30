package io.github.alexyoj123.hapercontroler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexyoj123.hapercontroler.data.PointerMode
import io.github.alexyoj123.hapercontroler.hid.HidState
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Trackpad con cursor REAL.
 *
 * La diferencia con las apps gratis de Play Store: aca los deslizamientos no
 * se traducen a flechas del D-pad — se mandan como movimientos de un raton
 * Bluetooth. Cuando eso no esta disponible, la pantalla lo DICE en vez de
 * fingir un cursor que no existe.
 */
@Composable
fun TrackpadScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val hidState by vm.hidState.collectAsState()
    val emparejados by vm.pairedBluetooth.collectAsState()
    val modo = vm.pointerMode()
    var texto by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        Text(
            text = when (modo) {
                PointerMode.BLUETOOTH_HID -> "Cursor real por Bluetooth · ~15 ms"
                PointerMode.DRIVER_NATIVE -> "Cursor nativo de la TV por Wi-Fi"
                PointerMode.DPAD_FALLBACK -> "Sin cursor: los gestos mueven el foco con flechas"
            },
            style = MaterialTheme.typography.titleMedium,
        )

        if (modo == PointerMode.DPAD_FALLBACK) {
            AvisoCard(
                titulo = "Este dispositivo no tiene cursor",
                detalle = "Android TV no tiene puntero de sistema, y esta TV no acepta puntero por red. " +
                    "Para tener un cursor de verdad, conectá el celular como mouse Bluetooth con el botón de abajo. " +
                    "Mientras tanto, los deslizamientos mueven el foco con las flechas del D-pad.",
            )
        }

        // ------------------------------------------------ superficie tactil
        TouchpadSurface(vm)

        // ------------------------------------------------------ bluetooth
        Text("Mouse y teclado Bluetooth", style = MaterialTheme.typography.titleMedium)
        Text(
            text = when (val s = hidState) {
                HidState.Unsupported -> "Este celular no puede actuar como periférico HID."
                HidState.PermissionMissing -> "Falta el permiso de Bluetooth. Aceptalo cuando la app lo pida."
                HidState.BluetoothOff -> "El Bluetooth del celular está apagado."
                HidState.Idle -> "Sin activar."
                HidState.Registered -> "Registrado como mouse+teclado. Falta conectarlo a la TV."
                is HidState.Connected -> "Conectado a ${s.deviceName}."
                is HidState.Error -> s.reason
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.startHid() }, modifier = Modifier.weight(1f)) { Text("Activar") }
            OutlinedButton(onClick = { vm.stopHid() }, modifier = Modifier.weight(1f)) { Text("Apagar") }
        }

        OutlinedButton(onClick = { vm.refreshPairedBluetooth() }, modifier = Modifier.fillMaxWidth()) {
            Text("Buscar dispositivos emparejados")
        }

        if (emparejados.isEmpty()) {
            AvisoCard(
                titulo = "Primero emparejá el celular desde la TV",
                detalle = "El emparejamiento se hace DESDE la TV, no desde el celular.",
                pasos = listOf(
                    "Android TV: Ajustes → Mandos y accesorios → Añadir accesorio.",
                    "Samsung Tizen: Ajustes → General → Administrador de dispositivos externos → Teclado/Mouse Bluetooth.",
                    "Cuando aparezca «HAPER CONTROLER», elegilo.",
                    "Volvé acá y tocá «Buscar dispositivos emparejados».",
                ),
            )
        } else {
            for ((nombre, mac) in emparejados) {
                OutlinedButton(
                    onClick = { vm.connectHid(mac) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Conectar a $nombre") }
            }
        }

        // --------------------------------------------------------- teclado
        Text("Escribir en la TV", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            label = { Text("Texto a enviar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                vm.sendText(texto)
                texto = ""
            },
            enabled = texto.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enviar texto") }
    }
}
