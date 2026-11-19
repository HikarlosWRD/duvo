# Duvo — M2: Modo Controle Remoto — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** O celular vira mouse, trackpad e teclado do PC, com painéis de mídia/navegador/apresentação/apps/energia, área de transferência e barra de status, substituindo o Remote Mouse.

**Architecture:** Input pela sessão "só input" do Apollo (canal de input do `moonlight-common-c`, sem decodificar vídeo). O resto por uma API Duvo na porta HTTPS do nvhttp, com o mesmo mTLS do pareamento e checagem de permissão por dispositivo; barra de status por long-poll. Lógica de gestos/teclas/painéis em Kotlin puro e testável.

**Tech Stack:** Kotlin/Compose, JNI (`moonlight-common-c`), OkHttp; C++ (Apollo), GoogleTest, Win32/Core Audio/WinRT (SMTC).

**Spec:** `docs/superpowers/specs/2026-11-18-m2-controle-remoto-design.md` (mãe: `docs/superpowers/specs/2026-11-17-remote-gaming-app-design.md`)

## Global Constraints

- GPLv3; cabeçalho em todo arquivo novo. UI em pt-BR. **Sem trabalho visual** (telas funcionais; design depois) [decidido].
- Android: minSdk 29, package `app.duvo.remote`, JUnit 4. Host: Windows 10/11, mudanças em `host/src/duvo/`, toques em arquivos do Apollo mínimos e marcados `// DUVO:`.
- Porta base 40989 (HTTPS do nvhttp = 40984). mDNS `_duvo._tcp`.
- Todo endpoint novo: só via HTTPS com certificado de cliente pareado **e** checagem do bit de permissão; nunca no servidor HTTP.
- Permissões novas: `duvo_windows = _operation << 5` (bit 21), `duvo_power = _operation << 6` (bit 22) em `crypto::PERM`.
- Preset QR "Controle" = `list|view|launch|_all_inputs|clipboard_set|clipboard_read|duvo_windows|duvo_power`. Migração: perm exatamente igual ao preset antigo `list|view|launch|_all_inputs` (0x07001F00) → preset novo.
- Sessão só input: `appuuid=8CB5C136-DA67-4F99-B4A1-F9CD35005CF4` (`REMOTE_INPUT_UUID`); host Duvo com `enable_input_only_mode = true` por padrão.
- Reconexão: backoff 1, 2, 4, 8, 16, 30, 30… s. Encerrar sessão só input após 5 min em segundo plano. Long-poll: timeout 25 s, 1 pendente por dispositivo.
- Trava de arrasto: segurar > 1000 ms. Imagem do clipboard: PNG, máx. 10 MB. Nome do PC no diálogo de deep link: sem caracteres de controle, máx. 40 caracteres.
- Commits: root em branch `m2` (criada a partir da `main`), host em `duvo`; autor `-c user.name="Duvo Dev" -c user.email="dev@duvo.invalid"`; trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Depois de cada commit no host: regenerar `tools/host/patches/duvo/` (`git -C host format-patch --no-signature --zero-commit adc5c5a0..duvo -o ../tools/host/patches/duvo`) e commitar no root.
- Teste ao vivo obrigatório (TL10 `adb-4AG69W71F-4fzcl1._adb-tls-connect._tcp`; Poco `adb-709a72f0-3paKjH…` se conectado) em toda tarefa que toca input ou API. Host de teste só via `Start-Process … local-test\duvo-test.conf` em `host\build`, parar só o PID próprio; **nunca** tocar no Sunshine do usuário (porta 47989).

## Review Focus

1. **PC reinicia / Wi-Fi cai no meio do controle:** a sessão reconecta sozinha com backoff e o app mostra "Reconectando…", nunca congela nem exige reabrir. → teste em Task 5.
2. **Toque rápido vs. arrasto vs. rolagem com tremor do dedo:** um toque com micro-movimento (< 8 px, < 200 ms) ainda é clique; rolagem não vira clique. → testes em Task 7.
3. **Dispositivo sem a permissão nova chama endpoint:** 403 com mensagem, e o app explica "o PC não deu essa permissão a este celular". → testes em Task 11 e Task 15.
4. **Janela que some entre listar e ativar / janela elevada:** `activate` devolve erro claro, app mostra mensagem, nada trava. → teste em Task 13.
5. **Clipboard grande ou vazio:** imagem > 10 MB recusada com mensagem; clipboard vazio não envia nada nem quebra. → testes em Task 14 e Task 15.

---

## Parte A — Pendências da fundação (antes do teclado)

### Task 1: PCs pareados persistidos e merge por IP/uniqueid

**Files:**
- Create: `android/app/src/main/java/app/duvo/remote/discovery/PairedHostStore.kt`
- Modify: `discovery/HostDiscovery.kt`, `discovery/DiscoveryHolder.kt`, `ui/PairingFlow.kt` (salvar ao parear)
- Test: `android/app/src/test/java/app/duvo/remote/discovery/PairedHostStoreTest.kt`, `HostDiscoveryTest.kt`

