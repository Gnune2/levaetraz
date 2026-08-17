#!/usr/bin/env python3
"""
Gera o ícone do levaetraz: duas setas, uma sobe e outra desce.

Fica como script em vez de PNG solto no repositório porque assim dá para
ajustar a cor ou o traço sem abrir editor nenhum — e porque o Android precisa
do mesmo desenho em seis tamanhos, o que ninguém quer exportar à mão.

    python scripts/gerar_icone.py

Escreve o icon.png da raiz (atalho do desktop) e os mipmaps do app.
"""

from pathlib import Path

from PIL import Image, ImageDraw

RAIZ = Path(__file__).resolve().parent.parent
RES = RAIZ / 'android/app/src/main/res'

FUNDO = (10, 10, 10, 255)       # --bg do painel
ACENTO = (0, 200, 150, 255)     # --acento

# Desenhamos grande e reduzimos: é o jeito mais simples de ter antialiasing
# decente sem depender de nada além do Pillow.
ESCALA = 4
LADO = 512


def _setas(d: ImageDraw.ImageDraw, s: int) -> None:
    """As duas setas, no espaço de um quadrado de lado `s`."""
    haste = int(s * 0.086)          # largura do corpo
    cabeca_w = int(s * 0.23)        # largura da ponta
    cabeca_h = int(s * 0.20)

    topo = int(s * 0.19)
    base = int(s * 0.81)

    # sobe (esquerda)
    cx = int(s * 0.375)
    d.polygon([(cx, topo),
               (cx - cabeca_w // 2, topo + cabeca_h),
               (cx + cabeca_w // 2, topo + cabeca_h)], fill=ACENTO)
    d.rectangle([cx - haste // 2, topo + cabeca_h - 2,
                 cx + haste // 2, base], fill=ACENTO)

    # desce (direita)
    cx = int(s * 0.625)
    d.rectangle([cx - haste // 2, topo,
                 cx + haste // 2, base - cabeca_h + 2], fill=ACENTO)
    d.polygon([(cx, base),
               (cx - cabeca_w // 2, base - cabeca_h),
               (cx + cabeca_w // 2, base - cabeca_h)], fill=ACENTO)


def desenhar(lado: int, com_fundo: bool, margem: float = 0.0) -> Image.Image:
    """
    `margem` encolhe o desenho para dentro — o ícone adaptativo do Android
    recorta as bordas, e sem a folga as pontas das setas seriam cortadas.
    """
    g = lado * ESCALA
    img = Image.new('RGBA', (g, g), FUNDO if com_fundo else (0, 0, 0, 0))
    interno = int(g * (1 - 2 * margem))
    camada = Image.new('RGBA', (interno, interno), (0, 0, 0, 0))
    _setas(ImageDraw.Draw(camada), interno)
    img.paste(camada, (int(g * margem), int(g * margem)), camada)
    return img.resize((lado, lado), Image.LANCZOS)


def main() -> None:
    alvo = RAIZ / 'icon.png'
    desenhar(LADO, com_fundo=True).save(alvo)
    print(f'  ✓ {alvo.relative_to(RAIZ)}')

    # Android: o ic_launcher tradicional (com fundo) e o foreground do
    # adaptativo (transparente, recuado para sobreviver ao recorte).
    tamanhos = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for densidade, px in tamanhos.items():
        pasta = RES / f'mipmap-{densidade}'
        pasta.mkdir(parents=True, exist_ok=True)
        desenhar(px, com_fundo=True).save(pasta / 'ic_launcher.png')
        desenhar(px, com_fundo=False, margem=0.22).save(pasta / 'ic_launcher_foreground.png')
    print(f'  ✓ mipmaps em {len(tamanhos)} densidades')


if __name__ == '__main__':
    main()
