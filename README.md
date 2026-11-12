# BAC BO AI ANALYZER (Mybot.ai)

Aplicação Android nativa (Kotlin) do **BAC BO AI ANALYZER**: navegador integrado, janela flutuante, importação de resultados por OCR e motor estatístico local, matematicamente validado.

> **Aviso estatístico importante:** se as rondas forem independentes e justas, **nenhum sistema consegue prever o próximo resultado**. Todas as percentagens apresentadas são estimativas de frequências do histórico com intervalos de confiança — **não são previsões garantidas**. A aplicação não aposta automaticamente, não pede credenciais de plataformas e não contorna restrições de segurança do Android ou de terceiros.

---

## 1. Funcionalidades

| Bloco | Funcionalidades |
| --- | --- |
| **Analisador** | Registo manual AZUL / VERMELHO / EMPATE; botão ANALISAR PRÓXIMA RONDA; barras de probabilidade com IC Wilson 95%; teste χ² de justiça; padrões condicionais (Markov) com amostra mínima; qualidade dos dados e aviso de dados insuficientes; explicação estatística textual gerada localmente |
| **Histórico** | Lista de rondas com hora e origem (MANUAL/OCR); edição do resultado de qualquer ronda; apagar ronda; desfazer último; exportar JSON; limpar tudo |
| **Navegador** | WebView com barra de endereço, voltar/avançar/recarregar/início, barra de progresso, página de erro com "tentar novamente", URL da plataforma configurável, JavaScript ativado com restantes flags de segurança reforçadas |
| **OCR** | Importar screenshot → ML Kit (no dispositivo, offline) → lista reconhecida → **revisão e correção manual** antes de guardar |
| **Janela flutuante** | Bolha arrastável sobre outras apps; painel compacto com últimas rondas e probabilidades; redimensionável (S/M/L); minimizar/fechar; permissão SYSTEM_ALERT_WINDOW pedida de forma explícita com instruções |
| **Ajustes** | Estado/permissão da janela flutuante; URL da plataforma; verificação de atualizações (GitHub Releases); exportar/apagar dados; sobre + versão |

## 2. Motor estatístico (matematicamente validado)

- **Frequências**: suavização de Laplace (add-one) — nunca mostra 0% ou 100% com amostras pequenas.
- **Incerteza**: intervalo de confiança **Wilson 95%** por resultado.
- **Justiça**: teste χ² de ajustamento contra a uniforme (df = 2, p exato = e^(−χ²/2)).
- **Padrões condicionais**: transições de Markov com amostra mínima de 20 por contexto; abaixo disso o app diz que não é fiável.
- **Qualidade dos dados**: INSUFICIENTE (< 12 rondas) / BAIXA (≥ 12) / MÉDIA (≥ 40) / BOA (≥ 100). Com dados insuficientes o app mostra 33,3% uniforme + aviso grande.
- **Testes automáticos**: 29 testes JUnit cobrem fórmulas, limites, persistência em ficheiro, parser OCR e comparação de versões (`app/src/test/...`).

## 3. Estrutura do projeto

```
.github/workflows/android-release.yml   # CI/CD: testes + APK assinado + Release
app/
  build.gradle.kts                      # assinatura só via variáveis de ambiente
  src/main/java/com/mybotai/bacboanalyzer/
    models/Models.kt                    # Outcome, Source, Round
    stats/StatsEngine.kt                # motor estatístico puro
    data/RoundFileStore.kt              # persistência JSON (pura, testável)
    data/RoundStore.kt                  # singleton da app
    ocr/ResultParser.kt                 # parser de texto OCR (puro)
    ocr/OcrActivity.kt                  # screenshot → ML Kit → revisão
    update/UpdateChecker.kt             # GitHub Releases + comparação de versões
    ui/MainActivity.kt                  # navegação (4 blocos)
    ui/AnalyzerFragment.kt / HistoryFragment.kt / BrowserFragment.kt / SettingsFragment.kt
    overlay/FloatingService.kt          # janela flutuante (TYPE_APPLICATION_OVERLAY)
  src/test/java/...                     # 29 testes unitários
gradle.properties                       # APP_VERSION_CODE / APP_VERSION_NAME
```

## 4. Configurar os GitHub Secrets (já feitos no repositório)

O workflow **exige** 4 secrets em *Settings → Secrets and variables → Actions*:

| Secret | Conteúdo |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Ficheiro `.jks` codificado em Base64 (`base64 -w0 bacbo-release-key.jks`) |
| `KEYSTORE_PASSWORD` | Palavra-passe do keystore |
| `KEY_ALIAS` | Alias da chave (ex.: `bacbo-release`) |
| `KEY_PASSWORD` | Palavra-passe da chave |

