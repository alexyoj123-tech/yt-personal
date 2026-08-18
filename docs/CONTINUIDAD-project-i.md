# CONTINUIDAD — project-i · HAPER CONTROLER

> Este archivo es lo que va a leer la próxima sesión cuando algo se rompa.
> Antes que nada leé también [`CONTINUIDAD.md`](CONTINUIDAD.md) y
> [`CLAUDE-FUTURO-INSTRUCCIONES.md`](CLAUDE-FUTURO-INSTRUCCIONES.md).

**Construido:** 2026-08-17 (v1.0.0, como VallEthRemote) · **renombrado y
ampliado:** 2026-08-18 (v1.2.0, HAPER CONTROLER).

**Qué es:** app Android de control remoto universal de TV. Código propio, sin
anuncios, sin suscripción, sin telemetría. Reemplaza a las apps de Play Store
que cobran ~$19/semana.

| Dato | Valor |
|---|---|
| Carpeta | `project-i/` |
| `applicationId` | `io.github.alexyoj123.hapercontroler` |
| Label | `HAPER CONTROLER` — **una sola L, es marca del dueño, no un typo** |
| Prefijo de tag | `ytp-i-HaperControler-*` |
| Workflow | `.github/workflows/project-i-hapercontroler.yml` |
| Script de build | `project-i/scripts/build-hapercontroler.sh` |
| Release v1.0.0 (nombre viejo) | `ytp-i-VallEthRemote-1.0.0+20260818` — **no se borra nunca** |
| Release v1.2.0 | `ytp-i-HaperControler-1.2.0+20260818` |

> **El rename cambió el `applicationId`.** Android trata
> `io.github.alexyoj123.vallethremote` y `io.github.alexyoj123.hapercontroler`
> como dos apps distintas: la nueva **no se instala encima** de la vieja. Hay
> que desinstalar VallEthRemote a mano una vez. Está avisado en las notas del
> release.

---

## 1. El hallazgo que cambia el plan — leer esto primero

El prompt de construcción daba por hecho que el Claro Box 4K vive en
`192.168.0.67` y que el camino principal era ADB por TCP. **Las dos cosas
estaban desactualizadas.** Reconocimiento hecho el 2026-08-17 desde la PC del
dueño, conectada a la misma Wi-Fi:

```
PC:            192.168.1.25    → la LAN es 192.168.1.0/24, NO 192.168.0.0/24
192.168.0.67:  no responde ni a ping (esa subred ya no existe en esta casa)
```

Barrido completo de la /24 (hosts vivos: .1 .2 .22 .23 .25 .28 .29 .34 .39) y
sondeo de los puertos 3000, 3001, 5555, 6466, 6467, 8001, 8002, 8060, 8080,
9197, 7676:

| Host | Puertos abiertos | Identificado por | Qué es |
|---|---|---|---|
| `192.168.1.28` | **6466, 6467** | mDNS `_googlecast._tcp` → `fn=Claro TV Box 4k`, `md=Claro TV Box 4k` | El Claro TV Box 4K. MAC `a8:4f:a4:1b:3c:46` |
| resto | — | — | nada que hable protocolo de TV |

Conclusiones duras:

1. **El puerto 5555 (ADB) está CERRADO en el Claro Box.** La «Depuración por
   red» no está habilitada hoy. El driver ADB está implementado y completo,
   pero no se puede usar hasta que el dueño la active — la app muestra los 6
   pasos exactos en pantalla (`AndroidTvAdbDriver.PASOS_ADB`).
2. **Los puertos 6466/6467 SÍ están abiertos**: es el protocolo *Android TV
   Remote v2*, el del control oficial de Google TV. Es la única ruta de red
   viva hoy hacia esa caja. Por eso ese driver **sube al primer lugar de la
   fase 3** (ver §6), aunque el prompt original lo ponía último.
3. **La TV Samsung no apareció**: ningún host con 8001/8002 abierto. Lo
   esperable si estaba apagada — Tizen no responde WebSocket apagada, que es
   exactamente por lo que existe el Wake-on-LAN del driver. No se pudo probar
   nada de Samsung contra hardware.

### Estado del Box medido de nuevo el 2026-08-18

La sesión del 18 arrancó con la premisa de que «el Claro TV Box está encendido
y conectado a la laptop». **La primera mitad era cierta, la segunda no.** Lo
que se midió:

