package io.github.alexyoj123.vallethremote.hid

/**
 * Descriptor HID combo: raton (report 1), teclado (report 2) y consumer
 * (report 3, volumen y play/pausa).
 *
 * Esto es lo que hace que la TV vea al celular como un raton fisico de verdad
 * y no como un remoto que traduce deslizamientos a flechas del D-pad.
 */
object HidDescriptor {

    const val REPORT_ID_MOUSE = 1
    const val REPORT_ID_KEYBOARD = 2
    const val REPORT_ID_CONSUMER = 3

    val BYTES: ByteArray = byteArrayOf(
        // ---- Report ID 1: raton ----
        0x05, 0x01, //  Usage Page (Generic Desktop)
        0x09, 0x02, //  Usage (Mouse)
        0xA1.toByte(), 0x01, //  Collection (Application)
        0x85.toByte(), 0x01, //    Report ID (1)
        0x09, 0x01, //    Usage (Pointer)
        0xA1.toByte(), 0x00, //    Collection (Physical)
        0x05, 0x09, //      Usage Page (Button)
        0x19, 0x01, //      Usage Minimum (1)
        0x29, 0x03, //      Usage Maximum (3)
        0x15, 0x00, //      Logical Minimum (0)
        0x25, 0x01, //      Logical Maximum (1)
        0x75, 0x01, //      Report Size (1)
        0x95.toByte(), 0x03, //      Report Count (3)
        0x81.toByte(), 0x02, //      Input (Data,Var,Abs)
        0x75, 0x05, //      Report Size (5) — relleno
        0x95.toByte(), 0x01, //      Report Count (1)
        0x81.toByte(), 0x01, //      Input (Const)
        0x05, 0x01, //      Usage Page (Generic Desktop)
        0x09, 0x30, //      Usage (X)
        0x09, 0x31, //      Usage (Y)
        0x09, 0x38, //      Usage (Wheel)
        0x15, 0x81.toByte(), //      Logical Minimum (-127)
        0x25, 0x7F, //      Logical Maximum (127)
        0x75, 0x08, //      Report Size (8)
        0x95.toByte(), 0x03, //      Report Count (3)
        0x81.toByte(), 0x06, //      Input (Data,Var,Rel)
        0xC0.toByte(), //    End Collection
        0xC0.toByte(), //  End Collection

        // ---- Report ID 2: teclado ----
        0x05, 0x01, 0x09, 0x06,
        0xA1.toByte(), 0x01,
        0x85.toByte(), 0x02, //    Report ID (2)
        0x05, 0x07, //    Usage Page (Keyboard)
        0x19, 0xE0.toByte(), //    Usage Min (LeftControl)
        0x29, 0xE7.toByte(), //    Usage Max (RightGUI)
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95.toByte(), 0x08,
        0x81.toByte(), 0x02, //    Input (modificadores)
        0x95.toByte(), 0x01, 0x75, 0x08,
        0x81.toByte(), 0x01, //    Input (reservado)
        0x95.toByte(), 0x06, 0x75, 0x08,
        0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65,
        0x81.toByte(), 0x00, //    Input (array de 6 keycodes)
        0xC0.toByte(),

        // ---- Report ID 3: consumer (volumen, play/pausa) ----
        0x05, 0x0C,
        0x09, 0x01,
        0xA1.toByte(), 0x01,
        0x85.toByte(), 0x03, //    Report ID (3)
        0x15, 0x00,
        0x26, 0xFF.toByte(), 0x03,
        0x19, 0x00,
        0x2A, 0xFF.toByte(), 0x03,
        0x75, 0x10, 0x95.toByte(), 0x01,
        0x81.toByte(), 0x00,
        0xC0.toByte(),
    )
}

/** Usages de la pagina Consumer que usa el remoto. */
object ConsumerUsage {
    const val PLAY_PAUSE = 0x00CD
    const val STOP = 0x00B7
    const val NEXT = 0x00B5
    const val PREVIOUS = 0x00B6
    const val FAST_FORWARD = 0x00B3
    const val REWIND = 0x00B4
    const val MUTE = 0x00E2
    const val VOLUME_UP = 0x00E9
    const val VOLUME_DOWN = 0x00EA
    const val HOME = 0x0223
    const val BACK = 0x0224
    const val MENU = 0x0040
}

/**
 * Teclado HID: caracter -> (usage, necesita shift).
 *
 * Es un mapa de teclado US porque el HID manda posiciones de tecla, no
 * caracteres: la TV decide como interpretarlas segun SU distribucion. Las
 * vocales acentuadas y la enie se normalizan antes de llegar aca (ver
 * [HidKeyboard.normalize]) — buscar "mi villano favorito" funciona igual sin
 * acentos en YouTube y en Netflix.
 */
object HidKeyboard {

    const val MOD_SHIFT = 0x02

    val KEY_ENTER = 0x28
    val KEY_ESC = 0x29
    val KEY_BACKSPACE = 0x2A
    val KEY_TAB = 0x2B
    val KEY_RIGHT = 0x4F
    val KEY_LEFT = 0x50
    val KEY_DOWN = 0x51
    val KEY_UP = 0x52

    private val map: Map<Char, Pair<Int, Boolean>> = buildMap {
        for (c in 'a'..'z') put(c, (0x04 + (c - 'a')) to false)
        for (c in 'A'..'Z') put(c, (0x04 + (c - 'A')) to true)
        put('1', 0x1E to false); put('2', 0x1F to false); put('3', 0x20 to false)
        put('4', 0x21 to false); put('5', 0x22 to false); put('6', 0x23 to false)
        put('7', 0x24 to false); put('8', 0x25 to false); put('9', 0x26 to false)
        put('0', 0x27 to false)
        put(' ', 0x2C to false)
        put('-', 0x2D to false); put('=', 0x2E to false)
        put('[', 0x2F to false); put(']', 0x30 to false)
        put('\\', 0x31 to false); put(';', 0x33 to false)
        put('\'', 0x34 to false); put('`', 0x35 to false)
        put(',', 0x36 to false); put('.', 0x37 to false); put('/', 0x38 to false)
        put('!', 0x1E to true); put('@', 0x1F to true); put('#', 0x20 to true)
        put('$', 0x21 to true); put('%', 0x22 to true); put('^', 0x23 to true)
        put('&', 0x24 to true); put('*', 0x25 to true); put('(', 0x26 to true)
        put(')', 0x27 to true); put('_', 0x2D to true); put('+', 0x2E to true)
        put(':', 0x33 to true); put('"', 0x34 to true); put('?', 0x38 to true)
    }

    fun usageFor(c: Char): Pair<Int, Boolean>? = map[c]

    /** Quita acentos y convierte la enie: el HID US no las tiene. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when (c) {
                    'á', 'à', 'ä', 'â' -> 'a'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'ó', 'ò', 'ö', 'ô' -> 'o'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    'ñ' -> 'n'
                    'Á', 'À', 'Ä', 'Â' -> 'A'
                    'É', 'È', 'Ë', 'Ê' -> 'E'
                    'Í', 'Ì', 'Ï', 'Î' -> 'I'
                    'Ó', 'Ò', 'Ö', 'Ô' -> 'O'
                    'Ú', 'Ù', 'Ü', 'Û' -> 'U'
                    'Ñ' -> 'N'
                    '¿' -> '?'
                    '¡' -> '!'
                    else -> c
                },
            )
        }
        return sb.toString()
    }
}
