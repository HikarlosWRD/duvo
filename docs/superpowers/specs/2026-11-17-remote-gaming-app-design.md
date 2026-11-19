# Design — App de controle remoto + gaming com streaming (nome provisório: **Duvo**)

Data: 2026-11-17 · Status: pronto para aprovação

Equipe: o usuário + Claude [proposta: o usuário não citou mais ninguém]. Sem prazo definido.

> Convenção: **[decidido]** = escolhido pelo usuário. **[proposta]** = escolha do Claude, aceita até o usuário corrigir. **[builder]** = deixado para quem implementar decidir.

---

## 1. Intenção e porquê

Um app Android que transforma o celular no controle total do PC Windows, juntando em um produto só o que hoje exige três: **Remote Mouse** (mouse/teclado remoto), **Moonlight/Parsec** (streaming de tela de baixa latência) e um **controle de videogame virtual**. O objetivo declarado é **superar os três**: mais funções, experiência mais bem acabada e latência no mínimo igual, buscando ser melhor e mais leve.

O app será **publicado gratuitamente só no GitHub** (APK + instalador do PC), como **open source GPLv3** [decidido]. Nada de Play Store ou Microsoft Store [decidido].

## 2. Objetivos

1. Conectar o celular ao PC sem esforço, em qualquer rede da mesma casa (2.4 GHz, 5 GHz, SSIDs diferentes do mesmo roteador) [decidido].
2. Experiência intuitiva, porém bem feita: não pode parecer "Moonlight modificado" [decidido].
3. Espelhamento com latência mínima e qualidade igual ou melhor que Parsec/Moonlight, mesmo em alta resolução [decidido].
4. Input lag "absurdamente baixo": sensação de jogar no próprio PC [decidido].
5. Conexão estável [decidido].

## 3. Não-objetivos (agora)

- Acesso fora de casa (internet/relay). **Virá depois** [decidido], então a arquitetura não pode bloquear isso (ver §9).
- iOS. Android primeiro [decidido]; o núcleo deve ser separável da UI para facilitar iOS depois [proposta].
- Host em macOS/Linux. Só Windows 10/11 no início [proposta].
- Jogos multiplayer com vários celulares no mesmo PC (pode vir depois) [proposta].

## 4. Anti-objetivos (seria fracasso mesmo funcionando)

- Latência pior que o Moonlight medido no mesmo PC, celular e rede.
- Aceitar comandos sem autenticação. O Remote Mouse tem falhas públicas desse tipo; aqui todo controle passa por pareamento e criptografia.
- Exigir que o usuário mexa no roteador (portas, UPnP) para usar em casa.
- Funcionar só quando o celular está no mesmo SSID do PC.
- Interface com cara de ferramenta técnica para quem só quer usar o mouse.

## 5. Restrições

- **Licença GPLv3:** o projeto herda código do Apollo/Sunshine e do Moonlight.
- **Sem engenharia reversa de software fechado** [proposta, apresentada ao usuário sem objeção]: Parsec e Remote Mouse proíbem isso nos termos de uso. As funções do Remote Mouse serão mapeadas **usando o app e a documentação pública**. Moonlight, Sunshine e Apollo são open source: estuda-se o código-fonte direto. REA e Ghidra ficam disponíveis para analisar binários próprios ou open source, se útil.
- **Marcas:** não usar logos ou nomes Xbox/PlayStation/Nintendo como marca do produto. Os presets usam layout e glifos "estilo" (ex.: "Layout A/B/X/Y", "Layout ✕○△□") [proposta].
- **Driver de controle virtual:** o ViGEmBus, usado pelo Sunshine/Apollo, está em fim de vida. Funciona, mas é risco (ver §10).

## 6. Abordagens consideradas

| | Abordagem | Prós | Contras |
|---|---|---|---|
| A | Fork completo do Apollo e do Moonlight Android | Streaming funcionando em dias | Preso à UI e estrutura deles; produto vira "Moonlight modificado" |
| **B** | **Híbrida: fork do Apollo no PC + app Android novo usando só `moonlight-common-c`** | Motor de latência comprovado; toda a experiência é nossa | Algumas semanas a mais até o primeiro stream |
| C | Tudo do zero (ex.: Rust/QUIC) | Controle total | Anos para alcançar a latência do Moonlight; contraria o objetivo |

