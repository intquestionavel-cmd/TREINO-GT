# Treino GT — telemetria e análise de pilotagem para GT Rivals (Android)

App Android nativo (Kotlin + Compose) que coleta dados do GT Rivals por captura de tela autorizada (MediaProjection),
lê velocidade/marcha por OCR on-device, estima o estado dos pedais por diferença de pixels, mede o tempo com um
relógio próprio (marco zero) e mostra um relatório pós-sessão. Tudo local; nenhum dado sai do aparelho.

## STATUS — leia primeiro

**Este código foi escrito num ambiente SEM Android SDK, Gradle, Kotlin ou rede. Ele NUNCA foi compilado, instalado
ou testado em aparelho, e nenhum teste foi executado.** Só foi feita revisão manual e checagem de balanceamento de
chaves. Espere corrigir alguns erros de compilação no primeiro build. Nada abaixo deve ser lido como "funciona".

| Item | Implementado em código | Compilado | Testado |
|---|---|---|---|
| Room, sessões, histórico, renomear/notas/excluir | sim | não | testes escritos, não executados |
| Marco zero (monotônico), pausa/continuar, encerrar | sim | não | testes JVM escritos, não executados |
| Passagens manuais, referência, intervalos, melhor volta | sim | não | testes JVM escritos, não executados |
| Recuperação após processo morto (INTERRUPTED) | sim | não | teste JVM + instrumentado escritos |
| Exportação CSV (amostras, passagens) e JSON | sim | não | testes JVM escritos |
| MediaProjection + serviço em primeiro plano | sim | não | **nunca rodou** |
| OCR de velocidade e marcha (ML Kit) | sim | não | **nunca rodou; precisão desconhecida** |
| Acelerador/freio por pixel-diff | sim (binário) | não | só com imagens sintéticas (escritas) |
| Calibração visual de ROIs sobre o jogo | sim | não | **nunca rodou** |
| Janela flutuante | sim | não | **nunca rodou; compatibilidade com o jogo/HyperOS desconhecida** |
| Relatório: resumo, gráficos velocidade/pedais/marcha, passagens | sim | não | cálculo com testes escritos; UI nunca rodou |
| Zoom nos gráficos | sim (slider horizontal) | não | não |
| Seleção de trecho no gráfico | **NÃO implementado** | | |
| Comparação de voltas/sessões, motor de recomendações (Fase 6) | **NÃO implementado** | | |
| Detecção automática de volta, leitura do minimap | **NÃO implementado** (ROI do minimap existe, não é lida) | | |
| Intensidade (%) de acelerador/freio | **NÃO** — só estado binário; campo de intensidade fica nulo | | |
| Fase 7 (teste em aparelho, sessão longa, APK release) | **NÃO feita** | | |

Não foi fornecida a captura de referência citada no documento. As ROIs iniciais são **palpites** (`calibrated=false`):
**o pipeline não grava nenhuma leitura até você calibrar** no aparelho.

## Como compilar

Requisitos: Android Studio Koala (2024.1) ou superior, JDK 17+, Android SDK 34, rede para baixar dependências.

1. Abra a pasta `TreinoGT` no Android Studio e aguarde o sync (ele gera o `gradle-wrapper.jar`, que não está incluído).
   Sem Android Studio: instale Gradle 8.7 e rode `gradle wrapper` na raiz.
2. `./gradlew testDebugUnitTest` — testes JVM (relógio, voltas, parsers, motor, relatório, exportação).
3. `./gradlew assembleDebug` — APK em `app/build/outputs/apk/debug/app-debug.apk`.
4. No POCO X6 Pro: ative Depuração USB e `./gradlew installDebug` (ou copie o APK e instale, permitindo fontes desconhecidas).
5. `./gradlew connectedDebugAndroidTest` — testes instrumentados (Room real, OCR sintético, grade de pedais); exige aparelho/emulador.
6. Release: `./gradlew assembleRelease` gera APK **não assinado**; assine com `apksigner` e um keystore seu antes de instalar.

## Obter o APK sem instalar nada (GitHub Actions)

