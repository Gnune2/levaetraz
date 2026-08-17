#!/usr/bin/env bash
# Instala e configura o Tailscale para acessar o levaetraz de qualquer
# lugar do mundo, sem abrir nenhuma porta no roteador.
#
# Como funciona: o Tailscale monta uma rede privada (WireGuard) entre os seus
# aparelhos. O PC ganha um IP fixo 100.x.y.z que só existe dentro dessa rede.
# Ninguém da internet aberta enxerga a porta do servidor — nem sabe que ela
# existe. O tráfego é criptografado ponta a ponta.
set -euo pipefail

azul()    { printf '\033[96m%s\033[0m\n' "$1"; }
verde()   { printf '\033[92m%s\033[0m\n' "$1"; }
amarelo() { printf '\033[93m%s\033[0m\n' "$1"; }
cinza()   { printf '\033[90m%s\033[0m\n' "$1"; }

# ── 1. instalar ─────────────────────────────────────────────
if command -v tailscale >/dev/null; then
    verde "✓ tailscale já instalado ($(tailscale version | head -1))"
else
    azul "→ instalando o tailscale (precisa de sudo)"
    if command -v dnf >/dev/null; then
        sudo dnf install -y tailscale
    elif command -v apt >/dev/null; then
        curl -fsSL https://tailscale.com/install.sh | sh
    else
        amarelo "!  distro não reconhecida — veja https://tailscale.com/download"
        exit 1
    fi
fi

# ── 2. subir o daemon ───────────────────────────────────────
if ! systemctl is-enabled --quiet tailscaled 2>/dev/null; then
    azul "→ habilitando o serviço tailscaled"
    sudo systemctl enable --now tailscaled
fi

# ── 3. autenticar ───────────────────────────────────────────
ESTADO="$(tailscale status --json 2>/dev/null | grep -oP '(?<="BackendState":")[^"]*' || echo '')"
if [ "$ESTADO" != "Running" ]; then
    echo
    azul "→ agora é preciso logar na sua conta Tailscale"
    cinza "  Vai abrir um link no navegador. Use a mesma conta no celular depois."
    echo
    sudo tailscale up
fi

IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"
NOME="$(tailscale status --json 2>/dev/null | grep -oP '(?<="DNSName":")[^"]*' | head -1 | sed 's/\.$//' || true)"

if [ -z "$IP" ]; then
    amarelo "!  o tailscale não reportou um IP. Rode 'sudo tailscale up' manualmente."
    exit 1
fi

# ── 4. apontar o servidor pro tailnet ───────────────────────
echo
verde "✓ tailnet no ar"
echo "  IP do PC     $IP"
[ -n "$NOME" ] && echo "  nome         $NOME"

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -x "$RAIZ/.venv/bin/python" ]; then
    azul "→ configurando o servidor pra escutar SÓ no tailnet"
    "$RAIZ/.venv/bin/python" - <<PY
from servidor import config as cfg
cfg.set_bind('tailscale')
print('   bind =', cfg.get_bind())
PY
    if systemctl --user is-active --quiet levaetraz 2>/dev/null; then
        systemctl --user restart levaetraz
        verde "✓ servidor reiniciado escutando em $IP"
    fi
fi

cat <<FIM

$(azul "Próximos passos no celular:")
  1. instale o app Tailscale (Play Store) e entre com a MESMA conta
  2. deixe a VPN ligada
  3. no levaetraz, use o endereço:  $IP:$(${RAIZ}/.venv/bin/python -c 'from servidor import config as c; print(c.get_port())' 2>/dev/null || echo 8765)
  4. a senha é a que você definiu com: python main.py --senha

$(cinza "A partir daí funciona igual em casa e no 4G — o mesmo IP vale em qualquer lugar.")
$(cinza "Para voltar a aceitar a rede local sem VPN:  python main.py --bind lan")
FIM
