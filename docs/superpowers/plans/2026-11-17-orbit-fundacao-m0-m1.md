# Duvo — Fundação (M0 + M1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ter o host Duvo (fork do Apollo) compilando no Windows e um app Android próprio que descobre o PC, pareia por QR code e transmite a área de trabalho com baixa latência, com a latência de base do Moonlight medida no TL10 e no Poco F6 para comparação.

**Architecture:** Monorepo `Projeto/` com `host/` (clone do Apollo, branch `duvo`, remote `upstream`) e `android/` (app Kotlin + Compose). O app fala o protocolo HTTP de pareamento/launch em Kotlin (portado do Moonlight Android) e usa `moonlight-common-c` via JNI para o streaming; o vídeo é decodificado com MediaCodec num SurfaceView.

**Tech Stack:** C++/CMake/MSYS2 UCRT64 (host); Kotlin, Jetpack Compose, Gradle KTS, NDK/CMake, OkHttp, ZXing (QR), JUnit5/MockWebServer (app); GoogleTest (host).

**Spec:** `docs/superpowers/specs/2026-11-17-remote-gaming-app-design.md`

**Planos seguintes (escritos depois do M0):** M2 Controle Remoto · M3 Espelhamento no modo remoto · M4 Gaming · M5 Streaming e otimização · M6 Modo inverso · M7 Publicação e atualização automática.

**Fora desta fundação, de propósito:** o broadcast UDP de reserva para descoberta (spec §8.1) e o Serviço Companion entram no plano do M2. Aqui, mDNS + QR + IP manual bastam para provar o caminho completo.

## Global Constraints

- Licença do projeto inteiro: **GPLv3**; cabeçalho de licença em todo arquivo novo.
- Host: **Windows 10/11** apenas. Cliente: **Android 10 (API 29)** mínimo; `targetSdk` = o mais recente estável no SDK instalado.
- Package Android: `app.duvo.remote` (nome provisório; renomear é um replace global).
- Host Duvo **não** se anuncia como `_nvstream._tcp`: serviço mDNS **`_duvo._tcp`**, porta base padrão **40989** (o Sunshine/Apollo usa 47989; os offsets do Sunshine continuam valendo, então o Duvo ocupa 40984–41010).
- Nada de engenharia reversa de Parsec/Remote Mouse. Código de referência só de projetos open source (Apollo, Sunshine, Moonlight Android, Artemis, scrcpy).
- Hardware de referência: **PC RTX 4070 Ti Super**; **alvo principal Vaio TL10 (Unisoc T616)**; secundário Poco F6.
- Toda mudança no host fica isolada em arquivos/módulos novos sempre que possível, para facilitar merges do upstream.
- Texto de interface em **português (Brasil)**.

## Review Focus

1. **QR com IP de outra sub-rede / rede de convidados:** o app deve explicar a causa em linguagem simples, nunca ficar girando para sempre. → teste em Task 10.
2. **PC com vários adaptadores (Ethernet + Wi-Fi + VPN/Hyper-V):** o QR deve listar só IPs utilizáveis e o app deve tentar todos, na ordem, antes de falhar. → testes em Task 5 e Task 7.
3. **Certificado do host trocado depois do pareamento** (reinstalação): o app recusa a conexão e oferece parear de novo, em vez de aceitar em silêncio. → teste em Task 8.
4. **Decoder que não aceita a resolução pedida** (TL10 com 4K): o app cai para a maior configuração suportada e avisa, em vez de tela preta. → teste em Task 9.
5. **Celular sai do Wi-Fi no meio do stream:** a sessão termina com mensagem clara e volta para a lista de PCs, sem crash. → teste em Task 9.

---

## Estrutura de arquivos

