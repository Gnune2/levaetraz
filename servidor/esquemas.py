"""Contratos de dados da API (Pydantic)."""

from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator

# Um envio (celular → PC) passa por estes estados. `recebendo` é o único em que
# existe um arquivo .parcial no disco; todos os outros são terminais ou de espera.
EstadoEnvio = Literal['aguardando', 'recebendo', 'pausado', 'concluido',
                      'duplicado', 'erro', 'cancelado']

Sentido = Literal['sobe', 'desce']          # sobe = celular→PC, desce = PC→celular


# ────────────────────────────────────────────────────────────
# REQUESTS
# ────────────────────────────────────────────────────────────
class LoginRequest(BaseModel):
    senha: str
    dispositivo: str = 'celular'


class PairRequest(BaseModel):
    codigo: str
    dispositivo: str = 'celular'


class NovaSenha(BaseModel):
    atual: str
    nova: str


class Rede(BaseModel):
    bind: Optional[Literal['auto', 'tailscale', 'lan', 'local']] = None
    porta: Optional[int] = Field(default=None, ge=1, le=65535)
    pastas: Optional[list[str]] = None


class AbrirEnvio(BaseModel):
    """Anuncia um arquivo que o celular quer mandar, antes de mandar os bytes."""
    nome: str
    tamanho: int = Field(ge=0)
    destino: Optional[str] = None            # None -> primeira pasta compartilhada
    sha256: Optional[str] = None             # opcional: habilita pular duplicata
    modificado_em: Optional[float] = None

    @field_validator('nome')
    @classmethod
    def _nome_limpo(cls, v: str) -> str:
        # O nome vem de outro aparelho: tratar como texto hostil. Só o último
        # componente interessa, e separador nenhum sobrevive — sem isto um
        # "../../.ssh/authorized_keys" escreveria fora da pasta compartilhada.
        v = v.replace('\\', '/').split('/')[-1].strip()
        v = v.replace('\x00', '')
        if v in ('', '.', '..'):
            raise ValueError('nome de arquivo inválido')
        return v[:255]


class AcaoEnvio(BaseModel):
    acao: Literal['cancelar', 'pausar', 'retomar', 'limpar_concluidos']


class Renomear(BaseModel):
    caminho: str
    nome_novo: str

    @field_validator('nome_novo')
    @classmethod
    def _sem_separador(cls, v: str) -> str:
        v = v.replace('\\', '/').split('/')[-1].strip().replace('\x00', '')
        if v in ('', '.', '..'):
            raise ValueError('nome inválido')
        return v[:255]


class NovaPasta(BaseModel):
    onde: str
    nome: str

    @field_validator('nome')
    @classmethod
    def _sem_separador(cls, v: str) -> str:
        v = v.replace('\\', '/').split('/')[-1].strip().replace('\x00', '')
        if v in ('', '.', '..'):
            raise ValueError('nome inválido')
        return v[:255]


class Apagar(BaseModel):
    caminhos: list[str]


class Preferencias(BaseModel):
    """Ajustes que o celular pode mudar e o PC guarda."""
    destino_padrao: str
    pular_duplicados: bool = True
    organizar_por_tipo: bool = False         # joga em Imagens/, Vídeos/, Documentos/
    manter_historico: int = Field(default=200, ge=0, le=5000)


# ────────────────────────────────────────────────────────────
# RESPONSES
# ────────────────────────────────────────────────────────────
class Envio(BaseModel):
    id: str
    nome: str
    tamanho: int = 0
    recebido: int = 0
    estado: EstadoEnvio = 'aguardando'
    destino: str = ''
    caminho_final: str = ''
    mensagem: str = ''
    origem: str = ''                          # nome do aparelho que mandou
    criado_em: float = 0.0
    atualizado_em: float = 0.0

    @property
    def percent(self) -> float:
        return round(self.recebido / self.tamanho * 100, 1) if self.tamanho else 0.0


class Resumo(BaseModel):
    """Cabeçalho do painel e do app: uma linha sobre o que está acontecendo."""
    ativos: int = 0
    percent: float = 0.0
    bytes_por_s: float = 0.0
    texto: str = ''
