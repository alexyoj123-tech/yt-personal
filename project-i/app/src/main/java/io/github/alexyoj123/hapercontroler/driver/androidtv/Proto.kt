package io.github.alexyoj123.hapercontroler.driver.androidtv

import java.io.ByteArrayOutputStream

/**
 * Codificador/decodificador minimo de protobuf.
 *
 * Se escribe a mano en vez de traer protobuf-javalite + codegen porque los
 * mensajes que usa el protocolo del control de Google TV son un punado y
 * todos son de campos escalares, bytes o submensajes. Sumar un plugin de
 * codegen al build por esto seria mas fragil que estas 120 lineas.
 */
object Proto {

    const val WIRE_VARINT = 0
    const val WIRE_LENGTH = 2

    // ------------------------------------------------------------ escribir

    class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long): Writer {
            if (value == 0L) return this // los campos por defecto no se serializan
            tag(field, WIRE_VARINT)
            writeVarint(value)
            return this
        }

        fun varintAlways(field: Int, value: Long): Writer {
            tag(field, WIRE_VARINT)
            writeVarint(value)
            return this
        }

        fun string(field: Int, value: String?): Writer {
            if (value.isNullOrEmpty()) return this
            return bytes(field, value.toByteArray(Charsets.UTF_8))
        }

        fun bytes(field: Int, value: ByteArray?): Writer {
            if (value == null || value.isEmpty()) return this
            tag(field, WIRE_LENGTH)
            writeVarint(value.size.toLong())
            out.write(value)
            return this
        }

        /** Submensaje: se serializa aparte y se mete como campo length-delimited. */
        fun message(field: Int, block: Writer.() -> Unit): Writer {
            val inner = Writer().apply(block).toByteArray()
            tag(field, WIRE_LENGTH)
            writeVarint(inner.size.toLong())
            out.write(inner)
            return this
        }

        /** Submensaje vacio: hay que emitirlo igual porque es la senal. */
        fun emptyMessage(field: Int): Writer {
            tag(field, WIRE_LENGTH)
            writeVarint(0)
            return this
        }

        private fun tag(field: Int, wire: Int) {
            writeVarint(((field shl 3) or wire).toLong())
        }

        private fun writeVarint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    fun encode(block: Writer.() -> Unit): ByteArray = Writer().apply(block).toByteArray()

    // --------------------------------------------------------------- leer

    /** Un mensaje decodificado: numero de campo -> valores crudos. */
    class Message(val fields: Map<Int, List<Field>>) {
        fun has(field: Int) = fields.containsKey(field)
        fun varint(field: Int): Long? = fields[field]?.firstOrNull()?.varint
        fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull()?.bytes
        fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
        fun message(field: Int): Message? = bytes(field)?.let { decode(it) }

        /** Primer numero de campo presente de la lista, para simular un oneof. */
        fun firstPresent(vararg candidates: Int): Int? = candidates.firstOrNull { has(it) }
    }

    class Field(val varint: Long = 0, val bytes: ByteArray? = null)

    fun decode(data: ByteArray): Message {
        val map = mutableMapOf<Int, MutableList<Field>>()
        var i = 0
        while (i < data.size) {
            val (tag, afterTag) = readVarint(data, i)
            if (afterTag < 0) break
            i = afterTag
            val field = (tag ushr 3).toInt()
            when ((tag and 0x07).toInt()) {
                WIRE_VARINT -> {
                    val (value, next) = readVarint(data, i)
                    if (next < 0) return Message(map)
                    i = next
                    map.getOrPut(field) { mutableListOf() }.add(Field(varint = value))
                }

                WIRE_LENGTH -> {
                    val (len, next) = readVarint(data, i)
                    if (next < 0) return Message(map)
                    val end = next + len.toInt()
                    if (end > data.size || end < next) return Message(map)
                    map.getOrPut(field) { mutableListOf() }
                        .add(Field(bytes = data.copyOfRange(next, end)))
                    i = end
                }

                1 -> i += 8 // fixed64, no se usa
                5 -> i += 4 // fixed32, no se usa
                else -> return Message(map)
            }
        }
        return Message(map)
    }

    /** Devuelve (valor, indiceSiguiente); indiceSiguiente = -1 si esta truncado. */
    fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift > 63) return 0L to -1
        }
        return 0L to -1
    }

    fun varintBytes(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                break
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        return out.toByteArray()
    }
}
