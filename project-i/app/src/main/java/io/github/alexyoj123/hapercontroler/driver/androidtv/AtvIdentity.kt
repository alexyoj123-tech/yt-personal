package io.github.alexyoj123.hapercontroler.driver.androidtv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.alexyoj123.hapercontroler.core.DiagLog
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/**
 * Identidad TLS de cliente para el protocolo del control de Google TV.
 *
 * El protocolo exige TLS mutuo: la app tiene que presentar un certificado
 * propio, y ese certificado es lo que la TV recuerda despues del
 * emparejamiento. Se genera dentro del **Keystore de Android**, indexado por
 * dispositivo, asi la clave privada nunca sale del hardware ni del proceso.
 *
 * Ventaja de usar el Keystore de Android para esto: `KeyGenParameterSpec`
 * genera de una el certificado autofirmado, sin necesidad de BouncyCastle ni
 * de armar el X.509 a mano.
 */
object AtvIdentity {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun aliasFor(deviceId: String): String =
        "haper_atv_" + deviceId.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasIdentity(deviceId: String): Boolean = runCatching {
        keyStore().containsAlias(aliasFor(deviceId))
    }.getOrDefault(false)

    /** Borra la identidad para forzar un emparejamiento nuevo. */
    fun forget(deviceId: String) {
        runCatching { keyStore().deleteEntry(aliasFor(deviceId)) }
        DiagLog.w("atv", "identidad TLS borrada, hará falta emparejar de nuevo")
    }

    /** Crea la identidad si no existe y devuelve el alias. */
    fun ensure(deviceId: String): String {
        val alias = aliasFor(deviceId)
        val ks = keyStore()
        if (ks.containsAlias(alias)) return alias

        DiagLog.i("atv", "generando identidad TLS propia para el emparejamiento")
        val notBefore = Calendar.getInstance()
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(2048)
                .setCertificateSubject(X500Principal("CN=HAPER CONTROLER, O=haper, C=GT"))
                .setCertificateSerialNumber(BigInteger(64, SecureRandom()).abs().max(BigInteger.ONE))
                .setCertificateNotBefore(notBefore.time)
                .setCertificateNotAfter(notAfter.time)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKeyPair()
        return alias
    }

    fun certificate(deviceId: String): X509Certificate? = runCatching {
        keyStore().getCertificate(aliasFor(deviceId)) as? X509Certificate
    }.getOrNull()

    /**
     * KeyManagers respaldados por el Keystore de Android. La clave privada no
     * es extraible, pero el proveedor la puede usar igual para el handshake.
     */
    fun keyManagers(): Array<KeyManager> {
        val kmf = KeyManagerFactory.getInstance("X509")
        kmf.init(keyStore(), null)
        return kmf.keyManagers
    }

    /**
     * Modulo y exponente en big-endian **minimo**, sin el byte de signo.
     *
     * Este detalle es el que hace o rompe el emparejamiento: la
     * implementacion de referencia (`androidtvremote2`, la de Home Assistant)
     * usa `n.to_bytes((n.bit_length() + 7) // 8, "big")`, que nunca lleva el
     * 0x00 inicial. `BigInteger.toByteArray()` de Java SI lo agrega cuando el
     * bit alto esta prendido — que en un modulo RSA es siempre. Si no se
     * quita, el hash da distinto y la TV responde STATUS_BAD_SECRET sin decir
     * por que.
     */
    fun publicNumbers(cert: X509Certificate): Pair<ByteArray, ByteArray>? {
        val pub = cert.publicKey as? RSAPublicKey ?: return null
        return stripLeadingZeros(pub.modulus.toByteArray()) to
            stripLeadingZeros(pub.publicExponent.toByteArray())
    }

    fun stripLeadingZeros(bytes: ByteArray): ByteArray {
        var i = 0
        while (i < bytes.size - 1 && bytes[i] == 0.toByte()) i++
        return if (i == 0) bytes else bytes.copyOfRange(i, bytes.size)
    }
}
