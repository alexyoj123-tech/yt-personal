# TV — Migration Guide (Origin + fallbacks)

Complemento del [MIGRATION-GUIDE.md](MIGRATION-GUIDE.md) general. Específico
al stack TV (Google TV / Android TV / Claro 4K TV Box).

---

## Stack TV actual (2026-04-23)

| App | Rol | Fuente | Firma |
|-----|-----|--------|-------|
| **YouTube Origin** ⭐ | YouTube TV con ad-block + SponsorBlock. UI oficial. Widevine HD. | [gitlab.com/energylove/originproject](https://gitlab.com/energylove/originproject) | **Original de energylove** (NO re-firmado) |
| **SmartTube** | Fallback robusto si Origin falla | [github.com/yuliskov/SmartTube](https://github.com/yuliskov/SmartTube) | `yt-personal` (re-firmado) |

## <a id="firma"></a>Por qué Origin mantiene firma externa (única excepción)

Todas las demás apps del proyecto — YouTube Morphe, YT Music Morphe, MicroG-RE, SmartTube — están firmadas con **`yt-personal`** (keystore del repo), para:
- Consistencia de signer chain (todas las apps una única firma).
- Auto-update via Obtainium sin re-install-from-scratch.
- Trazabilidad de origen (el keystore solo existe en el repo).

**Origin rompe esta regla intencionalmente.** Razones:

1. **Widevine DRM bindado a signature.** El APK wrapper de YouTube TV oficial
   usa la firma para attestar al servidor de Widevine (y al chip DRM del
   dispositivo). Re-firmar invalida la cadena de attestation → **YouTube
   Movies & TV deja de servir contenido HD/4K free** (solo SD o falla
   carga). Para el dueño (Claro 4K TV Box) esto importa: películas gratis
   de YouTube son parte del valor de usar Origin en vez de SmartTube.

2. **Attestation runtime interno.** Apps wrapper con Widevine típicamente
   verifican su propia signature en runtime (`PackageManager.getSignatures()`)
   y fallan silenciosamente si cambia. Aunque Origin puede que no lo haga
   estrictamente, el riesgo es alto para un problema que no tiene upside.

3. **Compat Google Play Services.** Algunos servicios GMS del TV Box pueden
   consultar la signature para decidir qué APIs exponer (ej. Cast). Firma
   original de energylove probablemente está auditada por el autor contra
   esas APIs; la nuestra no.

**Costo aceptado:** Obtainium configurará auto-update para Origin con "allow
signature change" OFF — nuevas versiones deben ser firmadas por la misma key
de energylove (lo están). Si energylove rotara su keystore en el futuro,
habría que desinstalar+reinstalar.

## Fallbacks si Origin falla

Orden de escalación recomendado:

### Fallback 1: **SmartTube** (siempre disponible)

Ya empaquetado en el release semanal del project-a (`ytp-a-*`). 
Package: `org.smarttube.stable`. 
Firma: `yt-personal` (re-firmada por nosotros).

**Pros:**
- 100% OSS, mantenimiento activo (yuliskov).
- UI propia pensada para D-pad.
- No depende de Widevine (no tiene Movies & TV paid tier, pero tampoco pretende).

**Contras:**
- UI distinta a YouTube oficial — periodo de adaptación.
- Play-to-TV / Cast integration más limitada que Origin.

**Activar:** ya está en el release `ytp-a-<fecha>`. Descarga y reinstala.

### Fallback 2: **TizenTube Cobalt** (solo Samsung Tizen TV)

Para TVs Samsung con Tizen OS (NO Android) — nuestro TV Box Claro es
Android así que aplica solo si el dueño cambia a Samsung Tizen.

- Repo: [github.com/reisxd/TizenTube](https://github.com/reisxd/TizenTube)
- Requiere Samsung TV Tizen 5.5+ + enable Developer mode (10 min setup).
- Ad-block + SponsorBlock nativos.
- Not applicable al Claro 4K (es Android TV, no Tizen).

### Fallback 3: **YouTube Vanced Retro** (legacy, último recurso)

Histórico — Vanced Retro dejó de actualizarse en 2022 pero sigue funcional
para contenido no-DRM. Solo si los 2 anteriores caen.

- Busqueda: "YouTube Vanced Retro APK"
- No auto-update, no mantenimiento activo.
- Cae eventualmente cuando YouTube cambia API server-side.

### Fallback 4: **YouTube oficial + configurar pi-hole/AdGuard a nivel red**

Última opción: dejar que la app oficial de YouTube de Google Play del TV Box
corra, y bloquear servidores de ads a nivel de red (router con pi-hole /
AdGuard Home / DNS filtrado). Ad-block imperfecto pero funciona para 80%
de casos.

## Si energylove abandona / GitLab elimina el repo

Señales tempranas:
- `last_activity` del proyecto > 6 meses sin cambios.
- Directorio `Releases/<nueva_version>/` solo tiene `.gitkeep` > 3 meses.
- GitLab devuelve 404 al repo.
- Hilo XDA oficial inactivo > 3 meses.

Plan de acción (en orden):

1. **Usar el último APK válido cached en nuestros Releases.** Ya están ahí,
   instalable indefinidamente (no requiere servidor externo para funcionar —
   solo deja de recibir updates).
2. **Forkear gitlab.com/energylove/originproject** en nuestro propio GitLab
   o GitHub si el repo sigue online. El pipeline sigue funcionando vía
   `ORIGIN_PROJECT_ID` env override al ID del fork.
3. **Cambiar default a SmartTube** como app TV primaria — actualizar
   `docs/INSTALL-TV.md` + `README.md` raíz.
4. **Buscar sucesor en XDA o r/androidtv.** Nuevas apps aparecen
   periódicamente (ej. SmartTube fue ese sucesor para muchos usuarios de
   Vanced TV).

## Mantenimiento de este documento

Revisar cada 6 meses o ante cualquier cambio mayor upstream. Añadir nuevos
fallbacks cuando aparezcan. Dejar el histórico visible (no borrar entries
obsoletas — son valor para decisiones futuras).
