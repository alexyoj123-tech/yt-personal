# Keystore — firma de APKs

El proyecto usa UN keystore release para firmar **todos** los APKs que
se publiquen desde GitHub Actions (Proyectos A, B y C).

## Propiedades

| Campo | Valor |
|-------|-------|
| Archivo | `yt-personal-release.jks` |
| Algoritmo | RSA |
| Tamaño clave | 4096 bits |
| Validez | 36,500 días (100 años) |
| Alias | `yt-personal` |
| Organización | yt-personal / Personal |
| Ubicación | Guatemala |

## Ubicación local (NO EN EL REPO)

```
C:\Users\alexy\yt-personal-secrets\
├── yt-personal-release.jks     ← el keystore
├── keystore-password.txt        ← password plano (respaldar en un
│                                   gestor tipo Bitwarden 1Password)
└── keystore.b64                 ← base64 del .jks (se sube como Secret)
```

**CRÍTICO:** si perdés este archivo, **todas las apps instaladas con
este keystore dejan de actualizarse**. Android firma-verifica cada
update contra la firma original. Respaldar en al menos 2 lugares fuera
de la máquina (USB cifrado + Bitwarden vault con el .jks adjunto).

## GitHub Secrets (usados por los workflows)

Los workflows de `project-a/` y `project-b/` esperan:

| Secret | Qué contiene |
|--------|--------------|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w 0` del archivo `.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | password del keystore |
| `ANDROID_KEY_ALIAS` | `yt-personal` |
| `ANDROID_KEY_PASSWORD` | password de la clave (igual al keystore) |

Se suben con `gh secret set ... --repo alexyoj123-tech/yt-personal` o en
Settings → Secrets and variables → Actions del repo en GitHub web.

## Rotación

- **No rotar** salvo compromiso confirmado. La firma está atada a la
  identidad del APK en Android — rotarla rompe auto-updates.
- Si se necesita rotar, hay que desinstalar+reinstalar todas las apps
  firmadas con la firma vieja.

## Regenerar desde cero

```bash
keytool -genkeypair -v \
  -keystore yt-personal-release.jks \
  -alias yt-personal \
  -keyalg RSA -keysize 4096 \
  -validity 36500 \
  -storepass "<NUEVO_PASSWORD>" -keypass "<NUEVO_PASSWORD>" \
  -dname "CN=alexyoj123, OU=Personal, O=yt-personal, L=Guatemala, ST=Guatemala, C=GT"
```

Luego:

```bash
base64 -w 0 yt-personal-release.jks > keystore.b64
gh secret set ANDROID_KEYSTORE_BASE64 --repo alexyoj123-tech/yt-personal < keystore.b64
gh secret set ANDROID_KEYSTORE_PASSWORD --repo alexyoj123-tech/yt-personal --body "<NUEVO_PASSWORD>"
gh secret set ANDROID_KEY_ALIAS --repo alexyoj123-tech/yt-personal --body "yt-personal"
gh secret set ANDROID_KEY_PASSWORD --repo alexyoj123-tech/yt-personal --body "<NUEVO_PASSWORD>"
```
