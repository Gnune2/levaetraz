"""
Jaula de caminhos, com dois níveis.

**Leitura** e **escrita** têm alcances diferentes de propósito:

- ler (navegar, miniatura, baixar para o celular) vale nas *pastas de leitura*,
  por padrão a home inteira — é o que permite achar um arquivo qualquer no PC
  pelo celular sem ter que copiá-lo antes para um lugar especial;
- escrever (receber envio, criar pasta, renomear, apagar) vale só nas *pastas
  compartilhadas*, por padrão só `~/Transferencias`.

A assimetria é o ponto. Olhar demais custa privacidade; escrever demais custa
dados. Como o pedido era "poder olhar tudo", quem abre é a leitura, e a escrita
continua num cercado pequeno onde um bug ou um toque errado não destrói nada.

Nos dois casos vale a lista de pastas negadas (chaves SSH, credenciais,
histórico de shell), e a checagem acontece **depois** de resolver symlinks —
então um link apontando para fora não escapa.
"""

import os
from pathlib import Path

# Pastas que nunca são expostas, nem para leitura. Relativas à home; qualquer
# caminho que passe por elas é negado.
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


class SomenteLeitura(PermissionError):
    """Dá para ver, mas não para mexer — está fora das pastas compartilhadas."""


class Jaula:
    def __init__(self,
                 escrita: list[str] | None = None,
                 leitura: list[str] | None = None,
                 negadas: list[str] | None = None):
        self.escrita = self._limpar(escrita) or [Path.home() / 'Transferencias']
        # Sem lista de leitura configurada, lê a home inteira. As pastas de
        # escrita entram junto: alguém pode compartilhar algo fora da home.
        self.leitura = self._limpar(leitura) or [Path.home()]
        for p in self.escrita:
            if not any(self._dentro(p, r) for r in self.leitura):
                self.leitura.append(p)

        base = Path.home()
        self.negadas: list[Path] = []
        for n in (negadas if negadas is not None else NEGADAS_PADRAO):
            p = Path(n)
            alvo = p if p.is_absolute() else base / p
            # não resolve symlink aqui: a pasta negada pode nem existir, e
            # queremos bloquear o nome mesmo assim
            self.negadas.append(alvo)

    @staticmethod
    def _limpar(caminhos: list[str] | None) -> list[Path]:
        saida = []
        for c in caminhos or []:
            try:
                saida.append(Path(c).expanduser().resolve())
            except (OSError, RuntimeError):
                continue
        return saida

    # ── resolução ────────────────────────────────────────────
    def _resolver(self, caminho: str | os.PathLike) -> Path:
        try:
            alvo = Path(caminho).expanduser().resolve()
        except (OSError, RuntimeError) as exc:
            raise CaminhoNegado(f'caminho inválido: {caminho}') from exc

        for negada in self.negadas:
            if self._dentro(alvo, negada):
                raise CaminhoNegado(f'pasta protegida ({negada.name})')
        return alvo

    # ── leitura ──────────────────────────────────────────────
    def validar(self, caminho: str | os.PathLike) -> Path:
        """Caminho liberado para ver e baixar. Resolve antes de comparar."""
        alvo = self._resolver(caminho)
        if not any(self._dentro(alvo, raiz) for raiz in self.leitura):
            raise CaminhoNegado('fora das pastas visíveis')
        return alvo

    def permitido(self, caminho: str | os.PathLike) -> bool:
        try:
            self.validar(caminho)
            return True
        except CaminhoNegado:
            return False

    # ── escrita ──────────────────────────────────────────────
    def validar_escrita(self, caminho: str | os.PathLike) -> Path:
        """Caminho onde o celular pode gravar, renomear ou apagar."""
        alvo = self._resolver(caminho)
        if not any(self._dentro(alvo, raiz) for raiz in self.escrita):
            raise SomenteLeitura(
                'esta pasta é só de leitura — para gravar aqui, adicione-a às '
                'pastas compartilhadas em ajustes')
        return alvo

    def gravavel(self, caminho: str | os.PathLike) -> bool:
        try:
            self.validar_escrita(caminho)
            return True
        except (CaminhoNegado, SomenteLeitura):
            return False

    def validar_para_criar(self, pai: str | os.PathLike, nome: str) -> Path:
        """
        Caminho de um arquivo que ainda **não existe** — resolve() não serve
        sozinho aqui, porque o alvo não está no disco. Valida a pasta pai (essa
        sim existe) e só então junta o nome, que já veio sem separador.
        """
        pasta = self.validar_escrita(pai)
        if not pasta.is_dir():
            raise CaminhoNegado('destino não é uma pasta')
        limpo = str(nome).replace('\\', '/').split('/')[-1].replace('\x00', '')
        if limpo in ('', '.', '..'):
            raise CaminhoNegado('nome de arquivo inválido')
        return pasta / limpo

    # ── util ─────────────────────────────────────────────────
    @staticmethod
    def _dentro(alvo: Path, base: Path) -> bool:
        return alvo == base or base in alvo.parents

    def raiz_para(self, caminho: str | os.PathLike | None) -> Path:
        """Caminho seguro para começar a navegar: o pedido, ou a 1ª de leitura."""
        if caminho:
            try:
                return self.validar(caminho)
            except CaminhoNegado:
                pass
        return self.leitura[0]

    def descricao(self) -> dict:
        return {
            'escrita': [str(p) for p in self.escrita],
            'leitura': [str(p) for p in self.leitura],
            'negadas': [str(n) for n in self.negadas],
            # A raiz do sistema inteira em leitura é legal? É. Mas quem fez
            # isso merece ver escrito na tela que fez.
            'amplo': any(p == Path('/') for p in self.leitura),
        }


# Instância global, reconfigurada no boot a partir da config.
jaula = Jaula()


def configurar(escrita: list[str] | None,
               leitura: list[str] | None = None,
               negadas: list[str] | None = None) -> Jaula:
    global jaula
    jaula = Jaula(escrita, leitura, negadas)
    return jaula
