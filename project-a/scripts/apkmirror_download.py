#!/usr/bin/env python3
"""Download a specific version of an APK from APKMirror.

Why this exists: apkeep (EFForg) doesn't support APKMirror as a source,
and APKPure often lacks the exact versions that inotia00/revanced-patches
v5.14.x is compatible with (patches target YT 19.x–20.05 and YTM 6.x–8.30;
APKPure only had newer 21.x / 9.x). APKMirror has the full historical
archive. See docs/APKMIRROR-SCRAPER.md for the full rationale.

Design goals:
  - Python 3 stdlib only (urllib + re + html.parser). No pip install needed
    on the GitHub Actions runner.
  - Tolerant to whitespace/attribute-order changes in APKMirror HTML.
  - Clear error messages per pipeline step (version-page → variant →
    key-page → download.php → CDN) so a future HTML change is a 1-line fix.
  - Validation at the end (min size + PK magic bytes) to catch partial
    downloads or HTML-wrapped error pages silently saved as .apk.

Exit codes:
  0  success
  2  version page not found (404) or app-slug wrong
  3  no non-bundle variant on version page (all were BUNDLE, or 0 hrefs)
  4  variant page missing /download/?key= link
  5  key page missing /wp-content/.../download.php link
  6  final download failed, too small, or not a valid APK (no PK magic)
  7  usage error (wrong args)

Usage:
  python3 apkmirror_download.py <app_org> <app_slug> <version> <out_path>

Example:
  python3 apkmirror_download.py google-inc youtube 19.47.53 /tmp/yt.apk
  python3 apkmirror_download.py google-inc youtube-music 7.25.53 /tmp/ytm.apk
"""

from __future__ import annotations

import html.parser
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
)
BASE = "https://www.apkmirror.com"
REFERER = "https://www.apkmirror.com/"
MIN_APK_BYTES = 5 * 1024 * 1024  # 5 MB sanity floor


def log(msg: str) -> None:
    print(f"[apkmirror] {msg}", file=sys.stderr, flush=True)


