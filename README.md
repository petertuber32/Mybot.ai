# BAC BO AI ANALYZER 2.0 (Mybot.ai)

Aplicação Android nativa (Kotlin) do **BAC BO AI ANALYZER**: navegador integrado, **observação contínua da mesa (MediaProjection)** com reconhecimento automático dos dados azuis e vermelhos, memória da partida, motor de interpretação contextual, janela flutuante e importação por OCR.

> **Aviso estatístico importante:** se as rondas forem independentes e justas, **nenhum sistema consegue prever o próximo resultado**. Todas as percentagens apresentadas são estimativas de frequências do histórico com intervalos de confiança — **não são previsões garantidas**. A aplicação distingue sempre três coisas diferentes: **reconhecimento visual** (o que foi lido no ecrã), **interpretação da mesa** (o que está a acontecer) e **estimativa estatística** (frequências passadas com incerteza). A aplicação não aposta automaticamente, não pede credenciais de plataformas e não contorna restrições de segurança do Android nem proteções de captura de terceiros.

---

## 1. Novidades da versão 2.0

| Módulo | Descrição |
| --- | --- |
| **Observação contínua** | Captura de ecrã via MediaProjection **com autorização explícita do Android em cada sessão**. Observa a mesa enquanto o jogo estiver aberto, reconhece os dados azuis e vermelhos, identifica os valores, o resultado da ronda e atualiza o histórico **sem introdução manual**. |
| **Captura por eventos** | Só processa frames quando há movimento (dados a rolar) ou estabilidade (dados parados). Ecrãs parados custam ~0 CPU. Frames são reduzidos para 360 px de largura antes da análise; taxa máxima de análise limitada por cooldown. |
| **Regras do Bac Bo** | Módulo dedicado: 2 dados azuis + 2 vermelhos, somas, vencedor por soma superior, empate em igualdade. Valida sempre se o resultado reconhecido corresponde aos valores dos dados. Distribuição teórica exata (sobre 6⁴ = 1296 resultados): Azul 44,4% · Vermelho 44,4% · Empate 11,3%. |
| **Memória da partida** | Estado persistente da sessão (JSON): número da ronda, dados, somas, resultado, momento de reconhecimento e confiança. **Anti-duplicação**: a mesma imagem repetida não cria rondas novas (assinatura dos dados + porta de movimento + janela temporal). Recuperação de sessão após fecho da app. |
| **Máquina de estados** | WAITING → ROLLING → READING → VALIDATING → SETTLED → ANALYZING → READY. **Leituras ambíguas nunca inventam resultados**: o candidato fica pendente e a janela flutuante pede confirmação AZUL / VERMELHO / EMPATE. |
| **Motor de interpretação** | Combina observação visual, sequências (streaks), alternância, mudanças de tendência entre janelas, transições (Markov), distribuição dos resultados e incerteza — e explica em texto o que está a acontecer na mesa. Usa o prior teórico do Bac Bo (não a uniforme) com suavização Dirichlet e IC Wilson 95%. |
| **Janela flutuante 2.0** | OBSERVAÇÃO ON/OFF, SCREENSHOT manual, indicador do estado da mesa, dados reconhecidos, últimas rondas, interpretação atual, estimativas AZUL/VERMELHO/EMPATE, nível de confiança, avisos de dados insuficientes e botões de confirmação de leituras incertas. |

## 2. Funcionalidades (base 1.0, preservadas)

| Bloco | Funcionalidades |
| --- | --- |
| **Analisador** | Registo manual AZUL / VERMELHO / EMPATE; barras de probabilidade com IC Wilson 95%; teste χ²; qualidade dos dados e aviso de dados insuficientes; explicação estatística textual |
| **Histórico** | Lista de rondas com hora e origem (MANUAL/OCR/VISÃO); edição e eliminação; exportar JSON |
| **Navegador** | WebView com barra de endereço, voltar/avançar/recarregar, barra de progresso, página de erro |
| **OCR** | Importar screenshot → ML Kit (offline) → revisão manual antes de guardar |

## 3. Motor estatístico (matematicamente validado)

