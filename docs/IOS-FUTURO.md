# IOS-FUTURO — Plan para cuando el dueño compre un iPhone

> **Este proyecto NO se ejecuta ahora.** Solo es plan documentado.

## Realidad dura del sideloading en iOS

iOS no permite instalar APKs. El equivalente se llama IPA. El
sideloading (instalar un IPA sin pasar por la App Store) está
restringido por Apple:

| Método | Costo | Permanente | Disponibilidad en 2026 |
|--------|-------|------------|------------------------|
| Apple ID gratuito + Sideloadly (PC/Mac) | $0 | ❌ caduca cada 7 días | ✅ cualquier región |
| Apple Developer account | $99/año | ✅ | ✅ cualquier región |
| AltStore PAL | $0 | ✅ | 🇪🇺 **solo Unión Europea** — Guatemala NO aplica |
| TrollStore | $0 | ✅ | ⚠️ solo iOS 14 – 16.6.1, iPhones nuevos (iOS 18+) NO aplican |
| Jailbreak | $0 | ✅ | 🚫 imposible en iPhones nuevos + arriesga seguridad |

## Apps candidatas (IPAs)

- **YouTube Plus / YTLitePlus** — ad-free, background, downloads, SponsorBlock.
  Versión actual cerca de 20.42.3 (abril 2026).
- **Cercube+** — similar con más features premium.

Ambas se distribuyen como IPAs en repos tipo iospack.com, procursusteam.
No hay fuente oficial única como ReVanced. Implica monitorear
manualmente o con un script.

## Plan de activación

Cuando el dueño compre iPhone y diga "activa el Proyecto D":

1. Crear `project-d-ios/scripts/fetch-ipa.sh`:
   - Monitorea la fuente de YouTube Plus / Cercube IPA.
   - Publica el IPA en Releases del repo con tag `ytp-ios-<ver>`.
   - Workflow GitHub Actions `.github/workflows/project-d-build.yml` cada día.

2. Crear `project-d-ios/docs/IOS-SIDELOAD.md`:
   - Guía paso a paso de 10 pasos con Sideloadly en PC/Mac.
   - Re-hacer cada 7 días (Apple ID gratis).

3. Opcional: integrar con AltStore o SideStore si se habilita la región del dueño.

## Recomendación honesta

Tres caminos en orden de conveniencia:

1. **$12/mes YouTube Premium.** La solución "olvídate y ya". Ligada a la
   cuenta Google. Funciona en el iPhone, Android TV, todo. Cero
   mantenimiento. Recomendado si el presupuesto lo permite.

2. **$99/año Apple Developer + Sideloadly.** IPA sideloaded dura 1 año
   sin refrescar. Unos $8.30/mes. Más barato que Premium, pero requiere
   mantenimiento inicial cada año.

3. **$0 con Sideloadly semanal.** Gratis pero costo de tiempo:
   ~5-10 minutos cada domingo conectando el iPhone a la PC para
   resideloadear. Sostenible si al dueño no le molesta el ritual.

**NO recomendado:** jailbreak. Rompe garantía, bloquea bancas móviles,
arriesga seguridad. No vale la pena.

## Cuándo revisar

Volver a leer este documento **cada 6 meses** por si cambia algo:
- Apple habilitó AltStore PAL fuera de la UE.
- Apareció un nuevo bypass permanente para iPhones nuevos.
- Precios / condiciones cambian.
