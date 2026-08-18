# project-i · VallEthRemote

Control remoto universal de TV para Android. Código propio, sin anuncios, sin
suscripción, sin telemetría.

- **Paquete:** `io.github.alexyoj123.vallethremote`
- **Tags de release:** `ytp-i-VallEthRemote-*`
- **Stack:** Kotlin + Jetpack Compose + Material 3, minSdk 28, targetSdk 36

## Qué hace

| Función | Cómo |
|---|---|
| Descubrir TVs | mDNS (`_androidtvremote2`, `_googlecast`, `_airplay`), SSDP/DIAL y sondeo de la /24 |
| Samsung Tizen | WebSocket `8002`, emparejamiento con token, teclas, abrir apps, Wake-on-LAN |
| Android TV | ADB sobre TCP `5555` con un **shell persistente** (teclas sin retardo), texto, búsqueda por intents e instalación de APKs con progreso real |
| Roku | ECP sobre HTTP `8060` |
| Cursor real | Bluetooth HID: el celular se registra como mouse + teclado + control de volumen (~15 ms, no pasa por el router) |
| Voz | `SpeechRecognizer` en el celular con reglas locales. El micrófono de la TV no se usa nunca |
| Diagnóstico | Log en anillo en disco, prueba de capacidades por dispositivo y exportación del log |

## La regla que gobierna la UI

La interfaz consulta las **capacidades** del driver activo, nunca la marca.
Si algo no existe en el dispositivo conectado, el botón se oculta o se explica
con el motivo real. Nunca un botón que parece funcionar y no hace nada — que es
exactamente el defecto de las apps de pago que esto reemplaza.

Agregar una marca nueva = agregar un driver que implemente `TvDriver`. No se
toca ninguna pantalla.

## Compilar

```bash
cd project-i
./gradlew assembleRelease
```

Sin las variables `VALLETH_KEYSTORE_*` firma con la debug key. El CI las
provee desde los secrets del repo y **aborta el build** si el package, el label
o la firma del APK no son los esperados.

## Estado, decisiones y qué falta probar

Todo está en [`docs/CONTINUIDAD-project-i.md`](../docs/CONTINUIDAD-project-i.md).
Leelo antes de tocar nada — incluye el reconocimiento real de la red de la casa
y la lista honesta de lo que todavía no se probó en hardware.
