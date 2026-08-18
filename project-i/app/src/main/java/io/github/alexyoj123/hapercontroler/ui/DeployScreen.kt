package io.github.alexyoj123.hapercontroler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexyoj123.hapercontroler.core.DriverKind
import io.github.alexyoj123.hapercontroler.deploy.DeployConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configuracion del actualizador automatico.
 *
 * Es la capa 2 del despliegue: Obtainium baja solo pero pide un toque para
 * instalar; esto instala por ADB, que corre como usuario `shell` y por eso no
 * le aplica la restriccion de "apps desconocidas" del operador.
 */
@Composable
fun DeployScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val config by vm.deployConfig.collectAsState()
    val devices by vm.devices.collectAsState()
    val reporteRaw by vm.deployReportRaw.collectAsState()
    var token by remember { mutableStateOf("") }

    val candidatos = devices.filter { it.kind == DriverKind.ANDROID_TV_ADB }
    val destino = candidatos.firstOrNull { it.id == config.targetDeviceId }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Despliegue automático", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Revisar y actualizar a diario")
                Text(
                    "A las ${config.hour}:00, con Wi-Fi y batería suficiente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { vm.saveDeployConfig(config.copy(enabled = it)) },
            )
        }

        // Requisitos, escritos donde se configuran y no escondidos en un doc.
        AvisoCard(
            titulo = "Qué hace falta para que esto funcione",
            detalle = "Sin estas dos cosas el chequeo corre pero no puede instalar nada, " +
                "y lo va a decir en el resultado en vez de fallar en silencio.",
            pasos = listOf(
                "El celular tiene que estar en la misma Wi-Fi que el aparato.",
                "El aparato tiene que tener encendida la «Depuración por red» " +
                    "(Ajustes → Opciones de programador).",
                "Ojo: en muchos Android TV esa opción se apaga sola al reiniciar el aparato. " +
                    "Si el chequeo empieza a fallar de un día para otro, revisá eso primero.",
            ),
        )

        Text("Aparato destino", style = MaterialTheme.typography.titleMedium)
        if (candidatos.isEmpty()) {
            AvisoCard(
                titulo = "No hay ningún aparato con ADB guardado",
                detalle = "El despliegue automático necesita ADB: es lo que permite instalar sin " +
                    "que el operador lo bloquee. Habilitá la depuración por red en el aparato y " +
                    "buscá dispositivos en la pestaña «Equipos».",
            )
        } else {
            for (device in candidatos) {
                val elegido = device.id == config.targetDeviceId
                OutlinedButton(
                    onClick = { vm.saveDeployConfig(config.copy(targetDeviceId = device.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text((if (elegido) "✓ " else "") + "${device.name} · ${device.host}")
                }
            }
        }

        Text("Líneas de releases que se vigilan", style = MaterialTheme.typography.titleMedium)
        for ((index, line) in config.lines.withIndex()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(line.label)
                    Text(
                        "${line.repo} · tags ${line.tagPrefix}*",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = line.enabled,
                    onCheckedChange = { activo ->
                        val nuevas = config.lines.toMutableList()
                        nuevas[index] = line.copy(enabled = activo)
                        vm.saveDeployConfig(config.copy(lines = nuevas))
                    },
                )
            }
        }
        Text(
            "microG se instala siempre antes que cualquier build de YouTube. " +
                "Al revés, la app abre y falla en el login.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Token de GitHub (opcional)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sin token, la API de GitHub permite 60 consultas por hora por IP y el chequeo puede " +
                "fallar con «límite excedido». Con un token personal sube a 5000. Se guarda solo " +
                "en este celular y nunca aparece en el log.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                vm.saveGithubToken(token)
                token = ""
            },
            enabled = token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Guardar token") }

        Button(
            onClick = { vm.runDeployNow() },
            enabled = destino != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Revisar ahora") }

        reporteRaw?.let { ReporteCard(it) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Envio manual: la misma pantalla, porque es el mismo problema
        // (meter un APK en el aparato) resuelto a mano en vez de solo.
        ApkScreen(vm, scrollable = false)
    }
}

@Composable
private fun ReporteCard(raw: String) {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
    val cuando = json.optLong("checkedAtMs")
    val entries = json.optJSONArray("entries")
    val formato = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Último chequeo", style = MaterialTheme.typography.titleMedium)
            FilaEtiqueta("Cuándo", if (cuando > 0) formato.format(Date(cuando)) else "—")
            FilaEtiqueta("Resultado", json.optString("resumen"))
            for (i in 0 until (entries?.length() ?: 0)) {
                val e = entries?.optJSONObject(i) ?: continue
                val marca = when (e.optString("outcome")) {
                    "INSTALADO" -> "✓"
                    "AL_DIA" -> "="
                    "ERROR" -> "✗"
                    else -> "·"
                }
                Text(
                    "$marca ${e.optString("label")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (e.optString("outcome") == "ERROR") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    e.optString("detail"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
