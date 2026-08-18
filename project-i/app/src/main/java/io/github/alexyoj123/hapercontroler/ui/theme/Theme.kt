package io.github.alexyoj123.hapercontroler.ui.theme

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
// La paleta esta alineada con el icono: superficie casi negra violacea,
// acento ambar, secundario magenta.
private val Ambar = Color(0xFFF2841B)
private val AmbarOscuro = Color(0xFF3A2208)
private val Magenta = Color(0xFFC2185B)
private val Fondo = Color(0xFF12101A)
private val Superficie = Color(0xFF1A1726)
private val SuperficieAlta = Color(0xFF231F31)
private val Texto = Color(0xFFEDE9F2)
private val TextoTenue = Color(0xFFA49CB5)
private val Rojo = Color(0xFFE0747A)

private val esquema = darkColorScheme(
    primary = Ambar,
    onPrimary = Color(0xFF2B1600),
    primaryContainer = AmbarOscuro,
    onPrimaryContainer = Ambar,
    secondary = Magenta,
    onSecondary = Color(0xFFFFFFFF),
    background = Fondo,
    onBackground = Texto,
    surface = Superficie,
    onSurface = Texto,
    surfaceVariant = SuperficieAlta,
    onSurfaceVariant = TextoTenue,
    error = Rojo,
    onError = Color(0xFF2B0A0C),
    outline = Color(0xFF3A3450),
)

private val tipografia = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun HaperControlerTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = esquema,
        typography = tipografia,
        content = content,
    )
}