**Interfaces:**
- Produces: `data class PairedHost(val uniqueId: String, val name: String, val addresses: List<String>, val port: Int, val lastUsedEpochMs: Long)`; `interface PairedHostStore { fun load(): List<PairedHost>; fun upsert(h: PairedHost); fun remove(uniqueId: String); fun touch(uniqueId: String, nowMs: Long) }` com `FilePairedHostStore(file: File)` (JSON, gravação atômica). `DiscoveredHost` ganha `val uniqueId: String? = null`. `HostDiscovery(nsd, store)` semeia `hosts` com os pareados (`paired = true`) e expõe `fun lastUsed(): PairedHost?`.

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `upsert e load sobrevivem a nova instancia`()          // grava, cria nova FilePairedHostStore no mesmo arquivo → load() igual
@Test fun `arquivo corrompido vira lista vazia sem apagar arquivo`() // conteúdo "{x" → load()==emptyList(); arquivo continua existindo
@Test fun `lastUsed devolve o de maior lastUsedEpochMs`()
@Test fun `hosts semeados com pareados marcados paired`()
@Test fun `merge por uniqueId mesmo com IPs diferentes`()          // pareado uid=A ips [1.1.1.1]; mDNS chega uid=A ips [2.2.2.2] → 1 entrada, ips unidos
@Test fun `mesmo nome IPs disjuntos sem uniqueId nao mescla`()
```
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` → FAIL.
- [ ] **Step 3:** Implementar; o mDNS passa a resolver o `uniqueid` via TXT/serverinfo apenas se já disponível sem rede extra (senão `null`); `PairingFlow` chama `store.upsert` com o uniqueid do `serverInfo()` HTTPS após `PairResult.Ok`.
- [ ] **Step 4:** Testes → PASS. Ao vivo no TL10: parear, fechar o app (force-stop), abrir → PC aparece como pareado sem mDNS (host desligado).
- [ ] **Step 5:** Commit `feat(android): persist paired hosts, merge by address or uniqueid`.

### Task 2: Deep link com nome sanitizado e WifiBinding robusto

**Files:**
- Modify: `pairing/PendingPairing.kt`, `MainActivity.kt` (diálogo), `net/WifiBinding.kt`
- Test: `android/app/src/test/java/app/duvo/remote/pairing/DisplayNameTest.kt`, `net/WifiBindingTest.kt`

**Interfaces:**
- Produces: `fun safeDisplayName(raw: String): String` (remove `\p{Cc}` e `\p{Cf}`, colapsa espaços, corta em 40 com "…"); o diálogo mostra `"Parear com ${safeDisplayName(name)}?\nIPs: ${ips.joinToString()}"`.

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `remove quebras de linha e controles`()   // "PC\n\n\n(1.2.3.4)?" → "PC (1.2.3.4)?" sem \n
@Test fun `corta em 40 com reticencias`()          // 60 chars → length==40, endsWith("…")
@Test fun `acquire nao vaza contador se bind lancar`() // fake bind lança na 1ª vez → depois acquire+release → bind(null) chamado
@Test fun `bind falhando nao marca bound`()         // bind devolve false → lease.network == null
```
- [ ] **Step 2:** FAIL. **Step 3:** Implementar (`WifiBindingCore` incrementa só após bind bem-sucedido; `bind: (N?) -> Boolean`). **Step 4:** PASS. **Step 5:** Commit `fix(android): sanitize deep-link host name, robust Wi-Fi binding`.

### Task 3: Corrida JNI no término da conexão

**Files:**
- Modify: `android/app/src/main/cpp/duvo_jni.c`, `stream/NativeBridge.kt` (MoonlightBridge)
- Test: `android/app/src/androidTest/java/app/duvo/remote/stream/NativeBridgeStressTest.kt`

**Interfaces:**
- Produces: callbacks nativos chamam **métodos estáticos permanentes** de `MoonlightBridge` (global ref da classe criada uma vez em `JNI_OnLoad`, nunca liberada), que repassam para `@Volatile var current: StreamCallbacks?`; `stop()` zera `current` antes de `nativeStop`.

- [ ] **Step 1:** Teste instrumentado: 50 ciclos de `start` contra endereço inexistente (falha rápida) + `stop()` imediato em paralelo com `connectionTerminated` → nenhum crash; callbacks de sessão antiga nunca chegam ao `StreamCallbacks` novo (contador por instância).
- [ ] **Step 2:** Rodar no TL10 (`:app:connectedDebugAndroidTest --tests '*NativeBridgeStressTest*'`) → FAIL ou crash intermitente documentado.
- [ ] **Step 3:** Implementar. **Step 4:** PASS 3 vezes seguidas. **Step 5:** Commit `fix(android): permanent JNI dispatcher, no use-after-release on terminate`.

### Task 4: build.sh detecta série de patches parcial

**Files:**
- Modify: `tools/host/apply-duvo-patches.sh`, `docs/dev-setup-host.md`

- [ ] **Step 1:** Regra: "aplicada" só se o assunto do **último** patch da série existe em `git -C host log --format=%s`; se `host/.git/rebase-apply` existe → erro "git am interrompido; rode git -C host am --abort"; senão aplica.
- [ ] **Step 2:** Verificar em worktree temporário em `adc5c5a0`: aplicar só os 3 primeiros patches à mão → script deve **falhar** com mensagem clara; série completa → "já aplicada"; worktree limpo → aplica tudo. Remover o worktree.
- [ ] **Step 3:** Commit `fix(build): detect partially applied Duvo patch series`.

## Parte B — Sessão só input

### Task 5: Sessão só input com reconexão (PORTÃO)

**Files:**
- Modify (host): `host/src/config.cpp` (`enable_input_only_mode` padrão `true`, `// DUVO:`)
- Create: `android/app/src/main/java/app/duvo/remote/input/InputSession.kt`, `input/Backoff.kt`
- Modify: `host/HostClient.kt` (`launchInputOnly`), `stream/NativeBridge.kt`
- Test: `android/app/src/test/java/app/duvo/remote/input/InputSessionTest.kt`, `BackoffTest.kt`, `host/HostClientTest.kt`

