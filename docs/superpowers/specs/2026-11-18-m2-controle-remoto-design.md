# Design — M2: Modo Controle Remoto (Duvo)

Data: 2026-11-18 · Status: pronto para aprovação · Spec mãe: `2026-11-17-remote-gaming-app-design.md` (§8.2, §8.2.1, §8.8)

> **[decidido]** = escolhido pelo usuário · **[proposta]** = escolha do Claude, vale até o usuário corrigir · **[builder]** = quem implementar decide.

## 1. Intenção
Aposentar o Remote Mouse no dia a dia do usuário: o celular vira mouse, trackpad e teclado do PC, com painéis de mídia, navegador, apresentação, troca de apps e energia, sincronia de área de transferência e barra de status. Tudo sobre a base da fundação (M0+M1): pareamento por QR, certificado fixado, descoberta mDNS, diagnóstico de rede.

## 2. Objetivos
1. Paridade com o Remote Mouse na lista aprovada [decidido, §8.2.1 do spec mãe].
2. Input com latência mínima (meta do spec mãe: toque→injeção p95 ≤ 5 ms em Wi-Fi 5 GHz).
3. Abrir o app e já estar controlando o último PC, sem passos [proposta].
4. Sem espelhamento: rede e bateria mínimas [decidido no spec mãe].

## 3. Não-objetivos
Espelhamento com toque absoluto (M3). Visual/modo escuro (depois do app funcional) [decidido]. Digitação por voz [decidido: fora]. Bluetooth, Apple Watch, papel de parede. Presets de acesso por dispositivo (M6c; aqui só os bits novos de permissão aparecem no editor existente).

## 4. Anti-objetivos
- Comando de energia sem confirmação no celular.
- Qualquer endpoint novo acessível sem o certificado de cliente pareado, ou que ignore as permissões do dispositivo.
- Ler a área de transferência do PC sem o dispositivo ter permissão.
- Teclado funcionando antes de fechar as pendências de segurança da fundação (§6.0).

## 5. Abordagens consideradas
| | Abordagem | Prós | Contras |
|---|---|---|---|
| **A** | **Input pelo canal de input do stream (sessão "só input" do Apollo) + API Duvo na porta HTTPS já existente, com autenticação por certificado de cliente; barra de status por long-poll** | Nada novo no firewall; reaproveita a segurança revisada; menos código | Barra de status com fração de segundo de atraso |
| B | Serviço separado com conexão contínua (porta nova) | Eventos instantâneos | Porta nova, mais código e superfície de ataque |
| C | Só atalhos de teclado | Zero código no host | Não lista janelas, não mostra música, energia frágil |

**Escolhida: A** [decidido].

## 6. Design

### 6.0 Pendências da fundação (primeiro, antes do teclado) [proposta, vindas da revisão final]
1. **Persistir PCs pareados** (uniqueid, nome, IPs, porta, último uso) e semear a lista com eles; merge por IP compartilhado **ou uniqueid** (nunca só por nome).
2. **Sanitizar o nome do PC** no diálogo de confirmação do deep link (sem quebras de linha/controle, máx. 40 caracteres) e mostrar os IPs reais.
3. **WifiBinding**: contador não vaza se `find/bind` lançar; checar retorno de `bindProcessToNetwork`.
4. **JNI**: corrida entre `connectionTerminated` (thread AsyncTerm desacoplada) e liberação de callbacks no stop → despachante permanente + referência "callbacks atuais" volátil.
5. **build.sh**: detectar série de patches aplicada pela metade (último commit da série é ancestral do HEAD; falhar se `.git/rebase-apply` existir).

### 6.1 Sessão "só input" [proposta]
- O host Duvo liga `enable_input_only_mode` por padrão. O app lança o app especial "input only" do Apollo (`REMOTE_INPUT_UUID`/`input_only_app_id`): o host manda **um único quadro fictício** e para de capturar vídeo (`video.cpp`), sem áudio.
- O app abre a conexão com `moonlight-common-c` sem decodificar vídeo (descarta o quadro fictício) e usa o canal de input (ENet/UDP, já criptografado pela versão do protocolo lida via HTTPS).
- **Risco a validar primeiro (spike):** `moonlight-common-c` aceitar uma sessão sem fluxo de vídeo contínuo sem derrubar por timeout. O Artemis faz isso com o Apollo; se precisar, o app ignora o watchdog de vídeo nesse modo.
- Ciclo de vida: inicia ao entrar no modo Controle Remoto; **reconecta sozinho** com backoff (1, 2, 4… até 30 s) após queda de rede; encerra ao sair do modo ou com o app em segundo plano por mais de 5 min [proposta].

### 6.2 Mouse e trackpad [decidido; detalhes proposta]
- Movimento relativo com curva de aceleração (pode desligar) e sensibilidade ajustável.
- Toque = clique esquerdo; toque com dois dedos = clique direito; toque com três dedos = clique do meio.
- Rolagem com dois dedos em alta resolução (eventos de scroll de alta resolução do protocolo), vertical e horizontal.
- **Trava de arrasto:** segurar >1 s trava o botão; próximo toque solta.
- Gestos de três dedos → atalhos do Windows: deslizar p/ cima = Win+Tab, p/ baixo = Win+D, esquerda/direita = Alt+Tab / Alt+Shift+Tab. Pinça = Ctrl+rolagem (zoom).
- Botões físicos de clique na tela (área inferior), com **modo canhoto** que espelha o layout.
- **Giroscópio:** último item, só se for barato [decidido].

