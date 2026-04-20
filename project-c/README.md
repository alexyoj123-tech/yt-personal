# project-c — ytp-setup

**El APK único.** Funciona en celular y Android TV desde el mismo binario.
Orquesta la instalación de todo y pre-configura auto-updates.

**Estado:** ⏳ WIP (se construirá en Fases C1 a C14).

## Qué hace en celular (PHONE)

1. Pre-flight: pide permiso "Install Unknown Apps" UNA vez.
2. Descarga e instala GmsCore + YouTube parcheado + YT Music parcheado.
3. (Toggle) Instala cliente nativo (project-b).
4. (Toggle ON default) Instala Obtainium + le pasa URL del
   `obtainium-import.json` → las 5 apps quedan auto-importadas con
   `autoUpdate=true`.
5. (Toggle) "Enviar a mi TV": descubre TVs por mDNS/NsdManager y envía
   el propio APK vía Send Files to TV.
6. NO se auto-elimina. Queda en launcher con menú de segunda apertura:
   Reinstalar / Verificar updates / Exportar APK / Abrir YT / Compartir.

## Qué hace en Android TV / Fire TV

1. Pre-flight: pide "Install Unknown Apps".
2. Descarga última stable de [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube).
3. Instala (1 prompt).
4. Intenta lanzar SmartTube con deep link a Settings → Auto-updates = ON.
   Si SmartTube no expone el intent, deja instrucciones D-pad friendly
   en pantalla.
5. NO se auto-elimina.

## Stack (fijo por MEGA_PROMPT v3.2)

- Kotlin 2.0+, Compose + Material 3, `androidx.tv:tv-material`.
- minSdk 24, targetSdk 35.
- **SIN Hilt, SIN Room** (debe ser liviano).
- jmDNS o `android.net.nsd.NsdManager` para descubrimiento TV.
- LocalBroadcastManager.
- Manifest dual: `uses-feature leanback + touchscreen required=false`,
  dos intent filters (`LAUNCHER` + `LEANBACK_LAUNCHER`).
- Peso target: **≤ 7 MB**.
