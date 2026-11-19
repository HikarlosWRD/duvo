# Baseline de desempenho

## Sonda de decoder

### Positivo Vaio TL10 (Unisoc T616, Android 13)

Saída da sonda **antes da correção** de validação de tamanho conjunto e de taxa de atualização
da tela (a recomendação `1920x1152 @ 120 fps` abaixo estava errada: os máximos `1920x3840` são
intervalos independentes e a tela é de 58 Hz). Com a sonda corrigida, o cabeçalho passou a ser:

```text
Tela: 2000x1200 @ 58 Hz
Recomendado: video/hevc 1920x1152 @ 60 fps
```

A lista de decoders abaixo não muda com a correção.

```text
Aparelho: Positivo Vaio TL10
Android: 13 (API 33)
Tela: 2000x1200

Recomendado: video/hevc 1920x1152 @ 120 fps

Decoders de vídeo:
- c2.unisoc.av1.decoder (video/av01)
    hardware: não
    máximo: 1920x1072
    fps máximo em 1080p: sem suporte a 1080p
    baixa latência: não
- OMX.sprd.av1.decoder (video/av01)
    hardware: não
    máximo: 1920x1072
    fps máximo em 1080p: sem suporte a 1080p
    baixa latência: não
- c2.android.av1-dav1d.decoder (video/av01)
    hardware: não
    máximo: 2048x2048
    fps máximo em 1080p: 30
    baixa latência: sim
- c2.android.av1.decoder (video/av01)
    hardware: não
    máximo: 2048x2048
    fps máximo em 1080p: 30
    baixa latência: sim
- c2.unisoc.avc.decoder (video/avc)
    hardware: sim
    máximo: 1920x3840
    fps máximo em 1080p: 106
    baixa latência: não
- OMX.sprd.h264.decoder (video/avc)
    hardware: sim
    máximo: 1920x3840
    fps máximo em 1080p: 106
    baixa latência: não
- c2.unisoc.h263.decoder (video/3gpp)
    hardware: sim
    máximo: 352x288
    fps máximo em 1080p: sem suporte a 1080p
    baixa latência: não
- OMX.sprd.h263.decoder (video/3gpp)
    hardware: sim
    máximo: 352x288
    fps máximo em 1080p: sem suporte a 1080p
    baixa latência: não
- c2.unisoc.hevc.decoder (video/hevc)
    hardware: sim
    máximo: 1920x3840
    fps máximo em 1080p: 212
    baixa latência: não
- OMX.sprd.hevc.decoder (video/hevc)
    hardware: sim
    máximo: 1920x3840
    fps máximo em 1080p: 212
    baixa latência: não
- c2.unisoc.mpeg4.decoder (video/mp4v-es)
    hardware: sim
    máximo: 896x896
    fps máximo em 1080p: sem suporte a 1080p
    baixa latência: não
- OMX.sprd.mpeg4.decoder (video/mp4v-es)
    hardware: sim
    máximo: 896x896
    fps máximo em 1080p: sem suporte a 1080p
    baixa latência: não
- c2.unisoc.vp8.decoder (video/x-vnd.on2.vp8)
    hardware: sim
    máximo: 1920x1088
    fps máximo em 1080p: 30
    baixa latência: não
- OMX.sprd.vpx.decoder (video/x-vnd.on2.vp8)
    hardware: sim
    máximo: 1920x1088
    fps máximo em 1080p: 30
    baixa latência: não
- c2.unisoc.vp9.decoder (video/x-vnd.on2.vp9)
    hardware: sim
    máximo: 1920x3840
    fps máximo em 1080p: 106
    baixa latência: não
- OMX.sprd.vp9.decoder (video/x-vnd.on2.vp9)
    hardware: sim
    máximo: 1920x3840
    fps máximo em 1080p: 106
    baixa latência: não

```

### Poco F6 (Xiaomi 24069PC21G, Android 15)

Saída da sonda corrigida (cabeçalho e apenas os decoders por hardware; os decoders por software
`c2.android.*`/`OMX.google.*` de h263, mpeg4 e vp8 foram omitidos).