**Interfaces:**
- Consumes: `NativeBridge`, `HostClient`, `WifiBinding` (fundação).
- Produces: `suspend fun HostClient.launchInputOnly(): LaunchResult` (`/launch` com `appuuid=REMOTE_INPUT_UUID`, `mode` = tela do aparelho a 30 fps); `class InputSession(bridge: NativeBridge, client: HostClient, clock: () -> Long, scope: CoroutineScope)` com `val state: StateFlow<InputState>`; `sealed interface InputState { Connecting; Connected; data class Reconnecting(val attempt: Int, val nextInMs: Long); data class Ended(val reason: String) }`; métodos de input repassados ao bridge; `fun onBackgrounded(nowMs)` / `onForegrounded()`; `object Backoff { fun delayMs(attempt: Int): Long }`.

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `backoff segue 1 2 4 8 16 30 30`()             // delayMs(1..7) == [1000,2000,4000,8000,16000,30000,30000]
@Test fun `queda de rede vai para Reconnecting e reconecta`() // fake bridge: connectionTerminated(-1) → Reconnecting(1,1000) → após 1000ms start de novo → Connected
@Test fun `5 min em background encerra`()                 // onBackgrounded(t0); clock = t0+300_000 → Ended
@Test fun `videoSubmit descartado sem decoder`()           // callback videoSubmit retorna DR_OK sem instanciar decoder
@Test fun `launchInputOnly manda appuuid`()                // MockWebServer: query contém appuuid=8CB5C136-DA67-4F99-B4A1-F9CD35005CF4
```
- [ ] **Step 2:** FAIL. **Step 3:** Implementar (host: só o padrão do config; app: `InputSession` usa o mesmo `MoonlightBridge`, `videoSetup` aceita qualquer formato e `videoSubmit` descarta).
- [ ] **Step 4:** PASS. **PORTÃO ao vivo (TL10):** host de teste com o build novo; sessão só input conecta; arrastar no app (tela de teste mínima) move o cursor; ficar 2 min parado sem desconectar; `GET /actions/clipboard?type=text` funciona nessa sessão; matar o host → app mostra Reconnecting → subir host → volta a Connected. **Se falhar:** aplicar fallback (a) vídeo mínimo 320×180 a 1 FPS descartado; se ainda falhar, (b) patch mínimo no submódulo; registrar no relatório qual foi usado.
- [ ] **Step 5:** Commits `feat(host): enable input-only mode by default` (host + patches) e `feat(android): input-only session with auto-reconnect`.

## Parte C — Input no app

### Task 6: Novos eventos de input no bridge

**Files:**
- Modify: `cpp/duvo_jni.c`, `stream/NativeBridge.kt`
- Test: `androidTest/.../NativeBridgeLoadTest.kt` (estender), fakes nos testes JVM

**Interfaces:**
- Produces em `NativeBridge`: `fun sendScroll(amount: Short)` (`LiSendHighResScrollEvent`), `fun sendHScroll(amount: Short)` (`LiSendHighResHScrollEvent`), `fun sendText(text: String)` (`LiSendUtf8TextEvent`, UTF-8), `fun sendKey(vk: Short, down: Boolean, modifiers: Byte)` (`LiSendKeyboardEvent2`, flags 0); constantes `BUTTON_LEFT=1, BUTTON_MIDDLE=2, BUTTON_RIGHT=3`, `MOD_SHIFT=0x01, MOD_CTRL=0x02, MOD_ALT=0x04, MOD_META=0x08`.

- [ ] **Step 1:** Teste instrumentado: cada novo nativo existe e não lança sem sessão (retorno ignorado). **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS + ao vivo: `sendText("ação ç ñ 日本")` aparece no Bloco de Notas do PC; `sendScroll(120)` rola. **Step 5:** Commit `feat(android): scroll, horizontal scroll, UTF-8 text and VK key events`.

### Task 7: Intérprete de gestos do trackpad

**Files:**
- Create: `input/TrackpadGestures.kt`, `input/PointerCurve.kt`
- Test: `input/TrackpadGesturesTest.kt`, `input/PointerCurveTest.kt`

**Interfaces:**
- Produces: `data class TouchSample(val pointerId: Int, val x: Float, val y: Float, val tMs: Long)`; `sealed interface PadAction { data class Move(val dx: Int, val dy: Int); data class Click(val button: Int); data class Press(val button: Int); data class Release(val button: Int); data class Scroll(val v: Int, val h: Int); data class Chord(val vk: List<Short>, val modifiers: Byte) }`; `class TrackpadGestures(cfg: PadConfig)` com `fun down(s: TouchSample): List<PadAction>`, `move(s)`, `up(s)`; `data class PadConfig(val sensitivity: Float = 1.0f, val acceleration: Boolean = true, val leftHanded: Boolean = false, val tapSlopPx: Float = 8f, val tapMaxMs: Long = 200, val dragLockMs: Long = 1000)`; `object PointerCurve { fun apply(dx: Float, dy: Float, dtMs: Long, cfg: PadConfig): Pair<Int, Int> }`.
- Gestos: 1 dedo toque = `Click(LEFT)` (direito se `leftHanded`); 2 dedos toque = `Click(RIGHT)`; 3 dedos toque = `Click(MIDDLE)`; 2 dedos arrastar = `Scroll` (120 por "notch", alta resolução proporcional); segurar 1 dedo parado > `dragLockMs` = `Press(LEFT)` e o próximo toque = `Release(LEFT)`; 3 dedos deslizar ↑ `Chord([VK_TAB], META)`, ↓ `Chord([VK_D], META)`, ← `Chord([VK_TAB], ALT)`, → `Chord([VK_TAB], ALT|SHIFT)`; pinça = `Chord` não — `Scroll` com `MOD_CTRL` mantido via `Press/Release` de Ctrl (emitir `Chord(emptyList(), CTRL)` antes e após) [builder pode modelar como ação própria `Zoom(steps)`].

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `toque com tremor ainda e clique`()          // down, move 5px em 120ms, up → [Click(1)]
@Test fun `arrasto curto nao vira clique`()             // move 30px → contém Move, não contém Click
@Test fun `dois dedos toque clique direito`()
@Test fun `dois dedos arrastar rola sem clicar`()        // 2 dedos ↓ 100px → só Scroll(v<0), nenhum Click
@Test fun `segurar 1s trava e proximo toque solta`()      // down, nada até t=1001 → Press(1); toque depois → Release(1)
@Test fun `tres dedos para cima Win+Tab`()
@Test fun `canhoto troca esquerdo e direito`()
@Test fun `curva sem aceleracao e linear`()              // acceleration=false, sens=1 → apply(10,0,16) == (10,0)
@Test fun `curva com aceleracao cresce com velocidade`() // apply(40,0,8).first > 4 * apply(10,0,8).first... ou > 40
```
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS. **Step 5:** Commit `feat(android): trackpad gesture interpreter`.

