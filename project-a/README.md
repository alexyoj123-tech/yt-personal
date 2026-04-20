# project-a — yt-auto-revanced

Pipeline diario en GitHub Actions. Descarga los APKs oficiales de YouTube
y YouTube Music, aplica patches de [ReVanced](https://revanced.app) +
GmsCore, los firma con el keystore del repo (como GitHub Secret), y
publica un Release con los tres APKs.

**Estado:** ⏳ WIP (estructura creada en Fase A1, scripts en A2, workflow en A3,
primer build end-to-end en A4, docs en A5).

## Cómo funcionará

1. Cron diario 06:00 UTC (`project-a-build.yml`).
2. `scripts/fetch-apks.sh` → descarga con `apkeep` los APKs oficiales
   arm64-v8a más recientes.
3. `scripts/apply-patches.sh` → baja la última versión de `revanced-cli`,
   `revanced-patches` y aplica el set recomendado.
4. `scripts/sign-apks.sh` → firma con `apksigner` usando el keystore de
   Secrets (`ANDROID_KEYSTORE_BASE64` + password).
5. `scripts/create-release.sh` → publica `youtube-personal-v<ver>.apk` +
   `youtube-music-personal-v<ver>.apk` + `gmscore-v<ver>.apk` en un
   Release con tag `ytp-a-<fecha>`.
6. Si no hubo cambios vs el Release anterior, skip.

## Artefactos esperados

- `youtube-personal-<ver>.apk` — YouTube parcheado.
- `youtube-music-personal-<ver>.apk` — YT Music parcheado.
- `gmscore-<ver>.apk` — Google Play Services Re-implementado (requerido
  por los anteriores para login Google).
