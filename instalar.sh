#!/usr/bin/env bash
#
# Transforma uma máquina Linux limpa num servidor levaetraz.
#
# Faz tudo em ordem: pacotes do sistema, ambiente Python, pasta compartilhada,
# senha, serviço que sobe com o PC e (opcional) Tailscale.
# É idempotente — rodar de novo só completa o que falta.
#
#   ./instalar.sh
#
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$RAIZ"

# ── aparência ───────────────────────────────────────────────
V=$'\033[92m'; A=$'\033[93m'; R=$'\033[91m'; C=$'\033[96m'; D=$'\033[90m'; N=$'\033[1m'; Z=$'\033[0m'
passo()  { printf '\n%s▸ %s%s\n' "$C$N" "$1" "$Z"; }
ok()     { printf '  %s✓%s %s\n' "$V" "$Z" "$1"; }
aviso()  { printf '  %s!%s %s\n' "$A" "$Z" "$1"; }
erro()   { printf '  %s✗%s %s\n' "$R" "$Z" "$1"; }
nota()   { printf '    %s%s%s\n' "$D" "$1" "$Z"; }

perguntar() {   # perguntar "texto" -> 0 se sim
    local resp
    read -r -p "  $1 [S/n] " resp </dev/tty || resp=n
    [[ -z "$resp" || "$resp" =~ ^[SsYy]$ ]]
}

# ── 1. distro e gerenciador de pacotes ──────────────────────
passo "Identificando o sistema"

if   command -v dnf     >/dev/null; then PM=dnf;    INSTALAR="sudo dnf install -y"
elif command -v apt-get >/dev/null; then PM=apt;    INSTALAR="sudo apt-get install -y"
elif command -v pacman  >/dev/null; then PM=pacman; INSTALAR="sudo pacman -S --noconfirm"
elif command -v zypper  >/dev/null; then PM=zypper; INSTALAR="sudo zypper install -y"
else
    erro "não reconheci o gerenciador de pacotes."
    nota "instale à mão: python3, python3-venv, python3-pip, git, ffmpeg"
    exit 1
fi
ok "$(. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME" || echo Linux) · $PM"

if ! command -v systemctl >/dev/null; then
    aviso "sem systemd — o servidor vai funcionar, mas não sobe sozinho com o PC"
fi

# ── 2. pacotes do sistema ───────────────────────────────────
passo "Pacotes do sistema"

# No Debian/Ubuntu o venv é um pacote separado; no Fedora vem junto do python3.
case "$PM" in
  apt)    PACOTES=(python3 python3-venv python3-pip git ffmpeg) ;;
  dnf)    PACOTES=(python3 python3-pip git ffmpeg) ;;
  pacman) PACOTES=(python python-pip git ffmpeg) ;;
  zypper) PACOTES=(python3 python3-pip git ffmpeg) ;;
esac

FALTAM=()
command -v python3 >/dev/null || FALTAM+=(python)
command -v git     >/dev/null || FALTAM+=(git)
command -v ffmpeg  >/dev/null || FALTAM+=(ffmpeg)
python3 -m venv --help >/dev/null 2>&1 || FALTAM+=(venv)