```
Projeto/
  .gitignore  LICENSE  README.md
  docs/superpowers/{specs,plans}/   docs/benchmarks/baseline.md
  tools/latency/flash.html                       # teste de latência ponta a ponta
  host/                                          # clone do Apollo, branch duvo
    src/duvo/duvo_config.h / .cpp              # padrões Duvo (porta, serviço mDNS)
    src/duvo/pair_qr.h / .cpp                   # payload do QR de pareamento
    tests/unit/test_duvo_config.cpp
    tests/unit/test_pair_qr.cpp
  android/
    settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml
    app/src/main/java/app/duvo/remote/
      pairing/QrPayload.kt                       # parse do QR
      discovery/HostDiscovery.kt                 # NsdManager + IP manual
      host/HostClient.kt                         # serverinfo / pair / applist / launch
      host/CertStore.kt                          # certificado do cliente + pin do host
      net/ReachabilityDiagnosis.kt               # explica por que não conecta
      stream/NativeBridge.kt                     # JNI moonlight-common-c
      stream/VideoDecoder.kt                     # MediaCodec baixa latência
      stream/DecoderProbe.kt                     # capacidades do decoder (M0)
      ui/...                                     # telas Compose
    app/src/main/cpp/  CMakeLists.txt  duvo_jni.c  moonlight-common-c/ (submódulo)
```

---

## M0 — Base e medição

### Task 1: Repositório e esqueleto

**Files:**
- Create: `.gitignore`, `LICENSE` (texto GPLv3), `README.md`

- [ ] **Step 1:** `git init` em `Projeto/`; `.gitignore` cobre `host/build/`, `android/**/build/`, `.gradle/`, `local.properties`, `*.apk`, `*.keystore`.
- [ ] **Step 2:** README com uma linha de descrição, link para o spec e "status: em desenvolvimento".
- [ ] **Step 3:** Commit: `git add -A && git commit -m "chore: repo skeleton, GPLv3, design spec"`.

### Task 2: Host Apollo compilando e rodando

**Files:**
- Create: `host/` (clone), `docs/dev-setup-host.md`

- [ ] **Step 1:** Instalar MSYS2 e as dependências de build Windows listadas na documentação de build do Apollo/Sunshine (UCRT64: toolchain, cmake, ninja, boost, openssl, opus, curl, miniupnpc, nodejs/npm). **Pedir confirmação ao usuário antes de instalar.**
- [ ] **Step 2:** `git clone --recurse-submodules https://github.com/ClassicOldSong/Apollo host && cd host && git remote rename origin upstream && git checkout -b duvo`.
- [ ] **Step 3:** Build: `cmake -B build -G Ninja -S . && ninja -C build`. Expected: `build/sunshine.exe` gerado sem erros.
- [ ] **Step 4:** Testes do upstream: rodar o executável de testes gerado em `build/tests/`. Expected: todos passam (registrar falhas preexistentes em `docs/dev-setup-host.md`, sem corrigir).
- [ ] **Step 5:** Rodar o host, instalar **Artemis** no TL10 e no Poco F6, parear e transmitir a área de trabalho. Expected: imagem e input funcionando nos dois aparelhos.
- [ ] **Step 6:** Documentar cada comando em `docs/dev-setup-host.md`; commit no repo raiz (o `host/` entra como diretório ignorado por enquanto; vira submódulo quando houver fork no GitHub).

### Task 3: Sonda de decoder (DecoderProbe)

**Files:**
- Create: `android/` (projeto Compose mínimo, `minSdk 29`), `stream/DecoderProbe.kt`, `ui/ProbeScreen.kt`
- Test: `android/app/src/test/java/app/duvo/remote/stream/DecoderProbeTest.kt`

