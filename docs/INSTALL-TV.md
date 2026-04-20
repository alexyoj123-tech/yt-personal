# Instalar SmartTube en Android TV / TV Box Claro 4K

SmartTube es el mejor cliente de YouTube para Android TV: 4K/8K,
SponsorBlock integrado, sin anuncios, navegación con control remoto.
Desarrollado por Yuri Liskov, **Apache 2.0**.

Este proyecto firma cada build nuevo con el keystore `yt-personal`
(consistencia con YouTube/YT Music del celular) y lo publica en el
mismo Release. Auto-update vía Obtainium o el propio updater interno
de SmartTube funcionan.

## Método recomendado — "Downloader by AFTVnews" (todas las TV)

Funciona en cualquier Android TV (Claro 4K, Xiaomi TV Stick, Fire TV,
Chromecast Google TV, MI Box, Nvidia Shield, etc.).

### Primera instalación (~5 min)

1. **En la TV** — instalar Downloader:
   - Google TV/Android TV: Google Play Store → buscar "Downloader" (desarrollador: AFTVnews). Install.
   - Fire TV: Amazon App Store → mismo proceso.

2. **En la TV** — habilitar "Apps from Unknown Sources" para Downloader:
   - Settings → Apps → Security & restrictions → Unknown sources → Downloader → **On**.
   - (En Fire TV: Settings → My Fire TV → Developer options → Install unknown apps → Downloader ON.)

3. **En la TV** — abrir Downloader. Campo "URL":
   - Tipear la URL del último APK de SmartTube de nuestro Release. Ejemplo:
     ```
     https://github.com/alexyoj123-tech/yt-personal/releases/latest/download/smarttube-<version>.apk
     ```
   - (Reemplazar `<version>` con el tag más reciente, visible en https://github.com/alexyoj123-tech/yt-personal/releases/latest. O usar el URL directo copiando el link del asset `smarttube-*.apk`.)

4. Tap **Go**. Downloader baja el APK (~24 MB, <1 min en WiFi decente).

5. Cuando termine, aparece "Install". Tap Install.

6. Android TV pregunta: "¿Instalar esta aplicación?" → **Install** con el D-pad.

7. Tap "Done" (o "Open" si querés abrir ya).

**Listo.** SmartTube queda en tu launcher. Login con tu cuenta Google
del celular vía "Link account" (te da un código, lo pegas en
`youtube.com/tv` desde el celular/PC).

### Updates futuros

Dos caminos, elegí uno:

**A — Auto-update interno de SmartTube (más simple):**
- Abrir SmartTube → Settings → About → "Check for updates" → **Automatic**.
- Cada vez que abrís la app, verifica si hay update y la instala en
  background. Necesita solo aceptar la instalación 1 vez cuando
  aparezca. **Este es el camino recomendado si solo tenés SmartTube en la TV.**

**B — Obtainium en la TV (si más apps):**
- Installar Obtainium en la TV (mismo método Downloader, baja de
  GitHub Releases de ImranR98/Obtainium).
- Add app → pegar URL del repo: `https://github.com/alexyoj123-tech/yt-personal`
- Filter regex: `smarttube-.*\.apk$`
- Activar auto-update.

## Método rápido — "Send Files to TV" (si ya instalaste YTP Setup en el celular)

Cuando el YTP Setup esté implementado (Proyecto C, WIP), tendrá una
opción "Enviar a mi TV" que usa el protocolo SFTV (Send Files to TV
by yablio) para enviar el APK directamente sin Downloader. 4 taps en
la TV y listo.

Ver [docs/INSTALL-TV-EASY.md](INSTALL-TV-EASY.md) — pendiente hasta
C14.

## Login / cuenta Google

SmartTube soporta login estándar vía "Sign in":
1. Abrí SmartTube → menú lateral → tu avatar → "Sign in".
2. Muestra un código corto (ej. `K7X3-F9DM`).
3. En el celular o PC: ir a `youtube.com/tv` → pegar el código → autorizar.
4. Listo — subs, historial, mis videos, todo sincronizado.

## Notas técnicas

- **APK firmado con `yt-personal`.** Si ya tenés instalado SmartTube
  de otra fuente (Play Store, upstream GitHub, etc.), **desinstalá
  primero** — Android rechaza instalar con firma diferente.
- **Package name:** `com.liskovsoft.smarttubetv.beta` (no modificado
  por nosotros — respetamos la build oficial de yuliskov).
- **SponsorBlock, ad-block y calidad máxima:** ya vienen on-by-default
  en SmartTube; no hay que configurar nada extra.

## Troubleshooting específico TV

| Síntoma | Fix |
|---------|-----|
| "La aplicación no está instalada" | Ya tenés SmartTube con otra firma. Desinstalá (Settings → Apps → SmartTube → Uninstall) y reintentá. |
| Downloader no puede acceder a GitHub | Verificá WiFi de la TV. Algunos ISPs bloquean github.com/releases; usar URL directa del asset R2 si eso pasa. |
| SmartTube dice "Update disponible" pero no instala | Activar Settings → About → "Install updates without prompt" si tu Android TV lo permite. Si no, aceptar el prompt manualmente (1 tap). |
