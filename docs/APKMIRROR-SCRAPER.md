# APKMirror scraper — arquitectura, mantenimiento y supervivencia

Este documento explica por qué, cómo y qué tocar si algún día se rompe.

## Por qué APKMirror (y por qué NO apkeep)

| Opción | Estado | Razón |
|--------|--------|-------|
| `apkeep --download-source apk-pure` | ❌ Descartado | APKPure solo sirve versiones muy recientes (YT 21.x+, YTM 9.x+). Los patches de `inotia00/revanced-patches v5.14.1` son compatibles hasta YT 20.05.46 y YTM 8.30.54. APKPure no tenía históricos disponibles. |
| `apkeep --download-source google-play` | ❌ | Requiere credenciales Google + aas_token. Fricción alta en CI. |
| `apkeep --download-source apkmirror` | ❌ | APKMirror no está en la lista de sources de apkeep (EFForg). |
| `apkmd` (pip) | ❌ | Removido de PyPI. `pip install apkmd` → "No matching distribution found". |
| **Scraper Python custom a APKMirror** | ✅ | Elegido. APKMirror tiene archivo histórico exhaustivo. Ver §cómo-funciona. |

## Versiones actualmente soportadas por `inotia00/revanced-patches` v5.14.1

Estas son las versiones de YT / YTM donde el patch `GmsCore support` aplica (y por tanto los renames de paquete + spoof de firma funcionan). Extraídas del `list-patches -o -p -v` del CLI v5.0.1.

### YouTube (`com.google.android.youtube`)
- `19.05.36`
- `19.16.39`
- `19.43.41`
- `19.44.39`
- `19.47.53`  ← **usada hoy** (verificada en APKMirror)
- `20.05.46`

### YouTube Music (`com.google.android.apps.youtube.music`)
- `6.20.51`
- `6.29.59`
- `6.42.55`
- `6.51.53`
- `7.16.53`
- `7.25.53`  ← **usada hoy** (verificada en APKMirror)
- `8.12.54`
- `8.28.54`
- `8.30.54`

Si en el futuro `inotia00` publica un v5.15+ con patches para YT 21.x / YTM 9.x, actualizar esta lista y cambiar las versiones default del workflow.

## Cómo funciona el scraper

Archivo: `project-a/scripts/apkmirror_download.py` (Python 3 stdlib only).

Entrada:
```
python3 apkmirror_download.py <org> <slug> <version> <out_path>
```
Ejemplo:
```
python3 apkmirror_download.py google-inc youtube 19.47.53 /tmp/yt.apk
```

Cadena de URLs seguida:

```
1. Version page
   https://www.apkmirror.com/apk/<org>/<slug>/<slug>-<ver-dashed>-release/
   └─► HTML con tabla de variants (arch/DPI distintos)

2. Variant page (prefiere "(nodpi)", rechaza "BUNDLE")
   https://www.apkmirror.com/apk/<org>/<slug>/.../<slug>-<ver>-android-apk-download/
   └─► HTML con botón "Download APK"

3. Key page (requiere Referer = variant page)
   https://www.apkmirror.com/apk/.../download/?key=<hex>
   └─► HTML con link a download.php

4. download.php (302 redirect al CDN R2)
   https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=<num>&key=<hex>
   └─► 302 Location: https://*.cloudflarestorage.com/.../<pkg>_<ver>_...apk?X-Amz-...

5. CDN R2 (signed URL, expira en 1h)
   └─► APK real (binario ZIP, empieza con 'PK')
```

**Validaciones al final:**
- Archivo existe
- Tamaño > 5 MB (detecta HTML de error guardado como .apk)
- Magic bytes `PK` (ZIP header)

## Gotchas descubiertos y resueltos

### Gotcha #1: Referer validation
APKMirror valida el `Referer` en la key-page. Sin el Referer correcto (= URL de la variant-page), redirige a `/?redirected=thank_you_invalid_referer` y sirve un HTML SIN el link `download.php`. Sin fallo HTTP — silencioso.