1. Crie um repositório no GitHub e envie o **conteúdo** da pasta `TreinoGT` na raiz (inclui `.github/workflows/build-apk.yml`).
2. Aba **Actions** → "Build APK (Treino GT)" → **Run workflow** (ou o push já dispara).
3. Ao terminar, baixe o artefato **TreinoGT-debug-apk** (`app-debug.apk`, assinado com a chave de debug) e instale no aparelho.
4. Se o job falhar, baixe o log: os erros de compilação/teste aparecem ali e são o ponto de partida para correção.

## Primeiro uso no aparelho

1. Abra o app → **Nova coleta**.
2. **Iniciar captura + janela flutuante**: concede sobreposição (tela de configurações), notificações e, no diálogo do Android,
   escolha **"Tela inteira"** (se escolher "um app", o jogo não é capturado).
3. Abra o GT Rivals. Na janela flutuante, **CALIBRAR**: arraste/redimensione as caixas (Velocidade, Marcha, Acelerador, Freio, Minimap).
   Use **LER AGORA** para ver o que o OCR enxerga. Com os pedais soltos, **PEDAIS SOLTOS = BASE**. Depois pise e use LER AGORA para
   conferir se a variação px-diff muda. **SALVAR**.
4. **INICIAR COLETA / MARCO ZERO** → pilote → **PASSAGEM** na linha de referência (a 1ª vira referência) → **ENCERRAR** (2 toques).
5. Relatório: histórico no app. Sem sobreposição concedida, use o painel "Controle da sessão" dentro do app, ou o modo manual.

## Permissões e por quê

| Permissão | Finalidade |
|---|---|
| `SYSTEM_ALERT_WINDOW` | janela flutuante e overlay de calibração (pedida só ao iniciar esses recursos) |
| `FOREGROUND_SERVICE` + `_MEDIA_PROJECTION` / `_SPECIAL_USE` | manter captura/janela vivas com notificação (exigência do Android 14) |
| `POST_NOTIFICATIONS` | mostrar a notificação do serviço |
| MediaProjection (consentimento em tempo de execução) | captura de tela; revogável a qualquer momento |

Sem root, sem AccessibilityService, sem internet (não há permissão INTERNET), `allowBackup=false`.

## Decisões técnicas

- **minSdk 26** (TYPE_APPLICATION_OVERLAY, Base64 java.util); **targetSdk/compileSdk 34** (Android 14 do aparelho de referência).
- Kotlin 1.9.24, AGP 8.5.2, Compose BOM 2024.06.00, Room 2.6.1 (KSP), DataStore 1.1.1, coroutines 1.8.1,
  ML Kit `text-recognition:16.0.0` (modelo embutido). OpenCV **não** usado (sem necessidade comprovada).
  Versões escolhidas de memória, sem poder verificar no repositório: se algum sync falhar, atualize.
- MVVM leve: `SessionEngine` (domínio, sem Android) + `SessionStore` (interface; Room na produção, fake nos testes).
- **Tempo**: `SystemClock.elapsedRealtimeNanos()` (monotônico, imune a mudança de hora). Marco zero e passagens são lidos de forma
  síncrona no toque, antes de qualquer I/O. Tempo de sessão ≠ tempo ativo ≠ tempo em pausa; passagem durante pausa não é aceita;
  intervalo que cruza pausa é inválido.
- **Persistência**: um único thread de I/O (ordem preservada), amostras em lote (padrão 40 ou 2 s), checkpoint a cada lote;
  na abertura do app, sessões RUNNING/PAUSED órfãs viram INTERRUPTED. Falha de gravação aparece na UI (`persistenceError`).
- **Pipeline**: ciclo adaptativo (padrão 300 ms, opções 200/300/500/1000), 1 frame por vez, OCR só nas ROIs, bitmap de frame reutilizado,
  só processa com a sessão RUNNING, sem guardar imagens. Métricas: taxa efetiva, tempo médio de OCR, % reconhecido, erros.
