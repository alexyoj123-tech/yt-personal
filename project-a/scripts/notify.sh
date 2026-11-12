#!/usr/bin/env bash
# Manda un mensaje de WhatsApp al dueño via CallMeBot (gratis, uso personal).
#
# Uso: notify.sh "texto del mensaje"
#
# Secrets del repo (Settings > Secrets and variables > Actions):
#   NOTIFY_WHATSAPP_PHONE   numero con codigo de pais, ej. +502XXXXXXXX
#   NOTIFY_WHATSAPP_APIKEY  apikey que CallMeBot manda por WhatsApp al activar
# El numero va SOLO en el secret: el repo es publico.
#
# Nunca hace fallar el build: si faltan los secrets o CallMeBot no responde,
# deja un warning y sale con 0.

set -uo pipefail

msg="${1:-}"
phone="${NOTIFY_WHATSAPP_PHONE:-}"
apikey="${NOTIFY_WHATSAPP_APIKEY:-}"

if [ -z "$msg" ]; then
  echo "::warning::notify.sh: mensaje vacio, no se envia nada."
  exit 0
fi
if [ -z "$phone" ] || [ -z "$apikey" ]; then
  echo "::warning::Aviso por WhatsApp desactivado: faltan los secrets NOTIFY_WHATSAPP_PHONE / NOTIFY_WHATSAPP_APIKEY."
  exit 0
fi

enc() { jq -rn --arg v "$1" '$v|@uri'; }
url="https://api.callmebot.com/whatsapp.php?phone=$(enc "$phone")&text=$(enc "$msg")&apikey=$(enc "$apikey")"

# CallMeBot a veces tarda o devuelve error transitorio: 3 intentos.
for i in 1 2 3; do
  resp="$(curl -sS --max-time 30 "$url" 2>&1)" && \
    ! grep -qiE 'error|invalid|not (allowed|valid)|apikey is' <<< "$resp" && {
      echo "Aviso por WhatsApp enviado."
      exit 0
    }
  echo "Intento $i de aviso por WhatsApp fallo; reintentando..."
  sleep $((i * 10))
done

# No imprimir $url (lleva numero y apikey). Solo un recorte de la respuesta.
echo "::warning::No se pudo enviar el aviso por WhatsApp. Respuesta: $(printf '%s' "$resp" | sed 's/<[^>]*>//g' | tr -s ' \n' ' ' | cut -c1-200)"
exit 0
