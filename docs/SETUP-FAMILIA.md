# YouTube Personal — guía rápida (5 minutos)

¡Hola! Si llegaste acá es porque alguien te compartió el link
<https://alexyoj123-tech.github.io/yt-personal/>. Esta guía te
explica qué es y cómo instalarlo en tu celular o TV.

## ¿Qué es esto?

Una forma de tener **YouTube y YouTube Music sin anuncios** en tu
Android, con descargas, reproducción en background (audio sigue
sonando con la pantalla apagada) y SponsorBlock (salta automáticamente
los segmentos de patrocinio dentro de los videos).

No es magia: son las apps oficiales con ajustes que les hace la
comunidad open source. Para vos cambia un detalle: **el ícono se ve
exactamente igual al original** (rojo de YouTube, ovalado de YT Music)
y el nombre dice "YouTube" / "YouTube Music" como siempre. Por dentro
están un poquito modificadas para no mostrar publicidad.

## Para celular Android (5 pasos, ~5 min)

### Paso 1 — Instalar Obtainium

Obtainium es una app que **mantiene actualizadas** las versiones
modificadas de YouTube/YT Music. Sin ella tendrías que reinstalar a
mano cada vez que sale una versión nueva. Con ella, todo es automático.

Descargalo de uno de estos lugares (cualquiera funciona):
- **F-Droid** (recomendado si ya lo tenés): buscá "Obtainium".
- **Accrescent**: buscá "Obtainium" en la app store de Accrescent.
- **GitHub directo**: <https://github.com/ImranR98/Obtainium/releases/latest>
  (descargar el `.apk` y abrirlo).

La primera vez que abrís un APK desde fuera de Play Store, Android te
pide permiso "Instalar apps desconocidas". Aceptá — es seguro,
Obtainium es open source con miles de usuarios.

### Paso 2 — Importar la configuración

Abrí <https://alexyoj123-tech.github.io/yt-personal/> en tu celular.
Vas a ver dos tarjetas: **📱 Para celular** y **📺 Para TV**.

Tap en el botón "Importar a Obtainium" de la tarjeta del celular. Se
copia una URL al portapapeles y aparece un mensaje "URL copiada".

Ahora abrí Obtainium en tu celular:
1. Tap en el menú **⋮** (3 puntos arriba a la derecha).
2. Tap **Import / Export** → **Import from URL**.
3. Pegá la URL (ya está en el portapapeles).
4. Tap **OK**.

Obtainium muestra **3 apps**:
- **YouTube** (la app oficial, sin anuncios)
- **YouTube Music** (lo mismo)
- **MicroG Services** (un componente que las dos necesitan para login con tu cuenta Google)

### Paso 3 — Instalar las 3 apps en orden

En Obtainium, tap **Add** en cada una de las 3 apps. Después tap
**Install** en cada una. Android va a pedirte permiso "Instalar app"
una vez por app — aceptá.

**Orden recomendado:**
1. **MicroG Services** primero (es lo que YouTube va a usar para login).
2. **YouTube** después.
3. **YouTube Music** al final.

### Paso 4 — Abrir MicroG Services una vez

Después de instalar, abrí la app **microG Services Core** desde tu
launcher. Vas a ver una pantalla con varios checks; algunos pueden
estar en amarillo o rojo. Eso es esperable.

Tocá los checks amarillos uno por uno y aceptá los permisos que pide
(notificaciones, ubicación, batería sin restricción para esta app, etc.).
Esto es **necesario una sola vez** y permite que YouTube y YT Music
puedan hacer login con tu cuenta Google sin necesidad de Google Play
Services.

Cuando todos los checks estén verdes (o casi todos — algunos como
"Work profile" no aplican y se quedan en gris, está OK), cerrá la app.

> Si la app dice **"Limitado"** en la pantalla principal aunque todo
> esté en verde: es solo un texto cosmético, no afecta nada. Detalle
> técnico en el TROUBLESHOOTING del repo si te interesa.

### Paso 5 — Abrir YouTube y disfrutar

Abrí **YouTube** desde tu launcher (ícono rojo, igual al de siempre).
Hacé login con tu cuenta Google (usá la cuenta que normalmente usás
para YouTube). Listo: tu home, suscripciones, historial, todo se
sincroniza igual que con YouTube oficial.

Hacé lo mismo con YouTube Music.