- **Prior do Bac Bo**: as estimativas partem da distribuição teórica exata do jogo (Azul/Vermelho 44,4% cada, Empate 11,3%) — calculada por força bruta sobre os 1296 resultados ordenados e coberta por teste unitário com valores em forma fechada.
- **Pós-estimativa**: mistura Dirichlet `(k + W·π)/(n + W)`, W = 8; intervalos **Wilson 95%** sobre a frequência bruta.
- **Justiça**: teste χ² (df = 2, p exato = e^(−χ²/2)); **padrões condicionais** Markov só são reportados com ≥ 20 transições por contexto.
- **Qualidade dos dados**: INSUFICIENTE (< 12) / BAIXA (≥ 12) / MÉDIA (≥ 40) / BOA (≥ 100).
- **Backtest sem fuga de informação**: testes automáticos verificam que a estimativa de cada prefixo do histórico só usa rondas passadas.

## 4. Reconhecimento dos dados (visão)

Pipeline determinístico, no dispositivo, sem rede nem modelos externos:

1. Classificação de píxeis por HSV (azul / vermelho / branco).
2. Componentes conexos (8-conectividade) → candidatos a dados: os 2 maiores blobs plausíveis de cada cor.
3. Contagem de pips brancos dentro de cada dado + validação da disposição típica das faces 1–6.
4. Confiança 0–1 combinando completude e qualidade da disposição; **confiança incompleta é limitada a 0,55** (zona de confirmação).

Limites honestos: a leitura depende de os dados estarem visíveis, com tamanho razoável no ecrã. Se a plataforma mostrar apenas ícones pequenos, use SCREENSHOT manual + confirmação ou introdução manual. O resultado só é guardado quando os 4 valores são lidos e validados pelas regras (ou confirmados pelo utilizador).

## 5. Estrutura do projeto

```
.github/workflows/android-release.yml   # CI/CD: testes + APK assinado + Release
app/
  build.gradle.kts                      # assinatura só via variáveis de ambiente
  src/main/java/com/mybotai/bacboanalyzer/
    models/Models.kt                    # Outcome, Source (MANUAL/OCR/VISION), Round
    stats/StatsEngine.kt                # motor estatístico puro
    vision/Frame.kt                     # frame ARGB puro + utilitários de cor
    vision/DiceImageAnalyzer.kt         # reconhecimento de dados por píxeis (puro)
    vision/FrameChangeDetector.kt       # gate por eventos (motação/estabilidade)
    game/BacBoRules.kt                  # regras + probabilidades teóricas exatas
    game/GameStateMachine.kt            # máquina de estados da observação
    game/MatchMemory.kt                 # memória da partida + anti-duplicação
    game/InterpretationEngine.kt        # interpretação contextual (puro)
    observe/ObservationBus.kt           # barramento de estado para a UI
    observe/ObservationService.kt       # MediaProjection + pipeline (FGS)
    observe/ObservationConsentActivity.kt # diálogo de autorização do Android
    data/RoundFileStore.kt / RoundStore.kt
    ocr/ResultParser.kt / OcrActivity.kt
    update/UpdateChecker.kt
    ui/MainActivity.kt + 4 fragmentos
    overlay/FloatingService.kt          # janela flutuante 2.0
  src/test/java/...                     # testes JUnit (ver secção 6)
gradle.properties                       # APP_VERSION_CODE / APP_VERSION_NAME
```

## 6. Testes automáticos

`gradle testReleaseUnitTest` corre no CI antes de qualquer build; o workflow **falha** se algum teste falhar.

| Suite | Cobre |
| --- | --- |
| `BacBoRulesTest` | somas, vencedores, validação, probabilidades teóricas vs forma fechada (146/1296 empates) |
| `GameStateMachineTest` | caminho completo, sincronização inicial, ambiguidade → confirmação/rejeição, timeouts, limpeza de pendências |
| `MatchMemoryTest` | novas rondas, duplicados (sem movimento / janela temporal), recuperação de sessão do disco, confirmação do utilizador |
| `DiceImageAnalyzerTest` | todas as 36 combinações de cada cor, empates, dado em falta, mesa vazia, posições e tamanhos variados |
| `FrameChangeDetectorTest` | mudança de frames, portão com histerese, cooldown |
| `InterpretationEngineTest` | prior teórico, mistura com histórico, alternância, tendência, backtest sem fuga de informação, disclaimers |
| + suites 1.0 | StatsEngine, RoundFileStore, ResultParser, UpdateChecker |

## 7. Configurar os GitHub Secrets (já feitos no repositório)

O workflow **exige** 4 secrets em *Settings → Secrets and variables → Actions*:

