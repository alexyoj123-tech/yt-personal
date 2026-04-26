# ROADMAP-FUTURO — mejoras opcionales

Lista priorizada de mejoras para el proyecto. Ninguna es necesaria para
que el sistema actual siga funcionando — todas son inversiones de tiempo
para añadir valor o resilencia.

## Corto plazo (próximos 6 meses, abril 2026 → octubre 2026)

### 1. Activar deeplink universal de Obtainium en `index.html`

**Estado:** la landing actualmente copia la URL al portapapeles. Si
Obtainium publica soporte estable para `obtainium://app/<base64>` (ya está
en discusión upstream), reemplazar el JS para usar deeplink directo →
1 tap en vez de 2.

**Esfuerzo:** ~30 min. Editar `docs/index.html` cuando salga la versión
de Obtainium con soporte. Pages re-deploy automático.

**Beneficio:** UX más fluida para familia/amigos.

### 2. QR codes para los Obtainium imports

**Estado:** la landing tiene texto + botón. Añadir QR generado client-side
con `qrcode.js` (1 KB) permitiría escanear desde un celular nuevo sin
abrir la página manualmente.

**Esfuerzo:** ~20 min. 1 archivo JS embebido + 2 `<canvas>` en el HTML.

**Beneficio:** útil cuando la TV no tiene browser y querés instalar
desde un celular ajeno (escanear con cámara).

### 3. Workflow para chequear cambios en `morphe-patches` README

**Estado:** las versiones compatibles de YT/YTM cambian con cada release
de morphe-patches. Hoy las versionamos a mano (`youtube_version=20.47.62`
en `project-a-manual.yml`). Un workflow podría detectar cuando hay nuevas
versiones soportadas y actualizar el default automáticamente.

**Esfuerzo:** ~2-3 horas. Script Python que parsea README de
morphe-patches + abre PR si hay cambios.

**Beneficio:** menos intervención manual; el pipeline siempre apunta a
la última versión compatible.

### 4. Métricas de los releases en CONTINUIDAD.md

**Estado:** §10 de CONTINUIDAD tiene "métricas de la construcción"
estáticas. Un workflow que las actualice automáticamente cada mes
mantendría el snapshot al día sin trabajo manual.

**Esfuerzo:** ~1 hora. Script bash que cuenta releases + commits + bugs
+ commitea actualizada `CONTINUIDAD.md`.

**Beneficio:** la documentación queda fresca sin esfuerzo continuo.

## Mediano plazo (6-18 meses, octubre 2026 → mediados 2027)

### 5. Project B — cliente nativo Kotlin+Compose

**Estado:** documentado en `project-b/README.md`, no implementado.

**Cuándo activar:** si Morphe-patches o el ecosistema Morphe completo
desaparece, y los fallbacks (anddea, otros) tampoco son viables. El
health-monitor abrirá issues `[HEALTH]` con suficiente anticipación
para iniciar este trabajo.

**Esfuerzo estimado:** 2-3 días de trabajo guiado por Claude. Stack
NewPipeExtractor + Compose + ExoPlayer + Room.

**Beneficio:** independencia total del ecosistema ReVanced/Morphe.
Sigue funcionando aunque YouTube cambie su API drásticamente
(NewPipeExtractor scrapea la versión web).

### 6. Migración a Android 16+ cuando Google saque API levels nuevos

**Estado:** los APKs target SDK 35-36 actualmente. Cuando Google publique
Android 17 / API 37 (Q4 2026 estimado), revisar:
- `compileSdk`, `targetSdk` en `build.gradle.kts` de Project B (cuando
  exista).
- Permisos nuevos que Android 17 introduzca.
- Compatibilidad de los wrappers (microG, Origin) con la nueva versión.

**Esfuerzo:** ~1-2 horas si es solo bump de número. Más si hay APIs
deprecadas que afecten patches.

**Beneficio:** mantenerse al día con el sistema operativo del usuario.

### 7. Custom domain para la landing

**Estado:** Pages sirve en `alexyoj123-tech.github.io/yt-personal/`. Si
el dueño quiere algo más memorable (ej. `yt.alexyoj.com` o
`miyoutube.alexyoj.com`):
1. Comprar dominio (~$10-15/año en Cloudflare/Namecheap).
2. Configurar DNS CNAME → `alexyoj123-tech.github.io`.
3. Settings → Pages → Custom domain del repo.
4. Marcar "Enforce HTTPS".

**Esfuerzo:** ~30 min + costo del dominio.

