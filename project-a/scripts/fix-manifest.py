#!/usr/bin/env python3
"""
fix-manifest.py — Renombra el permiso C2D_MESSAGE en APKs parcheados de YouTube.

PROBLEMA
--------
El patch "Change package name" de Morphe cambia el paquete de la app de
com.google.android.youtube a app.morphe.android.youtube, pero NO renombra
la DECLARACIÓN del permiso personalizado en el AndroidManifest.xml binario:

  <permission android:name="com.google.android.youtube.permission.C2D_MESSAGE" …/>

En cualquier dispositivo que tenga la YouTube de sistema instalada (aunque
esté deshabilitada), Android registra globalmente ese permiso bajo el paquete
com.google.android.youtube. Al intentar instalar el APK parcheado, el
PackageManager lanza:

  INSTALL_FAILED_DUPLICATE_PERMISSION: Package app.morphe.android.youtube
  attempting to redeclare permission
  com.google.android.youtube.permission.C2D_MESSAGE
  already owned by com.google.android.youtube

SOLUCIÓN
--------
Ambas cadenas tienen exactamente 49 caracteres (= 98 bytes en UTF-16LE,
que es el encoding del manifest binario). El tamaño no cambia, por lo que
la sustitución directa en el pool de strings no corrompe el formato binario.

El script reescribe el archivo dentro del ZIP (APK) sin tocar ninguna otra
entrada, y el resultado se vuelve a firmar normalmente por sign-apks.sh.
"""

import sys
import zipfile
import os
import tempfile

# Ambas cadenas: 49 caracteres = 98 bytes UTF-16LE. Longitud idéntica → safe.
OLD_PERM = "com.google.android.youtube.permission.C2D_MESSAGE"
NEW_PERM = "app.morphe.android.youtube.permission.C2D_MESSAGE"

OLD_BYTES = OLD_PERM.encode("utf-16-le")
NEW_BYTES = NEW_PERM.encode("utf-16-le")

assert len(OLD_BYTES) == len(NEW_BYTES), (
    f"BUG: longitudes distintas ({len(OLD_BYTES)} vs {len(NEW_BYTES)})"
)


def fix_apk(apk_path: str) -> bool:
    """
    Lee el APK, reemplaza la declaración de permiso en AndroidManifest.xml
    y sobreescribe el archivo original de forma atómica.

    Retorna True si se hizo el reemplazo, False si el permiso no estaba.
    """
    members: dict[str, bytes] = {}
    with zipfile.ZipFile(apk_path, "r") as zf:
        for name in zf.namelist():
            members[name] = zf.read(name)

    manifest = members.get("AndroidManifest.xml")
    if manifest is None:
        print(f"[fix-manifest] WARN: {apk_path} no contiene AndroidManifest.xml")
        return False

    if OLD_BYTES not in manifest:
        print(
            f"[fix-manifest] {apk_path}: "
            f"permiso '{OLD_PERM}' no encontrado — nada que hacer."
        )
        return False

    members["AndroidManifest.xml"] = manifest.replace(OLD_BYTES, NEW_BYTES)

    apk_dir = os.path.dirname(os.path.abspath(apk_path))
    fd, tmp_path = tempfile.mkstemp(dir=apk_dir, suffix=".apk.tmp")
    os.close(fd)

    try:
        with zipfile.ZipFile(tmp_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for name, data in members.items():
                zf.writestr(name, data)
        os.replace(tmp_path, apk_path)
    except Exception:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise

    print(
        f"[fix-manifest] {apk_path}: "
        f"'{OLD_PERM}' → '{NEW_PERM}' ✓"
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