| Secret | Conteúdo |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Ficheiro `.jks` codificado em Base64 |
| `KEYSTORE_PASSWORD` | Palavra-passe do keystore |
| `KEY_ALIAS` | Alias da chave (ex.: `bacbo-release`) |
| `KEY_PASSWORD` | Palavra-passe da chave |

**Regra fundamental:** nunca gerar uma nova chave quando já existe uma válida — todas as versões futuras têm de usar a **mesma chave** e o **mesmo package name** (`com.mybotai.bacboanalyzer`) para permitir atualizações sem desinstalar. O keystore de produção não é guardado no código-fonte (`.gitignore` bloqueia `*.jks`).

### Impressão digital do certificado de assinatura (referência para futuras versões)

```
SHA-256: 30:C2:04:C3:19:74:87:04:1C:23:E6:D7:6D:D1:1F:0B:B6:C2:20:AA:C5:C9:2C:75:5D:EB:24:A1:45:E2:E9:94
Assunto: CN=BAC BO AI ANALYZER, OU=Mybot.ai, O=Mybot.ai, L=Luanda, C=AO
Validade: até 2056 (30 anos)
```

Verificação: `apksigner verify --print-certs bacbo-analyzer-vX.Y.Z.apk`

## 8. Compilar e publicar uma versão

1. Editar `gradle.properties`: `APP_VERSION_CODE` (+1) e `APP_VERSION_NAME`.
2. Push para `main`.
3. O workflow executa: testes unitários → decode do keystore (secrets) → `assembleRelease` assinado → `apksigner verify` → artefacto → **GitHub Release** com o APK `bacbo-analyzer-v<versão>.apk`.

## 9. Instalação no telemóvel

1. Descarregar o APK da última Release: **https://github.com/petertuber32/Mybot.ai/releases/latest**.
2. Abrir o APK; conceder "Instalar apps desconhecidas" para o browser/ficheiro usado.
3. Instalar e abrir. Para a janela flutuante: **Ajustes → Janela flutuante → Conceder permissão** → ativar "Sobrepor outras janelas" → voltar → "Mostrar janela flutuante".
4. Para a **observação contínua**: toque em "OBSERVAÇÃO OFF" (no painel flutuante ou no Analisador) → o Android mostra o diálogo "Iniciar gravação/captura?" → **Iniciar** → abra a plataforma de jogo. O Android pede esta autorização **em cada sessão** (é a proteção nativa do MediaProjection); a observação também pode ser parada pela notificação a qualquer momento.

Requisitos: Android 8.0+ (API 26).

## 10. Verificação de qualidade

| Verificação | Estado |
| --- | --- |
| Compilação (`assembleRelease`) | ✅ executada automaticamente no CI em cada push |
| Testes unitários (novas suites 2.0 + suites 1.0) | ✅ executados no CI; o build falha se algum falhar |
| Assinatura digital do APK | ✅ verificada no CI com `apksigner verify --print-certs` |
| Secrets nos logs | ✅ GitHub mascara os valores; nenhum passo imprime variáveis secretas |
| Reconhecimento em dispositivo real, MediaProjection, overlay sobre outras apps, atualização sobre v1.0.0 | ⚠️ requer teste físico no telemóvel — o CI não executa emulador |

## 11. Compilar localmente

```bash
export ANDROID_HOME=/caminho/android-sdk
gradle testReleaseUnitTest      # testes
gradle assembleRelease          # sem secrets -> APK não assinado
```

## 12. Limitações conhecidas

- **Reconhecimento visual** requer que os dados azuis/vermelhos fiquem visíveis no ecrã com tamanho razoável; temas muito escuros ou jogos em modo mini-janela podem reduzir a confiança — nesses casos o app pede confirmação em vez de inventar resultados.
- **MediaProjection não captura conteúdo protegido**: se a plataforma marcar a janela como segura (FLAG_SECURE), o frame aparece preto e a observação não lê nada — a app respeita essa proteção deliberadamente (não há bypass).
- A autorização de captura é **por sessão** (exigência do Android); o serviço não sobrevive a morte do processo por design (o consentimento é de utilização única).
- Plataformas que mostrem apenas ícones no histórico exigem SCREENSHOT + revisão ou introdução manual.
- Sem backend nem sincronização entre dispositivos (armazenamento local JSON).
- A verificação de atualizações precisa de internet; se falhar, use "Abrir página de versões".
- Se os secrets de assinatura não estiverem configurados, o workflow falha deliberadamente (nunca gera chave automaticamente).
