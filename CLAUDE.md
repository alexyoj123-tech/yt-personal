# CLAUDE.md — guía para futuras invocaciones de Claude Code

Este repo fue bootstrapped siguiendo `MEGA_PROMPT_v3.2.txt` (abril 2026).
Cuando Claude Code abra este repo en sesiones futuras, leer este archivo
primero para entender el contexto y las reglas del juego.

## Contexto del proyecto

- **Dueño:** alexyoj123-tech (Samsung Galaxy A04e, arm64-v8a, Guatemala, español).
- **Objetivo:** YouTube + YT Music sin anuncios, con descargas, PiP,
  background y SponsorBlock. Android celular + Android TV + iOS futuro.
  Auto-updates casi invisibles, sin intervención del dueño.
- **Uso:** exclusivamente personal. No se distribuye a terceros.

## Triada del proyecto

- **A — `project-a/`** (yt-auto-revanced) — pipeline ReVanced en GitHub Actions.
- **B — `project-b/`** (yt-native-client) — cliente nativo Kotlin+Compose,
  motor NewPipeExtractor. Fallback si ReVanced rompe.
- **C — `project-c/`** (ytp-setup) — UN APK dual phone/TV que instala todo,
  pre-configura Obtainium y descubre TVs por WiFi. Peso target ≤ 7 MB.
- **D — `project-d-ios/`** — solo documentado. Se activa cuando el dueño
  compre iPhone. Ver `docs/IOS-FUTURO.md`.

## Stack técnico (FIJO — no cambiar sin consultar)

- **A:** Bash + GitHub Actions, revanced-cli, revanced-patches,
  ReVanced/GmsCore, apkeep, apksigner, gh.
- **B:** Kotlin 2.0+, Compose + Material 3, Hilt, Media3/ExoPlayer, Room,
  DataStore, WorkManager, NewPipeExtractor (JitPack), Coil 3, OkHttp,
  Retrofit, Coroutines, Gradle KTS.
- **C:** Kotlin 2.0+, Compose + Material 3, androidx.tv:tv-material,
  jmDNS (o NsdManager nativo), LocalBroadcastManager. minSdk 24,
  targetSdk 35. SIN Hilt, SIN Room. Manifest dual phone/TV.

## Restricciones duras (no negociables)

- ✗ Sin analytics / crashlytics / Firebase / tracking.
- ✗ Sin credenciales ni API keys en el repo.
- ✗ Sin logos/íconos con copyright de YouTube/Google en proyectos B y C.
- ✗ No distribuir APKs públicamente.
- ✗ No subir keystore al repo (está como secret).
- ✗ No cambiar stack técnico sin consultar al dueño.
- ✗ No saltar fases.
- ✗ No commitear código roto.
- ✗ Proyecto C NO se auto-elimina.

## Reglas siempre

- ✓ Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, etc.).
- ✓ Build + commit + reporte al final de cada fase.
- ✓ Reportes ≤ 6 líneas en español.
- ✓ Preguntar antes de decisiones de producto.
- ✓ Máxima automatización legítima disponible (auto-updates sin root).

## Keystore

- Archivo local: `C:\Users\alexy\yt-personal-secrets\yt-personal-release.jks`
  (NUNCA en el repo).
- Password: `C:\Users\alexy\yt-personal-secrets\keystore-password.txt`.
- Secrets en GitHub: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- Alias: `yt-personal`. Alg: RSA 4096. Validez: 100 años.
- Detalle: `docs/KEYSTORE.md`.

## Paths locales útiles

- Repo: `C:\Users\alexy\Desktop\yt-personal\`
- Secrets: `C:\Users\alexy\yt-personal-secrets\`
- Instaladores (gh CLI + JDK Temurin 21): `C:\Users\alexy\Desktop\yt-personal-installers\`
- MEGA_PROMPT original: `C:\Users\alexy\Desktop\youtube personal premium\MEGA_PROMPT_v3.2.txt`

## Fases

- **Completas:** Fase 0 (prerequisitos), A1 (bootstrap).
- **Siguientes:** A2 (scripts pipeline) → A3 (workflow diario) →
  A4 (primer build end-to-end) → A5 (docs A) → B1…B10 → C1…C14 →
  Verificación final.

Al terminar cada fase, Claude debe reportar en formato de 6 líneas y
esperar confirmación del dueño antes de continuar con la siguiente.
