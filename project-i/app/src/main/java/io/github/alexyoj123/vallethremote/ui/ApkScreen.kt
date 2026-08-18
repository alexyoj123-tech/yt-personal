package io.github.alexyoj123.vallethremote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexyoj123.vallethremote.core.Capability

/**
 * Envio e instalacion de APKs.
 *
 * El boton de instalar SOLO aparece si el driver activo declara APK_INSTALL.
 * Cuando no, no se muestra un error generico: se explica el motivo real
 * (Tizen no ejecuta APKs, Roku no permite sideload, etc.).
 */
@Composable
fun ApkScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val transfer by vm.apkTransfer.collectAsState()
    val pending by vm.pendingApk.collectAsState()
    val driver = vm.repo.activeDriver.collectAsState().value
    val puedeInstalar = driver != null && Capability.APK_INSTALL in driver.capabilities

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> vm.offerApk(uri) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Enviar un APK a la TV", style = MaterialTheme.typography.titleMedium)

        when {
            driver == null -> AvisoCard(
                titulo = "Sin dispositivo conectado",
                detalle = "Elegí una TV en la pestaña «Equipos» antes de enviar un APK.",
            )

            !puedeInstalar -> AvisoCard(
                titulo = "Este dispositivo no puede instalar APKs",
                detalle = vm.repo.motivoSinInstalacion(driver.kind),
            )
        }

        if (puedeInstalar) {
            Button(
                onClick = {
                    picker.launch(
                        arrayOf("application/vnd.android.package-archive", "application/octet-stream"),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Elegir un APK del celular") }

            Text(
                "También podés compartir un APK desde cualquier app (gestor de archivos, Obtainium, navegador) " +
                    "y elegir VallEthRemote: llega directo acá.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            pending?.let { uri ->
                Button(onClick = { vm.sendApk(uri) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Instalar en ${driver.device.name}")
                }
                OutlinedButton(onClick = { vm.offerApk(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar")
                }
            }
        }

        when (val t = transfer) {
            is ApkTransfer.Working -> {
                Text("Instalando ${t.name}… ${(t.progress * 100).toInt()} %")
                LinearProgressIndicator(
                    progress = { t.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is ApkTransfer.Done -> AvisoCard(
                titulo = "Instalado",
                detalle = "${t.name} quedó instalado en la TV.",
                accion = {
                    OutlinedButton(onClick = { vm.clearApkTransfer() }) { Text("Listo") }
                },
            )

            is ApkTransfer.Failed -> AvisoCard(
                titulo = "No se pudo instalar ${t.name}",
                // El motivo es el texto crudo de `pm install`, no un mensaje
                // inventado: es lo unico que sirve para diagnosticar despues.
                detalle = t.reason,
                accion = {
                    OutlinedButton(onClick = { vm.clearApkTransfer() }) { Text("Entendido") }
                },
            )

            ApkTransfer.Idle -> Unit
        }
    }
}
