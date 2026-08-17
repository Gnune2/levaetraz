#!/usr/bin/env bash
#
# Abre o painel do levaetraz no navegador.
#
# Lê a porta da configuração (em vez de cravar 8765), sobe o servidor se ele
# estiver parado e só então abre — assim clicar no atalho sempre funciona,
# mesmo que o serviço não esteja rodando.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$RAIZ"

if [ ! -x .venv/bin/python ]; then
    MSG="O levaetraz ainda não foi instalado.
Rode ./instalar.sh nesta pasta primeiro."
    command -v zenity >/dev/null && zenity --error --text="$MSG" --width=340 || echo "$MSG" >&2
    exit 1
fi

PORTA="$(.venv/bin/python -c 'from servidor import config as c; print(c.get_port())' 2>/dev/null || echo 8765)"
URL="http://127.0.0.1:$PORTA"

esta_no_ar() { curl -sf --max-time 2 "$URL/api/auth/status" >/dev/null 2>&1; }

if ! esta_no_ar; then
    # tenta o serviço primeiro; se não existir, sobe solto em segundo plano
    if systemctl --user list-unit-files levaetraz.service >/dev/null 2>&1; then
        systemctl --user start levaetraz 2>/dev/null || true
    fi
    if ! esta_no_ar; then
        nohup .venv/bin/python main.py >/dev/null 2>&1 &
    fi
    for _ in $(seq 1 30); do
        esta_no_ar && break
        sleep 0.5
    done
fi

if ! esta_no_ar; then
    MSG="O servidor não subiu.
Veja o motivo com:  journalctl --user -u levaetraz -n 30"
    command -v zenity >/dev/null && zenity --error --text="$MSG" --width=380 || echo "$MSG" >&2
    exit 1
fi

exec xdg-open "$URL"