### Task 8: Teclado (teclas especiais, grudentas, atalhos)

**Files:**
- Create: `input/Keys.kt` (constantes VK), `input/StickyModifiers.kt`, `input/Shortcuts.kt`
- Test: `input/StickyModifiersTest.kt`, `input/ShortcutsTest.kt`

**Interfaces:**
- Produces: `object Vk { val ESC=0x1B; TAB=0x09; ENTER=0x0D; BACK=0x08; DEL=0x2E; LEFT=0x25; UP=0x26; RIGHT=0x27; DOWN=0x28; HOME=0x24; END=0x23; PGUP=0x21; PGDN=0x22; LWIN=0x5B; SNAPSHOT=0x2C; F1=0x70 (até F12=0x7B, F24=0x87); MEDIA_PLAY_PAUSE=0xB3; MEDIA_NEXT=0xB0; MEDIA_PREV=0xB1; MEDIA_STOP=0xB2; VOL_UP=0xAF; VOL_DOWN=0xAE; VOL_MUTE=0xAD; BROWSER_BACK=0xA6; BROWSER_FWD=0xA7; BROWSER_REFRESH=0xA8; A..Z=0x41..0x5A; N0..N9=0x30..0x39; OEM_PLUS=0xBB; OEM_MINUS=0xBD }`; `class StickyModifiers { fun tap(mod: Byte); fun consumeForKey(): Byte; val state: Map<Byte, StickyState> }` com `enum StickyState { OFF, ONCE, LOCKED }` (1 toque ONCE, 2 toques LOCKED, 3º toque OFF; ONCE zera após uma tecla); `data class Shortcut(val id: String, val label: String, val keys: List<Short>, val modifiers: Byte, val confirm: Boolean = false)`; `object Shortcuts { val all: List<Shortcut> }` com exatamente: copiar Ctrl+C, colar Ctrl+V, recortar Ctrl+X, desfazer Ctrl+Z, refazer Ctrl+Y, selecionar tudo Ctrl+A, Alt+Tab, Win+D, Ctrl+Shift+Esc, Alt+F4 (`confirm=true`), Win+L.
- Emissão de chord: `fun chordEvents(keys: List<Short>, mods: Byte): List<KeyEvent>` → modificadores down, teclas down/up, modificadores up (ordem inversa).

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `ctrl uma vez aplica so na proxima tecla`()
@Test fun `ctrl duas vezes trava ate terceiro toque`()
@Test fun `chord ctrl shift esc ordem correta`()   // [CTRL↓,SHIFT↓,ESC↓,ESC↑,SHIFT↑,CTRL↑]
@Test fun `alt f4 exige confirmacao`()            // Shortcuts.all.first{it.id=="alt_f4"}.confirm
@Test fun `lista de atalhos exata`()               // ids == listOf("copy","paste","cut","undo","redo","select_all","alt_tab","win_d","task_manager","alt_f4","win_l")
```
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS. **Step 5:** Commit `feat(android): special keys, sticky modifiers and shortcuts`.

### Task 9: Painéis de teclas (mídia, navegador, apresentação) e preferências

**Files:**
- Create: `input/Panels.kt`, `input/RemotePrefs.kt`
- Test: `input/PanelsTest.kt`, `input/RemotePrefsTest.kt`

**Interfaces:**
- Produces: `enum class PanelId { KEYBOARD, MEDIA, BROWSER, PRESENTATION, APPS, POWER }`; `data class PanelAction(val id: String, val label: String, val keys: List<Short>, val modifiers: Byte)`; `object Panels { fun actions(p: PanelId): List<PanelAction> }` com exatamente:
  - MEDIA: play_pause, next, prev, stop, vol_up, vol_down, mute (teclas `Vk.MEDIA_*`/`VOL_*`).
  - BROWSER: back (Alt+←), forward (Alt+→), refresh (F5), new_tab (Ctrl+T), close_tab (Ctrl+W), reopen_tab (Ctrl+Shift+T), next_tab (Ctrl+Tab), prev_tab (Ctrl+Shift+Tab), zoom_in (Ctrl+OEM_PLUS), zoom_out (Ctrl+OEM_MINUS), zoom_reset (Ctrl+0), address (Ctrl+L), fullscreen (F11).
  - PRESENTATION: next (PgDn), prev (PgUp), start (F5), start_current (Shift+F5), exit (Esc), black (B).
- `class RemotePrefs(file: File)`: `panelOrder: List<PanelId>`, `hidden: Set<PanelId>`, `padConfig: PadConfig`, `haptics: Boolean = true`, `tapSound: Boolean = false`, `volumeButtonsControlPc: Boolean = true`, `autoClipboardOnResume: Boolean = false`; persistido em JSON.

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `ids exatos de cada painel`()
@Test fun `ordem e ocultos persistem`()            // salvar [MEDIA,KEYBOARD,...] e hidden={BROWSER} → nova instância lê igual
@Test fun `ordem padrao`()                         // KEYBOARD, MEDIA, BROWSER, PRESENTATION, APPS, POWER
```
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS. **Step 5:** Commit `feat(android): media, browser and presentation panels; remote prefs`.

