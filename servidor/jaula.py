"""
Jaula de caminhos.

O servidor só enxerga o que está dentro das **pastas compartilhadas**. Isso é
lista de permissão, não de proibição: um caminho que não esteja debaixo de uma
delas é negado, ponto. Sem isso, um `?caminho=/etc/passwd` leria qualquer
arquivo do PC e um envio poderia escrever em qualquer lugar.

A checagem acontece **depois** de resolver symlinks, então um link apontando
para fora da jaula não escapa.

A lista de pastas negadas é a segunda linha de defesa: ela só importa se alguém
compartilhar uma pasta grande demais (a própria home, por exemplo). Com o
padrão — só ~/Transferencias — ela nunca é acionada, e é assim que deve ser.
"""

import os
from pathlib import Path

# Pastas que nunca são expostas, mesmo dentro de uma pasta compartilhada.
# Nomes relativos à home; qualquer caminho que passe por elas é negado.
NEGADAS_PADRAO = [
    '.ssh',                     # chaves privadas
    '.gnupg',
    '.pki',
    '.aws', '.kube', '.docker', '.azure',
    '.config',                  # tokens e credenciais de apps (inclui o nosso)
    '.local/share/keyrings',
    '.mozilla', '.thunderbird',
    '.netrc', '.git-credentials', '.bash_history', '.zsh_history',
    '.password-store',
    '.var',                     # dados de apps flatpak
]

# Sufixo dos arquivos que ainda estão chegando. Nunca aparecem na listagem:
# mostrar um arquivo pela metade como se estivesse pronto engana quem navega.
SUFIXO_PARCIAL = '.levaetraz-parcial'


class CaminhoNegado(PermissionError):
    """Caminho fora da jaula ou dentro de uma pasta sensível."""


class Jaula:
    def __init__(self, pastas: list[str] | None = None,
                 negadas: list[str] | None = None):
        base_padrao = Path.home() / 'Transferencias'
        self.pastas = [p for p in (self._normalizar(r) for r in (pastas or []))
                       if p is not None]
        if not self.pastas:
            self.pastas = [base_padrao]

        base = Path.home()
        self.negadas: list[Path] = []
        for n in (negadas if negadas is not None else NEGADAS_PADRAO):
            p = Path(n)
            alvo = p if p.is_absolute() else base / p
            # não resolve symlink aqui: a pasta negada pode nem existir, e
            # queremos bloquear o nome mesmo assim
            self.negadas.append(alvo)

    @staticmethod
    def _normalizar(caminho: str) -> Path | None:
        try:
            return Path(caminho).expanduser().resolve()
        except (OSError, RuntimeError):
            return None

    # ── consulta ─────────────────────────────────────────────
    def permitido(self, caminho: str | os.PathLike) -> bool:
        try:
            self.validar(caminho)
            return True
        except CaminhoNegado:
            return False

    def validar(self, caminho: str | os.PathLike) -> Path:
        """
        Devolve o caminho resolvido se estiver liberado; senão levanta
        CaminhoNegado. Resolver antes de comparar é o que impede escapar por
        symlink ou por '..'.
        """
        bruto = Path(caminho).expanduser()
        try:
            alvo = bruto.resolve()
        except (OSError, RuntimeError) as exc:
            raise CaminhoNegado(f'caminho inválido: {caminho}') from exc

        if not any(self._dentro(alvo, pasta) for pasta in self.pastas):
            raise CaminhoNegado('fora das pastas compartilhadas')

        for negada in self.negadas:
            if self._dentro(alvo, negada):
                raise CaminhoNegado(f'pasta protegida ({negada.name})')

        return alvo

    def validar_para_criar(self, pai: str | os.PathLike, nome: str) -> Path:
        """
        Caminho de um arquivo que ainda **não existe** — resolve() não serve
        sozinho aqui, porque o alvo não está no disco. Valida a pasta pai (essa
        sim existe) e só então junta o nome, que já veio sem separador.
        """
        pasta = self.validar(pai)
        if not pasta.is_dir():
            raise CaminhoNegado('destino não é uma pasta')
        limpo = str(nome).replace('\\', '/').split('/')[-1].replace('\x00', '')
        if limpo in ('', '.', '..'):
            raise CaminhoNegado('nome de arquivo inválido')
        return pasta / limpo

    @staticmethod
    def _dentro(alvo: Path, base: Path) -> bool:
        return alvo == base or base in alvo.parents

    def raiz_para(self, caminho: str | os.PathLike | None) -> Path:
        """Caminho seguro para começar a navegar: o pedido, ou a 1ª pasta."""
        if caminho:
            try:
                return self.validar(caminho)
            except CaminhoNegado:
                pass
        return self.pastas[0]

    def descricao(self) -> dict:
        return {
            'pastas': [str(p) for p in self.pastas],
            'negadas': [str(n) for n in self.negadas],
            # A home inteira compartilhada é legal? Sim. Mas quem fez isso
            # merece ver escrito na tela que fez.
            'amplo': any(p == Path.home() or p == Path('/') for p in self.pastas),
        }


# Instância global, reconfigurada no boot a partir da config.
jaula = Jaula()


def configurar(pastas: list[str] | None, negadas: list[str] | None = None) -> Jaula:
    global jaula
    jaula = Jaula(pastas, negadas)
    return jaula