**Interfaces:**
- Produces: `data class DecoderCaps(val mime: String, val codecName: String, val hardware: Boolean, val maxWidth: Int, val maxHeight: Int, val maxFpsAt1080p: Double?, val lowLatency: Boolean)`; `object DecoderProbe { fun probe(list: List<CodecInfoView>): List<DecoderCaps>; fun recommend(caps: List<DecoderCaps>, screenW: Int, screenH: Int): StreamDefaults }`; `data class StreamDefaults(val mime: String, val width: Int, val height: Int, val fps: Int)`. `CodecInfoView` é uma interface fina sobre `MediaCodecInfo` para permitir teste sem aparelho.

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `ignora decoders de software quando existe hardware`()   // só hardware na saída para o mesmo mime
@Test fun `recommend prefere HEVC hardware com lowLatency`()        // HEVC hw lowLatency + H264 hw → mime == "video/hevc"
@Test fun `recommend nunca passa do maximo do decoder`()            // decoder max 1920x1080, tela 2000x1200 → width<=1920 && height<=1080, mantendo proporção
@Test fun `recommend usa 60 fps se 1080p120 nao suportado`()        // maxFpsAt1080p = 60.0 → fps == 60
```
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FAIL (classes inexistentes).
- [ ] **Step 3:** Implementar `DecoderProbe` (usa `MediaCodecList(REGULAR_CODECS)`, `isHardwareAccelerated`, `getVideoCapabilities()`, `isFeatureSupported(FEATURE_LowLatency)` em API 30+) e `ProbeScreen`, que lista as capacidades e tem botão "copiar como texto".
- [ ] **Step 4:** Testes → PASS.
- [ ] **Step 5:** Instalar no TL10 e no Poco F6; colar a saída em `docs/benchmarks/baseline.md`.
- [ ] **Step 6:** Commit `feat(android): decoder probe + project scaffold`.

### Task 4: Linha de base de latência

**Files:**
- Create: `tools/latency/flash.html`, `docs/benchmarks/baseline.md`

- [ ] **Step 1:** `flash.html`: tela preta que fica branca enquanto uma tecla ou o mouse está pressionado, com um contador de milissegundos grande visível.
- [ ] **Step 2:** Protocolo escrito em `baseline.md`: Wi-Fi 5 GHz, mesma posição do roteador, Artemis + Apollo sem modificações; configs **1080p60 H.264, 1080p60 HEVC, resolução nativa 60 FPS** em cada aparelho; anotar o overlay de estatísticas do Artemis (rede, decode, host) por 60 s e a média de 10 medições ponta a ponta. Ponta a ponta = filmar monitor e celular juntos na câmera lenta do Poco F6 (240 FPS) e contar quadros entre o branco do monitor e o branco do celular.
- [ ] **Step 3:** Executar com o usuário e preencher a tabela. **Estes números são a meta a bater nos marcos seguintes.**
- [ ] **Step 4:** Commit `docs: latency baseline (Artemis+Apollo) on TL10 and Poco F6`.

---

## M1 — Esqueleto

### Task 5: Host com identidade Duvo (porta e mDNS) + payload de QR

**Files:**
- Create: `host/src/duvo/duvo_config.{h,cpp}`, `host/src/duvo/pair_qr.{h,cpp}`
- Modify: config padrão de porta (`host/src/config.cpp`) e publicação mDNS (`host/src/platform/windows/publish.cpp`): trocar o literal do serviço e a porta padrão pelas constantes de `duvo_config.h`; registrar os novos `.cpp` no CMake.
- Test: `host/tests/unit/test_duvo_config.cpp`, `host/tests/unit/test_pair_qr.cpp`

**Interfaces:**
- Produces:
  - `namespace duvo { constexpr int kDefaultBasePort = 40989; constexpr const char* kMdnsService = "_duvo._tcp"; }`
  - `std::vector<std::string> duvo::usable_ipv4(const std::vector<Adapter>&)`: só adaptadores ativos, não loopback, não link-local 169.254/16, sem os nomes virtuais conhecidos (Hyper-V "vEthernet", VirtualBox, VMware, WSL); Ethernet antes de Wi-Fi.
  - `std::string duvo::build_pair_uri(const std::vector<std::string>& ips, int port, const std::string& otp, const std::string& cert_sha256_hex, const std::string& host_name)` → `duvo://pair?v=1&h=<ip1>,<ip2>&p=<port>&otp=<pin>&fp=<sha256hex>&n=<nome url-encoded>`