**Regra fundamental:** nunca gerar uma nova chave quando já existe uma válida — todas as versões futuras têm de usar a **mesma chave** e o **mesmo package name** (`com.mybotai.bacboanalyzer`) para permitir atualizações sem desinstalar. O keystore de produção não é guardado no código-fonte (`.gitignore` bloqueia `*.jks`). Guarda sempre uma cópia local do `.jks` + passwords num local seguro: sem essa chave, futuras atualizações teriam de ser instaladas após desinstalar a app.

Para criar um keystore novo (apenas se ainda não existir nenhum):

```bash
keytool -genkeypair -v -keystore bacbo-release-key.jks \
  -alias bacbo-release -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=BAC BO AI ANALYZER, OU=Mybot.ai, O=Mybot.ai, C=AO"
base64 -w0 bacbo-release-key.jks   # colar em ANDROID_KEYSTORE_BASE64
```

## 5. Compilar e publicar uma versão

1. Editar `gradle.properties`: `APP_VERSION_CODE` (+1) e `APP_VERSION_NAME` (ex.: `1.0.1`).
2. Fazer push para `main`.
3. O workflow executa: testes unitários → decode do keystore (secrets) → `assembleRelease` assinado → `apksigner verify` → artefacto → **GitHub Release** com o APK `bacbo-analyzer-v<versão>.apk`.

O botão "Verificar atualizações" na app compara o tag `v<versão>` mais recente do GitHub Releases com a versão instalada e abre a página de descarga.

## 6. Instalação no telemóvel

1. Descarregar o APK da última Release: **https://github.com/petertuber32/Mybot.ai/releases/latest** (ficheiro `bacbo-analyzer-v<versão>.apk`).
2. Abrir o APK; o Android pedirá permissão "Instalar apps desconhecidas" para o browser/ficheiro usado — conceder.
3. Instalar e abrir. Para a janela flutuante: **Ajustes → Janela flutuante → Conceder permissão** → ativar "Sobrepor outras janelas" nas definições do Android → voltar → "Mostrar janela flutuante".

Requisitos: Android 8.0+ (API 26).

## 6.1. Verificação de qualidade (o que está validado vs. o que precisa de teste físico)

| Verificação | Estado |
| --- | --- |
| Compilação (`assembleRelease`) | ✅ executada automaticamente no CI em cada push |
| Testes unitários do motor estatístico, parser OCR, persistência e versões | ✅ executados no CI; o build falha se algum falhar |
| Assinatura digital do APK | ✅ verificada no CI com `apksigner verify --print-certs` |
| Secrets nos logs | ✅ o GitHub mascara automaticamente os valores; nenhum passo imprime variáveis secretas |
| Instalação em dispositivo real, janela flutuante sobre outras apps, navegação WebView, atualização sobre versão anterior | ⚠️ requer teste físico no telemóvel (o CI não executa emulador) — seguir os passos da secção 6 e reportar problemas |

## 7. Compilar localmente

```bash
# Requisitos: JDK 17+, Android SDK (platform 34, build-tools 34)
export ANDROID_HOME=/caminho/android-sdk
gradle assembleRelease          # sem secrets no ambiente -> APK não assinado
gradle testReleaseUnitTest      # testes unitários
```

Com os 4 `KEYSTORE_*` definidos no ambiente, o `assembleRelease` produz o APK já assinado.

## 8. Limitações conhecidas

- **OCR**: funciona quando os resultados aparecem como **texto** no screenshot (ex.: "AZUL", "VERMELHO", "EMPATE"). Se a plataforma mostrar apenas ícones/dados, o OCR não consegue ler — nesse caso use a introdução manual. Todos os resultados OCR passam sempre por revisão manual antes de serem guardados.
- A verificação de instalação, da janela flutuante e do navegador em dispositivos reais não é executável no CI — testada em código e por compilação; validar no telemóvel físico após instalar.
- A app não tem backend nem sincronização entre dispositivos (armazenamento local JSON no dispositivo).
- A verificação de atualizações precisa de internet; se falhar, use "Abrir página de versões".
- Se os secrets de assinatura não estiverem configurados, o workflow falha deliberadamente com mensagem clara (nunca gera chave automaticamente).
- O OCR depende de os resultados estarem visíveis como texto no screenshot; plataformas que mostrem apenas dados/ícones exigem introdução manual.
- O Android pode terminar o serviço em segundo plano em condições extremas de memória; a janela flutuante usa um serviço em primeiro plano (foreground service) para minimizar este risco.
