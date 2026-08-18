package io.github.alexyoj123.hapercontroler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** Boton grande y cuadrado del D-pad. */
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
    Button(
        onClick = onClick,
        enabled = habilitado,
        colors = colores,
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        modifier = modifier.size(74.dp),
    ) {
        Text(texto, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

/** Boton redondo compacto para volumen/canal. */
@Composable
fun TeclaRedonda(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = habilitado,
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier.size(60.dp),
    ) {
        Text(texto, style = MaterialTheme.typography.labelLarge)
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
