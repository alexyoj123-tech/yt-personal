package io.github.alexyoj123.hapercontroler.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.alexyoj123.hapercontroler.core.DiagLog

/**
 * Diagnostico y prueba de capacidades.
 *
 * Esto es lo que hace que la app se pueda arreglar dentro de un ano: cada
 * comando queda registrado con driver, resultado y latencia, y la prueba de
 * capacidades dice exactamente que funciona y que no en ESTE modelo de TV.
 */
@Composable
fun DiagnosticsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val probes by vm.probes.collectAsState()
    val probing by vm.probing.collectAsState()
    val entries by DiagLog.entries.collectAsState()
    val driver = vm.repo.activeDriver.collectAsState().value

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Estado actual", style = MaterialTheme.typography.titleMedium)
        FilaEtiqueta("Dispositivo", driver?.device?.displayName ?: "ninguno")
        FilaEtiqueta("Driver", driver?.kind?.label ?: "—")
        FilaEtiqueta("Dirección", driver?.let { "${it.device.host}:${it.device.port}" } ?: "—")
        FilaEtiqueta("Capacidades", driver?.capabilities?.joinToString { it.name } ?: "—")
        FilaEtiqueta("Modo de puntero", vm.pointerMode().name)
        FilaEtiqueta("Bluetooth HID", vm.hidState.collectAsState().value.toString())

        Button(
            onClick = { vm.runProbe() },
            enabled = !probing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (probing) "Probando…" else "Probar capacidades en esta TV") }

        if (probing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        for (probe in probes) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${if (probe.supported) "✓" else "✗"}  ${probe.label}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (probe.supported) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        probe.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    probe.latencyMs?.let { FilaEtiqueta("latencia", "$it ms") }
                }
            }
        }

        Text("Registro", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val file = vm.exportLog() ?: return@Button
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "HAPER CONTROLER — diagnóstico")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Compartir diagnóstico"))
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Exportar log") }
            OutlinedButton(onClick = { DiagLog.clear() }, modifier = Modifier.weight(1f)) {
                Text("Limpiar")
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(entries.asReversed()) { entry ->
                Text(
                    entry.format(),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.level) {
                        DiagLog.Level.ERROR -> MaterialTheme.colorScheme.error
                        DiagLog.Level.WARN -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Text(
            "El registro nunca guarda tokens de la TV ni el contenido de lo que se dicta por voz.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Lo unico que la app no puede hacer sola. Va acá adentro y no en un
        // documento aparte, que es donde nadie lo lee cuando algo no anda.
        AvisoCard(
            titulo = "Lo que todavía depende de vos",
            detalle = "Cinco cosas que la app no puede hacer por su cuenta:",
            pasos = listOf(
                "Encender la TV Samsung y aceptar el aviso de permiso la primera vez que " +
                    "esta app intente conectarse. Sin eso no hay forma de probar nada de Samsung.",
                "Emparejar el Claro TV Box: o con el código de 6 dígitos desde «Equipos», " +
                    "o habilitando «Depuración por red» en el aparato, que además desbloquea " +
                    "instalar APKs y el despliegue automático.",
                "Bluetooth HID: emparejar el celular DESDE la TV como si fuera un mouse " +
                    "(Samsung: Ajustes → Administrador de dispositivos externos → Teclado/Mouse Bluetooth).",
                "Opcional — API key de YouTube: Google Cloud Console → habilitar YouTube Data API v3 " +
                    "→ Credenciales → Clave de API. Sin ella la búsqueda por voz en la Samsung " +
                    "cae al teclado Bluetooth, que igual funciona.",
                "Correr «Probar capacidades» acá arriba con la Samsung encendida y pegar el " +
                    "resultado en docs/CONTINUIDAD-project-i.md.",
            ),
        )
    }
}
