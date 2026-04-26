# Para Claude futuro — instrucciones de retomo

Hola Claude. Si Alexy te pidió retomar este proyecto, **leé
[CONTINUIDAD.md](CONTINUIDAD.md) primero** (5 minutos). Eso te da el
contexto completo del estado actual y dónde está cada cosa.

Este documento complementa CONTINUIDAD con lo que vos específicamente
necesitás saber sobre **secrets, keystore y builds** — cosas que el
usuario no va a tener que explicarte cada vez.

---

## 1. Dónde están los secrets (no preguntés al usuario, ya lo sabés)

| Item | Dónde | Notas |
|------|-------|-------|
| **Keystore release** (`yt-personal-release.jks`) | `C:\Users\alexy\yt-personal-secrets\` | Generado con RSA 4096, validez 100 años, alias `yt-personal`. |
| **Password del keystore** | `C:\Users\alexy\yt-personal-secrets\keystore-password.txt` | Texto plano local, nunca subido. |
| **Backup #1** | Google Drive → carpeta `yt-personal-secrets-backup-2026-04-25` | Cuenta Google del usuario. Archivo .jks + password.txt cifrado. |
| **Backup #2** | Gmail → buscar `"Backup yt-personal keystore"` | Email con archivos adjuntos cifrados. |
| **GitHub Secrets** del repo | `Settings → Secrets and variables → Actions` | 4 secrets ya configurados: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. **No requieren acción tuya** — el CI los usa automáticamente. |

**Vos NO tenés que pedirle al usuario que te comparta nada de esto** salvo
que necesités hacer un build local (caso raro — ver §3).

---

## 2. Builds en CI (lo normal)

Cualquier build de los APKs corre en GitHub Actions con los Secrets
ya configurados. **No requiere intervención tuya con el keystore.**

Para disparar un build manual:

```bash
gh workflow run project-a-manual.yml --repo alexyoj123-tech/yt-personal --ref main \
  -f youtube_version=20.47.62 \
  -f ytmusic_version=8.47.56 \
  -f publish_release=true \
  -f force_release=true \
  -f revanced_cli_tag=v1.7.0
```

Para ver el resultado:
```bash
gh run list --repo alexyoj123-tech/yt-personal --limit 3
gh run view <RUN_ID> --repo alexyoj123-tech/yt-personal
gh run view <RUN_ID> --repo alexyoj123-tech/yt-personal --log-failed   # si falló
```

Los APKs producidos quedan firmados con `yt-personal` automáticamente.

---

## 3. Builds locales (caso raro)

Solo necesitás esto si:
- El usuario te pide hacer algo que no se puede en CI.
- Estás debuggeando un problema que no se reproduce en CI.

**Si efectivamente lo necesitás:** pedile al usuario el path absoluto
del keystore + el password (los tiene en su PC, te los puede leer
verbalmente o copiar a un archivo temporal). **Nunca le pidas que te
los comparta vía chat público compartido** (Discord, Telegram, etc.).

Para verificar que tu sesión local tiene el keystore correcto:
```bash
keytool -list -v -keystore /path/to/yt-personal-release.jks \
        -storepass "<password>" -alias yt-personal | grep -i sha256
```

El SHA-256 del cert debe ser:
```
48:40:63:E1:F3:5E:8B:8E:20:D7:5B:60:8B:32:CD:D8:E0:07:CE:57:A1:33:D2:DC:CC:E9:F4:8D:B9:10:29:87
```

Si no matchea ese SHA-256, **NO uses ese keystore** — es otro distinto y
firmaría con identidad incorrecta.

---

## 4. Recovery si todo se pierde

Escenario peor: PC del usuario muere + Google Drive borrado + Gmail
inaccesible. Plan de recovery:

### 4.a Generar keystore nueva (5 min)

```bash
keytool -genkeypair -v \
  -keystore yt-personal-release-NEW.jks \
  -alias yt-personal \
  -keyalg RSA -keysize 4096 \
  -validity 36500 \
  -storepass "<NUEVO_PASSWORD_FUERTE>" -keypass "<NUEVO_PASSWORD_FUERTE>" \
  -dname "CN=alexyoj123, OU=Personal, O=yt-personal, L=Guatemala, ST=Guatemala, C=GT"
