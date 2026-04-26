# docs/ — Índice de documentación

Toda la documentación del proyecto yt-personal vive aquí. Esta página
es el mapa: qué hay en cada archivo y por dónde empezar según quién seas.

## Por audiencia — orden de lectura sugerido

### 👤 Soy familia/amigo y recibí el link
1. **[SETUP-FAMILIA.md](SETUP-FAMILIA.md)** — guía amigable de 5 pasos. Empezá acá.
2. (Opcional) **[INSTALL-TV.md](INSTALL-TV.md)** si tu dispositivo es Android TV.
3. (Opcional) Página principal: <https://alexyoj123-tech.github.io/yt-personal/>.

### 🛠️ Soy el dueño del proyecto y vengo a hacer una tarea concreta
1. **[CONTINUIDAD.md](CONTINUIDAD.md)** — snapshot del estado actual y ubicación de archivos clave.
2. **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)** — runbook si algo falla; los 12 bugs históricos con fix + SHA.
3. **[ANTI-FRAGIL.md](ANTI-FRAGIL.md)** — issues automáticos del health-monitor + procedimientos de migración.

### 🤖 Soy Claude (u otro agente AI) y el dueño me pidió retomar el proyecto
1. **[CONTINUIDAD.md](CONTINUIDAD.md)** — onboarding completo en 5 minutos. Empezar acá SIEMPRE.
2. **[HOW-IT-WORKS.md](HOW-IT-WORKS.md)** — arquitectura del pipeline + cómo se conectan los componentes.
3. **[MIGRATION-GUIDE.md](MIGRATION-GUIDE.md)** — planes B para cada upstream que pueda caer.
4. Si algo falla: **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)** primero, después los específicos según el componente afectado.

### 🔧 Vengo a entender o extender un componente específico
- Pipeline Morphe (Project A) → [HOW-IT-WORKS.md §A](HOW-IT-WORKS.md), [APKMIRROR-SCRAPER.md](APKMIRROR-SCRAPER.md), [KEYSTORE.md](KEYSTORE.md)
- Pipeline YouTube Origin (Project D) → [HOW-IT-WORKS.md §D](HOW-IT-WORKS.md), [MIGRATION-GUIDE-TV.md](MIGRATION-GUIDE-TV.md)
- Health monitor → [ANTI-FRAGIL.md](ANTI-FRAGIL.md)
- GitHub Pages + Obtainium configs → [HOW-IT-WORKS.md §Pages](HOW-IT-WORKS.md), `index.html`, `obtainium-{phone,tv}.json`
- Mejoras a futuro → [ROADMAP-FUTURO.md](ROADMAP-FUTURO.md)

## Tabla de contenidos completa

| Archivo | Líneas | Audiencia | Contenido |
|---------|-------:|-----------|-----------|
| [README.md](README.md) | 60 | Todos | Este índice. |
| [CONTINUIDAD.md](CONTINUIDAD.md) | 360+ | Mantenedor / Claude futuro | Snapshot estado actual, plan original vs ajustes, los 12 bugs ya resueltos, cómo retomar. **Leer primero siempre.** |
| [HOW-IT-WORKS.md](HOW-IT-WORKS.md) | 120 | Mantenedor | Diagrama ASCII del flujo + qué hace cada componente + cómo se conectan. |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 500 | Mantenedor | Catálogo de los 12 bugs históricos con síntoma literal → causa → fix → SHA. Template para añadir bugs nuevos. |
| [ANTI-FRAGIL.md](ANTI-FRAGIL.md) | 220 | Mantenedor | Sistema de health-monitor semanal. Procedimientos paso a paso si un upstream cae. Caso histórico postmortem inotia00→Morphe. |
| [MIGRATION-GUIDE.md](MIGRATION-GUIDE.md) | 190 | Mantenedor | Fallbacks generales por componente (patches, APKMirror, GmsCore, SmartTube). |
| [MIGRATION-GUIDE-TV.md](MIGRATION-GUIDE-TV.md) | 120 | Mantenedor | Fallbacks específicos para YouTube Origin / SmartTube / TizenTube. Sección §firma explicando por qué Origin es excepción. |
| [APKMIRROR-SCRAPER.md](APKMIRROR-SCRAPER.md) | 140 | Mantenedor | Cómo funciona el scraper Python que descarga YT/YTM oficiales. Riesgos y qué hacer si APKMirror cambia HTML. |
| [KEYSTORE.md](KEYSTORE.md) | 75 | Dueño | Detalles del keystore `yt-personal`, ubicación local, cómo regenerar. |
| [SETUP-FAMILIA.md](SETUP-FAMILIA.md) | 90 | Familia / amigo | Guía amigable sin tecnicismo. Lo que ven cuando reciben el link de la página. |
| [INSTALL-TV.md](INSTALL-TV.md) | 75 | Dueño / familia con TV | Instrucciones técnicas Android TV (Origin primario, SmartTube backup, Send Files to TV, ZArchiver). |
| [IOS-FUTURO.md](IOS-FUTURO.md) | 70 | Dueño | Plan documentado de qué hacer cuando el dueño compre un iPhone. NO ejecutado. |
| [ROADMAP-FUTURO.md](ROADMAP-FUTURO.md) | 90 | Mantenedor | Mejoras opcionales priorizadas a corto, mediano y largo plazo. |

Y los archivos servidos por GitHub Pages (no son `.md`, no se leen como prosa):
- [`index.html`](index.html) — landing pública en <https://alexyoj123-tech.github.io/yt-personal/>
- [`obtainium-phone.json`](obtainium-phone.json) — config Obtainium para celular (3 apps)
- [`obtainium-tv.json`](obtainium-tv.json) — config Obtainium para TV (2 apps)

## Convenciones

- Los archivos `MIGRATION-*.md` describen **qué hacer si algo upstream cae**.
- Los archivos `INSTALL-*.md` describen **cómo instalar el output del proyecto**.
- `TROUBLESHOOTING.md` es para **bugs ya resueltos del proyecto** (no es un FAQ de uso).
- `*-FUTURO.md` describe **planes documentados pero no ejecutados**.
- Convención de fechas: ISO `YYYY-MM-DD`.

## Mantenimiento de este índice

Cuando se añada un doc nuevo a `docs/`, actualizar la tabla de §contenidos
arriba + categorizar por audiencia. Mantener el orden de lectura útil.
