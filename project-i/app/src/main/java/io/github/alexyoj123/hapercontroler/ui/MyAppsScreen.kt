package io.github.alexyoj123.hapercontroler.ui

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
import androidx.compose.ui.unit.dp
import io.github.alexyoj123.hapercontroler.core.Capability

/**
 * «Mis apps» — abre paquetes instalados aunque el launcher del aparato los
 * esconda.
 *
 * Por que existe: una app sin `LEANBACK_LAUNCHER` queda instalada pero **no
 * aparece en la pantalla de inicio de un Android TV**. No es un fallo de
 * instalacion y no hay forma de arreglarlo desde el aparato. Desde acá se
 * lanza igual, resolviendo la activity con `cmd package resolve-activity`.
 */
@Composable
fun MyAppsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val apps by vm.installedApps.collectAsState()
    val cargando by vm.loadingApps.collectAsState()
    val driver = vm.repo.activeDriver.collectAsState().value
    var filtro by remember { mutableStateOf("") }

    val puedeListar = driver != null && Capability.APP_LAUNCH in driver.capabilities
    val visibles = apps.filter {
        filtro.isBlank() ||
            it.name.contains(filtro, true) ||
            it.packageName.orEmpty().contains(filtro, true)
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mis apps", style = MaterialTheme.typography.titleMedium)

        when {
            driver == null -> AvisoCard(
                titulo = "Sin dispositivo conectado",
                detalle = "Elegí un aparato en la pestaña «Equipos».",
            )

            !puedeListar -> AvisoCard(
                titulo = "Este protocolo no lista las apps instaladas",
                detalle = "El control oficial de Google TV solo sabe abrir enlaces conocidos. " +
                    "Para ver y abrir cualquier paquete instalado hace falta ADB.",
            )
        }

        if (puedeListar) {
            Button(onClick = { vm.refreshInstalledApps() }, enabled = !cargando, modifier = Modifier.fillMaxWidth()) {
                Text(if (cargando) "Leyendo el aparato…" else "Leer apps instaladas")
            }
            if (cargando) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (apps.isNotEmpty()) {
                OutlinedTextField(
                    value = filtro,
                    onValueChange = { filtro = it },
                    label = { Text("Filtrar") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${visibles.size} de ${apps.size} paquetes. Incluye los que el inicio del aparato " +
                        "no muestra por no tener launcher de TV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibles, key = { it.id }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.name, style = MaterialTheme.typography.titleMedium)
                                app.packageName?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Button(onClick = { vm.launchInstalled(app) }) { Text("Abrir") }
                        }
                    }
                }
            }
        }
    }
}