### Task 10: Tela do modo Controle Remoto

**Files:**
- Create: `ui/remote/RemoteScreen.kt`, `ui/remote/RemoteViewModel.kt`, `ui/remote/RemoteSettingsScreen.kt`
- Modify: `MainActivity.kt` (rota `remote/{hostId}`), `ui/HostListScreen.kt` (tocar num PC pareado → modo Controle Remoto; Stream continua acessível)
- Test: `ui/remote/RemoteViewModelTest.kt`

**Interfaces:**
- Consumes: `InputSession` (T5), `NativeBridge` novos métodos (T6), `TrackpadGestures` (T7), `StickyModifiers`/`Shortcuts`/`chordEvents` (T8), `Panels`/`RemotePrefs` (T9).
- Produces: `RemoteViewModel(session, prefs)` com `fun onTouch(event)`, `fun onText(text)`, `fun onKey(vk)`, `fun runAction(a: PanelAction)`, `fun runShortcut(s: Shortcut)` (se `confirm`, emite `UiEvent.Confirm`), `val ui: StateFlow<RemoteUi>` (estado da sessão, painel ativo, barra de status — vazio até T15).
- Botões de volume físicos: `onKeyDown(KEYCODE_VOLUME_UP/DOWN)` → `Vk.VOL_UP/DOWN` quando `volumeButtonsControlPc` e a tela está ativa (consome o evento). Vibração (`HapticFeedbackConstants.VIRTUAL_KEY`) e som conforme prefs.

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `atalho com confirmacao nao envia antes de confirmar`()
@Test fun `texto vai como sendText`()
@Test fun `tecla com ctrl grudento manda chord`()
@Test fun `estado Reconnecting aparece na ui`()
```
- [ ] **Step 2:** FAIL. **Step 3:** Implementar (Compose funcional: área de trackpad em `pointerInput`, botões esquerdo/direito embaixo — invertidos se canhoto — abas dos painéis visíveis na ordem de `RemotePrefs`, teclado com campo oculto + barra de teclas especiais + modificadores grudentos, tela de configurações com sensibilidade, aceleração, canhoto, vibração/som, volume pelo celular, clipboard automático e **"Editar painéis"** — mover para cima/baixo e mostrar/esconder, gravando em `RemotePrefs`).
- [ ] **Step 4:** PASS. Ao vivo (TL10): mover, clicar, rolar, arrastar com trava, digitar "Olá ç" no Bloco de Notas, Ctrl+A grudento, mídia pausa um vídeo, volume físico muda o volume do PC.
- [ ] **Step 5:** Commit `feat(android): Remote Control mode screen`.

## Parte D — API Duvo no host

### Task 11: Permissões novas, preset "Controle" e migração

**Files:**
- Modify: `host/src/crypto.h` (bits `// DUVO:`), `host/src/duvo/client_perm.{h,cpp}`, carregamento do estado de clientes no `nvhttp.cpp` (migração, `// DUVO:`), `src_assets/common/assets/web/pin.html` (grupos de permissão) e `public/assets/locale/{en,pt_BR}.json`
- Test: `host/tests/unit/test_client_perm.cpp`