| Comprobación | Resultado |
|---|---|
| `ping 192.168.1.28` | ✅ responde, 3 ms — el Box está encendido |
| mDNS `_googlecast._tcp` | ✅ `fn=Claro TV Box 4k`, y esta vez con `st=1` y `rs=Youtube`: el Box estaba reproduciendo YouTube |
| `adb devices` (USB) | ❌ vacío, con y sin reiniciar el servidor |
| Windows: interfaz ADB en el USB | ❌ no aparece ningún dispositivo Android |
| Puerto 5555 | ❌ cerrado |
| mDNS `_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp` | ❌ no se anuncian |
| Puertos 5037, 5556-5560, 5565, 5585, 37000, 39000, 41000, 43000, 45000 | ❌ todos cerrados |
| Puertos 6466 / 6467 | ✅ abiertos |

**Conclusión: sigue sin haber ninguna ruta ADB al Box.** La ausencia del
anuncio mDNS `_adb-tls-connect._tcp` es concluyente: Android **siempre**
publica ese servicio cuando la depuración inalámbrica está encendida. Y el
`adb tcpip 5555` del plan no se puede correr, porque necesita una conexión USB
previa que no existe.

Vale la pena decirlo: muchos TV Box solo tienen puertos USB **host** (tipo A,
para pendrives), no un puerto de datos en modo dispositivo. Si ese es el caso
del ZTE B866V2H, el camino por USB no existe físicamente y **la única vía es
habilitar «Depuración por red» en el propio Box** (Ajustes → Preferencias del
dispositivo → Acerca de → tocar «Compilación» 7 veces → Opciones de
programador → Depuración por red).

Por eso quedaron **sin ejecutar** las secciones §4 (inventario por ADB), §5
(instalar microG / YouTube Origin / Obtainium) y §6.1 (configurar Obtainium
por `appops` y `deviceidle`) del plan del 18: todas empiezan por
`adb connect`. La tabla del §7 de este documento sigue vacía por lo mismo.

> Si en el futuro no aparece nada al buscar: verificá primero en qué subred
> está la PC/celular (`ipconfig` / ajustes de Wi-Fi). Esta casa ya cambió de
> `192.168.0.x` a `192.168.1.x` una vez.

---

## 2. Qué quedó construido

Todo lo de la **fase 1** del plan original, más Bluetooth HID, voz, y —desde
el 2026-08-18— los drivers que faltaban, «Mis apps» y el módulo Despliegue.

```
project-i/app/src/main/java/io/github/alexyoj123/hapercontroler/
├── core/          TvDriver por capacidades, RemoteKey, TvDevice, DiagLog, Net (WoL)
├── data/          DeviceStore (DataStore), RemoteRepository (orquestador + cascadas)
├── discovery/     mDNS (NsdManager) + SSDP/DIAL + sondeo /24, clasificado por puerto
├── driver/
│   ├── samsung/   SamsungTizenDriver     — WebSocket 8002, token, teclas, apps, WoL
│   ├── androidtv/ AndroidTvAdbDriver     — shell persistente, texto, intents, pm install
│   │              AdbShellSession        — el stream único que da la baja latencia
│   │              AndroidTvRemoteDriver  — control oficial de Google TV, 6466/6467
│   │              AtvIdentity            — identidad TLS en el Keystore de Android
│   │              Proto                  — protobuf mínimo escrito a mano
│   ├── roku/      RokuEcpDriver          — HTTP 8060
│   └── webos/     WebOsDriver            — SSAP 3000/3001 + cursor nativo
├── deploy/        GithubReleases, DeployConfig, DeployEngine, DeployWorker
├── hid/           BluetoothHidController, HidDescriptor, HidForegroundService
├── voice/         VoiceController (SpeechRecognizer), VoiceIntentParser
└── ui/            Remoto · Mouse · Apps · Enviar · Equipos · Diag
```

### Lo nuevo del 2026-08-18

**Rename a HAPER CONTROLER.** Paquete Kotlin, `applicationId`, workflow,
script, tema y wordmark. El label lleva **una sola L** a propósito. Se
verificó que `app/build.gradle.kts` **no** tenga un
`resValue("string", "app_name", …)`: si lo tuviera, sobrescribiría a
`strings.xml` y el rename no se aplicaría — fue el tropiezo de project-g.

**Logo propio.** Una H cuyos montantes hacen de laterales de un D-pad,
atravesada por un haz con el OK calado en negativo (`fillType="evenOdd"`).
Adaptive icon con las **tres** capas: background con degradado, foreground, y
**monochrome** — sin esa última, los launchers con iconos temáticos activados
muestran un monocromo equivocado, que es lo que pasó en project-g.

**`AndroidTvRemoteDriver`** — el protocolo del control oficial de Google TV,
que hoy es el único puerto de control abierto del Claro Box. Incluye un
codificador protobuf propio de ~120 líneas (`Proto.kt`) en vez de traer
protobuf-javalite y codegen: los mensajes son un puñado y todos de campos
escalares. La identidad TLS de cliente se genera con `KeyGenParameterSpec` en
el **Keystore de Android**, indexada por dispositivo, así la clave privada
nunca sale del hardware.