**Mitigación en el scraper:** `fetch_text(key_url, referer=variant_url)` pasa explícitamente la variant-page como Referer. Mismo para el download final: `fetch_file(final_url, referer=key_url)`.

### Gotcha #2: Variantes múltiples
Cada version page lista ≥ 2 variants:
- `<slug>-<ver>-android-apk-download/` → **APK universal** (nodpi, todos los arches)
- `<slug>-<ver>-2-android-apk-download/` → **APK bundle** (split-APKs para density/arch específico)

Queremos el universal (single APK instalable directo).

**Heurística en el scraper:** busca el `<title>` de cada variant. Prefiere el que contiene "(nodpi)". Rechaza los que contienen "BUNDLE".

## Riesgos y qué hacer si se rompen

### Si APKMirror cambia el HTML
El scraper emite códigos de salida específicos para cada paso:

| Exit code | Significado | Qué revisar |
|-----------|-------------|-------------|
| 2 | Version page 404 | ¿La versión existe en APKMirror? ¿El slug cambió? Verifica manualmente: `curl -I https://www.apkmirror.com/apk/<org>/<slug>/<slug>-<ver-dashed>-release/` |
| 3 | No variants o todas BUNDLE | Inspeccionar `find_variant_hrefs()` regex en el .py. ¿APKMirror cambió la URL pattern de las variants? |
| 4 | Key page no tiene `/download/?key=` | Inspeccionar `find_download_key_url()` regex. ¿Pattern cambió? |
| 5 | Key page no tiene `download.php` | **Probable causa: Referer validation falló.** Verificar que el scraper está pasando la variant-page como Referer. Secundario: `find_download_php_url()` regex. |
| 6 | Download final inválido | ¿APK < 5 MB o no-ZIP? Puede ser: URL signed expirada (retry), o HTML de error guardado como .apk (inspeccionar con `file out.apk`). |

**Pattern común de mantenimiento:** inspeccionar la cadena manualmente en un navegador, guardar el HTML de cada paso, grep para el pattern esperado, ajustar la regex correspondiente en `apkmirror_download.py`.

### Si Cloudflare empieza a desafiar con JavaScript
No lo vi durante pruebas (2026-04-20), pero es el riesgo mayor a largo plazo. Si aparece "Just a moment..." o "Cloudflare is checking your browser":

**Opciones de escalada:**
1. Añadir delay entre requests (`time.sleep(3)`) — simular usuario humano.
2. Usar `curl_cffi` (fingerprinting TLS de browser real) en vez de urllib stdlib. Requiere pip install, rompe nuestro criterio "stdlib only".
3. Cambiar de fuente (AppMirror, APKCombo, archive.org como últimos recursos).

### Si la versión que necesitamos no está en APKMirror
Muy improbable — APKMirror tiene archivo histórico completo de apps Google. Pero si pasa:
1. Verificar manualmente navegando.
2. Probar la siguiente versión compatible de la lista de §versiones-soportadas.
3. Workflow dispatch acepta cualquier version string; solo cambiar el input.

## Legalidad

APKMirror opera bajo la teoría de que redistribuir APKs gratuitos descargados de Google Play es legal (los APKs son distribuidos gratis por Google, APKMirror solo los remirrorea con verificación de firma). Ha operado públicamente desde ~2011 sin takedowns. **Uso del scraper para este proyecto personal (no distribución a terceros) es consistente con el pattern de uso que APKMirror tolera.**

Si APKMirror llegara a ser bloqueado o a implementar anti-scraping agresivo, el proyecto D (iOS futuro) ya documenta el camino alternativo "pagar $12/mes YouTube Premium". Para Android, el fallback realista es extraer el APK original del dispositivo con ADB de un Android que tenga el APK oficial instalado — fricción alta pero factible.

## Actualización de este documento

Cuando se rompa algo y se arregle, añadir el incidente a §gotchas. Cuando
inotia00 publique una nueva versión compatible con YT/YTM más nuevos,
actualizar §versiones-soportadas.