**Escolhida: B** [decidido]. Motivo: dá para ficar melhor exatamente onde os três são fracos (experiência, modos, pareamento) sem jogar fora o motor de streaming que já é referência, e ainda otimizá-lo por dentro.

## 7. Arquitetura

```
┌──────────────── PC (Windows) ────────────────┐        ┌──────────── Celular (Android) ────────────┐
│ Duvo Host = fork do Apollo (C++)            │        │ App Duvo (Kotlin + Jetpack Compose)       │
│  ├─ captura (DXGI/WGC) + encode hardware     │  vídeo │  ├─ UI: modos, editor de layouts, config   │
│  │   NVENC / AMF / QuickSync                 │◄──────►│  ├─ núcleo de sessão (JNI)                 │
│  ├─ tela virtual (SudoVDA)                   │  input │  │   └─ moonlight-common-c (vídeo/áudio/   │
│  ├─ input: mouse/teclado/touch/caneta        │  áudio │  │       input/controle)                   │
│  ├─ controle virtual (ViGEm: X360 / DS4)     │        │  ├─ decoder MediaCodec baixa latência      │
│  └─ Serviço Companion (novo)                 │◄──────►│  └─ cliente Companion (TLS)                │
│      pareamento QR, descoberta, energia,     │ TLS    │                                            │
│      mídia/volume, atalhos, status           │        │                                            │
│ Ícone na bandeja do Windows                  │        │                                            │
└──────────────────────────────────────────────┘        └────────────────────────────────────────────┘
```

- **Host** [decidido B]: fork do **Apollo** (GPLv3; fork do Sunshine com tela virtual SudoVDA, "input only mode", permissões por cliente, sincronia de clipboard). O **Serviço Companion** é um módulo novo do mesmo executável/instalador, para o usuário instalar uma coisa só [proposta].
- **Cliente** [decidido B]: app Android novo em **Kotlin + Jetpack Compose** [proposta], com `moonlight-common-c` via NDK/JNI para o protocolo de streaming e input. O **Artemis** (cliente Android do Apollo, fork do Moonlight) serve de referência de código para decoder e integração, não de base.
- **Android mínimo:** Android 10 (API 29) [proposta]; o builder pode baixar se o custo for pequeno.

## 8. Comportamento do produto

### 8.1 Conexão e pareamento [proposta, validada no playback]
- Host roda na bandeja. Ao abrir o app, PCs da rede aparecem sozinhos (mDNS `_nvstream._tcp` + broadcast UDP próprio como reserva).
- **Pareamento único** por **QR code** exibido no PC. O QR leva endereço(s) IP, porta, impressão digital do certificado e um PIN de uso único. Alternativa: PIN digitado. Depois disso, a conexão é automática e criptografada (certificado fixado).
- O QR resolve redes onde só a **descoberta** falha. Redes que **bloqueiam** a comunicação entre aparelhos (rede de convidados, isolamento de clientes, sub-redes sem rota) **não precisam funcionar agora** [decidido]: o app detecta e explica a causa em linguagem simples (ex.: "seu celular está na rede de convidados, troque para a rede principal"). Funcionar nelas vem junto com o acesso remoto (§9).
- IP manual como último recurso.

### 8.2 Modo Controle Remoto [decidido]
- **Mouse e teclado** com todas as funções do Remote Mouse. O inventário completo é uma tarefa do builder (usando o app, sem descompilar). Funções conhecidas, a confirmar: trackpad com gestos (clique, clique direito com dois dedos, scroll com dois dedos, arrastar), teclado com teclas especiais/F1–F12/atalhos, digitação por voz, controles de mídia e volume, troca de apps, desligar/reiniciar/suspender o PC, "air mouse" pelo giroscópio, modo canhoto.
- **Modo trackpad** (movimento relativo) [decidido].
- **Espelhamento** liga/desliga dentro do modo [decidido; esclarecido no playback]: toque = clique na posição tocada (absoluto), dois dedos = scroll, pinça = zoom.
- Sem espelhamento, só trafega input: rede e bateria mínimas [proposta]. Implementação: sessão "input only" do Apollo + canal Companion.