if [ ${#FALTAM[@]} -eq 0 ]; then
    ok "python3, venv, git e ffmpeg já estão instalados"
else
    aviso "faltando: ${FALTAM[*]}"
    if perguntar "instalar agora? (pede sudo)"; then
        [ "$PM" = apt ] && sudo apt-get update -qq
        $INSTALAR "${PACOTES[@]}"
        ok "pacotes instalados"
    else
        erro "sem eles o servidor não roda."; exit 1
    fi
fi

python3 - <<'PY' || { erro "precisa de Python 3.10 ou mais novo"; exit 1; }
import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)
PY
ok "Python $(python3 -c 'import sys;print(".".join(map(str,sys.version_info[:3])))')"

# ── 3. ambiente Python ──────────────────────────────────────
passo "Ambiente Python e dependências"

if [ ! -x .venv/bin/python ]; then
    python3 -m venv .venv
    ok "ambiente virtual criado"
else
    ok "ambiente virtual já existe"
fi

.venv/bin/python -m pip install -q --upgrade pip
.venv/bin/python -m pip install -q -r requirements.txt
ok "dependências instaladas"

# ── 4. ffmpeg (opcional) ────────────────────────────────────
passo "ffmpeg"
if command -v ffmpeg >/dev/null; then
    ok "ffmpeg encontrado — vídeos vão ter miniatura"
else
    aviso "sem ffmpeg: vídeos aparecem com ícone em vez de prévia"
    nota "o resto funciona igual; instale depois se quiser as miniaturas"
fi

# ── 5. pasta compartilhada ──────────────────────────────────
passo "Pasta compartilhada"

PASTA="$HOME/Transferencias"
if [ -d "$PASTA" ]; then
    ok "$PASTA já existe"
else
    mkdir -p "$PASTA"
    ok "criada em $PASTA"
fi
nota "é a única pasta que o servidor enxerga — nada fora dela é acessível"
nota "para compartilhar outras, use o painel → ajustes"

# ── 6. senha ────────────────────────────────────────────────
passo "Senha de acesso"

# Tem navegador nesta máquina? Se sim, a senha é criada no painel — mais
# amigável e já deixa você logado. Se não (instalação por SSH), volta pro
# terminal, senão você ficaria sem forma nenhuma de configurar.
TEM_GUI=0
if [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && command -v xdg-open >/dev/null; then
    TEM_GUI=1
fi

if .venv/bin/python -c "
from servidor import auth
import sys; sys.exit(0 if auth.tem_senha() else 1)" 2>/dev/null; then
    ok "já existe uma senha definida"
    nota "para trocar: pelo painel, ou python main.py --senha"
elif [ "$TEM_GUI" = 1 ]; then
    ok "você vai criar a senha no painel, daqui a pouco"
    nota "só o navegador aberto neste PC consegue criar a primeira senha"
else
    nota "sem interface gráfica aqui — definindo pelo terminal."
    nota "guardada só como hash Argon2id. Mínimo 8 caracteres."
    .venv/bin/python main.py --senha </dev/tty
fi

# ── 7. serviço ──────────────────────────────────────────────
passo "Rodar sempre que o PC estiver ligado"

if command -v systemctl >/dev/null; then
    if systemctl --user is-enabled levaetraz.service >/dev/null 2>&1; then
        ok "serviço já instalado"
        systemctl --user restart levaetraz
        nota "reiniciado para pegar esta instalação"
    elif perguntar "instalar como serviço do systemd?"; then
        ./systemd/instalar.sh
    fi
else
    aviso "sem systemd: rode com ./start-server.sh quando precisar"
fi

# ── 8. acesso de fora ───────────────────────────────────────
passo "Acesso de qualquer lugar (Tailscale)"

if command -v tailscale >/dev/null && tailscale status >/dev/null 2>&1; then
    ok "Tailscale ativo: $(tailscale ip -4 2>/dev/null | head -1)"
elif perguntar "instalar e configurar o Tailscale? (abre o navegador para login)"; then
    ./systemd/tailscale.sh
    # Deixa o comando disponível sem sudo, para o painel conseguir reconectar
    # e mostrar o link de login quando a VPN cair.
    sudo tailscale set --operator="$USER" 2>/dev/null && \
        ok "painel autorizado a controlar o Tailscale" || true
else
    nota "sem ele, o servidor só responde na rede local"
fi

# ── 9. app do celular ──────────────────────────────────────
passo "App do celular"

if .venv/bin/python scripts/baixar_apk.py 2>&1 | sed 's/^/  /'; then :; fi
nota "o painel mostra um QR para o celular baixar e instalar"

# ── 10. atalho ──────────────────────────────────────────────
passo "Atalho do painel"

if [ "$TEM_GUI" = 1 ]; then
    ./scripts/instalar-atalho.sh
    nota "clicar nele sobe o servidor se estiver parado e abre o painel"
else
    nota "sem interface gráfica — atalho não faz sentido aqui"
fi

# ── 11. esperar o servidor responder ────────────────────────
passo "Subindo o servidor"

PORTA="$(.venv/bin/python -c 'from servidor import config as c; print(c.get_port())')"

# Sem systemd, sobe agora em segundo plano para o painel ter o que abrir.
if ! systemctl --user is-active --quiet levaetraz 2>/dev/null; then
    nohup .venv/bin/python main.py >/dev/null 2>&1 &
    nota "iniciado em segundo plano (sem systemd)"
fi

PRONTO=0
for _ in $(seq 1 40); do
    if curl -sf --max-time 2 "http://127.0.0.1:$PORTA/api/auth/status" >/dev/null 2>&1; then
        PRONTO=1; break
    fi
    sleep 0.5
done
if [ "$PRONTO" = 1 ]; then
    ok "respondendo na porta $PORTA"
else
    aviso "não respondeu a tempo"
    nota "veja o motivo: journalctl --user -u levaetraz -n 30"
fi

# ── 12. abrir o painel ──────────────────────────────────────
passo "Pronto"

# Sempre 127.0.0.1 para abrir: funciona com qualquer modo de bind e não depende
# do Tailscale estar de pé. O endereço dos outros aparelhos vai impresso.
.venv/bin/python - <<'RESUMO'
from servidor import auth, rede, config as cfg
V='\033[92m'; C='\033[96m'; D='\033[90m'; Z='\033[0m'
try:
    hosts, _ = rede.resolver_bind(cfg.get_bind())
except RuntimeError:
    hosts = ['0.0.0.0']
porta = cfg.get_port()
print(f'  {V}o servidor está no ar.{Z}\n')
print(f'  neste PC          {C}http://127.0.0.1:{porta}{Z}')
print(f'  outros aparelhos  {C}http://{rede.endereco_publicado(hosts, porta)}{Z}')
if not auth.tem_senha():
    print(f'\n  {D}o painel vai pedir para você criar a senha.{Z}')
RESUMO

if [ "$TEM_GUI" = 1 ] && [ "$PRONTO" = 1 ]; then
    echo
    ok "abrindo o painel no navegador..."
    xdg-open "http://127.0.0.1:$PORTA" >/dev/null 2>&1 &
    sleep 1
else
    echo
    nota "abra o endereço acima no navegador para configurar."
fi

printf '\n  %sconectar o celular:%s  aba "dispositivos" do painel\n' "$D" "$Z"
printf '  %slog ao vivo:%s         journalctl --user -u levaetraz -f\n' "$D" "$Z"
printf '  %so manual inteiro está na aba "ajuda".%s\n' "$D" "$Z"
