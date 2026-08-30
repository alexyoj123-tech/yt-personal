package io.github.alexyoj123.hapercontroler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * La superficie tactil del trackpad — el gesto de arrastrar, hacer tap y
 * mantener presionado. Vive aca sola para que la use tanto la pestana Mouse
 * (con toda la gestion de Bluetooth alrededor) como la pagina de touchpad
 * dentro del carrusel de Remoto, sin duplicar la logica del gesto.
 */
@Composable
fun TouchpadSurface(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    height: Dp = 320.dp,
    pista: String = "Deslizá para mover · tocá para clic\ndos dedos para desplazar · mantené para clic derecho",
) {
    val superficie = modifier
        .fillMaxWidth()
        .height(height)
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
        .pointerInput(Unit) {
            awaitEachGesture {
                val primero = awaitFirstDown(requireUnconsumed = false)
                val inicio = System.currentTimeMillis()
                var recorrido = 0f
                var multiTouch = false

                while (true) {
                    val evento = awaitPointerEvent()
                    val activos = evento.changes.filter { it.pressed }
                    if (activos.isEmpty()) break

                    if (activos.size >= 2) {
                        // Dos dedos en vertical = rueda del raton.
                        multiTouch = true
                        val dy = activos.map { it.positionChange().y }.average().toFloat()
                        if (abs(dy) > 0.5f) vm.scroll(-dy / 14f)
                        activos.forEach { it.consume() }
                    } else {
                        val cambio = activos.first()
                        val delta = cambio.positionChange()
                        if (delta.x != 0f || delta.y != 0f) {
                            recorrido += abs(delta.x) + abs(delta.y)
                            // Aceleracion suave: mover despacio da precision,
                            // mover rapido cruza la pantalla de una.
                            val velocidad = hypot(delta.x, delta.y)
                            val factor = 1.1f + (velocidad / 9f).coerceAtMost(2.4f)
                            vm.move(delta.x * factor, delta.y * factor)
                            cambio.consume()
                        }
                    }
                }

                val duracion = System.currentTimeMillis() - inicio
                // Tap = clic izquierdo. Tap largo = clic derecho.
                if (!multiTouch && recorrido < 14f) {
                    vm.click(right = duracion > 450)
                }
                primero.consume()
            }
        }

    Column(
        modifier = superficie,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            pista,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
