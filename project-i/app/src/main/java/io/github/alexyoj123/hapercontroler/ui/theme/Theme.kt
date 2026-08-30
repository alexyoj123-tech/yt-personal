package io.github.alexyoj123.hapercontroler.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Sigue al sistema por defecto, pero el dueño puede forzarlo desde la app. */
enum class ThemeMode { SISTEMA, CLARO, OSCURO }

// La paleta esta alineada con el icono: acento ambar, secundario magenta.
// Existen las dos variantes — antes el tema estaba fijo en oscuro a
// proposito ("se usa a oscuras"), pero ahora el dueno lo puede elegir.
private val Ambar = Color(0xFFF2841B)
private val AmbarOscuro = Color(0xFF3A2208)
private val AmbarClaro = Color(0xFFFFE3C4)
private val Magenta = Color(0xFFC2185B)
private val Rojo = Color(0xFFE0747A)
private val RojoClaro = Color(0xFFB3261E)

private val FondoOscuro = Color(0xFF12101A)
private val SuperficieOscura = Color(0xFF1A1726)
private val SuperficieAltaOscura = Color(0xFF231F31)
private val TextoOscuro = Color(0xFFEDE9F2)
private val TextoTenueOscuro = Color(0xFFA49CB5)
private val OutlineOscuro = Color(0xFF3A3450)

private val FondoClaro = Color(0xFFFBF8F5)
private val SuperficieClara = Color(0xFFFFFFFF)
private val SuperficieAltaClara = Color(0xFFF1E9E0)
private val TextoClaro = Color(0xFF221C14)
private val TextoTenueClaro = Color(0xFF6F6459)
private val OutlineClaro = Color(0xFFDDD2C4)

private val esquemaOscuro = darkColorScheme(
    primary = Ambar,
    onPrimary = Color(0xFF2B1600),
    primaryContainer = AmbarOscuro,
    onPrimaryContainer = Ambar,
    secondary = Magenta,
    onSecondary = Color(0xFFFFFFFF),
    background = FondoOscuro,
    onBackground = TextoOscuro,
    surface = SuperficieOscura,
    onSurface = TextoOscuro,
    surfaceVariant = SuperficieAltaOscura,
    onSurfaceVariant = TextoTenueOscuro,
    error = Rojo,
    onError = Color(0xFF2B0A0C),
    outline = OutlineOscuro,
)

private val esquemaClaro = lightColorScheme(
    primary = Color(0xFFB35F0E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AmbarClaro,
    onPrimaryContainer = Color(0xFF572D00),
    secondary = Magenta,
    onSecondary = Color(0xFFFFFFFF),
    background = FondoClaro,
    onBackground = TextoClaro,
    surface = SuperficieClara,
    onSurface = TextoClaro,
    surfaceVariant = SuperficieAltaClara,
    onSurfaceVariant = TextoTenueClaro,
    error = RojoClaro,
    onError = Color(0xFFFFFFFF),
    outline = OutlineClaro,
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
    mode: ThemeMode = ThemeMode.SISTEMA,
    content: @Composable () -> Unit,
) {
    val oscuro = when (mode) {
        ThemeMode.SISTEMA -> isSystemInDarkTheme()
        ThemeMode.CLARO -> false
        ThemeMode.OSCURO -> true
    }
    MaterialTheme(
        colorScheme = if (oscuro) esquemaOscuro else esquemaClaro,
        typography = tipografia,
        content = content,
    )
}