- [ ] **Step 1: Testes que falham**
```cpp
TEST(DuvoConfig, DefaultPort) { EXPECT_EQ(duvo::kDefaultBasePort, 40989); }
TEST(DuvoConfig, ServiceName) { EXPECT_STREQ(duvo::kMdnsService, "_duvo._tcp"); }
TEST(PairQr, UsableIpsFiltersVirtualAndLinkLocal) // entrada: Ethernet 192.168.0.10, vEthernet 172.20.0.1, Wi-Fi 192.168.0.11, 169.254.3.4 → {"192.168.0.10","192.168.0.11"}
TEST(PairQr, BuildsUri) // ips {"192.168.0.10","192.168.0.11"}, 40989, "1234", "ab"*32, "PC Sala" → "duvo://pair?v=1&h=192.168.0.10,192.168.0.11&p=40989&otp=1234&fp=abab…&n=PC%20Sala"
```
- [ ] **Step 2:** Build e testes → FAIL.
- [ ] **Step 3:** Implementar as funções e aplicar as constantes nos dois pontos de modificação.
- [ ] **Step 4:** Testes → PASS; rodar o host e confirmar com `dns-sd -B _duvo._tcp` (ou um scanner mDNS no celular) que o serviço aparece e que as portas 40984–41010 estão em uso.
- [ ] **Step 5:** Commit (no repo `host/`, branch `duvo`): `feat(duvo): own mDNS service, base port 40989, QR pair URI`.

### Task 6: Host gera OTP e mostra o QR

**Files:**
- Modify: pareamento por OTP do Apollo (`host/src/nvhttp.cpp`, procurar `otp`) e a página de pareamento da interface web (`host/src_assets/common/assets/web/`, a página que hoje mostra o OTP)

**Interfaces:**
- Consumes: `duvo::build_pair_uri`, `duvo::usable_ipv4` (Task 5)
- Produces: a interface web e o menu da bandeja ("Parear celular…") mostram um QR com a URI da Task 5. O OTP expira em **5 minutos** e vale para **um** pareamento.

- [ ] **Step 1: Teste que falha:** `TEST(PairQr, OtpSingleUse)`: gerar OTP, consumir uma vez → ok; consumir de novo → rejeitado; consumir após 5 min (relógio injetado) → rejeitado. Se o OTP do Apollo já tiver essa regra, o teste documenta isso e a implementação é só expor a URI.
- [ ] **Step 2:** Testes → FAIL.
- [ ] **Step 3:** Expor um endpoint autenticado (mesma auth da interface web) que devolve `{uri, expires_at}`; a página renderiza o QR (lib JS de QR já disponível via npm no build da interface web).
- [ ] **Step 4:** Testes → PASS; o QR aparece e o Artemis consegue parear usando o OTP digitado (prova de que o OTP vale no protocolo).
- [ ] **Step 5:** Commit `feat(duvo): QR pairing page with single-use OTP`.

### Task 7: App: parser de QR e descoberta

**Files:**
- Create: `pairing/QrPayload.kt`, `discovery/HostDiscovery.kt`, `ui/HostListScreen.kt`, `ui/ScanQrScreen.kt`
- Test: `pairing/QrPayloadTest.kt`, `discovery/HostDiscoveryTest.kt`

