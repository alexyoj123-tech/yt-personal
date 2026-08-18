package io.github.alexyoj123.hapercontroler.deploy

import io.github.alexyoj123.hapercontroler.core.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** Un APK publicado en un release de GitHub. */
data class ReleaseAsset(
    val releaseTag: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * Cliente minimo de la API de releases de GitHub.
 *
 * Sin token funciona igual, pero GitHub limita a 60 peticiones por hora por
 * IP; con un PAT sube a 5000. El token se guarda en el dispositivo y **nunca**
 * se escribe al log — igual que los tokens de las TVs.
 */
class GithubReleases(private val token: String? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Ultimo release cuyo tag empiece con [tagPrefix], con sus APKs.
     * Se pide la lista completa en vez de `/releases/latest` porque el repo
     * publica varias lineas de releases a la vez (a, d, f, g, i) y `latest`
     * devuelve la mas reciente de todas, no la de la linea que interesa.
     */
    suspend fun latestWithPrefix(repo: String, tagPrefix: String): Result<List<ReleaseAsset>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$repo/releases?per_page=40")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "HaperControler")
                    .apply { token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val pista = if (response.code == 403) {
                            " (probablemente el límite de la API anónima: configurá un token de GitHub)"
                        } else {
                            ""
                        }
                        throw IllegalStateException("GitHub respondió HTTP ${response.code}$pista")
                    }
                    val body = response.body?.string().orEmpty()
                    val releases = JSONArray(body)

                    for (i in 0 until releases.length()) {
                        val release = releases.optJSONObject(i) ?: continue
                        if (release.optBoolean("draft")) continue
                        val tag = release.optString("tag_name")
                        if (!tag.startsWith(tagPrefix)) continue

                        val assets = release.optJSONArray("assets") ?: continue
                        val apks = buildList {
                            for (j in 0 until assets.length()) {
                                val asset = assets.optJSONObject(j) ?: continue
                                val name = asset.optString("name")
                                if (!name.endsWith(".apk", ignoreCase = true)) continue
                                add(
                                    ReleaseAsset(
                                        releaseTag = tag,
                                        assetName = name,
                                        downloadUrl = asset.optString("browser_download_url"),
                                        sizeBytes = asset.optLong("size"),
                                    ),
                                )
                            }
                        }
                        if (apks.isNotEmpty()) {
                            DiagLog.i("deploy", "release $tag con ${apks.size} APK(s)")
                            return@use apks
                        }
                    }
                    DiagLog.w("deploy", "ningún release con prefijo $tagPrefix tiene APKs")
                    emptyList()
                }
            }
        }

    suspend fun download(asset: ReleaseAsset, target: File, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(asset.downloadUrl)
                    .header("User-Agent", "HaperControler")
                    .apply { token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("La descarga falló con HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Respuesta vacía")
                    val total = body.contentLength().takeIf { it > 0 } ?: asset.sizeBytes.coerceAtLeast(1)
                    target.parentFile?.mkdirs()
                    var leidos = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                                leidos += n
                                onProgress((leidos.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                DiagLog.i("deploy", "descargado ${asset.assetName} (${target.length() / 1024} KB)")
                target
            }
        }
}