### 8.2.1 Escopo do M2 revisado com o usuário (2026-11-18) [decidido]
Paridade com o Remote Mouse aprovada, com estes ajustes: **sem digitação por voz**; **mouse por giroscópio só se for barato** (último item, opcional); **modo escuro e todo o visual ficam para depois** — o app primeiro fica totalmente funcional, o design vem depois. Fora: Bluetooth, Apple Watch, papel de parede.

### 8.3 Modo Gaming [decidido]
- **Controle virtual** com presets estilo **Xbox, PlayStation e Switch**.
- **Editor de layout:** mover, redimensionar e mudar a opacidade dos botões, salvar layouts com nome, layouts personalizados.
- **Controle base emulado no PC** selecionável: Xbox 360 ou DualShock 4 via ViGEm. O estilo Switch emula Xbox com rótulos e posições Nintendo e opção de trocar A/B [proposta].
- **Espelhamento** com os botões sobrepostos à imagem do jogo.
- **Esconder/mostrar botões** com um toque (para cutscene ou controle físico).
- **Controle físico ligado no celular** (Bluetooth/USB) [decidido]: repassado ao PC como controle; vibração e giroscópio repassados quando o controle suportar [proposta]. Ao conectar um controle, o app oferece esconder os botões virtuais.
- Layouts salvos no celular, com exportar/importar como arquivo [proposta].

### 8.3.1 Gaming sem espelhamento [decidido]
O modo gaming pode ser usado **só como controle** (o usuário olha o monitor ou a TV do PC), como o usuário pediu ("apenas como um controle digital"). Nesse caso a sessão é só de input, e o controle vai pelo caminho de menor latência, sem vídeo.

### 8.3.2 Tela e áudio por modo [proposta]
| Situação | Tela transmitida | Áudio |
|---|---|---|
| Controle remoto, sem espelhamento | nenhuma | no PC |
| Controle remoto, com espelhamento | **monitor real do PC** (padrão; é a tela que o usuário já está controlando, e não mexe nos monitores) | no PC (opção: no celular) |
| Gaming, só controle | nenhuma | no PC |
| Gaming, com espelhamento | **tela virtual** na resolução escolhida (padrão) | no celular (opção: no PC) |

A tabela acima mostra só os **padrões de fábrica**. Nada disso é fixo [decidido]: a tela de configurações de cada modo permite trocar **qual tela é transmitida** (monitor real, cada monitor, tela virtual) e **onde o áudio toca** (PC, celular, ambos). Todas as opções de resolução do §8.4 também ficam disponíveis nos dois modos.

### 8.4 Configurações de streaming [decidido]
No nível do Moonlight: resolução, FPS, bitrate, codec (H.264/HEVC/AV1, conforme suporte do celular e da GPU), escolha de decoder, HDR quando suportado.
- **Resolução** [decidido no playback]: "resolução do celular" (tela virtual do tamanho exato, padrão), "personalizada" (inclusive **maior** que a tela do celular, ex.: 1440p/4K reduzido no celular para mais nitidez) e "usar o monitor real do PC".
- Overlay de estatísticas (latência de rede, decode, quadros perdidos) para o usuário e para medir.

### 8.5 Compatibilidade com o ecossistema Moonlight [proposta]
- **Não é requisito** o app Duvo conectar em Sunshine/Apollo comuns, nem o host Duvo aceitar Moonlight/Artemis comuns. Isso libera mudar o protocolo para as otimizações do §10.
- O host Duvo se anuncia com **serviço mDNS próprio** (não `_nvstream`) e portas padrão próprias, para não colidir com um Sunshine/Apollo já instalado.
- O instalador detecta Sunshine/Apollo instalados e avisa; os drivers compartilhados (SudoVDA, ViGEm) são reaproveitados se já existirem.
- Enquanto não houver mudança de protocolo, manter compatibilidade sai de graça; ela só é quebrada quando uma otimização medida justificar.

### 8.6 Modo inverso: celular espelhado no PC [decidido]
O celular aparece numa janela no PC e é controlado pelo mouse e teclado do PC.
- **Abordagem estilo scrcpy via ADB** [decidido]: o host Duvo conecta ao celular pela **depuração sem fio** do Android 11+ (pareamento sem cabo, guiado pelo app; ativar o modo desenvolvedor uma vez). Base técnica: **scrcpy** (Apache 2.0, compatível com GPLv3).
- Inclui: vídeo de baixa latência, mouse/teclado completos (digitar em qualquer app, atalhos), áudio do celular (Android 11+), clipboard nos dois sentidos [proposta].
- Alternativa descartada: captura + acessibilidade só pelo app (toques lentos, teclado limitado, apps que bloqueiam captura).
- USB funciona como alternativa ao Wi-Fi [proposta].