**Interfaces:**
- Produces:
  - `data class PairPayload(val ips: List<String>, val port: Int, val otp: String, val certSha256: String, val name: String)`; `object QrPayload { fun parse(uri: String): Result<PairPayload> }`
  - `data class DiscoveredHost(val name: String, val addresses: List<String>, val port: Int, val source: Source)` com `enum class Source { MDNS, QR, MANUAL }`
  - `class HostDiscovery(private val nsd: NsdBrowser)` com `val hosts: StateFlow<List<DiscoveredHost>>`, `fun addManual(ip: String, port: Int = 40989)`, `fun addFromQr(p: PairPayload)`. `NsdBrowser` é a interface sobre `NsdManager` (`_duvo._tcp`).

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `parse URI valida com dois IPs`()        // ips == listOf("192.168.0.10","192.168.0.11"), port == 40989, name == "PC Sala"
@Test fun `rejeita versao desconhecida`()          // v=2 → isFailure
@Test fun `rejeita fingerprint que nao tem 64 hex`() // fp="xyz" → isFailure
@Test fun `rejeita esquema diferente de duvo`()   // "https://..." → isFailure
@Test fun `mesmo host via mDNS e QR vira uma entrada so`() // mesmo nome e IPs sobrepostos → hosts.size == 1, addresses = união
@Test fun `IP manual sem porta usa 40989`()
```
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FAIL.
- [ ] **Step 3:** Implementar. A câmera do QR usa ZXing (`journeyapps:zxing-android-embedded`); a lista de PCs atualiza ao vivo.
- [ ] **Step 4:** Testes → PASS; no TL10 o PC aparece sozinho na lista.
- [ ] **Step 5:** Commit `feat(android): QR payload parser and host discovery`.

### Task 8: App: cliente HTTP do host e pareamento

**Files:**
- Create: `host/HostClient.kt`, `host/CertStore.kt`, `ui/PairingFlow.kt`
- Test: `host/HostClientTest.kt` (MockWebServer)

**Interfaces:**
- Consumes: `PairPayload` (Task 7)
- Produces:
  - `class CertStore(ctx)`: `fun clientCert(): X509Certificate` (gera um par RSA 2048 na primeira execução, guardado no Android Keystore), `fun pinnedHostCert(hostId: String): String?`, `fun pin(hostId: String, sha256: String)`
  - `class HostClient(addr: String, port: Int, certs: CertStore)`: `suspend fun serverInfo(): ServerInfo`, `suspend fun pair(otp: String, expectedCertSha256: String): PairResult`, `suspend fun appList(): List<HostApp>`, `suspend fun launchDesktop(w: Int, h: Int, fps: Int): LaunchResult`
  - `sealed interface PairResult { object Ok; object WrongPin; object CertMismatch; data class Failed(val reason: String) }`
- Referência para portar: `NvHTTP` e `PairingManager` do Moonlight Android (GPLv3) e o fluxo de OTP do Artemis.

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `serverInfo le nome uniqueid e versao`()
@Test fun `pair com cert do host diferente do QR retorna CertMismatch`()  // fingerprint do servidor ≠ expectedCertSha256
@Test fun `conexao com host pareado cujo cert mudou e recusada`()        // pin salvo ≠ cert atual → exceção CertChanged; a UI oferece parear de novo
@Test fun `OTP errado retorna WrongPin`()
```
- [ ] **Step 2:** Testes → FAIL.
- [ ] **Step 3:** Implementar com OkHttp + TLS mútuo (cert do cliente) e pinning pelo SHA-256 do QR; tentar os IPs do payload em ordem, com timeout de **1,5 s** cada.
- [ ] **Step 4:** Testes → PASS; parear o TL10 de verdade lendo o QR da Task 6.
- [ ] **Step 5:** Commit `feat(android): host HTTP client, QR pairing, cert pinning`.

