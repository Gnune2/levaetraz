# levaetraz

Troca de arquivos entre o seu PC e o seu celular, nos dois sentidos, sem nuvem
e sem conta em lugar nenhum.

O PC roda um servidor pequeno. Pelo app Android, o celular **navega na sua home
inteira** e puxa qualquer arquivo — e manda arquivos de volta para uma pasta
escolhida, por padrão `~/Transferencias`. Com
[Tailscale](https://tailscale.com), funciona igual em casa ou no 4G do outro
lado do mundo.

```
┌──────────────┐      HTTP + WebSocket       ┌──────────────┐
│  seu PC      │ ◀─────── tailnet ─────────▶ │  seu celular │
│              │                             │              │
│ ~/Transf...  │  ── GET /arquivos/baixar ─▶ │  Download/   │
│              │  ◀─ PUT /envios/{id} ─────  │              │
└──────────────┘                             └──────────────┘
       │
       └── painel web em http://localhost:8765
```

## Instalar

```bash
git clone https://github.com/Gnune2/levaetraz
cd levaetraz
./instalar.sh
```

O script cuida de tudo: pacotes do sistema, ambiente Python, a pasta
compartilhada, o serviço que sobe junto com o PC, o Tailscale (opcional) e o
app do celular. No fim ele abre o painel no navegador para você criar a senha.

É idempotente — rodar de novo só completa o que faltou.

## Como funciona

### Os dois sentidos

| sentido | como | o que acontece |
| --- | --- | --- |
| PC → celular | um `GET` comum | com `Range`, então o celular retoma sozinho se a rede cair |
| celular → PC | protocolo de 3 passos | anuncia, manda os bytes, fecha — dá para continuar de onde parou |

Baixar é fácil: o arquivo já existe inteiro no PC e o cliente pede o pedaço que
quiser. **Subir** é que dá trabalho, porque o servidor precisa guardar o pedaço
recebido em algum lugar e saber costurar o resto depois de uma queda:

```
POST /api/envios          anuncia nome+tamanho, devolve id e o offset
PUT  /api/envios/{id}     manda os bytes a partir do offset
POST /api/envios/{id}/fim fecha, confere o tamanho e move para o lugar
```

Os bytes vão para `<destino>/.levaetraz-parcial/<id>.parcial` e só viram o
arquivo de verdade no último passo. Duas coisas caem de graça daí: você nunca vê
na pasta um arquivo pela metade se passando por pronto, e uma queda no meio do
caminho não deixa lixo com o nome do arquivo bom.

### O mesmo arquivo duas vezes

Antes de mandar, o celular calcula o `sha256` e pergunta. Se esse conteúdo já
estiver no PC, o servidor responde `duplicado` e **nenhum byte trafega** — mesmo
que o nome seja outro. Se o nome já existir mas o conteúdo for diferente, o novo
entra como `foto (2).jpg`; o antigo nunca é sobrescrito.

No sentido contrário, o celular mantém um índice do que já puxou e não baixa de
novo — a menos que você marque "baixar mesmo assim".

### A jaula tem dois níveis

|  | alcance padrão | o que permite |
| --- | --- | --- |
| **ver** | sua home inteira | navegar, prévia, baixar para o celular |
| **gravar** | só `~/Transferencias` | receber envio, criar pasta, renomear, apagar |

A assimetria é o ponto. Olhar demais custa privacidade; gravar demais custa
dados. Como o uso normal é achar um arquivo qualquer do PC pelo celular sem
copiá-lo antes, quem abre é a leitura — e a escrita fica num cercado pequeno,
onde um bug ou um toque errado não destrói nada.

Nos dois casos é lista de **permissão**: caminho fora dela é recusado, e a
checagem acontece *depois* de resolver os links simbólicos, então um atalho
apontando para fora não escapa. Pastas sensíveis (`.ssh`, `.gnupg`, `.config` e
outras 14) ficam bloqueadas mesmo para leitura. Nomes de arquivo que chegam pela
rede perdem qualquer separador antes de tocar o disco.

As duas listas se ajustam no painel, em **ajustes**. Dá para deixar a leitura
ver o PC inteiro (`/`), ou apertá-la numa pasta só.

## Segurança

- **Senha** guardada só como hash Argon2id, nunca em texto.
- **Sessões** revogáveis uma a uma, pelo painel ou pelo app.
- **Primeira senha** só pode ser criada de dentro do próprio PC (`127.0.0.1`).
- **Tentativas erradas** travam o IP por tempo crescente, de 5s a 15min.
- **Pareamento** por QR de uso único, válido 10 minutos.
- **No celular**: digital a cada abertura (com a senha do servidor como saída),
  e `FLAG_SECURE` — a bandeja de apps recentes mostra a tela em branco e
  screenshot fica bloqueado.

O servidor **não** fala HTTPS. Ele não precisa: só escuta no tailnet (onde o
WireGuard já criptografa tudo) ou na sua rede local. Não exponha a porta na
internet aberta.

## De onde dá para acessar

| modo | alcance |
| --- | --- |
| `auto` | Tailscale se existir, senão a rede local |
| `tailscale` | só pela VPN — de qualquer lugar do mundo |
| `lan` | só quem estiver no mesmo Wi-Fi |
| `local` | só o próprio PC |

Com Tailscale, PC e celular ganham um IP fixo entre si e conversam diretamente,
criptografado, sem abrir porta nenhuma no roteador.

## Quando o PC reinicia

O serviço sobe sozinho junto com o sistema — antes mesmo de alguém fazer login,
graças ao *linger* do systemd. Se o processo travar sem morrer, o watchdog
percebe a falta de sinal de vida e reinicia em até um minuto.

Um envio que estava no meio vira **pausado**: o pedaço recebido continua no
disco, e o celular retoma pelo ponto exato quando reconectar.

## Comandos

```bash
python main.py                     sobe o servidor
python main.py --senha             define/troca a senha (derruba as sessões)
python main.py --parear            QR de pareamento no terminal
python main.py --sessoes           lista os aparelhos conectados
python main.py --revogar todas     desconecta tudo
python main.py --bind lan          muda onde escuta

systemctl --user status levaetraz
journalctl --user -u levaetraz -f
```

## Estrutura

```
main.py              entrada; CLI e arranque do uvicorn
servidor/
  app.py             rotas HTTP + WebSocket
  transferencias.py  o motor de envios (celular → PC)
  jaula.py           as duas listas de permissão (ver / gravar)
  auth.py            Argon2id, sessões, pareamento, lockout
  arquivos.py        listar, renomear, apagar, espaço em disco
  banco.py           SQLite dos envios
  eventos.py         barramento com coalescência para o WebSocket
  rede.py            resolve onde escutar (tailnet / LAN / local)
  web/               o painel — HTML, CSS e JS sem build
android/             o app, Kotlin + Jetpack Compose
systemd/             a unit e os scripts de serviço
```

## Como o app é publicado

O APK sai de um build de **debug**, assinado com a chave padrão do Android SDK.
É o suficiente para instalar fora da Play Store e evita guardar uma chave só
para uso pessoal:

```bash
cd android && ./gradlew assembleDebug
gh release create vX.Y.Z app/build/outputs/apk/debug/app-debug.apk
```

O `scripts/baixar_apk.py` pega sempre o release mais recente, então publicar já
faz o painel servir a versão nova.

Duas consequências de usar a chave de debug, para decidir com elas à vista: o app
fica `debuggable` (quem tiver depuração USB liberada e acesso físico lê os dados
dele), e a chave "Android Debug" é a mesma em todo SDK do mundo — ela não prova
que uma atualização veio de você. Se isso incomodar, gere uma chave com `keytool`
e assine o `release`.

## Requisitos

- Linux com systemd (testado em Fedora; o instalador reconhece dnf, apt, pacman
  e zypper)
- Python 3.11+
- Android 8.0+ no celular
- `ffmpeg` é opcional: sem ele, vídeo aparece com ícone em vez de miniatura

## Licença

MIT — veja [LICENSE](LICENSE).