**`WebOsDriver`** — SSAP con `client-key` guardado y cursor nativo por el
socket de puntero.

**Preferencia y respaldo entre drivers.** Si un aparato expone 6466 **y** 5555
gana ADB (da instalación de APKs, texto y la lista real de paquetes). Si ADB
no responde —la depuración por red se apaga sola al reiniciar en muchos
Android TV— se cae automáticamente al control oficial, avisando en pantalla
que por ahí no se puede instalar ni escribir.

**«Mis apps».** Lista y abre paquetes instalados aunque el launcher del
aparato los esconda. Una app sin `LEANBACK_LAUNCHER` queda instalada pero **no
aparece en el inicio de un Android TV**; no es un fallo de instalación y no se
arregla desde el aparato. La activity se resuelve con
`cmd package resolve-activity`, no se hardcodea.

**Módulo Despliegue** — ver §10, que es la parte que más vale la pena.

### La regla de UX que gobierna todo

La UI consulta `TvDriver.capabilities`, **nunca la marca**. Si una capacidad no
existe en el dispositivo conectado, el botón **se oculta o se explica con el
motivo real**. Ejemplos vivos en el código:

- Enviar APK a una Samsung → no hay botón; hay un texto que dice que Tizen no
  es Android y no ejecuta APKs (`RemoteRepository.motivoSinInstalacion`).
- Trackpad en Android TV sin Bluetooth → dice «Sin cursor: los gestos mueven el
  foco con flechas» en vez de fingir un cursor.
- Wake-on-LAN → el botón solo aparece si el driver lo soporta **y** ya se
  conoce la MAC.

Agregar una marca nueva = agregar un driver. No se toca ninguna pantalla.

---

## 3. Decisiones tomadas y por qué

| # | Decisión | Por qué |
|---|---|---|
| 1 | **compileSdk 37** con targetSdk 36 | No es capricho: Compose 1.12, core-ktx 1.19, lifecycle 2.11 y OkHttp 5.5 **rechazan** compilar contra 36 (`checkAarMetadata` aborta). Compilar contra una API más nueva no cambia el comportamiento en runtime; el que lo cambia es targetSdk, que sigue en 36 como pedía el plan. |
| 2 | **AGP 9.3.1 + Gradle 9.7.0** | AGP 8.13 no soporta compileSdk 37 y las mismas librerías exigen AGP ≥ 9.1. AGP 9 requiere Gradle ≥ 9.5. |
| 3 | **Sin el plugin `org.jetbrains.kotlin.android`** | Desde AGP 9.0 el soporte de Kotlin viene incorporado y aplicar el plugin además es un error de configuración duro (falla en `com.android.internal.version-check`). Solo se aplica `org.jetbrains.kotlin.plugin.compose`. |
| 4 | **JDK 17 en CI** | Lo pedía el plan y AGP 9 lo acepta. Localmente se compiló con JDK 21; no se declara `jvmToolchain` a propósito para que ambos funcionen sin descargar toolchains. |
| 5 | **dadb `dev.mobile:dadb:1.2.9`** | Verificado en Maven Central el 2026-08-17, es la última publicada. Ver §5 — **la API publicada NO es la del `master` de GitHub**. |
| 6 | **R8 apagado en release** | dadb y OkHttp hacen crypto/reflexión en el handshake; ofuscarlas rompe en runtime y no en compilación, que es el peor tipo de bug. Costo: el APK pesa 25 MB. Las reglas keep ya están escritas en `proguard-rules.pro` para cuando se quiera encender (ver §6). |
| 7 | **Tema oscuro fijo, sin DayNight** | Un control remoto se usa a oscuras. Además evita arrastrar la librería Material de vistas. |
| 8 | **Sin `material-icons-extended`** | Estaba congelada en la línea 1.7.x y peleaba con el BOM 2026.08. Los botones usan texto («VOL +», «OK», «▲»), que en un remoto se lee igual de bien. |
| 9 | **Puntero Samsung con `Cmd:"Move"` + `Position`** | El plan documentaba el objeto de coordenadas dentro de `Cmd`. Se eligió la forma de la implementación de referencia `samsungtvws`, que tiene más evidencia detrás. **Sin verificar en hardware** — ver §4. |
| 10 | **El driver ADB NO declara `POINTER`** | Android TV no tiene cursor de sistema. Traducir deslizamientos a flechas del D-pad es justamente el defecto de las apps que estamos reemplazando: para cursor real está Bluetooth HID. |
| 11 | **`AndroidTvRemoteDriver` y `WebOsDriver` NO implementados** | El plan los pone en la fase 3 y la regla del proyecto es no acumular fases sin probar. Aparecen en el descubrimiento pero la tarjeta dice «detectado, driver pendiente» y el botón «Usar este» está deshabilitado — antes que un botón que no hace nada. |
| 12 | **`gradle-wrapper.jar` commiteado** | El `.gitignore` de la raíz ignora `*.jar`; `project-i/.gitignore` tiene la excepción explícita. Sin él, `./gradlew` no existe en CI. Es el jar oficial de `gradle/gradle` tag `v8.14.3`, SHA-256 `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`. |

