package io.github.alexyoj123.hapercontroler.deploy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Una linea de releases a vigilar. Un repo puede publicar varias a la vez
 * (en yt-personal conviven `ytp-a-`, `ytp-d-origin-`, `ytp-f-`, `ytp-g-`,
 * `ytp-i-`), asi que el prefijo del tag es lo que separa una de otra.
 */
data class WatchedLine(
    val repo: String,
    val tagPrefix: String,
    val label: String,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("repo", repo)
        put("tagPrefix", tagPrefix)
        put("label", label)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = WatchedLine(
            repo = o.optString("repo"),
            tagPrefix = o.optString("tagPrefix"),
            label = o.optString("label").ifBlank { o.optString("tagPrefix") },
            enabled = o.optBoolean("enabled", true),
        )
    }
}

data class DeployConfig(
    val enabled: Boolean = false,
    /** Id del dispositivo destino; tiene que ser uno con ADB. */
    val targetDeviceId: String? = null,
    val lines: List<WatchedLine> = DEFAULT_LINES,
    /** Hora local del chequeo diario (0-23). */
    val hour: Int = 4,
) {
    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        targetDeviceId?.let { put("targetDeviceId", it) }
        put("hour", hour)
        put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
    }.toString()

    companion object {
        /**
         * Lo que el dueno ya tiene publicado en su propio repo. El orden de
         * esta lista NO decide el orden de instalacion: eso lo decide
         * [DeployEngine], que siempre pone microG primero.
         */
        val DEFAULT_LINES = listOf(
            WatchedLine("alexyoj123-tech/yt-personal", "ytp-d-origin-", "YouTube Origin (Android TV)"),
            WatchedLine("alexyoj123-tech/yt-personal", "ytp-a-", "Pipeline Morphe (microG + SmartTube)"),
        )

        fun fromJson(raw: String?): DeployConfig {
            if (raw.isNullOrBlank()) return DeployConfig()
            return runCatching {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("lines")
                val lines = if (arr == null) {
                    DEFAULT_LINES
                } else {
                    List(arr.length()) { WatchedLine.fromJson(arr.getJSONObject(it)) }
                }
                DeployConfig(
                    enabled = o.optBoolean("enabled", false),
                    targetDeviceId = o.optString("targetDeviceId").takeIf { it.isNotBlank() },
                    lines = lines,
                    hour = o.optInt("hour", 4).coerceIn(0, 23),
                )
            }.getOrDefault(DeployConfig())
        }
    }
}

/** Resultado de un chequeo, para la notificacion y la pantalla. */
data class DeployReport(
    val checkedAtMs: Long,
    val entries: List<Entry>,
) {
    data class Entry(
        val label: String,
        val packageName: String?,
        val outcome: Outcome,
        val detail: String,
    )

    enum class Outcome { INSTALADO, AL_DIA, OMITIDO, ERROR }

    val instalados: Int get() = entries.count { it.outcome == Outcome.INSTALADO }
    val errores: Int get() = entries.count { it.outcome == Outcome.ERROR }

    fun resumen(): String = when {
        entries.isEmpty() -> "Nada que revisar"
        errores > 0 && instalados > 0 -> "$instalados instalada(s), $errores con error"
        errores > 0 -> "$errores con error"
        instalados > 0 -> "$instalados actualización(es) instalada(s)"
        else -> "Todo al día"
    }
}