**Interfaces:**
- Produces: `crypto::PERM::duvo_windows`, `crypto::PERM::duvo_power`; `duvo::kQrPairedPerm` = preset "Controle" (Global Constraints); `crypto::PERM duvo::migrate_perm(crypto::PERM stored)`; `bool duvo::has_perm(const crypto::named_cert_t*, crypto::PERM)`.

- [ ] **Step 1: Testes que falham**
```cpp
TEST(ClientPerm, ControlePresetBits)        // kQrPairedPerm inclui duvo_windows, duvo_power, clipboard_set, clipboard_read; exclui file_upload, file_dwnload, server_cmd
TEST(ClientPerm, MigratesOldQrPreset)       // migrate_perm(0x07001F00) == kQrPairedPerm
TEST(ClientPerm, KeepsOtherPermsAsIs)       // migrate_perm(PERM::_default) == PERM::_default; migrate_perm(PERM::_all) == PERM::_all
TEST(ClientPerm, NewBitsDoNotCollide)       // duvo_windows == 1u<<21, duvo_power == 1u<<22
```
- [ ] **Step 2:** RED. **Step 3:** Implementar (migração aplicada uma vez ao carregar o estado; gravar de volta). **Step 4:** GREEN + suíte sem falhas novas; o editor de permissões do web UI mostra "Janelas e apps" e "Energia" (pt-BR). **Step 5:** Commit host `feat(duvo): window/power permissions, Controle preset, migration` + patches no root.

### Task 12: Status e long-poll

**Files:**
- Create: `host/src/duvo/status_tracker.{h,cpp}`, `host/src/duvo/platform_status_win.cpp`, `host/src/duvo/api_routes.{h,cpp}`
- Modify: `host/src/nvhttp.cpp` (uma linha `duvo::register_api(https_server)` com `// DUVO:`), CMake
- Test: `host/tests/unit/test_status_tracker.cpp`

**Interfaces:**
- Produces: `struct duvo::Status { uint64_t seq; std::string active_app, window_title, media_title, media_artist, media_app; bool media_playing; int volume; bool muted; }`; `class duvo::StatusSource { virtual Status read() = 0; }` (Windows: `GetForegroundWindow` + nome do processo, Core Audio, SMTC); `class duvo::StatusTracker(std::unique_ptr<StatusSource>, clock)` com `Status current()`, `void poll_once()` (incrementa `seq` só se algo mudou), `void wait_change(uint64_t since, std::chrono::milliseconds timeout, std::function<void(Status)> done)` — guarda o callback; no máximo 1 pendente por `client_uuid` (o novo substitui e responde o antigo com o status atual); rotas `GET /duvo/status` e `GET /duvo/events?since=&timeout=` (timeout limitado a 25 s), JSON, exigem `view`; resposta assíncrona (guardar `response`, escrever depois).
- Se SMTC não funcionar no contexto do processo (SYSTEM), `media_*` vazios e item registrado no relatório (o resto segue).

- [ ] **Step 1: Testes que falham**
```cpp
TEST(StatusTracker, SeqOnlyIncrementsOnChange)
TEST(StatusTracker, WaitAnswersOnChange)         // fake source muda título → callback com seq+1
TEST(StatusTracker, WaitTimesOutWithCurrent)      // sem mudança, clock avança 25s → callback com mesmo seq
TEST(StatusTracker, NewWaitReplacesOldForSameClient) // 2º wait do mesmo uuid responde o 1º imediatamente
TEST(DuvoApi, StatusRequiresView)                // cliente sem view → 403
```
- [ ] **Step 2:** RED. **Step 3:** Implementar (thread de poll a cada 500 ms). **Step 4:** GREEN; ao vivo com `curl` usando o certificado de cliente do teste live do app — ou pelo app na Task 15 — trocar de janela no PC muda `window_title`. **Step 5:** Commits host + patches.

### Task 13: Janelas, apps, área de trabalho, energia e volume