def die(msg: str, code: int = 1) -> None:
    print(f"[apkmirror] ERROR (exit {code}): {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


def _build_request(url: str, referer: str | None = None) -> urllib.request.Request:
    """Build a Request mimicking a browser. APKMirror's /download/?key= step
    validates Referer against the variant page — passing the previous URL
    is essential, else they redirect to ?redirected=thank_you_invalid_referer."""
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Referer", referer or REFERER)
    req.add_header(
        "Accept",
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )
    req.add_header("Accept-Language", "en-US,en;q=0.9")
    req.add_header("Accept-Encoding", "identity")  # no gzip — keep it simple
    return req


def fetch_text(url: str, retries: int = 5, referer: str | None = None) -> str:
    """GET a URL, return decoded HTML. Retries 5xx/network; raises 4xx immediately."""
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = _build_request(url, referer=referer)
            with urllib.request.urlopen(req, timeout=30) as resp:
                if resp.status == 200:
                    return resp.read().decode("utf-8", errors="replace")
                raise urllib.error.HTTPError(
                    url, resp.status, resp.reason, resp.headers, None
                )
        except urllib.error.HTTPError as e:
            # Don't retry on client errors (404, 403, etc.) — they won't heal.
            if 400 <= e.code < 500:
                raise
            last_err = e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last_err = e
        wait = 2 ** attempt
        log(f"GET {url} failed ({last_err!r}) — retry in {wait}s (attempt {attempt+1}/{retries})")
        time.sleep(wait)
    raise RuntimeError(f"all retries exhausted for {url}: {last_err!r}")


def fetch_file(url: str, out_path: str, retries: int = 5, referer: str | None = None) -> int:
    """Streaming download with retry. urllib follows 302 redirects (up to 10) by default."""
    tmp = out_path + ".part"
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = _build_request(url, referer=referer)
            with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as f:
                if resp.status != 200:
                    raise urllib.error.HTTPError(
                        url, resp.status, resp.reason, resp.headers, None
                    )
                total = int(resp.headers.get("Content-Length", 0) or 0)
                copied = 0
                last_report = 0
                while True:
                    buf = resp.read(1 << 16)  # 64 KB
                    if not buf:
                        break
                    f.write(buf)
                    copied += len(buf)
                    if total and copied - last_report > total // 10:
                        log(f"download progress: {copied}/{total} bytes ({100 * copied // total}%)")
                        last_report = copied
                if total and copied < total:
                    raise OSError(f"incomplete: {copied}/{total}")
            os.replace(tmp, out_path)
            return copied
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as e:
            last_err = e
            try:
                os.unlink(tmp)
            except OSError:
                pass
            wait = 2 ** attempt
            log(f"download failed ({e!r}) — retry in {wait}s (attempt {attempt+1}/{retries})")
            time.sleep(wait)
    die(f"fetch_file: all retries exhausted: {last_err!r}", code=6)
    return 0  # unreachable, keeps type checker happy


class _TitleExtractor(html.parser.HTMLParser):
    """Extract the first <title>…</title> content, tolerant to malformed HTML."""

    def __init__(self) -> None:
        super().__init__()
        self._in_title = False
        self._done = False
        self.title = ""

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:  # noqa: ARG002
        if tag.lower() == "title" and not self._done:
            self._in_title = True

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "title" and self._in_title:
            self._in_title = False
            self._done = True

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self.title += data


def extract_title(html_text: str) -> str:
    p = _TitleExtractor()
    try:
        p.feed(html_text)
    except Exception:
        pass  # tolerant
    return p.title.strip()


def find_variant_hrefs(html_text: str, app_slug: str) -> list[str]:
    """Unique, order-preserving list of variant hrefs off the version page.

    Pattern: /apk/<org>/<slug>/<slug>-<ver-dashes>-release/<slug>-<ver-dashes>[-N]-android-apk-download/
    We only care about the part that identifies the variant.
    """
    pattern = re.compile(
        r'href="(/apk/[^"]+/' + re.escape(app_slug) + r'-[0-9a-z.-]+-android-apk-download/)"',
        re.IGNORECASE,
    )
    seen: list[str] = []
    for m in pattern.finditer(html_text):
        href = m.group(1)
        if href not in seen:
            seen.append(href)
    return seen


def find_download_key_url(variant_html: str) -> str | None:
    """/apk/.../download/?key=<hex> href on the variant page."""
    m = re.search(r'href="(/apk/[^"]+/download/\?key=[^"]+)"', variant_html)
    return m.group(1) if m else None


def find_download_php_url(key_page_html: str) -> str | None:
    """/wp-content/themes/APKMirror/download.php?id=<id>&key=<hex> href on the key page.

    Normalizes HTML-encoded ampersands (&amp; → &) which urllib handles fine
    either way, but cleaner.
    """
    m = re.search(
        r'href="(/wp-content/themes/APKMirror/download\.php\?[^"]+)"',
        key_page_html,
    )
    if not m:
        return None
    return m.group(1).replace("&amp;", "&")


def is_valid_apk(path: str) -> tuple[bool, str]:
    """APK = ZIP = first 2 bytes 'PK'. Also check min size to catch error pages."""
    if not os.path.isfile(path):
        return False, "file does not exist"
    size = os.path.getsize(path)
    if size < MIN_APK_BYTES:
        return False, f"too small ({size} bytes < {MIN_APK_BYTES})"
    with open(path, "rb") as f:
        magic = f.read(4)
    if magic[:2] != b"PK":
        return False, f"not a ZIP/APK (magic={magic!r})"
    return True, f"{size} bytes"


def download_version(app_org: str, app_slug: str, version: str, out_path: str) -> None:
    ver_dash = version.replace(".", "-")
    ver_page_url = f"{BASE}/apk/{app_org}/{app_slug}/{app_slug}-{ver_dash}-release/"
    log(f"version page → {ver_page_url}")

    try:
        ver_html = fetch_text(ver_page_url)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            die(
                f"version page 404: {app_slug} {version} not on APKMirror. "
                f"Verifica la versión en docs/APKMIRROR-SCRAPER.md.",
                code=2,
            )
        raise
    # APKMirror sometimes returns 200 with a 'not found' page for bad slugs.
    if "isn't here" in ver_html.lower() or "page not found" in ver_html.lower():
        die(f"version page says not-found for {app_slug} {version}", code=2)

    hrefs = find_variant_hrefs(ver_html, app_slug)
    log(f"variant hrefs found: {len(hrefs)}")
    if not hrefs:
        die(
            "no variant hrefs on version page — posible cambio de HTML. "
            "Revisa find_variant_hrefs() regex y docs/APKMIRROR-SCRAPER.md §mantenimiento.",
            code=3,
        )

    # Selección de variant con prioridad explícita (Bug #12 fix, 2026-04-23):
    #   1. arm64-v8a + nodpi       ← ideal (target arch del A04e, universal DPI)
    #   2. arm64-v8a + any DPI     ← arm64 aunque DPI específico
    #   3. universal nodpi         ← sin arch en title (APK universal)
    #   4. fallback non-bundle     ← lo que quede
    # Rechazo explícito:
    #   - "BUNDLE" en title (split APKs empaquetados)
    #   - density ranges tipo "(120-640dpi)" (también son bundles multi-density)
    #   - "(arm-v7a)" / "(armeabi-v7a)" (arch equivocada para A04e arm64)
    preferred: tuple[str, str, str] | None = None      # arm64 + nodpi
    arm64_any: tuple[str, str, str] | None = None      # arm64 cualquier dpi
    universal_nodpi: tuple[str, str, str] | None = None # nodpi sin arch
    fallback: tuple[str, str, str] | None = None        # lo que sea

    density_range_re = re.compile(r"\(\d+-\d+DPI\)")

    for href in hrefs:
        url = BASE + href
        log(f"inspecting variant: {href}")
        try:
            v_html = fetch_text(url, retries=3)
        except Exception as e:
            log(f"  variant fetch failed: {e!r} — skip")
            continue
        title = extract_title(v_html)
        log(f"  title: {title!r}")
        up = title.upper()

        # Rechazo absoluto: BUNDLE / density-range / arm-v7a
        if "BUNDLE" in up:
            log("  → BUNDLE, skipping")
            continue
        if density_range_re.search(up):
            log("  → density range (multi-DPI bundle), skipping")
            continue
        if "(ARM-V7A)" in up or "(ARMEABI-V7A)" in up:
            log("  → arm-v7a (arch equivocada para arm64-v8a target), skipping")
            continue

        is_arm64 = "(ARM64-V8A)" in up
        is_nodpi = "(NODPI)" in up
        pack = (href, title, v_html)

        if is_arm64 and is_nodpi and preferred is None:
            preferred = pack
        elif is_arm64 and arm64_any is None:
            arm64_any = pack
        elif is_nodpi and universal_nodpi is None:
            # nodpi sin arch = APK universal
            universal_nodpi = pack
        if fallback is None:
            fallback = pack

    pick = preferred or arm64_any or universal_nodpi or fallback
    if not pick:
        die(
            f"no variant elegible para {app_slug} {version} "
            "(todos los matches eran BUNDLE / density-range / arm-v7a / inaccesibles)",
            code=3,
        )
    chosen_href, chosen_title, v_html = pick
    log(f"chose variant: {chosen_title}")
    variant_url = BASE + chosen_href

    dl_key = find_download_key_url(v_html)
    if not dl_key:
        die(
            "no /download/?key= link in variant page — posible cambio de HTML. "
            "Revisa find_download_key_url() regex.",
            code=4,
        )
    log(f"download-key page → {dl_key}")

    # CRÍTICO: el Referer DEBE ser la variant page; si no, APKMirror redirige
    # a ?redirected=thank_you_invalid_referer y sirve un HTML sin el enlace
    # download.php. Descubierto durante pruebas locales.
    key_url = BASE + dl_key
    key_html = fetch_text(key_url, referer=variant_url)
    dl_php = find_download_php_url(key_html)
    if not dl_php:
        die(
            "no /wp-content/.../download.php link in key page — posible cambio de HTML "
            "o Referer validation falló. Revisa find_download_php_url() regex y "
            "que el Referer de la key-page sea la variant-page.",
            code=5,
        )
    log(f"download.php → {dl_php}")

    final_url = BASE + dl_php
    log(f"starting final download → {out_path}")
    # El download.php también valida Referer — usamos la key-page.
    n = fetch_file(final_url, out_path, referer=key_url)
    log(f"downloaded {n} bytes")

    ok, info = is_valid_apk(out_path)
    if not ok:
        die(f"final validation failed: {info} — file path: {out_path}", code=6)
    log(f"✓ valid APK: {info}")


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print(
            "usage: apkmirror_download.py <app_org> <app_slug> <version> <out_path>",
            file=sys.stderr,
        )
        print("example: apkmirror_download.py google-inc youtube 19.47.53 /tmp/yt.apk",
              file=sys.stderr)
        return 7
    _, app_org, app_slug, version, out_path = argv
    try:
        download_version(app_org, app_slug, version, out_path)
        return 0
    except SystemExit as e:
        return int(e.code) if isinstance(e.code, int) else 1
    except Exception as e:
        log(f"unexpected error: {e!r}")
        import traceback

        traceback.print_exc(file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
