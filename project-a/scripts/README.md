# project-a/scripts — pipeline ReVanced

4 scripts bash que el workflow GitHub Actions ejecuta en secuencia.
Diseñados para:
- **Runner Ubuntu** (`ubuntu-latest` con JDK 21, Android build-tools, jq, gh).
- **Idempotencia:** si ya hay tools descargados en `build/tools/`, se reutilizan.
- **Cero output inadvertido:** todos los archivos van a `project-a/build/`
  (ignorado por `.gitignore`).

| Orden | Script | Qué hace | Inputs | Outputs |
|------:|--------|----------|--------|---------|
| 1 | `fetch-apks.sh` | Baja YouTube + YT Music oficiales (arm64-v8a) | `ARCH`, `YT_VERSION`, `YTM_VERSION`, `APKEEP_SOURCE` | `build/apks/*.apk` + `build/meta/fetch.json` |
| 2 | `apply-patches.sh` | Baja revanced-cli + patches + GmsCore, aplica patches | Archivos de #1 | `build/patched/*.apk` + `build/meta/patch.json` |
| 3 | `sign-apks.sh` | zipalign + apksigner con keystore del Secret | Secrets `ANDROID_KEYSTORE_BASE64` etc. | `build/signed/*.apk` + `build/meta/sign.json` |
| 4 | `create-release.sh` | Publica tag `ytp-a-YYYY.MM.DD` con los 3 APKs | `GITHUB_TOKEN`, todo lo anterior | GitHub Release |

`common.sh` es un helper compartido (logging, paths, `ensure_tool()` para
bajar assets de GitHub Releases, `fetch()` con retry/resume).

## Ejecución local (para debugging, no recomendado en máquina del dueño)

Requiere: `apkeep`, JDK 21, `apksigner`+`zipalign` (Android build-tools),
`jq`, `gh` autenticado.

```bash
cd project-a
export ANDROID_KEYSTORE_BASE64="$(base64 -w 0 /path/to/release.jks)"
export ANDROID_KEYSTORE_PASSWORD="..."
export ANDROID_KEY_ALIAS="yt-personal"
export ANDROID_KEY_PASSWORD="..."

bash scripts/fetch-apks.sh
bash scripts/apply-patches.sh
bash scripts/sign-apks.sh
bash scripts/create-release.sh
```

En GitHub Actions, estos se encadenan en `.github/workflows/project-a-build.yml`
(rellenado en Fase A3).

## Opciones útiles

| Variable | Script | Efecto |
|----------|--------|--------|
| `YT_VERSION`, `YTM_VERSION` | fetch | Fija una versión específica (ej. `20.12.34`). Si vacío, usa la más alta compatible con los patches actuales. |
| `APKEEP_SOURCE` | fetch | `APKMirror` (default) o `APKPure`. |
| `ARCH` | fetch | `arm64-v8a` (default), `armeabi-v7a`, `x86_64`. |
| `FORCE_RELEASE` | create-release | `1` publica aunque no haya cambios (útil para debug). |
| `DEVICE_NAME` | create-release | Etiqueta cosmética en el changelog. |
