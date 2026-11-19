# Backlog (itens adiados)

Pendências pequenas encontradas nas revisões, para triar antes do lançamento (M7).

## Do M2
- **App:** o receiver de debug está exportado no source set `main`. Mover para `src/debug` ou protegê-lo com permissão.
- **App:** uma falha de escrita no store de PCs pareados propaga a exceção para a coroutine do pareamento.
- **App:** `HostClientTest` é instável: falhou uma vez com "Connection or outbound has been closed" (TLS do MockWebServer).
- **App:** os painéis (Apps, por exemplo) não rolam, e a lista de apps fica cortada na tela. Resolver junto com o design visual.
- **Host:** um dono com o `_all` antigo (0x071F1F00) não ganha os bits novos (energia, janelas) até editar no web UI. Avaliar uma migração.
- **Host:** depois de 60 s ocioso, o status lê o Windows de forma síncrona na thread de io, o que bloqueia o HTTPS por até ~0,8 s. Uma corrida pode incrementar o `seq` duas vezes.
- **Adiado para o M5:** a medição de latência de input (antiga Task 17 do M2).
- **Não feito:** o mouse por giroscópio (opcional).

## Do M3 unificado
- **SudoVDA não está instalado** no PC de teste. Com isso, a "tela virtual" do Gaming cai em silêncio no monitor principal. O instalador (M7) deve instalar o SudoVDA, e o app deve avisar quando a tela virtual não estiver disponível.
- **Não testado ao vivo:**
  - controle físico (não havia controle conectado ao tablet);
  - preset PlayStation (DS4 no PC);
  - teclado do espelhamento (usa o mesmo `sendText` já testado);
  - vibração vinda do host.
- **Adiados para a etapa de otimização (antigo M5):**
  - HDR (caminho de 10 bits e metadados);
  - "resolução do monitor real" (o host precisa informar o tamanho do monitor);
  - escolha entre vários monitores (junto com o Modo Monitor);
  - comparação de latência com o Moonlight/Artemis usando o overlay.
- **Visual:**
  - os sliders do editor de layout ficam espremidos;
  - o cabeçalho do controle remoto (Espelhar, Gaming, Configurações, Sair) aperta em celular na vertical.
- **Teste:** `connectedDebugAndroidTest` desinstala o app e apaga o pareamento. Reparear depois via `pairqr.py` e deep link.

## Do M6 unificado (modo inverso, Modo Monitor, acesso por aparelho)
- **Modo inverso:**
  - o host chama `adb` e `scrcpy`, mas nenhum dos dois acompanha o instalador. Falta incluir ambos na pasta `tools` (M7);
  - testado ao vivo com o scrcpy 5.0.1: a janela do celular abriu e fechou pela API do host;
  - o scrcpy abre também uma janela de console, que deveria ser escondida, e precisa da pasta `tools` completa (inclusive os PNGs de ícone);
  - se o host rodar como serviço, o `adb` roda na conta do serviço e as chaves de depuração ficam lá; o scrcpy abre na sessão do usuário. Hoje só vale com o host rodando na conta do usuário.
- **Modo Monitor:**
  - **SudoVDA:** o instalador de Apollo (`install.bat`) instala o certificado do driver como raiz confiável do Windows e depende do `nefconc.exe`, que não vem no repositório. Só instalar com o conhecimento do usuário; ver como evitar o certificado raiz no M7;
  - **testado ao vivo no TL10 com o SudoVDA:** o Windows ganhou o monitor `DISPLAY21` (1920x1152, à direita do principal, que continuou ativo), uma janela do Bloco de Notas arrastada para ele apareceu no tablet, e um toque no tablet abriu o menu certo da janela; ao parar o host o monitor sumiu;
  - o monitor virtual ainda existia 6 s depois de sair no app (o host espera um tempo antes de encerrar a sessão); avaliar encerrar na hora no modo Monitor;
  - o `SudoVDA.dll` não vem no repositório do host: o instalador (M7) precisa levá-lo do pacote oficial do Apollo e o `install.bat` precisa de administrador;
  - já usa a tela virtual estendida do Apollo (não isolada) e o toque nativo, mas depende do SudoVDA, que não está instalado, e por isso não foi testado ao vivo;
  - faltam lembrar a posição do monitor e a escala (DPI) por aparelho, e o 4:4:4 para texto nítido.
- **Acesso por aparelho:** o preset "Desativado" zera as permissões, mas não derruba a sessão em andamento. O próximo passo é desconectar o aparelho ao desativar.
- **Teste:** o tablet bloqueia a tela sozinho, e o teste ao vivo precisa dele desbloqueado.

## Do visual Duvo
- **Aplicado:** tema escuro, claro e automático (Configurações > Aparência), fontes IBM Plex, ícone adaptativo e nome Duvo no app, telas Início, Controle remoto e Configurações redesenhadas, painel do PC com a identidade (escuro por padrão, destaque âmbar, marca no menu).
- **Só pelo tema, sem redesenho próprio:** configurações de transmissão, editor do controle virtual, pareamento por QR, barras do Gaming e do espelhamento. Vale uma passada para alinhar ao conceito.
- **Ainda com o nome antigo:** ícones da bandeja do Windows (Apollo), nome do instalador e do serviço, certificado do aparelho (CN "Duvo"), pacote `app.duvo.remote`, serviço mDNS `_duvo._tcp` e esquema `duvo://`. Renomear com cuidado para não quebrar o pareamento.
- **Painel do PC:** a conferência visual foi só do CSS numa página estática (o painel exige HTTPS com certificado próprio e login); falta olhar com o painel logado.
- **Barra de status do Android** fica preta no modo claro (cor fixa do tema da janela).
- **Antes de publicar:** conferir domínio e marca de "Duvo".
- **Painel do PC, textos do Apollo:** as páginas Aplicativos, Configuração, Solução de problemas e Alterar senha ainda trazem textos e links do Apollo/Sunshine (e o aviso de nova versão do Apollo foi removido com a página inicial antiga). Passar por todas com o texto do Duvo.
- **App Android:** quando o último PC pareado está desligado ou foi trocado, o app fica em "Reconectando…" sem oferecer esquecer o PC ou parear de novo; deveria sugerir isso depois de algumas tentativas.

## Da passada de polimento
- **Não visto no tablet:** gestos de toque e rolagem (corrigidos por densidade), faixas de rolagem em uso, teclado redimensionando, busca e ícones de apps, filtro por toque no Gaming, editor sobre a tela do jogo, e esconder a barra do espelhamento. Só o menu Ajustes do Gaming, as abas com ícone e a tela inicial foram vistos.
- **Botão Guia:** o bit do Guia (0x0400) chega ao pad Xbox no Windows. Se o Steam não reage, é configuração do Steam.
- **Bluetooth e Wi-Fi 2,4 GHz:** coexistência de rádio; só mitigado (bloqueio de baixa latência e aviso).
- **Modo Monitor:** criou o monitor virtual nos testes; a queixa de "igual ao Espelhar" não foi reproduzida. Ao alternar Monitor e Espelhar, o monitor virtual fica um tempo e o Espelhar pode mostrá-lo.

## Regras de teste ao vivo
- Antes de qualquer input injetado, conferir que o Bloco de Notas está em primeiro plano (no PC do usuário já houve jogos recebendo input).
- Ações de janela, foco ou energia só com o PC livre e confirmação ao vivo do usuário.
- Não executar desligar, reiniciar, suspender ou sair da conta ao vivo.
