# project-b — yt-native-client

Cliente Android nativo (Kotlin + Jetpack Compose) con motor NewPipeExtractor.
Fallback total por si ReVanced rompe algún día.

**Estado:** ⏳ WIP (se construirá en Fases B1 a B10).

## Features target

- Búsqueda, home feed, trending.
- Reproductor con PiP, background, pantalla apagada.
- Descargas (audio + video, calidad configurable).
- SponsorBlock integrado.
- Biblioteca, historial, suscripciones.
- Modo "Música" tipo YT Music.
- Self-updater (baja la última release del repo y se reemplaza).
- CI/CD: build release firmado con tag `ytb-v<ver>`.

## Stack (fijo por MEGA_PROMPT v3.2)

- Kotlin 2.0+, Compose + Material 3.
- Hilt (DI), Media3/ExoPlayer (reproducción).
- Room (DB local), DataStore (prefs), WorkManager (downloads).
- NewPipeExtractor vía JitPack.
- Coil 3 (imágenes), OkHttp + Retrofit, Coroutines.
- Gradle KTS.

## Restricciones

- Sin logos/íconos con copyright YouTube/Google.
- Sin analytics, tracking, Firebase.
- Sin distribución pública.
