#!/usr/bin/env python3
"""
fix-manifest.py — Renombra el permiso C2D_MESSAGE en APKs parcheados de YouTube.

PROBLEMA
--------
El patch "Change package name" de Morphe cambia el paquete principal de la
app de com.google.android.youtube a app.morphe.android.youtube, pero NO
renombra identificadores secundarios que el SDK/AndroidX inserta en el
manifest compilado usando el applicationId original como prefijo, por
ejemplo permisos personalizados:

  <permission android:name="com.google.android.youtube.permission.C2D_MESSAGE" …/>
  <permission android:name="com.google.android.youtube.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" …/>

(este segundo lo auto-inserta AndroidX core para receivers dinámicos no
exportados, usando el applicationId de compilación original).

En cualquier dispositivo que tenga la YouTube de sistema instalada (aunque
esté deshabilitada), Android registra esos permisos globalmente bajo el
paquete com.google.android.youtube. Al intentar instalar el APK parcheado,
el PackageManager lanza:

  INSTALL_FAILED_DUPLICATE_PERMISSION: Package app.morphe.android.youtube
  attempting to redeclare permission com.google.android.youtube.<X>
  already owned by com.google.android.youtube

SOLUCIÓN
--------
En vez de parchar un permiso a la vez (frágil — cada nuevo permiso
huérfano rompe la instalación de nuevo), se reemplaza el PREFIJO completo
"com.google.android.youtube." (con el punto final) por
"app.morphe.android.youtube." en TODO el manifest binario. Ambos prefijos
tienen exactamente 27 caracteres (54 bytes en UTF-16LE, el encoding del
manifest binario), por lo que el reemplazo no corrompe el formato binario
y cubre cualquier identificador con ese prefijo, presente o futuro.

El script reescribe el archivo dentro del ZIP (APK) sin tocar ninguna otra
entrada, y el resultado se vuelve a firmar normalmente por sign-apks.sh.
"""

import sys
import zipfile
import os
import tempfile

# Prefijo completo (con punto final) del paquete original vs el nuevo.
# 27 caracteres cada uno = 54 bytes UTF-16LE. Longitud idéntica → safe.
# El punto final asegura que solo se reemplacen identificadores DERIVADOS
# (com.google.android.youtube.<algo>), nunca el nombre de paquete exacto
# sin sufijo (ese ya lo renombra correctamente el patch "Change package
# name" en otro lugar del manifest).
OLD_PREFIX = "com.google.android.youtube."
NEW_PREFIX = "app.morphe.android.youtube."

OLD_BYTES = OLD_PREFIX.encode("utf-16-le")
NEW_BYTES = NEW_PREFIX.encode("utf-16-le")

assert len(OLD_BYTES) == len(NEW_BYTES), (
    f"BUG: longitudes distintas ({len(OLD_BYTES)} vs {len(NEW_BYTES)})"
)


def fix_apk(apk_path: str) -> bool:
    """
    Lee el APK, reemplaza la declaración de permiso en AndroidManifest.xml
    y sobreescribe el archivo original de forma atómica.

    IMPORTANTE: se preserva el compress_type ORIGINAL de cada entrada del
    ZIP. Android 11+ (API 30+) exige que resources.arsc (y normalmente las
    librerías nativas .so) se mantengan STORED (sin comprimir) y alineadas
    a 4 bytes — si se reescriben como DEFLATE, el PackageManager rechaza
    la instalación con:
      Failure [-124: ... resources.arsc of installed APKs to be stored
      uncompressed and aligned on a 4-byte boundary]
    zipalign (que corre después en sign-apks.sh) solo AÑADE padding para
    alinear entradas ya STORED — no puede descomprimir una entrada que
    quedó mal comprimida aquí. Por eso cada entrada debe re-escribirse
    con su compress_type original.

    Retorna True si se hizo el reemplazo, False si el permiso no estaba.
    """
    with zipfile.ZipFile(apk_path, "r") as zf:
        infos = zf.infolist()
        contents = {info.filename: zf.read(info.filename) for info in infos}

    manifest = contents.get("AndroidManifest.xml")
    if manifest is None:
        print(f"[fix-manifest] WARN: {apk_path} no contiene AndroidManifest.xml")
        return False

    if OLD_BYTES not in manifest:
        print(
            f"[fix-manifest] {apk_path}: "
            f"prefijo '{OLD_PREFIX}' no encontrado — nada que hacer."
        )
        return False

    occurrences = manifest.count(OLD_BYTES)
    contents["AndroidManifest.xml"] = manifest.replace(OLD_BYTES, NEW_BYTES)

    # Escribir APK temporal en el mismo directorio y reemplazar atómicamente
    apk_dir = os.path.dirname(os.path.abspath(apk_path))
    fd, tmp_path = tempfile.mkstemp(dir=apk_dir, suffix=".apk.tmp")
    os.close(fd)

    try:
        with zipfile.ZipFile(tmp_path, "w") as zf_out:
            for info in infos:
                # Reconstruir ZipInfo preservando compress_type, fecha y
                # permisos originales — solo cambia el contenido de bytes.
                new_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                new_info.compress_type = info.compress_type
                new_info.external_attr = info.external_attr
                new_info.create_system = info.create_system
                new_info.internal_attr = info.internal_attr
                zf_out.writestr(new_info, contents[info.filename])
        os.replace(tmp_path, apk_path)
    except Exception:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise

    print(
        f"[fix-manifest] {apk_path}: "
        f"{occurrences} ocurrencia(s) de '{OLD_PREFIX}' → '{NEW_PREFIX}' ✓ "
        f"(compresión original preservada por entrada)"
    )
    return True


def main() -> None:
    if len(sys.argv) < 2:
        print(f"Uso: {sys.argv[0]} <apk> [<apk> ...]", file=sys.stderr)
        sys.exit(1)

    any_fixed = False
    for apk in sys.argv[1:]:
        if not os.path.isfile(apk):
            print(f"[fix-manifest] ERROR: archivo no encontrado: {apk}", file=sys.stderr)
            sys.exit(1)
        if fix_apk(apk):
            any_fixed = True

    if not any_fixed:
        print(
            "[fix-manifest] Ningún APK requirió corrección "
            "(el permiso ya no se declara con el nombre original)."
        )


if __name__ == "__main__":
    main()
