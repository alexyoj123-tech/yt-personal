# yt-personal

> Proyecto personal. **No distribuir.** YouTube sin anuncios en cada dispositivo
> del dueño — celular, TV Box, en el futuro iPhone — con auto-update casi
> invisible.

## Apps empaquetadas por este repo

| Tipo | App | Package | Tag release | Instalar en |
|------|-----|---------|-------------|-------------|
| 📱 | **YouTube (Morphe)** | `app.morphe.android.youtube` | `ytp-a-<fecha>` | Celular Android |
| 📱 | **YouTube Music (Morphe)** | `app.morphe.android.apps.youtube.music` | `ytp-a-<fecha>` | Celular Android |
| 📱 | **MicroG-RE** (reemplazo de Google Play Services) | `app.revanced.android.gms` | `ytp-a-<fecha>` | Celular Android (requerido por YT/YTM Morphe) |
| 📺 | **YouTube Origin** ⭐ | `com.google.android.youtube.tv` (wrapper) | `ytp-d-origin-<ver>` | Google TV / Android TV / TV Box (Claro 4K, Fire TV) |
| 📺 | **SmartTube** (fallback TV) | `org.smarttube.stable` | `ytp-a-<fecha>` | Android TV si Origin falla |

## Instalación rápida

- **Celular:** [docs/INSTALL-PHONE.md](docs/INSTALL-PHONE.md) — 3 APKs (YT + YTM + MicroG-RE).
- **TV Box:** [docs/INSTALL-TV.md](docs/INSTALL-TV.md) — Origin (primario) o SmartTube (fallback).

Después del primer setup, Obtainium en cada dispositivo recoge updates en background. Detalle en [docs/AUTO-UPDATES.md](docs/AUTO-UPDATES.md).

## Proyectos del monorepo

| Proyecto | Qué es | Estado |
|----------|--------|--------|
| **A** `project-a/` — YouTube + YT Music Morphe + MicroG-RE + SmartTube | Pipeline GitHub Actions diario que descarga YT/YTM oficiales, les aplica patches de [MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches) con `Spoof video streams`, firma con keystore `yt-personal`, y publica release con los 4 APKs. | ✅ Activo |
| **D** `project-d/` — YouTube Origin (TV) | Chequeo semanal de [energylove/originproject](https://gitlab.com/energylove/originproject) (GitLab). Descarga el RAR arm64-v8a, extrae el APK, publica release. **No re-firma** (preserva Widevine DRM). | ✅ Activo |
| **B** `project-b/` — cliente nativo Kotlin+Compose con NewPipeExtractor | Fallback total si el ecosistema ReVanced/Morphe cae definitivamente. | ⏳ WIP (no iniciado) |
| **C** `project-c/` — YTP Setup (APK único installer) | APK dual phone/TV que orquesta instalación de todo + configura Obtainium. | ⏳ WIP (no iniciado) |
| **iOS futuro** `project-d-ios/` | Plan documentado solo. Se activa cuando el dueño compre iPhone. | 📋 Plan solo |

## Estructura del monorepo

```
yt-personal/
├── project-a/          Pipeline Morphe (bash + GitHub Actions) — YT/YTM/MicroG/SmartTube
├── project-b/          Cliente nativo Kotlin + Compose (WIP)
├── project-c/          YTP Setup installer dual phone/TV (WIP)
├── project-d/          YouTube Origin pipeline (TV) — nuevo 2026-04-23
├── project-d-ios/      iOS sideload (documentado, sin código)
├── .github/workflows/  CI/CD (diario A, semanal D, manuales)
├── docs/               Guías + runbooks
└── assets/             Recursos estáticos
```

## Docs clave

**Instalación:**
- [docs/INSTALL-PHONE.md](docs/INSTALL-PHONE.md) — celular
- [docs/INSTALL-TV.md](docs/INSTALL-TV.md) — TV Box
- [docs/AUTO-UPDATES.md](docs/AUTO-UPDATES.md) — 3 niveles de automatización

**Mantenimiento (para retomar el proyecto en el futuro):**
- [docs/CONTINUIDAD.md](docs/CONTINUIDAD.md) — onboarding en 5 min
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) — runbook de los 12+ bugs resueltos
- [docs/MIGRATION-GUIDE.md](docs/MIGRATION-GUIDE.md) — plan B si upstream cae (patches / APKs / GmsCore / SmartTube)
- [docs/MIGRATION-GUIDE-TV.md](docs/MIGRATION-GUIDE-TV.md) — específico para Origin (TV)
- [docs/APKMIRROR-SCRAPER.md](docs/APKMIRROR-SCRAPER.md) — supervivencia del scraper
- [docs/KEYSTORE.md](docs/KEYSTORE.md) — keystore + backups
- [docs/IOS-FUTURO.md](docs/IOS-FUTURO.md) — plan para cuando haya iPhone

## Restricciones duras

- Uso personal. **No se distribuye** el APK ni los binarios a terceros.
- Sin analytics, crashlytics, Firebase ni tracking.
- Sin credenciales ni API keys en el repo (keystore como Secret de Actions).
- Proyectos B y C no usan logos/íconos copyright de YouTube/Google.
- Firma única **`yt-personal`** para todas las apps — excepción: **YouTube Origin** mantiene firma original de energylove para preservar Widevine ([rationale](docs/MIGRATION-GUIDE-TV.md#firma)).

## Licencia

Sin licencia pública. Todos los derechos reservados para uso personal.