### Task 9: App: streaming da área de trabalho

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/duvo_jni.c`, submódulo `app/src/main/cpp/moonlight-common-c`, `stream/NativeBridge.kt`, `stream/VideoDecoder.kt`, `stream/StreamSession.kt`, `ui/StreamScreen.kt`
- Test: `stream/StreamSessionTest.kt` (unit, com `NativeBridge` falso); `androidTest/.../NativeBridgeLoadTest.kt`

**Interfaces:**
- Consumes: `HostClient.launchDesktop`, `DecoderProbe.recommend` (Tasks 3 e 8)
- Produces:
  - `interface NativeBridge { fun start(cfg: StreamConfig, cb: StreamCallbacks): Int; fun stop(); fun sendMouseMove(dx: Short, dy: Short); fun sendMouseButton(down: Boolean, button: Int); fun sendKeyboard(keyCode: Short, down: Boolean, modifiers: Byte) }`. A função de input é usada já aqui (teste mínimo de toque) e expandida no M2.
  - `data class StreamConfig(val host: String, val width: Int, val height: Int, val fps: Int, val bitrateKbps: Int, val mime: String, val riKey: ByteArray, val riKeyId: Int, val rtspUrl: String)`
  - `class StreamSession(bridge: NativeBridge, decoder: VideoDecoder)` com `val state: StateFlow<StreamState>`; `sealed interface StreamState { Connecting; Streaming(stats: Stats); Ended(reason: EndReason) }`; `enum class EndReason { USER, NETWORK_LOST, HOST_CLOSED, DECODER_UNSUPPORTED }`
- Referência para o JNI: `app/src/main/jni/moonlight-core/` do Moonlight Android.

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `config acima do decoder cai para recommend e avisa`()      // pedido 3840x2160 num decoder max 1920x1080 → start chamado com 1920x1080; state carrega aviso "resolução ajustada"
@Test fun `perda de rede termina com NETWORK_LOST`()                  // callback connectionTerminated(-1) → Ended(NETWORK_LOST)
@Test fun `stop do usuario termina com USER e libera o decoder`()
```
e no androidTest: `NativeBridgeLoadTest`: `System.loadLibrary("duvo")` não lança exceção e a versão do protocolo retornada é > 0.
- [ ] **Step 2:** Testes → FAIL.
- [ ] **Step 3:** Implementar. `VideoDecoder`: `MediaCodec` assíncrono no `Surface` de um `SurfaceView`, `KEY_LOW_LATENCY=1` (API 30+), `KEY_PRIORITY=0`, `KEY_OPERATING_RATE` alto, chaves de baixa latência específicas de fabricante quando o nome do codec indicar (Qualcomm `vendor.qti-ext-dec-low-latency`; para Unisoc, verificar e registrar no M0); apresentação imediata (`releaseOutputBuffer(index, true)`), sem fila extra. Overlay simples com FPS, decode ms e perda de pacotes.
- [ ] **Step 4:** Testes → PASS; transmitir a área de trabalho no TL10 e no Poco F6 com a configuração recomendada.
- [ ] **Step 5:** Repetir o protocolo da Task 4 com o app Duvo e anotar em `baseline.md` lado a lado com o Artemis. **Critério do M1:** ponta a ponta no máximo igual ao Artemis no mesmo aparelho (otimizações para ficar melhor são do M5).
- [ ] **Step 6:** Commit `feat(android): desktop streaming via moonlight-common-c + low-latency MediaCodec`.

### Task 10: App: diagnóstico de conexão

**Files:**
- Create: `net/ReachabilityDiagnosis.kt`, mensagem na `HostListScreen`/`PairingFlow`
- Test: `net/ReachabilityDiagnosisTest.kt`

**Interfaces:**
- Produces: `enum class Diagnosis { OK, DIFFERENT_SUBNET_LIKELY_GUEST, HOST_OFF_OR_FIREWALL, NO_WIFI }`; `fun diagnose(phoneIps: List<String>, phonePrefixLen: Int, hostIps: List<String>, tcpResults: Map<String, Boolean>, onWifi: Boolean): Diagnosis`; `fun Diagnosis.message(): String` (pt-BR)

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `celular em 192_168_2_x e host em 192_168_0_x sem conexao vira guest`() // → DIFFERENT_SUBNET_LIKELY_GUEST; message contém "rede de convidados"
@Test fun `mesma subnet sem resposta vira HOST_OFF_OR_FIREWALL`()               // message contém "firewall" e "Duvo está aberto"
@Test fun `sem wifi vira NO_WIFI`()
@Test fun `qualquer IP respondeu vira OK`()
```
- [ ] **Step 2:** Testes → FAIL.
- [ ] **Step 3:** Implementar; a UI mostra a mensagem em até **5 s** após começar a tentativa, nunca um loading infinito.
- [ ] **Step 4:** Testes → PASS; teste manual com o TL10 na rede de convidados do roteador (se existir).
- [ ] **Step 5:** Commit `feat(android): connection diagnosis with plain-language messages`.

---

## Definição de pronto da Fundação
- Host Duvo compila, passa nos testes, anuncia `_duvo._tcp` na porta 40989 e mostra o QR.
- No TL10 e no Poco F6: o PC aparece sozinho, o pareamento por QR funciona, a área de trabalho é transmitida e a latência é registrada lado a lado com o Artemis, sem ficar pior.
- `docs/benchmarks/baseline.md` preenchido; serve de entrada para o plano do M2 e para as metas do M5.