```text
Aparelho: Xiaomi 24069PC21G
Android: 15 (API 35)
Tela: 2712x1220 @ 60 Hz

Recomendado: video/hevc 2712x1220 @ 60 fps

Decoders de vídeo por hardware:
- c2.qti.av1.decoder (video/av01)
    hardware: sim
    máximo: 4096x4096
    fps máximo em 1080p: 537
    baixa latência: não
- c2.qti.av1.decoder.low_latency (video/av01)
    hardware: sim
    máximo: 4096x4096
    fps máximo em 1080p: 535
    baixa latência: sim
- c2.qti.avc.decoder (video/avc)
    hardware: sim
    máximo: 7680x7680
    fps máximo em 1080p: 412
    baixa latência: não
- OMX.qcom.video.decoder.avc (video/avc)
    hardware: sim
    máximo: 7680x7680
    fps máximo em 1080p: 412
    baixa latência: não
- c2.qti.avc.decoder.low_latency (video/avc)
    hardware: sim
    máximo: 7680x7680
    fps máximo em 1080p: 410
    baixa latência: sim
- OMX.qcom.video.decoder.avc.low_latency (video/avc)
    hardware: sim
    máximo: 7680x7680
    fps máximo em 1080p: 410
    baixa latência: sim
- c2.qti.hevc.decoder (video/hevc)
    hardware: sim
    máximo: 7680x7680
    fps máximo em 1080p: 625
    baixa latência: não
- OMX.qcom.video.decoder.hevc (video/hevc)
    hardware: sim
    máximo: 7680x7680
    fps máximo em 1080p: 625
    baixa latência: não
- c2.qti.hevc.decoder.low_latency (video/hevc)
    hardware: sim
    máximo: 7680x7680
    fps máximo em 1080p: 632
    baixa latência: sim
- OMX.qcom.video.decoder.hevc.low_latency (video/hevc)
    hardware: sim
    máximo: 7680x7680
    fps máximo em 1080p: 632
    baixa latência: sim
- c2.qti.vp9.decoder (video/x-vnd.on2.vp9)
    hardware: sim
    máximo: 4096x4096
    fps máximo em 1080p: 592
    baixa latência: não
- OMX.qcom.video.decoder.vp9 (video/x-vnd.on2.vp9)
    hardware: sim
    máximo: 4096x4096
    fps máximo em 1080p: 592
    baixa latência: não
- c2.qti.vp9.decoder.low_latency (video/x-vnd.on2.vp9)
    hardware: sim
    máximo: 4096x4096
    fps máximo em 1080p: 588
    baixa latência: sim
- OMX.qcom.video.decoder.vp9.low_latency (video/x-vnd.on2.vp9)
    hardware: sim
    máximo: 4096x4096
    fps máximo em 1080p: 588
    baixa latência: sim
- c2.qti.dv.decoder (video/dolby-vision)
    hardware: sim
    máximo: 7680x7680
    fps máximo em 1080p: 1200
    baixa latência: não
```

## Latência ponta a ponta

Linha de base do Artemis + Apollo sem modificações, a ser batida pelos marcos seguintes.
A tabela de resultados fica vazia até a medição ser feita com o usuário.

### Protocolo

1. **Rede:** Wi-Fi 5 GHz em todos os aparelhos e no PC host, com o roteador na mesma posição
   para todas as medições. Sem outros dispositivos com tráfego intenso durante o teste. Anotar
   se o PC host estiver ligado por cabo.
2. **Software:** Artemis + Apollo sem modificações. Anotar as versões exatas usadas no início de
   cada sessão de medição.
3. **Host e porta 47989:** o serviço Sunshine instalado pelo usuário ocupa a porta 47989. Para
   testar o Apollo puro, pare o serviço do Sunshine antes da medição. Se o teste for feito contra
   o Sunshine, registre na coluna de observações qual host foi usado.
4. **Vaio TL10:** não tem decodificação AV1 por hardware, então não use AV1 em nenhuma config.
   A tela é de 58 Hz. A config "nativa 60 FPS" usa 2000x1200 ou a resolução recomendada pela
   sonda (1920x1152), sempre a 60 FPS.
5. **Configs por aparelho:** 1080p60 H.264, 1080p60 HEVC e resolução nativa a 60 FPS. Em todas,
   manter o mesmo bitrate e registrar o valor usado.
6. **Overlay do Artemis:** com o overlay de estatísticas ligado, observar por 60 s e anotar a
   média de rede, decode e host (colunas da tabela).
7. **Ponta a ponta:** abrir `tools/latency/flash.html` em tela cheia no PC que está sendo
   transmitido. A medição usa uma única filmagem em câmera lenta de 240 FPS que mostra, no mesmo
   vídeo, o monitor do PC e a tela do aparelho testado. Pressionar uma tecla ou clicar no PC para
   deixar a tela branca e contar os quadros entre o **quadro em que o monitor fica branco** e o
   **quadro em que a tela do aparelho testado fica branca**. Cada medição vale
   `quadros x 1000 / 240` ms (cerca de 4,17 ms por quadro). Fazer 10 medições e registrar a média.
   - **Definição:** a linha de base é "quadro branco do monitor até quadro branco do aparelho",
     medida no mesmo vídeo. Por construção, isso exclui a latência de entrada até o monitor, pois
     o ponto de partida é o próprio quadro branco exibido no host. O instante do toque ou clique
     não entra na conta e não deve ser cronometrado.
   - **Câmera:** ao medir o Poco F6, a câmera não pode ser o próprio Poco F6, pois ele também é um
     aparelho em teste. Use um segundo celular como câmera, ou o Vaio TL10 se ele filmar a 240 FPS.
     Se nenhuma dessas opções existir, o aparelho medido não pode aparecer filmando a si mesmo.
   - **Verificação cruzada (opcional):** quando o contador do `flash.html` estiver visível nos
     dois quadros, a diferença entre as leituras do contador no monitor e no aparelho testado
     pode servir como conferência. Ela não substitui a contagem de quadros.

### Resultados

| Aparelho | Config | Rede (ms) | Decode (ms) | Host (ms) | Ponta a ponta média de 10 (ms) | Observações |
|---|---|---|---|---|---|---|
| Vaio TL10 | 1080p60 H.264 | | | | | |
| Vaio TL10 | 1080p60 HEVC | | | | | |
| Vaio TL10 | Nativa 60 FPS | | | | | |
| Poco F6 | 1080p60 H.264 | | | | | |
| Poco F6 | 1080p60 HEVC | | | | | |
| Poco F6 | Nativa 60 FPS | | | | | |
