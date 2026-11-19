# M3 unificado: espelhamento + gaming + configurações de streaming

Data: 2026-11-19. Junta os antigos M3 (espelhamento no modo remoto), M4 (modo gaming) e a parte de configurações e estatísticas do M5, a pedido do usuário ("unificar marcos numa lapada só"). Execução nativa, sem subagentes, com revisão própria e testes JVM em cada tarefa. Branch `m3`.

**Fica fora:**
- A passada de otimização contra a linha de base do Moonlight/Artemis, porque depende da medição com câmera que o usuário faz. O overlay de estatísticas desta etapa é a ferramenta para ela.
- A escolha entre cada monitor físico. Por enquanto as opções são "monitor principal" e "tela virtual". Escolher outros monitores entra com o Modo Monitor (M6b).
- Áudio "nos dois" ao mesmo tempo: o Apollo não transmite o áudio quando ele toca no PC.

## Decisões (propostas, aceitas até o usuário corrigir)
- **Espelhamento no modo remoto:** um botão liga e desliga. Ao ligar, a sessão só de input é trocada por uma sessão Desktop com vídeo: monitor real, áudio no PC.
  - **Toque padrão, modo "mouse":**
    - toque curto = clique na posição tocada;
    - toque longo = clique direito;
    - tocar e arrastar = arrastar com o botão esquerdo;
    - dois dedos = rolagem;
    - pinça = Ctrl+roda (zoom).
  - **Opção "toque nativo do Windows":** os toques vão como touch real (`LiSendTouchEvent`), quando o host anuncia `LI_FF_PEN_TOUCH_EVENTS`.
  - Teclado, painéis e atalhos continuam funcionando durante o espelhamento.
- **Gaming:**
  - Dois jeitos de usar:
    - com espelhamento: tela virtual na resolução do aparelho, áudio no celular;
    - só controle: sessão só de input, sem vídeo.
  - Controle emulado no PC: Xbox 360 para os presets Xbox e Switch, DualShock 4 para o preset PlayStation (o tipo vai no `LiSendControllerArrivalEvent`).
  - O preset Switch troca A/B e X/Y de posição, com opção de desfazer a troca.
  - Os layouts usam coordenadas normalizadas (0..1), o que serve para celular e tablet. Ficam salvos em JSON e podem ser exportados e importados via SAF.
  - **Controle físico:** eventos do `InputDevice` (teclas e eixos) viram o mesmo `GamepadState`. A vibração vai para o vibrador do controle (ou do aparelho). Giroscópio e acelerômetro vão quando o host pedir (`setMotionEventState`, API 31+). Ao conectar um controle físico, o app oferece esconder os botões virtuais.
- **Configurações de streaming, por modo:** resolução (do aparelho, personalizada ou do monitor real), FPS, bitrate (automático ou manual), codec (automático, H.264, HEVC ou AV1, filtrados pelo DecoderProbe), HDR (quando o decoder suporta), tela (monitor principal ou virtual), áudio (celular ou PC). Os padrões são os da tabela §8.3.2 do design.
- **Overlay de estatísticas:** FPS recebido e renderizado, latência de decode, RTT da rede, latência do host, quadros perdidos, codec e resolução.

## Tarefas
1. **JNI:** posição absoluta do mouse, touch, controle (arrival, estado, movimento), callbacks de rumble e de sensores, feature flags do host, RTT.
2. **Modelo de configurações de streaming** (`StreamPrefs`) com padrões por modo e encaixe nas capacidades do decoder, mais a tela de configurações.
3. **`HostClient`:** opções de launch (tela virtual, áudio local, HDR, bitmap de controles) e troca de sessão (cancelar a sessão atual antes de uma nova).
4. **Overlay de estatísticas** no stream.
5. **Mapeamento do toque absoluto** (área com letterbox), com o motor de gestos do espelhamento puro e testado.
6. **Espelhamento no `RemoteScreen`:** botão liga/desliga, superfície de vídeo, input pela sessão de stream.
7. **`GamepadState` e `ControllerSender`** (flags do Moonlight, arrival, envio agrupado).
8. **Modelo de layout do controle virtual:** presets Xbox, PlayStation e Switch, JSON, exportar e importar.
9. **Overlay do controle virtual:** multitoque, analógicos com zona morta, gatilhos, d-pad, vibração tátil, esconder e mostrar.
10. **Editor de layout:** mover, redimensionar, opacidade, salvar com nome, duplicar, apagar, exportar e importar.
11. **Tela Gaming:** com espelhamento ou só controle, com entrada pela lista de PCs.
12. **Controle físico:** mapeamento, vários controles, rumble, sensores.
13. **Teste ao vivo** no TL10 (e no Poco, se estiver no adb). Depois, mesclar na main.

## Regras de teste ao vivo (mantidas)
- Só o host de teste próprio, nunca o Sunshine do usuário.
- Antes de injetar input: Bloco de Notas em primeiro plano, ou jogo/janela de teste confirmados pelo usuário.
- Para testar o controle no PC, usar uma página de teste de gamepad aberta no PC, só com o usuário presente.