### 6.3 Teclado [decidido; detalhes proposta]
- Campo invisível que abre o teclado do Android; texto vai como **UTF-8** pelo protocolo (qualquer idioma/acentos); Backspace/Enter como teclas.
- Barra de teclas especiais: Esc, Tab, F1–F12, setas, Win, Del, PrtSc, Home/End/PgUp/PgDn.
- **Ctrl/Alt/Shift/Win grudentos**: um toque = próxima tecla combinada; dois toques = travado.
- Atalhos prontos: copiar, colar, recortar, desfazer, refazer, selecionar tudo, Alt+Tab, Win+D, Ctrl+Shift+Esc, Alt+F4 (com confirmação), Win+L.

### 6.4 Painéis [decidido]
Todos via **teclas virtuais do Windows** pelo canal de input (sem API):
- **Mídia:** tocar/pausar, próxima, anterior, parar, volume +/−, mudo.
- **Navegador:** voltar (Alt+←), avançar, recarregar (F5), nova aba, fechar aba, reabrir aba, aba seguinte/anterior, zoom +/−/100%, barra de endereço (Ctrl+L), tela cheia (F11).
- **Apresentação:** próximo/anterior (PgDn/PgUp), iniciar (F5), do slide atual (Shift+F5), sair (Esc), tela preta (B).
- **Troca de apps** e **Energia**: via API (§6.6).
- Reordenar e esconder painéis [decidido]; ordem salva no app.
- **Botões de volume do celular** controlam o volume do PC enquanto o modo está na tela (opção) [decidido].
- Vibração e som ao tocar (opção) [decidido].

### 6.5 Barra de status [decidido]
App ativo, título da janela e música tocando (título/artista/app). Vem do long-poll da API (§6.6). Mostrada no topo do modo Controle Remoto.

### 6.6 API Duvo no host [proposta]
Na porta HTTPS do nvhttp (40984), mesma autenticação mútua por certificado do pareamento; cada endpoint checa a permissão do dispositivo. Código em `host/src/duvo/`, registro mínimo marcado `// DUVO:` no nvhttp.

| Endpoint | Faz | Permissão |
|---|---|---|
| `GET /duvo/status` | `{seq, activeApp, windowTitle, media:{title,artist,app,playing}, volume:{level,muted}}` | `view` |
| `GET /duvo/events?since=<seq>&timeout=25` | long-poll: responde quando `seq` muda ou no timeout | `view` |
| `GET /duvo/windows` | janelas de topo visíveis: `{id, title, process}` | `duvo_windows` (novo) |
| `POST /duvo/windows/activate?id=` | traz a janela para frente (restaura se minimizada) | `duvo_windows` |
| `POST /duvo/desktop` | mostrar área de trabalho | `duvo_windows` |
| `GET /duvo/apps` / `POST /duvo/apps/launch?id=` | atalhos do Menu Iniciar (usuário + todos) e abrir | `duvo_windows` |
| `POST /duvo/power?action=shutdown\|restart\|sleep\|logoff\|lock` | energia; o app sempre confirma antes | `duvo_power` (novo) |
| `POST /duvo/volume?level=0..100` / `?mute=0\|1` | volume do Windows (slider) | input (`input_kbd`) |
| clipboard texto | **reaproveita** `getClipboard`/`setClipboard` do Apollo | `clipboard_read` / `clipboard_set` |
| `GET/POST /duvo/clipboard?type=image` | imagem PNG (base64), máx. 10 MB | `clipboard_read` / `clipboard_set` |

- **Permissões novas** `duvo_windows` e `duvo_power` adicionadas ao enum do Apollo com `// DUVO:` e ao editor de permissões do web UI (rótulos pt-BR).
- **Preset do QR ("Controle")** passa a incluir `duvo_windows`, `duvo_power`, `clipboard_set` e `clipboard_read` [proposta: o usuário aprovou energia com confirmação e sincronia de área de transferência; ler o clipboard do PC é sensível, mas o QR exige estar logado no host]. Dispositivos já pareados mantêm o que têm.
- **Clipboard do Apollo exige stream conectado** — a sessão "só input" conta como conectada (validar no spike).
- Sincronia da área de transferência: **manual por padrão** (botões "enviar para o PC" / "pegar do PC") com opção automática ao voltar para o app [proposta: Android 10+ não deixa apps lerem o clipboard em segundo plano].
- Música tocando: Windows "Global System Media Transport Controls" (SMTC). Janela ativa: `GetForegroundWindow` + nome do processo. Volume: Core Audio.
- Long-poll: no máximo 1 requisição pendente por dispositivo; `seq` incrementa a cada mudança; polling interno do host a cada 500 ms [builder pode trocar por eventos do Windows].

