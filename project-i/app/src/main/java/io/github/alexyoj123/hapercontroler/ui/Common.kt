package io.github.alexyoj123.hapercontroler.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * El "resorte" detras de que los botones se sientan vivos: se achican un
 * poquito al tocarlos y rebotan de vuelta al soltar. Es lo que hace que la
 * app se sienta fluida en vez de solo responder con un cambio de color.
 */
@Composable
private fun pressScale(interactionSource: MutableInteractionSource): Float {
    val presionado by interactionSource.collectIsPressedAsState()
    val escala by animateFloatAsState(
        targetValue = if (presionado) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )
    return escala
}

/**
 * Wordmark de la barra superior. Letras gruesas de verdad (`FontWeight.Black`)
 * y tracking cerrado: `HAPER` en blanco y `CONTROLER` en el ambar del acento.
 * Va con una sola L a proposito — es la marca del dueno.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White)) { append("HAPER") }
            append(" ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("CONTROLER") }
        },
        modifier = modifier,
        fontWeight = FontWeight.Black,
        fontSize = 19.sp,
        letterSpacing = (-0.4).sp,
        maxLines = 1,
    )
}

/**
 * Tarjeta de aviso honesto. La usa toda la app cuando una capacidad NO existe
 * en el dispositivo conectado: se explica el motivo real en vez de dejar un
 * boton que parece funcionar y no hace nada.
 */
@Composable
fun AvisoCard(
    titulo: String,
    detalle: String,
    modifier: Modifier = Modifier,
    pasos: List<String> = emptyList(),
    accion: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium)
            Text(
                detalle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pasos.forEachIndexed { index, paso ->
                Text(
                    "${index + 1}. $paso",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            accion?.invoke()
        }
    }
}

/** Boton grande y cuadrado del D-pad. Rebota al tocarlo. */
@Composable
fun TeclaCuadrada(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destacado: Boolean = false,
    habilitado: Boolean = true,
) {
    val colores = if (destacado) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
    val interaction = remember { MutableInteractionSource() }
    val escala = pressScale(interaction)
    Button(
        onClick = onClick,
        enabled = habilitado,
        colors = colores,
        interactionSource = interaction,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(4.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (destacado) 6.dp else 2.dp),
        modifier = modifier
            .size(74.dp)
            .scale(escala),
    ) {
        Text(texto, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

/** Boton redondo compacto para transporte de video. Rebota al tocarlo. */
@Composable
fun TeclaRedonda(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val escala = pressScale(interaction)
    FilledTonalButton(
        onClick = onClick,
        enabled = habilitado,
        interactionSource = interaction,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .size(60.dp)
            .scale(escala),
    ) {
        Text(texto, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Boton de encendido. Va SOLO, arriba de todo, como en cualquier control
 * remoto de verdad — no mezclado con Atrás/Inicio/Menú.
 */
@Composable
fun BotonEncendido(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val escala = pressScale(interaction)
    Button(
        onClick = onClick,
        enabled = habilitado,
        interactionSource = interaction,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .size(58.dp)
            .scale(escala),
    ) {
        Text("⏻", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Balancín vertical de dos mitades — volumen o canal, a los costados del
 * D-pad, como en cualquier control universal.
 */
@Composable
fun BalancinVertical(
    arriba: String,
    abajo: String,
    onArriba: () -> Unit,
    onAbajo: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
) {
    Column(
        modifier = modifier
            .width(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .alpha(if (habilitado) 1f else 0.4f),
    ) {
        MitadBalancin(arriba, onArriba, habilitado)
        HorizontalDivider(color = MaterialTheme.colorScheme.background, thickness = 1.dp)
        MitadBalancin(abajo, onAbajo, habilitado)
    }
}

@Composable
private fun MitadBalancin(texto: String, onClick: () -> Unit, habilitado: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val presionado by interaction.collectIsPressedAsState()
    val fondo by animateColorAsState(
        targetValue = if (presionado) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        } else {
            Color.Transparent
        },
        label = "rockerBg",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(fondo)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = habilitado,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FilaEtiqueta(etiqueta: String, valor: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            etiqueta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            valor,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