**Beneficio:** URL más fácil de compartir verbalmente con familia.

## Largo plazo (2027+)

### 8. Soporte iOS — activación del proyecto D-iOS

**Estado:** documentado en `docs/IOS-FUTURO.md`, no ejecutado. Se activa
cuando el dueño compre un iPhone.

**Realidad iOS 2026+:**
- Apple no permite sideload de IPAs sin pago de Apple Developer ($99/año)
  o sin restricciones de TrollStore (iOS 14-16.6.1, no aplicable a iOS
  17+ que vienen los iPhones nuevos).
- AltStore PAL solo en UE.
- En la mayoría de mercados, **la única ruta sin fricción para iOS es
  pagar YouTube Premium $12/mes**. Es la recomendación oficial del
  documento.

**Cuándo decidir:** cuando el dueño efectivamente compre iPhone, evaluar:
- ¿Sostiene YouTube Premium $12/mes? → camino más simple.
- ¿Quiere mantener stack OSS aún en iOS? → invertir tiempo en
  setup AltStore/SideStore (requiere PC o servidor con Wireguard).

### 9. Federar instalación con Tailscale / VPN privada

**Estado:** la landing es pública. Para audiencias muy cerradas (solo
familia), hostear los APKs detrás de Tailscale daría:
- 0 atención de bots/crawlers buscando releases públicos.
- Auto-distribución solo a dispositivos en la red Tailscale del dueño.

**Esfuerzo:** ~3-4 horas. Setup Tailscale Funnel o Pangolin en una VPS
+ migrar Pages a un sitio bajo VPN.

**Beneficio:** privacidad extrema. Trade-off: complejidad de onboarding
(familia tiene que instalar Tailscale primero).

### 10. Backup automático del keystore + secrets

**Estado:** keystore en `C:\Users\alexy\yt-personal-secrets\`, backup
manual. Si el disco muere o el dueño pierde la PC, **todas las apps
firmadas dejan de poderse actualizar** (Android verifica firma).

**Mejora:** workflow GitHub Actions encriptado que:
1. Cada mes hace `gh secret list` + descarga el keystore base64 desde el
   secret (vía API privada, requiere PAT con scope admin:repo).
2. Lo encripta con `age` o `gpg` usando una key derivada de un
   passphrase del dueño.
3. Lo sube como GitHub release de un repo privado de backup, o lo manda
   por email cifrado.

**Esfuerzo:** ~3-5 horas. Cuidado con security model — esto es un
secret crítico, mejor reforzar el backup manual antes de inventar
soluciones.

**Beneficio:** seguro contra pérdida del keystore. Crítico a largo
plazo.

### 11. Reemplazar GitHub Pages si GitHub cambia el modelo

**Estado:** Pages es gratuito en repos públicos. Si GitHub algún día
introduce limitaciones (CDN bandwidth, SLAs, contenido prohibido):

- **Opción A:** mover landing a Cloudflare Pages (gratuito, mismo modelo
  estático).
- **Opción B:** Netlify (idem).
- **Opción C:** S3 + CloudFront (requiere AWS cuenta).
- **Opción D:** auto-host en VPS personal (~$5/mes Hetzner).

**Esfuerzo cuándo se necesite:** ~1-2 horas la migración. Los archivos
son estáticos puros (HTML + JSON), drop-in en cualquier static host.

## Decisiones que NO entran al roadmap

- **Aplicación nativa Android instaladora (Plan A original de Project C):**
  evaluado en abril 2026 y rechazado a favor de Pages + Obtainium. La
  decisión fue correcta — el costo/beneficio no cambió. Si en el
  futuro se quisiera revisar, leer la conversación de abril 2026 que
  documenta el tradeoff.
- **Distribución por Telegram bot / canal:** los archivos pesan ~270 MB
  por release; Telegram tiene límites de tamaño y rate limits. No
  vale la complejidad.
- **App propia en F-Droid:** requiere mantener un repo F-Droid o
  publicar en main F-Droid (proceso lento, audit obligatorio). Para uso
  personal/familiar, Obtainium ya cumple el rol de "package manager
  alternativo".

## Cómo extender este roadmap

Cuando se identifique una mejora nueva:
1. Categorizarla (corto / mediano / largo plazo).
2. Estimar esfuerzo realista (en horas o días).
3. Articular el beneficio concreto.
4. Decidir si entra al roadmap (consciente del costo de mantener una
   lista larga sin priorizar).
