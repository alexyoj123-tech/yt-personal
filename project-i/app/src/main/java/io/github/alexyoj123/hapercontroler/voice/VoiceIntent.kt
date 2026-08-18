package io.github.alexyoj123.hapercontroler.voice

import io.github.alexyoj123.hapercontroler.core.RemoteKey

/** Lo que el dueno quiso decir. */
sealed interface VoiceIntent {
    data class OpenApp(val appAlias: String) : VoiceIntent
    data class SearchInApp(val appAlias: String?, val query: String) : VoiceIntent
    data class Key(val key: RemoteKey) : VoiceIntent
    data class Unknown(val raw: String) : VoiceIntent
}

/**
 * Parseo con reglas locales: rapido, sin red y sin mandar la voz a ningun
 * servidor. El reconocimiento de voz ya paso en el celular; aca solo se
 * convierte texto en intencion.
 */
object VoiceIntentParser {

    /** Alias de apps -> clave canonica que los drivers saben resolver. */
    val APP_ALIASES: Map<String, String> = buildMap {
        listOf("youtube", "yutub", "yutu", "yutub", "you tube", "yu tub").forEach { put(it, "youtube") }
        listOf("netflix", "netflis", "netfliz", "nefli", "net flix").forEach { put(it, "netflix") }
        listOf("prime video", "prime", "amazon prime", "amazon").forEach { put(it, "prime") }
        listOf("disney", "disney plus", "disney mas", "disni").forEach { put(it, "disney") }
        listOf("spotify", "espotifai", "spotifai").forEach { put(it, "spotify") }
        listOf("max", "hbo", "hbo max").forEach { put(it, "max") }
        listOf("plex").forEach { put(it, "plex") }
        listOf("smarttube", "smart tube").forEach { put(it, "smarttube") }
    }

    private val KEY_PHRASES: List<Pair<List<String>, RemoteKey>> = listOf(
        listOf("sube el volumen", "subi el volumen", "sube volumen", "mas volumen", "subir volumen") to RemoteKey.VOLUME_UP,
        listOf("baja el volumen", "baja volumen", "menos volumen", "bajar volumen") to RemoteKey.VOLUME_DOWN,
        listOf("silencio", "mutear", "mute", "quita el sonido", "sin sonido") to RemoteKey.MUTE,
        listOf("inicio", "pantalla de inicio", "home", "menu principal") to RemoteKey.HOME,
        listOf("atras", "regresa", "volver", "vuelve") to RemoteKey.BACK,
        listOf("pausa", "pausar", "para", "detene") to RemoteKey.PLAY_PAUSE,
        listOf("reproduce", "play", "continua", "seguir") to RemoteKey.PLAY,
        listOf("apaga la tele", "apaga la tv", "apagar", "apaga") to RemoteKey.POWER,
        listOf("adelanta", "adelantar") to RemoteKey.FAST_FORWARD,
        listOf("retrocede", "retroceder") to RemoteKey.REWIND,
        listOf("siguiente", "siguiente capitulo") to RemoteKey.NEXT,
        listOf("anterior", "capitulo anterior") to RemoteKey.PREVIOUS,
        listOf("arriba") to RemoteKey.UP,
        listOf("abajo") to RemoteKey.DOWN,
        listOf("izquierda") to RemoteKey.LEFT,
        listOf("derecha") to RemoteKey.RIGHT,
        listOf("acepta", "aceptar", "ok", "selecciona") to RemoteKey.OK,
    )

    private val OPEN_PREFIXES = listOf("abre ", "abri ", "abrir ", "entra a ", "entra en ", "entrar a ", "anda a ", "poneme ", "pon ")
    private val SEARCH_PREFIXES = listOf("busca ", "busca me ", "buscame ", "buscar ", "busque ", "encuentra ", "pon ", "poneme ", "quiero ver ", "reproduce ")

    fun parse(rawText: String): VoiceIntent {
        val raw = rawText.trim()
        if (raw.isEmpty()) return VoiceIntent.Unknown(raw)
        val text = normalize(raw)

        // 1. Frases de tecla exactas — lo mas rapido y lo mas usado.
        for ((phrases, key) in KEY_PHRASES) {
            if (phrases.any { it == text }) return VoiceIntent.Key(key)
        }
        for ((phrases, key) in KEY_PHRASES) {
            if (phrases.any { text.startsWith("$it ") || text.endsWith(" $it") }) return VoiceIntent.Key(key)
        }

        // 2. "busca X en Y" / "pon X en Y"
        for (prefix in SEARCH_PREFIXES) {
            if (!text.startsWith(prefix)) continue
            val rest = text.removePrefix(prefix).trim()
            val split = rest.lastIndexOf(" en ")
            if (split > 0) {
                val query = rest.substring(0, split).trim()
                val appPart = rest.substring(split + 4).trim()
                val app = APP_ALIASES[appPart]
                if (app != null && query.isNotBlank()) {
                    return VoiceIntent.SearchInApp(app, originalSlice(raw, query))
                }
            }
            if (rest.isNotBlank()) {
                // "busca X" a secas: se busca en la app activa / busqueda global.
                APP_ALIASES[rest]?.let { return VoiceIntent.OpenApp(it) }
                return VoiceIntent.SearchInApp(null, originalSlice(raw, rest))
            }
        }

        // 3. "abre X" / "entra a X"
        for (prefix in OPEN_PREFIXES) {
            if (!text.startsWith(prefix)) continue
            val rest = text.removePrefix(prefix).trim()
            APP_ALIASES[rest]?.let { return VoiceIntent.OpenApp(it) }
            if (rest.isNotBlank()) return VoiceIntent.SearchInApp(null, originalSlice(raw, rest))
        }

        // 4. El nombre de una app a secas: "youtube".
        APP_ALIASES[text]?.let { return VoiceIntent.OpenApp(it) }

        // 5. Nada encaja: se trata como busqueda global antes que rendirse.
        return VoiceIntent.SearchInApp(null, raw)
    }

    /**
     * Devuelve el trozo original (con acentos y mayusculas) que corresponde al
     * fragmento normalizado, para no buscar "mi villano favorito" sin tildes
     * cuando el usuario si las dijo.
     */
    private fun originalSlice(raw: String, normalizedFragment: String): String {
        val normalizedRaw = normalize(raw)
        val index = normalizedRaw.indexOf(normalizedFragment)
        if (index < 0 || normalizedFragment.length > raw.length) return normalizedFragment
        val end = (index + normalizedFragment.length).coerceAtMost(raw.length)
        return raw.substring(index, end).trim()
    }

    /** minusculas, sin acentos, sin puntuacion, espacios colapsados. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            sb.append(
                when (c) {
                    'á', 'à', 'ä', 'â' -> 'a'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'ó', 'ò', 'ö', 'ô' -> 'o'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    'ñ' -> 'n'
                    ',', '.', ';', ':', '!', '?', '¿', '¡', '"' -> ' '
                    else -> c
                },
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
