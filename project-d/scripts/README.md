# project-d/scripts

Single-script pipeline para YouTube Origin (Google TV).

| Script | Qué hace |
|--------|----------|
| `fetch-origin.sh` | Lista GitLab tree API de `energylove/originproject/Releases/`, encuentra la versión más reciente con `.rar`, descarga el arm64-v8a, extrae el APK con `unrar`, lo copia a `build/out/`, genera `meta/fetch.json`. |

**NO hay script de signing.** El APK se distribuye con la firma original de
energylove — re-firmar rompe Widevine DRM (el APK usa la firma para
attestation con el chip DRM del TV Box, lo que desbloquea películas de
pago free-tier en YouTube Movies & TV).

Ver [docs/MIGRATION-GUIDE-TV.md](../../docs/MIGRATION-GUIDE-TV.md) §firma.

## Ejecución local (para debugging)

Requiere: `curl`, `jq`, `unrar` (en Ubuntu: `sudo apt install -y unrar`).

```bash
cd <repo-root>
bash project-d/scripts/fetch-origin.sh
ls project-d/build/out/youtube-origin-*.apk
cat project-d/build/meta/fetch.json
```

En CI corre vía [`.github/workflows/project-d-weekly.yml`](../../.github/workflows/project-d-weekly.yml)
cada domingo 04:00 UTC (cadencia real del upstream es ~bimensual, chequeo
semanal es suficiente margen sin spam a GitLab).

## Env overrides

| Variable | Default | Uso |
|----------|---------|-----|
| `ORIGIN_PROJECT_ID` | `62144433` | ID numérico GitLab del proyecto. Cambiar si energylove migra. |
| `ORIGIN_ARCH` | `arm64-v8a` | Variante de CPU. Opciones del upstream: `arm64-v8a`, `armeabi-v7a`, `x86_64`. |
