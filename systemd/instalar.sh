#!/usr/bin/env bash
# Instala o levaetraz como serviço de usuário do systemd.
#
# Serviço de *usuário* (não de sistema) porque ele roda como você e escreve nas
# suas pastas. O "linger" é o que faz ele subir junto com o PC, sem precisar
# que alguém faça login primeiro.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINO="$HOME/.config/systemd/user"
UNIT="levaetraz.service"

azul()    { printf '\033[96m%s\033[0m\n' "$1"; }
verde()   { printf '\033[92m%s\033[0m\n' "$1"; }
amarelo() { printf '\033[93m%s\033[0m\n' "$1"; }

azul "→ instalando o serviço a partir de $RAIZ"

if [ ! -x "$RAIZ/.venv/bin/python" ]; then
    amarelo "!  .venv não encontrado. Rode ./start-server.sh uma vez primeiro."
    exit 1
fi

mkdir -p "$DESTINO"
# O caminho do projeto muda em cada máquina; a unit vem com @RAIZ@ no lugar.
sed "s|@RAIZ@|$RAIZ|g" "$RAIZ/systemd/$UNIT" > "$DESTINO/$UNIT"
verde "✓ unit em $DESTINO/$UNIT"

systemctl --user daemon-reload

# Linger: sem isso o serviço só roda enquanto houver uma sessão sua aberta,
# e morre quando você desloga.
if [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != "yes" ]; then
    azul "→ habilitando linger (precisa de sudo uma única vez)"
    if sudo loginctl enable-linger "$USER"; then
        verde "✓ linger ligado — o servidor sobe com o PC, sem precisar logar"
    else
        amarelo "!  linger não pôde ser ligado; o serviço só rodará enquanto você estiver logado"
    fi
else
    verde "✓ linger já estava ligado"
fi

systemctl --user enable --now "$UNIT"
sleep 2

echo
systemctl --user status "$UNIT" --no-pager --lines=8 || true
echo
verde "pronto."
echo "  ver log ao vivo:  journalctl --user -u levaetraz -f"
echo "  reiniciar:        systemctl --user restart levaetraz"
echo "  parar:            systemctl --user stop levaetraz"
echo "  desinstalar:      systemctl --user disable --now levaetraz"
