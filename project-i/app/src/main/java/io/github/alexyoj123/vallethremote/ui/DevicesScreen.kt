package io.github.alexyoj123.vallethremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexyoj123.vallethremote.core.ConnectionState
import io.github.alexyoj123.vallethremote.core.DriverKind

@Composable
fun DevicesScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val devices by vm.devices.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val connection by vm.connection.collectAsState()
    val activo = vm.repo.activeDriver.collectAsState().value?.device?.id

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Button(
            onClick = { vm.scan() },
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (scanning) "Buscando en la red…" else "Buscar dispositivos") }

        if (scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        (connection as? ConnectionState.NeedsPairing)?.let { pareo ->
            AvisoCard(titulo = pareo.title, detalle = "Hacé esto en el dispositivo:", pasos = pareo.steps)
        }

        if (devices.isEmpty() && !scanning) {
            AvisoCard(
                titulo = "Todavía no hay dispositivos",
                detalle = "Asegurate de que el celular y la TV estén en la misma red Wi-Fi. " +
                    "Una TV apagada no responde: encendela una vez para que quede guardada, " +
                    "después se puede prender con Wake-on-LAN.",
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices, key = { it.id }) { device ->
                val implementado = device.kind != DriverKind.ANDROID_TV_REMOTE && device.kind != DriverKind.WEBOS
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (device.id == activo) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                        FilaEtiqueta("Protocolo", device.kind.label)
                        FilaEtiqueta("Dirección", "${device.host}:${device.port}")
                        device.macAddress?.let { FilaEtiqueta("MAC", it) }

                        if (!implementado) {
                            Text(
                                "Detectado, pero su driver todavía no está implementado (queda para la fase 3). " +
                                    "No se conecta para no dejar botones que no hacen nada.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.select(device) },
                                enabled = implementado,
                            ) { Text(if (device.id == activo) "Reconectar" else "Usar este") }
                            OutlinedButton(onClick = { vm.forget(device) }) { Text("Olvidar") }
                        }
                    }
                }
            }
        }

        TextButton(onClick = { vm.refreshApps() }) { Text("Actualizar lista de apps de la TV") }
    }
}