**Files:**
- Create: `host/src/duvo/windows_api.{h,cpp}`, `host/src/duvo/platform_windows_win.cpp`, `host/src/duvo/power_api.{h,cpp}`
- Modify: `host/src/duvo/api_routes.cpp`
- Test: `host/tests/unit/test_windows_api.cpp`, `test_power_api.cpp`

**Interfaces:**
- Produces: `class duvo::WindowPlatform { virtual std::vector<WindowInfo> list(); virtual ActivateResult activate(uint64_t id); virtual bool show_desktop(); virtual std::vector<AppEntry> start_menu(); virtual bool launch(const std::string& id); virtual bool set_volume(int level); virtual bool set_mute(bool); }`; `struct WindowInfo { uint64_t id; std::string title, process; }`; `enum class ActivateResult { ok, not_found, denied }`; `class duvo::PowerPlatform { virtual bool run(PowerAction); }` com `enum class PowerAction { shutdown, restart, sleep, logoff, lock }`.
- Rotas (Global Constraints: HTTPS + permissão): `GET /duvo/windows`, `POST /duvo/windows/activate?id=` (404 `not_found`, 409 `denied`), `POST /duvo/desktop`, `GET /duvo/apps`, `POST /duvo/apps/launch?id=` → `duvo_windows`; `POST /duvo/power?action=` → `duvo_power`, ação inválida 400, log `"[Duvo] power <acao> pedido por <nome do dispositivo>"`; `POST /duvo/volume?level=0..100|mute=0|1` → `input_kbd`.
- Lista de janelas: visíveis, com título, não "tool windows", sem a própria janela do host. Apps: atalhos `.lnk` do Menu Iniciar do usuário e de todos; lançar como o usuário logado (mecanismo de processo do Apollo; utilitário auxiliar se necessário).

- [ ] **Step 1: Testes que falham** (fakes de plataforma)
```cpp
TEST(WindowsApi, ActivateMissingIs404)
TEST(WindowsApi, ActivateElevatedIs409)
TEST(WindowsApi, RequiresDuvoWindowsPerm)    // sem bit → 403
TEST(PowerApi, InvalidActionIs400)
TEST(PowerApi, RequiresDuvoPowerPerm)
TEST(PowerApi, LogsDeviceName)
TEST(VolumeApi, ClampsLevel)                  // level=150 → set_volume(100)
```
- [ ] **Step 2:** RED. **Step 3:** Implementar. **Step 4:** GREEN; ao vivo: listar janelas, ativar o Bloco de Notas minimizado, mostrar área de trabalho, abrir a Calculadora pelo Menu Iniciar, `lock` (bloqueia o PC — avisar o usuário antes; **não** testar shutdown/restart/logoff ao vivo, só com fake). **Step 5:** Commits host + patches.

### Task 14: Clipboard de imagem

**Files:**
- Create: `host/src/duvo/clipboard_image.{h,cpp}` (+ `_win.cpp`)
- Modify: `host/src/duvo/api_routes.cpp`
- Test: `host/tests/unit/test_clipboard_image.cpp`

**Interfaces:**
- Produces: `GET /duvo/clipboard?type=image` → `{"png":"<base64>"}` ou 204 se não houver imagem (`clipboard_read`); `POST /duvo/clipboard?type=image` corpo `{"png":"<base64>"}` → grava no clipboard do Windows (`clipboard_set`); > 10 MB (decodificado) → 413.

- [ ] **Step 1:** `TEST(ClipboardImage, RejectsOver10MB)`, `TEST(ClipboardImage, EmptyIs204)`, `TEST(ClipboardImage, RoundTripPng)` (fake), `TEST(ClipboardImage, PermsChecked)`. **Step 2:** RED. **Step 3:** Implementar (CF_DIB/PNG). **Step 4:** GREEN; ao vivo: copiar print no PC → app recebe. **Step 5:** Commits host + patches.

## Parte E — Integração no app

### Task 15: Cliente da API, barra de status, Apps, Energia e clipboard

**Files:**
- Create: `host/DuvoApi.kt`, `ui/remote/AppsPanel.kt`, `ui/remote/PowerPanel.kt`, `ui/remote/StatusBar.kt`, `input/ClipboardSync.kt`
- Modify: `ui/remote/RemoteViewModel.kt`, `ui/remote/RemoteScreen.kt`
- Test: `host/DuvoApiTest.kt` (MockWebServer TLS), `ui/remote/RemoteViewModelTest.kt`, `input/ClipboardSyncTest.kt`

