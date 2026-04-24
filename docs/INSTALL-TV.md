# Instalar cliente YouTube en Android TV / TV Box

Este repo provee 2 clientes para TV Box:

1. **YouTube Origin** ⭐ **(recomendado)** — wrapper oficial de Google YouTube TV con ad-block + SponsorBlock, UI idéntica a la oficial, con Widevine HD para Movies & TV.
2. **SmartTube** (fallback robusto) — cliente OSS alternativo, UI propia D-pad friendly.

Ver [docs/MIGRATION-GUIDE-TV.md](MIGRATION-GUIDE-TV.md) para fallbacks adicionales y detalle de por qué Origin mantiene firma externa.

---

## Método A — YouTube Origin (primario)

Funciona en: Google TV, Fire TV, Android TV, TV Boxes 4K (Claro 4K, Xiaomi TV Stick, etc.) con **Android 9+**.

### Preparación (solo la primera vez)

1. **En tu celular:** instalar **"Send Files to TV"** por yablio desde la Play Store.
2. **En la TV:** instalar **"Send Files to TV"** en la Play Store de Google TV (misma app, cuenta conectada).
3. **En la TV:** instalar **"ZArchiver"** para extraer el `.rar` (el APK viene comprimido).
4. **En la TV:** Settings → Apps → Security → Unknown sources → permitir Send Files to TV y ZArchiver.

### Instalación

Origin está en nuestro release dedicado `ytp-d-origin-<version>`:

1. **En el celular**, abrir: https://github.com/alexyoj123-tech/yt-personal/releases
2. Buscar el release más reciente con tag `ytp-d-origin-*` (último confirmado: `ytp-d-origin-1.4.6`).
3. Descargar `youtube-origin-1.4.6.apk` (~198 MB — baja en 1-2 min con WiFi decente).
4. Abrir "Send Files to TV" en el celular → seleccionar el APK → seleccionar tu TV Box en la lista.
5. **En la TV**, aceptar la recepción → abrir el APK con "Package Installer" → Install.
6. Abrir Origin → iniciar sesión con tu cuenta Google del TV Box.

### Updates futuros

Dos opciones:

**A — Cuando releases un nuevo `ytp-d-origin-*`:**
- Nuestro workflow chequea cada domingo 04:00 UTC. Si energylove publica nueva versión, aparece en Releases automáticamente.
- Instalar igual que la primera vez (Send Files to TV). Origin soporta update in-place si la firma es la misma (energylove mantiene su key).

**B — Obtainium (recomendado):**
- Instalar Obtainium en el TV Box (vía Downloader by AFTVnews → `https://github.com/ImranR98/Obtainium/releases/latest`).
- Add app → pegar URL del repo: `https://github.com/alexyoj123-tech/yt-personal`
- Filter regex: `youtube-origin-.*\.apk$`
- Activar auto-update.

### Importante sobre la firma

El APK de Origin **NO** está re-firmado con `yt-personal` — mantiene la firma original de energylove. Razón: preservar Widevine DRM (HD/4K en YouTube Movies & TV gratis). Todos los demás APKs del repo SÍ están firmados con `yt-personal`; Origin es la única excepción documentada. Detalle en [docs/MIGRATION-GUIDE-TV.md §firma](MIGRATION-GUIDE-TV.md#firma).

---

## Método B — SmartTube (fallback / alternativa)

Si Origin falla por cualquier motivo (update rompe algo, GitLab bloquea el repo, etc.), SmartTube está siempre disponible en cada release del `project-a` (archivo `smarttube-<version>.apk`).

Instalación: ver [docs/INSTALL-TV.md versión previa](https://github.com/alexyoj123-tech/yt-personal/blob/ytp-a-2026.04.23/docs/INSTALL-TV.md) o seguir el mismo flujo que Origin (Send Files to TV). Descarga: desde el release `ytp-a-<fecha>`, asset `smarttube-*.apk` (24 MB).

SmartTube está re-firmado con `yt-personal` (consistente con el resto), así que Obtainium/auto-update funciona sin caveats.

---

## Troubleshooting específico TV

| Síntoma | Solución |
|---------|----------|
| ZArchiver dice "archivo corrupto" al abrir el RAR | Re-descargar — descarga parcial. Verificar SHA-256 del APK con el publicado en las release notes. |
| Origin abre pero dice "No connection" / "check your internet" | WiFi del TV Box. Reiniciar WiFi. Si persiste, probar VPN (algunos ISPs bloquean servidores YouTube TV). |
| Origin dice "Se requiere actualización" al abrir | energylove publicó nueva versión; esperar al próximo cron (domingo) o triggear manualmente el workflow `project-d-weekly` desde Actions. |
| Video arranca pero sin audio | Settings → Audio → Prefer Dolby (solo si el TV Box lo soporta). En Claro 4K: setear "Stereo PCM" en Audio output. |
| "La aplicación no está instalada" al instalar | Ya tenés Origin con otra firma (instalación previa). Desinstalá (Settings → Apps → YouTube Origin → Uninstall) y reintentá. |
| Quiero volver a SmartTube temporal | Instalarlo del release `ytp-a-*` — convive con Origin sin conflicto (distintos package names). |
