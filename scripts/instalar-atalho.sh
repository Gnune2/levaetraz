#!/usr/bin/env bash
#
# Cria o atalho do painel: no menu de aplicativos e na própria pasta do projeto.
#
# O .desktop precisa de caminhos absolutos, então é gerado aqui em vez de ir
# pronto no repositório — assim funciona de qualquer lugar onde você clonar.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPS="$HOME/.local/share/applications"
NOME="levaetraz.desktop"

# O ícone é gerado por script; se faltar, gera agora em vez de deixar o atalho
# com o quadrado genérico do sistema.
if [ ! -f "$RAIZ/icon.png" ] && [ -x "$RAIZ/.venv/bin/python" ]; then
    "$RAIZ/.venv/bin/python" "$RAIZ/scripts/gerar_icone.py" >/dev/null 2>&1 || true
fi

conteudo() {
cat <<FIM
[Desktop Entry]
Type=Application
Version=1.0
Name=levaetraz
GenericName=Troca de arquivos com o celular
Comment=Abre o painel do servidor de arquivos no navegador
Exec=$RAIZ/abrir-painel.sh
Icon=$RAIZ/icon.png
Terminal=false
Categories=Network;FileTransfer;
Keywords=arquivos;celular;transferencia;servidor;painel;levaetraz;
StartupNotify=true
FIM
}

mkdir -p "$APPS"
conteudo > "$APPS/$NOME"
chmod +x "$APPS/$NOME"

# Uma cópia na pasta do projeto, para quem procura o atalho ali.
# O GNOME exige a flag "trusted" para permitir clique direto num .desktop
# solto numa pasta qualquer; sem ela, o do menu é o que funciona.
conteudo > "$RAIZ/$NOME"
chmod +x "$RAIZ/$NOME"
gio set "$RAIZ/$NOME" metadata::trusted true 2>/dev/null || true

command -v update-desktop-database >/dev/null && \
    update-desktop-database "$APPS" 2>/dev/null || true

printf '\033[92m✓\033[0m atalho criado\n'
printf '    menu de aplicativos: procure por "levaetraz"\n'
printf '    na pasta do projeto: %s\n' "$NOME"