### 8.7 Atualização automática via GitHub [decidido]
Precisa estar pronta **já na primeira versão publicada**, para todo usuário receber as próximas.
- **App Android:** consulta os Releases do repositório no GitHub (ao abrir e 1x por dia), mostra as novidades, baixa o APK e chama o instalador do Android. O Android sempre pede um toque de confirmação para apps fora da loja; não há como evitar sem root. O próprio Android garante que o APK novo foi assinado com a mesma chave.
- **Host no PC:** a mesma consulta; baixa o instalador, confere o hash SHA-256 publicado no release e atualiza com um clique (ou sozinho, se o usuário ativar), reiniciando o serviço sem perder o pareamento.
- **Versões compatíveis:** app e host trocam a versão do protocolo ao conectar. Se estiverem incompatíveis, o app avisa qual dos dois atualizar e oferece fazer isso na hora.
- Canal estável por padrão; canal beta opcional nas configurações (releases marcados como pre-release) [proposta].
- Releases gerados por CI (GitHub Actions) com APK assinado, instalador e hashes [proposta].

### 8.8 Acesso por dispositivo [decidido]
O host deve ter, **para cada dispositivo conectado**, configuração de acesso total ou parcial [decidido].
- Base já existente: o Apollo tem editor de permissões por cliente na página de pareamento do web UI (input: controle, toque, caneta, mouse, teclado; operações: clipboard enviar/ler, arquivos enviar/baixar, comandos no PC; ações: listar, ver, abrir apps).
- Duvo acrescenta **presets** por dispositivo: "Acesso total", "Controle (padrão do QR)" = ver + abrir + todo input, "Só assistir" = ver + listar, e "Personalizado" (o editor atual) [proposta]. Também: renomear, desativar e remover o dispositivo.
- Padrão para quem pareia por QR continua "Controle"; elevar para "Acesso total" é sempre ação explícita no host [proposta, segurança].
- O app mostra no PC selecionado o que aquele celular pode fazer (e explica quando algo foi bloqueado pelo host) [proposta].

### 8.9 Modo Monitor [decidido]
Terceiro modo: o celular/tablet vira um **monitor real estendido** do Windows [decidido] — aparece em Configurações de vídeo como outro monitor, dá para arrastar janelas para ele, e o toque na tela do aparelho funciona nele [decidido].
- Implementação [proposta]: tela virtual do SudoVDA (que o Apollo já cria) em topologia **estendida** (não espelhada, sem desligar o monitor principal), na resolução e taxa do aparelho; o stream captura **só essa tela**; o toque vira toque/caneta nativo do Windows mapeado para esse monitor; ao desconectar, o monitor some e as janelas voltam para o principal (comportamento do Windows).
- Lembrar a posição do monitor (esquerda/direita/acima) e a escala (DPI) por aparelho [proposta].
- Prioridade de imagem: nitidez de texto (bitrate maior, YUV 4:4:4 quando GPU e decoder suportarem) com latência baixa, mas sem as exigências extremas do modo gaming [proposta].
- Fica dentro dos limites do SudoVDA (já assinado); sem driver próprio.

## 9. Preparação para acesso remoto (futuro)
Não implementar agora, mas: a identidade do par (certificado fixado no pareamento) não deve depender de IP local; o transporte não deve assumir mesma sub-rede; o Companion deve poder rodar sobre um túnel no futuro [proposta].

## 10. Latência: metas e como medir

### 10.0 Hardware de referência [decidido]
- **PC:** RTX 4070 Ti Super (NVENC: H.264/HEVC/AV1).
- **Alvo principal de otimização: tablet Vaio TL10** (Positivo; Unisoc T616, Mali-G57 MP1, tela 2000×1200, Wi-Fi 5, Android 13). O usuário exige que funcione **perfeitamente** nele, e otimizar para o aparelho mais fraco garante o mais forte.
- **Secundário:** Poco F6 (Snapdragon 8s Gen 3).
- O M0 deve levantar o que o decoder do T616 aguenta de verdade (codecs, resolução/FPS máximos, latência de decode, suporte a modo de baixa latência) e definir os **padrões automáticos por aparelho** a partir disso. Ex.: no TL10 o padrão provável é 1080p–1200p a 60 FPS em H.264/HEVC; o builder confirma medindo.
- As metas abaixo valem **nos dois aparelhos**, sempre comparadas com o Moonlight no mesmo aparelho.

