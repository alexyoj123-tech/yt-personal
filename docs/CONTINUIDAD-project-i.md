# CONTINUIDAD — project-i · VallEthRemote

> Este archivo es lo que va a leer la próxima sesión cuando algo se rompa.
> Antes que nada leé también [`CONTINUIDAD.md`](CONTINUIDAD.md) y
> [`CLAUDE-FUTURO-INSTRUCCIONES.md`](CLAUDE-FUTURO-INSTRUCCIONES.md).

**Fecha de construcción:** 2026-08-17
**Qué es:** app Android de control remoto universal de TV. Código propio, sin
anuncios, sin suscripción, sin telemetría. Reemplaza a las apps de Play Store
que cobran ~$19/semana.

| Dato | Valor |
|---|---|
| Carpeta | `project-i/` |
| `applicationId` | `io.github.alexyoj123.vallethremote` |
| Label | `VallEthRemote` |
| Prefijo de tag | `ytp-i-VallEthRemote-*` |
| Workflow | `.github/workflows/project-i-vallethremote.yml` |
| Script de build | `project-i/scripts/build-vallethremote.sh` |
| minSdk / targetSdk / compileSdk | 28 / 36 / **37** |

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

> Si en el futuro no aparece nada al buscar: verificá primero en qué subred
> está la PC/celular (`ipconfig` / ajustes de Wi-Fi). Esta casa ya cambió de
> `192.168.0.x` a `192.168.1.x` una vez.

---

## 2. Qué quedó construido

Todo lo de la **fase 1** del plan, más dos piezas de la fase 2 (Bluetooth HID
y voz) y una de la fase 3 (Roku).

```
project-i/app/src/main/java/io/github/alexyoj123/vallethremote/
├── core/          TvDriver, Capability, RemoteKey, TvDevice, DiagLog, Net (WoL)
├── data/          DeviceStore (DataStore), RemoteRepository (orquestador + cascadas)
├── discovery/     mDNS (NsdManager) + SSDP + sondeo /24, y clasificación por puerto
├── driver/
│   ├── samsung/   SamsungTizenDriver   — WebSocket 8002, token, teclas, apps, WoL
│   ├── androidtv/ AndroidTvAdbDriver   — shell persistente, texto, intents, pm install
│   │              AdbShellSession      — el stream único que da la baja latencia
│   └── roku/      RokuEcpDriver        — HTTP 8060
├── hid/           BluetoothHidController, HidDescriptor, HidForegroundService
├── voice/         VoiceController (SpeechRecognizer), VoiceIntentParser (reglas locales)
└── ui/            Remoto, Trackpad, Enviar (APK), Equipos, Diagnóstico
```

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
| Script de CI completo | Corrido local con `PUBLICAR=false` | ✅ Hasta la verificación de firma |

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

1. **Probar en hardware y llenar la tabla del §7.** Es el bloqueo de todo lo
   demás. Instalar el APK del release en el A04e y correr
   Diagnóstico → «Probar capacidades» contra la Samsung y contra el Claro Box.
2. **Habilitar «Depuración por red» en el Claro TV Box** y con eso responder
   por fin el §5.2 del plan: ¿`pm install` esquiva el bloqueo del operador o
   devuelve `INSTALL_FAILED_USER_RESTRICTED`? El botón de instalar APK ya
   reporta el texto crudo de `pm`.
3. **`AndroidTvRemoteDriver` (protobuf, 6466 controlar / 6467 emparejar).**
   Sube al primer lugar de la fase 3 porque hoy es el único camino de red vivo
   hacia el Claro Box. Notas de protocolo ya recogidas:
   - Mutual TLS con certificado de cliente autofirmado en los dos puertos.
   - Mensajes con prefijo de longitud varint (los mensajes son chicos, por eso
     varias implementaciones simples usan un solo byte y les funciona).
   - Emparejamiento: `PairingRequest` → `PairingRequestAck` → `PairingOption`
     → `PairingConfiguration` → `PairingConfigurationAck` → la TV muestra un
     código hexadecimal de 6 símbolos → el cliente manda `PairingSecret`.
   - El secreto es `SHA-256(mód_cliente ‖ exp_cliente ‖ mód_servidor ‖
     exp_servidor ‖ nonce)`, donde `nonce` son los últimos 2 bytes del código y
     se valida que el primer byte del código sea igual al primer byte del hash.
     **El detalle exacto de cómo se serializan módulo y exponente (con o sin el
     cero de signo) es lo que hay que copiar literal de `androidtvremote2`, la
     implementación que usa Home Assistant. Es donde falla si se improvisa.**
   - No da puntero ni instalación de APKs: es el respaldo para cuando no se
     quiere o no se puede habilitar ADB.
4. **`WebOsDriver`** (WebSocket 3000/3001, handshake SSAP con `client-key`,
   y cursor nativo pidiendo el socket con
   `ssap://com.webos.service.networkinput/getPointerInputSocket`).
5. **Encender R8.** Bajaría el APK de 25 MB a ~6 MB. Las reglas keep ya están
   en `proguard-rules.pro`. Hacerlo **después** de que la app esté probada en
   hardware, nunca antes: si algo falla, hay que saber que no fue R8.
6. **Protocolo Lounge de YouTube** para escribir en la Samsung sin teclado.
   Requiere crear el secret `YOUTUBE_API_KEY`, que hoy no existe en el repo.
7. **Perfiles por dispositivo, widget y controles en pantalla de bloqueo**
   (fase 3 del plan, item 10).
8. Opcional: publicar la app en `docs/tienda.html` / `obtainium-phone.json`
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
gh workflow run project-i-vallethremote.yml --repo alexyoj123-tech/yt-personal --ref main

# Solo compilar y verificar, sin publicar
gh workflow run project-i-vallethremote.yml --repo alexyoj123-tech/yt-personal --ref main -f publicar=false

# Ver el run
gh run list --repo alexyoj123-tech/yt-personal --limit 5
gh run view <RUN_ID> --repo alexyoj123-tech/yt-personal --log-failed

# Build local (firma con la debug key si no hay variables VALLETH_*)
cd project-i && ./gradlew assembleRelease

# Reconocimiento de la red, como el del §1
#   (PowerShell, desde la PC en la misma Wi-Fi)
#   barrido de hosts vivos + sondeo de 3000/5555/6466/6467/8001/8060

# Verificar identidad y firma de un APK publicado
gh release download <TAG> --repo alexyoj123-tech/yt-personal -p '*.apk' -O /tmp/vr.apk
aapt2 dump badging /tmp/vr.apk | head -3
keytool -printcert -jarfile /tmp/vr.apk | grep -i SHA256
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