### 6.7 Conexão automática [decidido]
Ao abrir o app: tenta o último PC usado (lista persistida, §6.0.1); se responder, entra direto no modo Controle Remoto. Se não, mostra a lista com o diagnóstico (fundação).

### 6.8 Telas do app (funcionais, sem design visual ainda) [proposta]
`ModoControleRemoto`: barra de status no topo · área de trackpad · botões de clique · barra inferior com abas dos painéis (Teclado, Mídia, Navegador, Apresentação, Apps, Energia) e "editar painéis". Configurações: sensibilidade, aceleração, canhoto, vibração/som, volume pelo celular, sincronia automática do clipboard.

## 7. Latência e verificação
- Meta: toque→injeção p95 ≤ 5 ms (spec mãe). Verificação: o app mostra RTT do canal de input (estatística do `moonlight-common-c`); teste manual no TL10 e no Poco.
- Testes automáticos: lógica de gestos, teclas grudentas e mapeamento de painéis (JVM); endpoints do host (GoogleTest com fakes de Windows APIs onde possível); checagem de permissão por endpoint.
- Teste ao vivo obrigatório em cada tarefa que toca input/API, como na fundação.

## 8. Riscos
| Risco | Mitigação |
|---|---|
| `moonlight-common-c` derruba sessão sem vídeo | Spike na primeira tarefa; fallback: ignorar watchdog de vídeo no modo só input |
| SMTC/Core Audio via C++/WinRT no MinGW | Se WinRT não compilar no MSYS2, ler SMTC com COM puro ou deixar "música tocando" para depois sem bloquear o resto |
| Energia por API abusada | Permissão própria + confirmação no app + log no host |
| Clipboard do PC vazando senhas | Permissão `clipboard_read` por dispositivo; sincronia manual por padrão |

## 9. Deixado para o builder
Formato JSON exato das respostas, nomes internos, estrutura dos ViewModels, biblioteca de persistência (DataStore/JSON), ordem interna das tarefas depois do §6.0 e do spike.

## 10. Respostas às perguntas do revisor (decisões do Claude, marcadas [proposta])
1. **Spike da sessão só input falhar:** existe um **portão** depois da tarefa do spike. Fallback em ordem: (a) pedir ao host um vídeo mínimo real (ex.: 320×180 a 1 FPS) e descartar no app; (b) só se (a) falhar, patch mínimo no `moonlight-common-c` (submódulo) para tolerar ausência de vídeo. Trocar o caminho de input por um canal próprio (abordagem B) **não** é fallback.
2. **Latência ≤ 5 ms:** é **meta** no M2, não portão. Medir = tempo do toque no app até a injeção no host, com carimbo de tempo do host num log de depuração e relógios alinhados por troca de ping no início da sessão; registrar p50/p95 no TL10 e no Poco. O portão de latência fica no M5.
3. **Permissões:** `view` já existe no enum do Apollo (`PERM::view`). Novos bits `duvo_windows` e `duvo_power` usam os bits livres do grupo "operação" (o enum é máscara de 32 bits; bits 21–23 estão livres). Barra de status e long-poll exigem `view`; slider de volume exige `input_kbd`. **Migração única** no host: dispositivo cuja permissão é exatamente o preset QR antigo (`list|view|launch|todos os inputs`) é promovido ao preset "Controle" novo — assim o TL10 e o Poco já pareados funcionam sem parear de novo. Outros dispositivos ficam como estão.
4. **Host como serviço / sessão do usuário:** a API roda no processo do host. Operações que só funcionam no contexto do usuário logado (música tocando via SMTC, abrir atalhos do Menu Iniciar) usam o mecanismo do Apollo que já executa programas como o usuário; se isso não bastar, um utilitário pequeno lançado na sessão do usuário responde por stdout (uma chamada por vez). Janela ativa, lista/ativar janelas e volume são tentados direto. **Aceitável:** não conseguir ativar janelas elevadas (admin) ou da área de trabalho segura (UAC/tela de bloqueio) — o app mostra "não foi possível trocar para essa janela".
5. **Confirmação de energia/Alt+F4/Win+L:** só no app + log no host com nome do dispositivo. A barreira de segurança é a permissão `duvo_power` por dispositivo.
6. **Long-poll:** timeout de 25 s, no máximo 1 pendente por dispositivo (o novo substitui o antigo); a resposta é guardada e escrita depois (resposta assíncrona do servidor HTTP), sem prender thread. Se o servidor não suportar isso bem, cair para polling a cada 1 s. O app para o long-poll quando a sessão só input é encerrada (em segundo plano > 5 min).
7. **Conexão automática:** não acorda PC (Wake-on-LAN fica fora do M2). Se o PC já tem um stream de outro dispositivo, a sessão só input entra junto (o Apollo permite clientes com `view` se juntarem à sessão atual) e o app avisa "PC em uso por outro dispositivo — controle compartilhado".
8. **Clipboard:** texto pelos endpoints do Apollo (a sessão só input conta como stream conectado — validar no spike) e imagem PNG pelo endpoint Duvo, máx. 10 MB. Arquivos e texto formatado ficam fora.