```

### 4.b Subir a GitHub Secrets (sustituye los viejos)

```bash
base64 -w 0 yt-personal-release-NEW.jks > keystore.b64
gh secret set ANDROID_KEYSTORE_BASE64    --repo alexyoj123-tech/yt-personal < keystore.b64
gh secret set ANDROID_KEYSTORE_PASSWORD  --repo alexyoj123-tech/yt-personal --body "<NUEVO_PASSWORD>"
gh secret set ANDROID_KEY_PASSWORD       --repo alexyoj123-tech/yt-personal --body "<NUEVO_PASSWORD>"
# ANDROID_KEY_ALIAS sigue siendo "yt-personal", no hace falta tocarlo
```

### 4.c Notificar a usuarios para reinstalar (CRÍTICO)

Una keystore nueva = firma nueva. **Todos los APKs ya instalados en
celulares y TV Boxes dejarán de poder actualizarse** porque Android
verifica que la firma del update coincida con la de la instalación
original.

El usuario debe notificar a su familia/amigos:
1. Desinstalar las apps actuales (YouTube, YouTube Music, MicroG Services
   en celular; YouTube Origin / SmartTube en TV).
2. Re-instalarlas desde el release nuevo (Obtainium con la config nueva
   las baja como "instalación nueva", no como update).

Comunicación sugerida (template):
> "Hola, tuve que regenerar la firma del proyecto YouTube Personal por
> [razón]. Necesitás desinstalar las apps actuales y re-instalarlas
> desde Obtainium una vez. Es rápido — 5 min. Avisame cuando lo hagas."

### 4.d Actualizar `docs/KEYSTORE.md` y `docs/CONTINUIDAD.md`

Cambiar el SHA-256 documentado al de la keystore nueva. Mencionar la
fecha del cambio + razón.

---

## 5. Lo que NUNCA debes hacer

- ❌ **Subir el keystore (`*.jks`) al repo**, ni siquiera en commit que
  pienses revertir después. Git history lo conserva. El `.gitignore` ya
  bloquea `*.jks` por defensa, pero no confíes solo en eso.
- ❌ **Pedirle al usuario que comparta el keystore o password vía chat
  público compartido** (canales de Discord, grupos de Telegram con más
  gente, screenshots posteables). Si necesitás ese acceso, el usuario te
  lo pasa por canal directo (DM 1:1 o copia local).
- ❌ **Modificar GitHub Secrets sin razón clara documentada en commit/issue.**
  Los secrets están operativos; tocarlos puede romper el CI silenciosamente.
- ❌ **Hacer commit de logs de runs CI con secrets desenmascarados**
  (ej. si un run viejo tiene un secret leak, no lo cites en docs sin
  redactarlo).
- ❌ **Cambiar el alias del keystore** sin coordinarlo con el usuario.
  El alias `yt-personal` está hardcodeado en docs + workflows + scripts.
  Cambiar requiere actualización en 5+ lugares.

---

## 6. Comandos útiles que vas a necesitar

### Verificar firma de un APK publicado
```bash
gh release download <TAG> --repo alexyoj123-tech/yt-personal -p '*.apk' -O /tmp/check.apk
apksigner verify --print-certs /tmp/check.apk | grep -i "SHA-256"
# Debe coincidir con: 484063e1f35e8b8e20d75b608b32cdd8e007ce57a133d2dccce9f48db9102987
# (excepto YouTube Origin que mantiene firma original de energylove —
#  ver docs/MIGRATION-GUIDE-TV.md §firma)
```

### Trigger manual de un workflow
```bash
gh workflow list --repo alexyoj123-tech/yt-personal
gh workflow run <workflow-name>.yml --repo alexyoj123-tech/yt-personal -f input1=value1 -f input2=value2
gh run watch --repo alexyoj123-tech/yt-personal   # ver el run en tiempo real
```

### Leer logs de runs pasados
```bash
gh run list --repo alexyoj123-tech/yt-personal --limit 10
gh run view <RUN_ID> --repo alexyoj123-tech/yt-personal --log         # log completo
gh run view <RUN_ID> --repo alexyoj123-tech/yt-personal --log-failed  # solo steps fallidos
```

### Verificar estado de los upstreams (health monitor on-demand)
```bash
gh workflow run health-check-manual.yml --repo alexyoj123-tech/yt-personal -f dry_run=true
# después:
gh run view --repo alexyoj123-tech/yt-personal --log | grep -E "OK|STALE|ARCHIVED"
```

### Ver issues abiertos del health monitor
```bash
gh issue list --repo alexyoj123-tech/yt-personal --label health --state open
```

### Listar secrets configurados (sin exponer valores)
```bash
gh secret list --repo alexyoj123-tech/yt-personal
# Solo muestra nombres + última-actualización, no los valores.
```

### Verificar que el repo es público y Pages funciona
```bash
gh repo view alexyoj123-tech/yt-personal --json visibility,url
curl -sI https://alexyoj123-tech.github.io/yt-personal/ | head -1
# Debe devolver: HTTP/2 200
```

---

## 7. Checklist mental para vos antes de empezar

Cuando Alexy te pida algo, pasate este checklist mental:

1. ✅ ¿Leí `CONTINUIDAD.md` (al menos §1, §2, §10, §11)?
2. ✅ ¿La tarea requiere keystore/secrets? Si NO: proceder sin
   tocar nada de §1-§3 de este doc.
3. ✅ Si SÍ: usar Secrets de CI cuando sea posible. Solo pedir
   keystore local si es absolutamente necesario.
4. ✅ ¿La tarea modifica workflows o defaults? Recordar Bug #11 —
   defaults en UN solo lugar (composite action YAML).
5. ✅ ¿La tarea agrega upstream nuevo? Añadir entry a
   `.github/scripts/health-monitor.sh` UPSTREAMS y procedimiento
   en `docs/ANTI-FRAGIL.md` §3.
6. ✅ ¿Documenté el cambio? Si introdujiste un patrón nuevo,
   actualizá `TROUBLESHOOTING.md` o `CONTINUIDAD.md` según
   corresponda.

---

## Si todo lo anterior te parece confuso

Pegale este mensaje al usuario:

> "Leí `CLAUDE-FUTURO-INSTRUCCIONES.md` pero necesito clarificación
> sobre [X]. ¿Podés explicarme [pregunta específica]?"

El usuario sabe que vos sos Claude nuevo y va a clarificar sin
problema. Es mejor preguntar que asumir mal sobre secrets/keystore.
