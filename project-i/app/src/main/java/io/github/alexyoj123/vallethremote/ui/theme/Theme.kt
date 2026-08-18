package io.github.alexyoj123.vallethremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Un control remoto se usa a oscuras: el tema es oscuro siempre, no
// DayNight. Es una decision de uso, no de gusto.
private val Verde = Color(0xFF4ADE9B)
private val VerdeOscuro = Color(0xFF17301F)
private val Fondo = Color(0xFF0E1116)
private val Superficie = Color(0xFF161B23)
private val SuperficieAlta = Color(0xFF1E2530)
private val Texto = Color(0xFFE6EAF0)
private val TextoTenue = Color(0xFF9AA4B2)
private val Rojo = Color(0xFFE0747A)

private val esquema = darkColorScheme(
    primary = Verde,
    onPrimary = Color(0xFF06281A),
    primaryContainer = VerdeOscuro,
    onPrimaryContainer = Verde,
    secondary = Color(0xFF7FB3FF),
    onSecondary = Color(0xFF07203F),
    background = Fondo,
    onBackground = Texto,
    surface = Superficie,
    onSurface = Texto,
    surfaceVariant = SuperficieAlta,
    onSurfaceVariant = TextoTenue,
    error = Rojo,
    onError = Color(0xFF2B0A0C),
    outline = Color(0xFF2C3441),
)

private val tipografia = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun VallEthRemoteTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = esquema,
        typography = tipografia,
        content = content,
    )
}