"Melhor que Moonlight/Parsec" é verificado por **medição comparativa** no mesmo PC, celular e rede, não por afirmação.

- **Linha de base (M0):** medir Moonlight/Artemis + Apollo sem modificação: latência de host (captura→pacote enviado), rede, decode e ponta a ponta (câmera lenta filmando PC e celular, ou teste de "flash" automatizado).
- **Metas** [proposta]:
  - Ponta a ponta ≤ Moonlight no mesmo hardware (obrigatório); buscar 10–20% melhor.
  - Input toque→injeção no PC: p95 ≤ 5 ms em Wi-Fi 5 GHz sem congestionamento.
  - Zero regressão de estabilidade: sem travadas perceptíveis em 30 min de sessão a 1080p60 em 5 GHz.
- **Alavancas de otimização** [builder]: flags de baixa latência do MediaCodec por fabricante de chip, ritmo de apresentação dos quadros, input em canal prioritário, ajuste dinâmico de bitrate e FEC, modo de Wi-Fi de baixa latência do Android.

## 11. Riscos

| Risco | Mitigação |
|---|---|
| ViGEmBus em fim de vida | Lançar com ViGEm; marco dedicado para avaliar alternativa (ex.: VIIPER) ou driver próprio antes da publicação |
| Decoder fraco do TL10 (Unisoc T616) | Medir no M0; perfis por chip; padrões automáticos conservadores; nunca expor ao usuário uma configuração que o aparelho não aguenta sem aviso |
| Divergência do upstream (Apollo/moonlight-common-c) | Manter mudanças no host isoladas em módulos; sincronizar upstream periodicamente |
| Inventário do Remote Mouse incompleto | Checklist de paridade revisado com o usuário antes do M2 |
| Usuário precisa instalar APK fora da loja | Guia de instalação claro no README; APK assinado com chave própria e atualização avisada pelo app |

## 12. Marcos [proposta]

- **M0. Base e medição:** compilar o Apollo no Windows, rodar com Moonlight/Artemis no **TL10** e no Poco F6, registrar a latência de base e as capacidades do decoder de cada um.
- **M1. Esqueleto:** app Kotlin + JNI com `moonlight-common-c`, descoberta, pareamento por QR, stream básico.
- **M2. Modo Controle Remoto:** mouse, trackpad, teclado, Companion (energia, mídia, atalhos), paridade com o Remote Mouse.
- **M3. Espelhamento no modo remoto:** toque absoluto e gestos.
- **M4. Modo Gaming:** controle virtual, presets, editor, layouts salvos, controle físico, esconder botões.
- **M5. Streaming e otimização:** todas as configurações, overlay de estatísticas, passada de otimização contra a linha de base.
- **M6. Modo inverso:** celular espelhado no PC via ADB sem fio, estilo scrcpy.
- **M6b. Modo Monitor:** monitor estendido virtual com toque (§8.9).
- **M6c. Acesso por dispositivo:** presets e gerenciamento no host (§8.8) — pode entrar junto com o M2.
- **M7. Preparação para publicar:** instalador do host, APK assinado, CI de releases, **atualização automática (app e host)**, release no GitHub, decisão ViGEm vs. VIIPER. **Sem driver próprio**: só drivers já assinados (ViGEm, SudoVDA) [decidido: sem orçamento de assinatura].

## 13. Deixado para o builder
- Estrutura de repositórios (fork do host como repo separado ou submódulo).
- Formato do protocolo do Companion (ex.: protobuf ou JSON sobre TLS).
- Mapeamento de gestos para eventos Windows (toque nativo vs. mouse sintético) no espelhamento.
- Bibliotecas Android auxiliares (QR, injeção de dependência, etc.).
- Detalhes de UI e visual: serão desenhados à parte, com mockups para o usuário aprovar.