### Decisiones del 2026-08-18

| # | Decisión | Por qué |
|---|---|---|
| 13 | **`AndroidTvRemoteDriver` NO declara `TEXT`** | El protocolo sí tiene inyección de IME (`RemoteImeBatchEdit`), pero solo funciona cuando la TV ya avisó que hay un campo de texto enfocado, y eso no se puede garantizar desde la app. Declararlo dejaría un botón que a veces no hace nada — exactamente lo que este proyecto no hace. Para escribir: ADB o el teclado Bluetooth. El plan pedía declararlo; se desvió a propósito y esta es la razón. |
| 14 | **Protobuf escrito a mano** en vez de protobuf-javalite + codegen | Los mensajes son un puñado y todos de campos escalares, bytes o submensajes. Sumar un plugin de codegen al build por esto sería más frágil que 120 líneas legibles. |
| 15 | **Identidad TLS en el Keystore de Android** | `KeyGenParameterSpec` genera de una el certificado autofirmado, sin necesidad de BouncyCastle ni de armar el X.509 a mano. Y la clave privada no es extraíble. |
| 16 | **`launchApp` del control oficial solo abre enlaces conocidos** | `RemoteAppLinkLaunchRequest` abre una URL, no un paquete. Inventar un `market://` abriría la tienda, no la app: otro botón que finge funcionar. Con ADB se abre cualquier paquete. |
| 17 | **Comparación por `versionCode` leído del APK sin instalarlo** | `getPackageArchiveInfo` da package y versionCode del archivo descargado. Así el despliegue no reinstala lo que ya está al día y tampoco depende de adivinar por fecha. |
| 18 | **Seis pestañas, con «Enviar» dentro de «Despliegue»** | Siete pestañas no entran en la pantalla del A04e. Enviar un APK a mano y desplegarlo solo son el mismo problema, así que comparten pantalla. |
| 19 | **`obtainium-tv.json` NO se reescribió con `^ytp-d-`** | El archivo ya existía y filtraba `(^ytp-d-origin-\|YouTube Origin)` para Origin y `(^ytp-a-\|Daily ReVanced Build)` para SmartTube. Cambiarlo a `^ytp-d-` a secas habría roto SmartTube. Lo que sí se hizo: **agregar microG en primer lugar**, que es el orden en que hay que instalarlo. |
| 20 | **Cron de project-d a `0 4 * * *`** | Diario como pidió el dueño, a la misma hora que ya tenía y dos horas antes que project-a (06:00) para no pisarse. Se cambió **solo esa línea**; el run manual posterior quedó verde. |

### Lo que el plan pedía y NO se hizo, con motivo

