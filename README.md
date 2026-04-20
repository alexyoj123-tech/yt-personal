# yt-personal

> Proyecto personal. **No distribuir.** YouTube + YT Music sin anuncios,
> con descargas, PiP, reproducción en segundo plano y SponsorBlock,
> auto-actualizado casi de forma invisible.

## Triada + iOS futuro

| Proyecto | Qué es | Estado |
|----------|--------|--------|
| **A** `project-a/` — yt-auto-revanced | Pipeline GitHub Actions que descarga YouTube + YT Music oficiales cada día y los parchea con [ReVanced](https://revanced.app). | ⏳ WIP |
| **B** `project-b/` — yt-native-client | Cliente Android nativo Kotlin+Compose con NewPipeExtractor. Fallback por si ReVanced rompe. | ⏳ WIP |
| **C** `project-c/` — ytp-setup | **El APK único**. Funciona en celular y Android TV. Instala todo, pre-configura auto-updates con Obtainium, descubre TVs por WiFi. | ⏳ WIP |
| **D** `project-d-ios/` — yt-ios-sideload | Documentado, no ejecutado. Se activa cuando el dueño compre un iPhone. Ver [docs/IOS-FUTURO.md](docs/IOS-FUTURO.md). | 📋 Plan |

## Cómo lo uso

1. En el celular abro `https://github.com/alexyoj123-tech/yt-personal/releases/latest`.
2. Descargo `YTP-Setup.apk`. Doble-tap. Permito instalar. Tap "Instalar todo".
3. Acepto 3 prompts de Android. Obtainium se abre solo con las 5 apps ya configuradas. Listo.
4. Si tengo Android TV: desde el celular, "Enviar a mi TV" en YTP Setup. 4 taps en la TV y SmartTube queda instalado.

Desde ahí todo se actualiza solo en background. Detalle en [docs/AUTO-UPDATES.md](docs/AUTO-UPDATES.md).

## Estructura del monorepo

```
yt-personal/
├── project-a/          Pipeline ReVanced (bash + GitHub Actions)
├── project-b/          Cliente nativo Kotlin + Compose
├── project-c/          YTP Setup dual phone/TV (Kotlin + Compose)
├── project-d-ios/      Plan iOS documentado (no ejecutado)
├── .github/workflows/  CI/CD (build diario + manual + por-proyecto)
├── docs/               Guías de instalación, auto-updates, keystore, iOS
└── assets/             Íconos, banners, otros recursos estáticos
```

## Enlaces de documentación

- [docs/INSTALL-PHONE.md](docs/INSTALL-PHONE.md)
- [docs/INSTALL-TV-EASY.md](docs/INSTALL-TV-EASY.md) — con Send Files to TV
- [docs/INSTALL-TV-FALLBACK.md](docs/INSTALL-TV-FALLBACK.md) — con Downloader by AFTVnews
- [docs/AUTO-UPDATES.md](docs/AUTO-UPDATES.md) — 3 niveles de automatización
- [docs/OBTAINIUM.md](docs/OBTAINIUM.md)
- [docs/KEYSTORE.md](docs/KEYSTORE.md)
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)
- [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)
- [docs/IOS-FUTURO.md](docs/IOS-FUTURO.md)

## Restricciones duras

- Uso personal. **No se distribuye** el APK ni los binarios a terceros.
- Sin analytics, crashlytics, Firebase ni tracking.
- Sin credenciales ni API keys en el repo.
- Keystore nunca se commitea (está como secret en GitHub Actions).
- Proyectos B y C no usan logos/íconos con copyright de YouTube/Google.

## Licencia

Sin licencia pública. Todos los derechos reservados para uso personal.