**Interfaces:**
- Consumes: endpoints das Tasks 12–14 e os de clipboard texto do Apollo (`/actions/clipboard`).
- Produces: `class DuvoApi(client: HostClient)` com `suspend fun status(): DuvoStatus`, `suspend fun events(since: Long): DuvoStatus`, `windows()`, `activate(id)`, `showDesktop()`, `apps()`, `launch(id)`, `power(a: PowerAction)`, `setVolume(level)`, `setMute(m)`, `getText(): String?`, `setText(s)`, `getImage(): ByteArray?`, `setImage(png)`; erros: `ApiForbidden` (403) → mensagem "O PC não deu essa permissão a este celular."; `ApiNotFound`, `ApiDenied` → "Não foi possível trocar para essa janela."
- `HostClient` ganha um método genérico `internal suspend fun duvoCall(method: String, path: String, query, body: ByteArray?): Pair<Int, ByteArray>` sobre o mesmo HTTPS fixado (nunca HTTP).
- Barra de status: loop de long-poll enquanto `InputState.Connected`; para ao sair.
- Energia: diálogo de confirmação obrigatório por ação; Alt+F4 idem (T8).
- Clipboard: botões "Enviar para o PC" / "Pegar do PC" (texto; imagem se o clipboard do celular tiver imagem); `autoClipboardOnResume` envia o texto do celular ao voltar ao app.

- [ ] **Step 1: Testes que falham**
```kotlin
@Test fun `403 vira mensagem de permissao`()
@Test fun `activate 409 vira mensagem de janela`()
@Test fun `energia nao chama api sem confirmar`()
@Test fun `clipboard vazio nao envia`()
@Test fun `imagem acima de 10MB recusada no app`()
@Test fun `long-poll para quando sessao sai de Connected`()
@Test fun `duvoCall nunca usa http`()   // URL scheme == "https"
```
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS; ao vivo: barra de status mostra janela/música; Apps lista e troca janela; abrir Calculadora; Energia → Bloquear (com confirmação; avisar o usuário); texto e imagem nos dois sentidos.
- [ ] **Step 5:** Commit `feat(android): Duvo API client, status bar, apps, power and clipboard`.

### Task 16: Conexão automática e sessão compartilhada

**Files:**
- Modify: `MainActivity.kt`, `ui/HostListScreen.kt`, `ui/remote/RemoteViewModel.kt`
- Test: `ui/AutoConnectTest.kt`

**Interfaces:**
- Consumes: `HostDiscovery.lastUsed()` (T1), `InputSession` (T5), diagnóstico (fundação).
- Produces: ao abrir o app (não em deep link), se `lastUsed()` existe: tenta `launchInputOnly` (com `raceWithDiagnosis`); sucesso → rota `remote/{id}` direto; falha → lista com a mensagem do diagnóstico. Se o host indica sessão de outro dispositivo ativa, a UI mostra "PC em uso por outro dispositivo — controle compartilhado".

- [ ] **Step 1:** Testes: `sem pareados fica na lista`, `com pareado que responde vai para remote`, `pareado que nao responde mostra diagnostico na lista`, `deep link nao dispara autoconnect`. **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS; ao vivo: abrir o app no TL10 com host ligado → entra direto no controle. **Step 5:** Commit `feat(android): auto-connect to last PC`.

### Task 17: Medição de latência do input

**Files:**
- Create: `host/src/duvo/input_probe.{h,cpp}`; `android/.../input/LatencyProbe.kt`; seção em `docs/benchmarks/baseline.md`
- Modify: hook mínimo no recebimento de teclado do host (`// DUVO:`), rota `GET /duvo/debug/inputlog` e `GET /duvo/debug/time` (só com `duvo_debug = enabled` no config; desligado por padrão)
- Test: `host/tests/unit/test_input_probe.cpp`, `input/LatencyProbeTest.kt`

**Interfaces:**
- Produces: host registra num buffer circular (256) o instante (steady clock, µs) de cada tecla `Vk.F24` recebida; app: alinha relógio com 10 trocas de `/duvo/debug/time` (menor RTT vence), envia 100 F24 com 50 ms de intervalo, lê o log, calcula p50/p95 de (injeção host − envio app − offset).

- [ ] **Step 1:** Testes: buffer circular mantém os últimos 256; cálculo de percentis com dados conhecidos; offset escolhe a amostra de menor RTT; rotas 404 quando `duvo_debug` desligado. **Step 2:** RED. **Step 3:** Implementar (botão escondido na tela de configurações "Medir latência"). **Step 4:** GREEN; medir no TL10 e no Poco (5 GHz) e registrar p50/p95 em `baseline.md`. **Step 5:** Commits.

### Task 18 (opcional, último): Mouse por giroscópio

Só se as Tasks 1–17 estiverem completas e revisadas.
- Create `input/GyroPointer.kt` (`fun onGyro(rateX: Float, rateY: Float, dtMs: Long): Pair<Int, Int>?` com zona morta 0,02 rad/s e ganho por `PadConfig.sensitivity`), toggle "Mouse no ar" no modo Controle Remoto (segurar botão para mover).
- Testes: zona morta não move; rotação constante move proporcional. Ao vivo no TL10. Commit `feat(android): optional gyro air mouse`.

---

## Definição de pronto do M2
- Tasks 1–17 concluídas e revisadas; ao vivo no TL10 (e Poco se disponível): controle completo sem espelhamento, todos os painéis, energia com confirmação, clipboard nos dois sentidos, barra de status, reconexão automática, abrir o app e já controlar.
- Latência de input medida e registrada (meta p95 ≤ 5 ms; não é portão no M2).
- Série de patches do host atualizada em `tools/host/patches/duvo/`.