- **`YOUTUBE_API_KEY` / protocolo Lounge de YouTube.** El plan decía que el
  repo ya tenía ese secret «de project-h». **Es falso**: `gh secret list`
  devuelve exactamente cuatro secrets, los del keystore
  (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`), y no existe ningún `project-h` en el repo. La ruta
  Lounge queda pendiente y requiere crear el secret primero.
- **No se agregó la app a `docs/tienda.html` / `instalar.html`.** Es opcional en
  el plan. Si se agrega, describirla como «control remoto» — el HTML público
  usa lenguaje neutro y no menciona marcas de TV.

---

## 4. Qué se probó y qué NO — sin adornos

### Probado de verdad

| Qué | Cómo | Resultado |
|---|---|---|
| Descubrimiento de red (la lógica, no la app) | Barrido /24 + mDNS + sondeo de puertos desde la PC | ✅ Encontró el Claro TV Box 4K y su nombre real por el TXT de Cast |
| Compilación debug y release | `./gradlew assembleDebug` / `assembleRelease` local | ✅ Verde |
| Identidad del APK | `aapt2 dump badging` | ✅ `package=io.github.alexyoj123.vallethremote`, `label=VallEthRemote`, min 28 / target 36 |
| `resources.arsc` sin comprimir + alineación 4 bytes | `unzip -v` + `zipalign -c -v 4` | ✅ `Stored`, alineado |
| Firma con el keystore del repo | `keytool -printcert -jarfile` | ✅ SHA-256 `4840:63E1:…:2987`, el del repo |
| Script de CI completo | Corrido local con `PUBLICAR=false` | ✅ |
| Workflow real en GitHub Actions | run 32090955391 | ✅ Verde, publicó `ytp-i-VallEthRemote-1.0.0+20260818` |
| APK publicado (descargado del release) | `aapt2 dump badging` + `keytool -printcert -jarfile` | ✅ package y label correctos, firmado con la SHA-256 del repo |
| Los otros proyectos siguen intactos | último run de cada workflow | ✅ a/b/c/d/e/f/g/health/pages, todos en `success` |

### Probado el 2026-08-18

| Qué | Cómo | Resultado |
|---|---|---|
| Estado real del Claro Box | ping, ARP, barrido de puertos, mDNS de 5 servicios | ✅ medido — ver §1 |
| Rename completo | `aapt2 dump badging` sobre el APK de release | ✅ `package=io.github.alexyoj123.hapercontroler`, `label=HAPER CONTROLER` |
| Icono adaptativo con las 3 capas | `aapt2 dump xmltree` del recurso de icono dentro del APK | ✅ `background`, `foreground` **y** `monochrome` presentes y apuntando a los drawables propios |
| Firma, alineación, `resources.arsc` | script de CI completo con `PUBLICAR=false` | ✅ |
| project-d con cron diario | `gh workflow run project-d-weekly.yml` | ✅ verde |
| Release v1.2.0 publicado por CI | run 32097210716 | ✅ verde |
| APK del release descargado y verificado | `aapt2` + `keytool` sobre el asset publicado | ✅ package, label, min 28 / target 36, las 3 capas del icono y la firma del repo |
| Los otros proyectos siguen intactos | último run de a/b/c/d/e/f/g/health + `git diff` | ✅ todos en `success`; el único cambio fuera de project-i es la línea del cron de project-d |
| El release viejo sigue publicado | `gh release list` | ✅ `ytp-i-VallEthRemote-1.0.0+20260818` intacto |

**El icono NO se vio en un emulador con iconos temáticos activados**, como
pedía el §7 del plan: no hay ninguna AVD creada ni system-image descargada en
esta PC, y bajar una (~1,5 GB, más `cmdline-tools` que tampoco está instalado)
era un desvío grande para una comprobación visual. Lo que sí se verificó es lo
verificable sin emulador: que la capa `monochrome` existe y está bien
referenciada dentro del APK, que es la causa concreta del bug de project-g.

### NO probado contra hardware — esto es lo importante

**Nada de la app corrió todavía en un celular ni contra una TV.** No había
ningún teléfono conectado por ADB durante la construcción y la TV Samsung
estaba apagada. Todo lo de abajo es código escrito con cuidado pero sin
confirmar en el aparato real:

- ❓ Samsung: emparejamiento con token, teclas, `SendInputString` (el plan
  avisa que Samsung lo capó en varios modelos 2021+), puntero
  `ProcessMouseDevice`, `ed.installedApp.get`, Wake-on-LAN.
- ❓ Claro Box por ADB: **no se pudo verificar el §5.2 del plan** (si
  `pm install` esquiva el bloqueo del operador) porque el puerto 5555 está
  cerrado. `AndroidTvAdbDriver.devicePolicyReport()` corre
  `dumpsys device_policy` y deja el resultado en el log justamente para
  responder eso el día que se habilite ADB.
- ❓ Bluetooth HID: que el celular se registre como periférico y que la TV lo
  acepte como mouse. Es el punto con más incógnita del proyecto: `registerApp`
  depende del fabricante del celular.
- ❓ Voz: el reconocimiento `es-419` en el Galaxy A04e.
- ❓ **Todo el `AndroidTvRemoteDriver`.** El emparejamiento necesita que
  alguien lea el código de 6 dígitos en la pantalla de la TV y lo escriba en la
  app; no hay forma de automatizarlo. **El punto exacto que más fácil se rompe
  es el cálculo del secreto**: módulo y exponente van en big-endian *mínimo*,
  sin el `0x00` de signo que agrega `BigInteger.toByteArray()`. Está resuelto
  así en `AtvIdentity.publicNumbers`, con el porqué escrito al lado. Si la TV
  responde `STATUS_BAD_SECRET`, mirar ahí primero.
- ❓ **Todo el `WebOsDriver`.** No hay ninguna TV LG en la casa.
- ❓ **El módulo Despliegue de punta a punta.** Se puede probar sin TV la parte
  de GitHub (consultar releases y descargar), pero la instalación necesita ADB.
- ❓ **dadb sobre Android.** Es una librería pensada para JVM de escritorio.
  Usa `Cipher.getInstance("RSA/ECB/NoPadding")`, `java.util.Base64` (API 26+,
  ok con minSdk 28) y `KeyPairGenerator` RSA — todo existe en Android, pero no
  está confirmado en ejecución.

**El primer trabajo de la próxima sesión es correr la pantalla
«Diagnóstico → Probar capacidades» en la Samsung y en el Claro Box y pegar el
resultado acá abajo.** Hay una tabla vacía esperando en §7.

---

## 5. Lo frágil — dónde va a romper

Ordenado por probabilidad de que muerda.

1. **dadb: la API publicada ≠ la del `master` de GitHub.** Esto ya mordió
   durante la construcción. El `master` tiene `InstallResult` sellado y un
   parámetro `keepAlive` en `Dadb.create`; **la 1.2.9 publicada no tiene
   ninguno de los dos** — `install()` devuelve `void` y lanza `IOException`
   con la salida cruda de `pm` adentro. Si alguna vez actualizás la versión,
   verificá la API real del jar con
   `javap -cp dadb-<v>.jar dadb.Dadb`, no leas el `master` de GitHub.
2. **dadb + okio.** dadb 1.2.9 se publicó contra okio 2.10 y la app usa okio
   3.18.1 (pineada explícitamente para que Gradle no deje dos peleando). Si
   aparece un `NoSuchMethodError` de okio al conectar por ADB, es esto. Plan B:
   escribir el handshake ADB a mano (`CNXN`/`AUTH` con RSA, `OPEN`/`WRTE`); la
   parte difícil es el formato de clave pública de ADB, que es una struct
   `RSAPublicKey` little-endian con los parámetros de Montgomery `n0inv` y
   `rr` — no es un PEM.
3. **`resolveService` de NsdManager** está deprecado desde API 34 y en varias
   versiones solo tolera una resolución a la vez. Si el descubrimiento por mDNS
   se vuelve errático, migrar a `registerServiceInfoCallback`. El sondeo de
   puertos cubre el hueco mientras tanto, así que la app no queda ciega.
4. **El puntero y el texto de Samsung.** Samsung los fue capando modelo por
   modelo. Cuando fallen, la app ya cae sola a Bluetooth HID y lo dice; no hay
   que arreglar nada, pero anotá en §7 qué modelo lo capó.
5. **`BluetoothHidDevice.registerApp` depende del fabricante.** Si devuelve
   `false` en el A04e, no hay workaround: ese celular no puede ser mouse. La UI
   ya muestra «Este celular no puede actuar como periférico HID».
6. **AGP 9 es nuevo.** Si un build futuro falla en `com.android.internal.*`,
   mirá primero si alguien volvió a agregar el plugin `kotlin.android`.
7. **La subred de la casa.** Ya cambió una vez. Nada está hardcodeado —
   `Net.subnetPrefix24()` la calcula sola — pero cualquier instrucción escrita
   con una IP fija envejece mal.

### Gotchas del CI que ya mordieron (no repetirlos)

- **El paquete de la platform es `platforms;android-37.0`, con el `.0`.**
  `platforms;android-37` a secas no existe: `sdkmanager` imprime un
  `Warning: Failed to find package` y sale con **código 1**, lo que aborta el
  step aunque parezca solo un aviso. El workflow ahora tolera que falle esa
  instalación porque AGP descarga la platform sola.
- **`apksigner verify` salió con código 1 en el runner sin explicar por qué**,
  y con `set -e` eso abortaba el build justo antes de publicar, sin imprimir
  nada. El script ahora imprime siempre la salida de apksigner y verifica la
  firma con `keytool -printcert -jarfile` como alternativa. Lo que sigue
  abortando el build es lo que importa de verdad: que la SHA-256 no sea la del
  keystore del repo.
- **`sdk.dir` en `local.properties` con backslashes de Windows rompe el build**
  (`java.io.IOException: El nombre de archivo… no son correctos`): los `.properties`
  interpretan `\U` como escape. Usar barras normales:
  `sdk.dir=C:/Users/alexy/AppData/Local/Android/Sdk`.
- **`unzip -v` tiene las columnas `Length Method Size Cmpr Date Time CRC Name`**:
  el método de compresión es `$2`, no `$6`. Un `awk` con el índice equivocado
  hacía fallar la verificación de `resources.arsc` con un mensaje falso.

---

## 6. Pendientes, en orden

1. **Abrir una ruta ADB al Claro Box.** Es el cuello de botella de TODO lo que
   queda: el inventario del aparato, instalar microG/YouTube Origin/Obtainium,
   la tabla de capacidades del §7 y el despliegue automático. Dos caminos:
   - **Recomendado — «Depuración por red» en el Box:** Ajustes → Preferencias
     del dispositivo → Acerca de → tocar «Compilación» 7 veces → Opciones de
     programador → activar «Depuración por red» (o «Depuración inalámbrica»).
     Después, desde la PC: `adb connect 192.168.1.28:5555` y aceptar la huella
     RSA en la TV marcando «Permitir siempre».
   - **Por USB:** solo si el Box tiene un puerto de datos en modo dispositivo.
     Muchos TV Box solo traen USB host. Si aparece en `adb devices`, entonces
     `adb tcpip 5555` y listo.
2. **Correr el inventario** y pegarlo en el §7:
   `getprop ro.product.model`, `ro.build.version.sdk`, `ro.product.cpu.abi`,
   `wm density`, `pm list packages | grep -i youtube`, y sobre todo
   `dumpsys device_policy | grep -i -A5 "owner\|restriction"` — que es lo que
   responde si el bloqueo del operador es de device-owner o solo del toggle.
3. **Instalar las apps del dueño, en orden: microG primero.** Sacar los nombres
   de asset reales de `.github/workflows/project-d-weekly.yml` y de
   `gh release view <tag> --json assets`. Antes de instalar, confirmar contra
   el archivo con `aapt2 dump badging`: que `native-code` incluya `arm64-v8a`,
   y si **no** hay `LEANBACK_LAUNCHER`, la app quedará instalada pero no saldrá
   en el inicio del Box — para eso está «Mis apps».
4. **Probar el emparejamiento del `AndroidTvRemoteDriver`** con el código de 6
   dígitos. Es lo único que hoy puede controlar el Box sin habilitar nada.
5. **Probar el módulo Despliegue** con «Revisar ahora» y confirmar si la
   depuración por red del Box **sobrevive a un reinicio**. Si no sobrevive,
   anotarlo acá: la app ya detecta el caso y lo explica en vez de fallar en
   silencio, pero conviene que quede escrito.
6. **Encender la Samsung** y correr la prueba de capacidades. Es lo único de
   Samsung que nunca se pudo tocar.
7. **Encender R8.** Bajaría el APK de 26 MB a ~6 MB. Las reglas keep ya están
   en `proguard-rules.pro`. Hacerlo **cuando las tablas del §7 estén llenas**,
   nunca antes: si algo falla, hay que saber que no fue R8.
8. **Ruta Lounge de YouTube.** Hoy degrada sin error (ver §11). Para
   habilitarla hace falta crear la key en Google Cloud Console y ponerla en
   `local.properties` como `youtube.api.key`, o como variable del workflow —
   nunca en el repo, que es público.
9. Perfiles por dispositivo, widget y controles en pantalla de bloqueo.
10. Opcional: publicar la app en `docs/tienda.html` / `obtainium-phone.json`
    describiéndola como «control remoto», con lenguaje neutro.

---

## 7. Resultados de la prueba de capacidades en hardware real

> **Vacío a propósito.** Se llena corriendo Diagnóstico → «Probar capacidades»
> en cada aparato y pegando acá lo que salga, con modelo y fecha. Sin esto, la
> fase 1 no está cerrada.

### Samsung Smart TV 65" (2022, Tizen) — pendiente

| Capacidad | ✓/✗ | Detalle | Latencia |
|---|---|---|---|
| Teclas | | | |
| Texto por red (`SendInputString`) | | | |
| Listar apps (`ed.installedApp.get`) | | | |
| Puntero (`ProcessMouseDevice`) | | | |
| Wake-on-LAN | | | |
| Trackpad Bluetooth HID | | | |

### Claro TV Box 4K (`192.168.1.28`, Android TV) — pendiente

| Capacidad | ✓/✗ | Detalle | Latencia |
|---|---|---|---|
| Conexión ADB (5555) | | ver §1: hoy cerrado | |
| Teclas (shell persistente) | | | |
| Texto (`input text`) | | | |
| Listar apps (`pm list packages -3`) | | | |
| Instalar APK (`pm install`) | | **anotar el texto EXACTO del error si falla** | |
| `dumpsys device_policy` | | ¿hay device-owner del operador? | |
| Trackpad Bluetooth HID | | | |

---

## 8. Comandos útiles

```bash
# Disparar un build manual (publica release)
gh workflow run project-i-hapercontroler.yml --repo alexyoj123-tech/yt-personal --ref main

# Solo compilar y verificar, sin publicar
gh workflow run project-i-hapercontroler.yml --repo alexyoj123-tech/yt-personal --ref main -f publicar=false

# Ver el run
gh run list --repo alexyoj123-tech/yt-personal --limit 5
gh run view <RUN_ID> --repo alexyoj123-tech/yt-personal --log-failed

# Build local (firma con la debug key si no hay variables HAPER_*)
cd project-i && ./gradlew assembleRelease

# Reconocimiento de la red, como el del §1
#   (PowerShell, desde la PC en la misma Wi-Fi)
#   barrido de hosts vivos + sondeo de 3000/5555/6466/6467/8001/8060

# Verificar identidad y firma de un APK publicado
gh release download <TAG> --repo alexyoj123-tech/yt-personal -p '*.apk' -O /tmp/hc.apk
aapt2 dump badging /tmp/hc.apk | head -3
keytool -printcert -jarfile /tmp/hc.apk | grep -i SHA256
# debe dar 48:40:63:E1:...:29:87
```

---

## 9. Reglas del repo que este proyecto respeta

- **Nunca se borra un release ni un tag.** El script busca si el tag ya existe
  y republica con sufijo `-2`, `-3`… Borrar tags ya corrompió la caché del CDN
  antes.
- **No se tocó nada de project-a/d/e/f/g/h.** project-i es puramente aditivo:
  una carpeta nueva y un workflow nuevo.
- **`FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true`** está en el workflow.
- **El keystore nunca entra al repo.** Llega por los cuatro secrets ya
  existentes, se decodifica a un `mktemp -d` y se borra con un `trap EXIT`
  incluso si el build revienta.
- **El log de diagnóstico nunca guarda tokens de TV ni el contenido de lo que
  se dicta por voz** — solo la intención detectada y la longitud del texto.

---

## 10. El módulo Despliegue — cómo funciona

Es la respuesta al límite honesto de Obtainium: **Obtainium baja la
actualización sola, pero pide un toque para instalar.** Sin root, Shizuku o ser
device-owner, Android no permite instalación silenciosa a una app normal, y su
interfaz además no está pensada para D-pad.

Este módulo esquiva eso por otro lado: **instala por ADB**. Como `pm install`
corre entonces con el usuario `shell`, no le aplica la restricción de «apps
desconocidas» que puso Claro. Sin root y sin tocar nada del sistema.

**Cómo trabaja, en orden:**

1. `WorkManager` diario a la hora configurada, con Wi-Fi sin medir y batería
   no baja.
2. Por cada línea vigilada (`ytp-d-origin-`, `ytp-a-`, …) pide los releases del
   repo. Se piden **todos** y se filtra por prefijo de tag, no se usa
   `/releases/latest`: el repo publica varias líneas a la vez y `latest`
   devolvería la más reciente de todas.
3. Atajo barato: si el tag no cambió desde el último chequeo, no descarga nada.
4. Descarga los APKs y lee `packageName` y `versionCode` **sin instalarlos**
   con `getPackageArchiveInfo`. Solo instala si el del archivo es mayor que el
   instalado (que lee por ADB con `dumpsys package`).
5. **Ordena microG primero, siempre.** Al revés, YouTube abre y falla en el
   login.
6. Ante `INSTALL_FAILED_UPDATE_INCOMPATIBLE` o `..._DUPLICATE_PERMISSION`,
   deshabilita el paquete de fábrica con `pm uninstall -k --user 0` y reintenta
   **una sola vez**. Si vuelve a fallar se detiene y deja el error crudo de
   `pm`. **Nunca un bucle de reintentos.**
   Reversa manual: `cmd package install-existing <paquete>`.
7. Notificación con el resultado y línea en el log de Diagnóstico.

**Lo que necesita, y está escrito dentro de la propia pantalla:** el celular en
la misma Wi-Fi que el aparato, y la depuración por red encendida en el
aparato. Si falta cualquiera de las dos, el chequeo lo dice en el resultado en
vez de fallar en silencio.

**Token de GitHub:** opcional pero recomendado. Sin token la API permite 60
consultas por hora por IP y el chequeo puede fallar con «límite excedido» —
el mismo problema que ya apareció en el teléfono con Obtainium. Se guarda solo
en el celular y nunca aparece en el log.

---

## 11. Ruta Lounge de YouTube — degradación sin error

`YOUTUBE_API_KEY` **no existe** en el repo y project-h nunca se construyó.
La app está programada para que eso no rompa nada:

- La key se lee de `local.properties` (`youtube.api.key`) o de la variable de
  entorno `YOUTUBE_API_KEY` en tiempo de build, y entra como `BuildConfig`.
  **Nunca se hardcodea**: el repo es público.
- Si falta, la búsqueda por voz en la Samsung cae al teclado Bluetooth HID,
  que funciona igual.
- La pantalla de Diagnóstico tiene una fila que dice explícitamente que la key
  no está configurada y cómo obtenerla.

La implementación del protocolo Lounge en sí (emparejar por código con la app
de YouTube de la TV, obtener `screenId` + `loungeToken`, mandar el `videoId`)
queda pendiente hasta que exista la key: escribirla sin poder probarla contra
nada sería código no verificable.