- **Validação**: leitura inválida (vazia, não numérica, fora de faixa, baixa confiança, salto implausível) é **gravada com a causa**
  e ignorada nos cálculos — nada é apagado. Marcha só aceita dígitos 1–9 por padrão; N/R só se você os adicionar ao perfil após
  confirmar que o jogo os exibe.
- **Pedais**: fração de células de uma grade 32×16 cuja luminância difere da base "pedal solto" → estado binário com histerese.
  "Confiança" é heurística, não probabilidade. **Não há percentual de intensidade.**
- Velocidade média = média ponderada no tempo (retenção de ordem zero) das leituras válidas, sem interpolar lacunas nem pausas.

## Limitações e riscos conhecidos (não verificados)

1. **FLAG_SECURE / DRM**: se o GT Rivals bloquear captura, os frames virão pretos. Não verificado.
2. **Precisão do OCR** com a fonte estilizada do velocímetro: desconhecida. Use LER AGORA; se falhar, ajuste a ROI (tamanho/contraste).
   Pré-processamento além de ampliação não foi implementado.
3. `Text.Element.confidence` do ML Kit 16.0.0: usado de memória; se não compilar, retorne `null` em `MlKitOcrEngine`
   (a leitura passa a ser aceita sem confiança, registrada como nula).
4. **Pixel-diff dos pedais** funciona só se o indicador mudar de aparência e o fundo da ROI for estável. Não validado contra o jogo.
5. **Latência**: o timestamp da amostra é o da aquisição do frame; a imagem pode estar ~1 frame atrás. O erro de reação do toque de
   PASSAGEM está embutido nos intervalos.
6. A janela flutuante aparece na própria captura; posicione-a fora das ROIs. Alguns jogos/HyperOS podem ocultar overlays ou matar o
   serviço (restrições de bateria/autostart do HyperOS podem exigir exceção manual). Fallback: controle no app.
7. Frames só chegam quando a tela muda; ciclos sem frame novo geram lacuna, não dado repetido.
8. Mudança de orientação redimensiona o VirtualDisplay (código escrito, não testado). ROIs calibradas em paisagem não valem em retrato.
9. FPS do jogo, temperatura e consumo **não foram medidos**; o app não afirma ausência de impacto. Meça numa sessão longa.
10. O consentimento do MediaProjection vale por uma captura; ao parar, é preciso autorizar de novo.
11. Hora de término de sessão INTERROMPIDA é aproximada.
12. Comparação entre trechos usaria tempo relativo (sem posição na pista); recomendações de pilotagem ainda não existem.

## Estrutura

```
app/src/main/java/br/com/treinogt/
  core/         TimeSource, SessionClock (marco zero/pausas), LapCalculator, Formatters       (puro, testado em JVM)
  recognition/  Readings, Parsers (velocidade/marcha/validador), PedalMath, Roi, ProfileConfig (puro)
  engine/       SessionEngine (sessão, passagens, lotes, recuperação)
  data/         Room: entidades, DAO, SessionStore, SessionRepository
  capture/      ScreenCapturer (MediaProjection), OcrEngine (ML Kit), RecognitionPipeline, métricas
  overlay/      OverlayPanel (janela flutuante), RoiCalibrationOverlay
  service/      CollectionService (foreground service)
  analysis/     ReportCalculator, ChartData
  export/       ExportFormatter (CSV/JSON), SessionExporter (compartilhar)
  ui/           Compose: Histórico, Controle, Relatório, Gráficos
app/src/test/         testes JVM
app/src/androidTest/  testes instrumentados (dados SINTÉTICOS, identificados como teste)
```

## Próximos passos sugeridos

1. Compilar, corrigir erros, rodar `testDebugUnitTest`.
2. Validar captura + OCR + calibração no POCO X6 Pro com o jogo real; registrar precisão e taxa efetiva (tela de diagnóstico).
3. Validar pedais (binário) com o jogo; só então considerar intensidade proporcional.
4. Sessão longa: medir temperatura/FPS; ajustar frequência.
5. Fase 6: comparação de intervalos por tempo relativo e regras de recomendação baseadas em dados.
