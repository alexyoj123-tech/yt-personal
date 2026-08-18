package io.github.alexyoj123.hapercontroler.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.alexyoj123.hapercontroler.core.DiagLog

/**
 * Escucha de una sola pulsacion: se toca el boton, se habla, la frase corta
 * sola. Nada de mantener presionado.
 *
 * El reconocimiento pasa **en el celular** — el microfono de la TV no se usa
 * nunca. Al log solo va la intencion detectada y la longitud del texto, jamas
 * el contenido de lo que se dicto.
 */
class VoiceController(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var busy = false

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Debe llamarse desde el hilo principal: lo exige SpeechRecognizer. */
    fun listenOnce(
        onReady: () -> Unit = {},
        onRms: (Float) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (busy) return
        if (!isAvailable) {
            onError("Este celular no tiene reconocimiento de voz disponible.")
            return
        }
        busy = true

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onReady()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = onRms(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                busy = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                release()
                if (text.isBlank()) {
                    onError("No entendí nada. Probá de nuevo.")
                } else {
                    DiagLog.i("voz", "frase reconocida (${text.length} caracteres)")
                    onResult(text)
                }
            }

            override fun onError(error: Int) {
                busy = false
                release()
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Problema con el micrófono."
                    SpeechRecognizer.ERROR_CLIENT -> "El reconocedor se cerró solo."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso de micrófono."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "El reconocimiento de voz necesita datos y no hubo respuesta."
                    SpeechRecognizer.ERROR_NO_MATCH -> "No entendí nada. Probá de nuevo."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No escuché nada."
                    else -> "Falló el reconocimiento (código $error)."
                }
                DiagLog.w("voz", "error de reconocimiento: $error")
                onError(message)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // es-419 es el español latinoamericano; si el celular no lo tiene,
            // los otros dos son los respaldos naturales para Guatemala.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-419")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-GT")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        runCatching { sr.startListening(intent) }.onFailure {
            busy = false
            release()
            onError("No se pudo iniciar la escucha: ${it.message}")
        }
    }

    fun cancel() {
        busy = false
        runCatching { recognizer?.cancel() }
        release()
    }

    private fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