Desde ahora, cuando salga una versión nueva, Obtainium te avisa con
una notificación. Tap → Update → listo. Cero trabajo manual.

## Para Android TV / TV Box (Google TV, Fire TV, Claro 4K)

Mismo flujo pero con **2 apps en vez de 3**:
- **YouTube Origin** (cliente principal — UI igual al YouTube oficial de TV)
- **SmartTube** (backup, si alguna vez Origin falla)

### Setup en TV (~10 min)

1. **En el celular:** abrí <https://alexyoj123-tech.github.io/yt-personal/>
   y tap "Importar a Obtainium" en la tarjeta **📺 Para TV**. URL copiada.
2. **En la TV:** instalá Obtainium (igual que en celular — buscá en Play
   Store de Google TV o instalá vía Downloader by AFTVnews con la URL
   del GitHub release).
3. **En la TV:** abrí Obtainium → ⋮ → Import → URL → pegá la URL.
   (Si tu TV no tiene buen teclado, podés usar la app "Send Files to TV"
   por yablio para mandar texto desde el celular, o el plugin "Phone
   Remote" del control de Google TV.)
4. Add + Install las 2 apps.
5. Abrí **YouTube Origin** (ícono igual al YouTube TV oficial).
   Hacé login con tu cuenta Google.

Detalle técnico TV en [INSTALL-TV.md](INSTALL-TV.md).

## FAQ

**¿Es legal?**
Sí, las apps están construidas con código open source público
(MorpheApp, ReVanced histórico, microG, SmartTube, energylove
energylove/originproject). El uso es personal y educativo. No las
distribuyas a desconocidos ni las uses con propósito comercial.

**¿Es seguro?**
Sí, no se recolecta ni envía nada de tus datos. Cero analytics.
Todas las apps son open source, podés revisar el código.

**¿Funciona con mi cuenta Google normal?**
Sí. Login normal. Tu historial, subs, mis videos, playlists — todo se
sincroniza igual.

**¿Funciona YouTube Premium si lo tengo?**
Si pagás Premium, sí: la cuenta lo reconoce y obtenés audio lossless
en YT Music + algunas otras features. Si NO pagás Premium, igual no
hay anuncios (los bloquea el patch).

**¿Qué pasa si quiero volver al YouTube oficial?**
Desinstalá las 3 apps (YouTube, YouTube Music, MicroG Services) y
reinstalá YouTube oficial desde Play Store. Tu cuenta no se ve
afectada.

**¿Necesito root?**
No. Funciona sin root, sin custom ROM, sin nada raro.

**¿Mi banco / WhatsApp / etc. se va a romper?**
No. Las apps modificadas tienen otro nombre interno (`app.morphe.android.youtube`
en vez de `com.google.android.youtube`) — coexisten sin tocar nada
del resto del sistema.

**¿Qué hago si una app no abre / da error?**
1. Reiniciá el celular (siempre el primer paso).
2. Abrí MicroG Services Core, verificá que todos los checks estén
   verdes y permisos concedidos.
3. Si persiste: mandale un mensaje al amigo/familia que te pasó el
   link. Ellos pueden mirar el TROUBLESHOOTING del repo o pedirme
   ayuda.

**¿Recibo updates automáticos?**
Sí. Obtainium chequea diariamente por nuevas versiones y te notifica.
Tap notif → Update → listo. Si no querés notifs, configurá Obtainium
para silenciar updates automáticas (Settings → notifications).

**¿Puedo ver YouTube en 4K?**
En celular: hasta lo que tu pantalla soporte (en celulares típicos,
1080p). En TV con YouTube Origin + TV Box potente: 4K@30fps real.

**¿Puedo usar PiP (Picture-in-Picture)?**
Sí, está activado por default. Volvé a home con un video reproduciendo,
queda en miniatura.

**¿Reproduce con la pantalla apagada?**
Sí. YouTube Music siempre lo hace; YouTube modificado también (a
diferencia del oficial).

**¿Bloquea anuncios en directos / shorts también?**
La mayoría sí. Algunos pueden colarse ocasionalmente — la comunidad
publica fixes cuando YouTube cambia algo.

## ¿Algo no funciona?

Mandale screenshot al amigo/familia que te pasó el link. Si querés
ver más detalles técnicos, todo está en
<https://github.com/alexyoj123-tech/yt-personal>.
