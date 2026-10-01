# AGENTS.md - BlessGuardian Android

## Projeto

Repositorio Android do BlessGuardian / AntiFraud Agent.

Objetivo: aplicativo Android em Kotlin/Jetpack Compose para capturar mensagens suspeitas em tempo real, enviar ao backend FastAPI (AWS API Gateway + Lambda) e consultar historico oficial no DynamoDB.

## Contexto tecnico

- Linguagem: Kotlin
- UI: Jetpack Compose
- Minimum SDK: API 31
- Target SDK: API 36
- Package: `com.example.antifraudagent`
- Banco local: Room/SQLite apenas como fila offline
- Branch de referencia atual: `main`

## Arquitetura atual

O Android captura mensagens por tres caminhos:

- `MessageListenerService`: notificacoes quando o usuario esta fora do app de conversa.
- `FraudAccessibilityService`: mensagens visiveis quando o usuario esta dentro do app de conversa.
- `SmsReceiver`: SMS recebido diretamente.

O fluxo correto de captura automatica e:

```text
Mensagem capturada
-> SettingsRepository.isCaptureEnabled() (kill switch da aba Perfil)
-> LocalMessagePreprocessor (higiene local)
-> se online: POST /detect
-> se offline: Room PENDING
-> backend grava no DynamoDB
-> historico vem de GET /logs?device_id=...
```

Regras da fila `PENDING` (`MessageRepository`):

- A mensagem atual e enviada antes da fila; so depois de um envio bem-sucedido a fila e processada.
- Um unico envio da fila por vez no processo (`Mutex` no companion, `tryLock`): Activity, servicos de captura e `SmsReceiver` compartilham a trava, evitando registros duplicados no DynamoDB.
- Falha transitoria (timeout, rede, HTTP 5xx/408/429) interrompe a fila; ela volta no proximo gatilho (abrir/atualizar o app, rede disponivel, nova captura).
- HTTP 4xx definitivo (`FraudApiHttpException.isPermanent`) remove o item da fila, para ele nao travar as demais pendencias.
- O kill switch e conferido a cada item: desligar o envio com a fila em andamento interrompe no proximo item.
- Backup do Android (`data_extraction_rules.xml`): sharedpref e database vao para o backup (device_id,
  Perfil, contatos confiaveis, ligacoes). A fila PENDING restaurada NAO e reenviada: na primeira
  execucao de cada instalacao (marcador `install_marker` em `noBackupFilesDir`, fora do backup) o
  `MessageRepository.discardRestoredQueueIfNeeded` apaga os PENDING. Antes disso, reinstalar o app
  reenviava a fila antiga e duplicava registros no DynamoDB. Efeito colateral: ao atualizar para a
  versao com o marcador, a fila PENDING existente e descartada uma vez.
- `MessageRepository.syncState` (`IDLE`, `SYNCING`, `SERVER_UNAVAILABLE`, `OFFLINE`) alimenta o card da Inicio; pendencias com internet NAO devem aparecer como "offline".
- A tela carrega `GET /logs` primeiro e processa a fila em paralelo; o contador vem de `observePendingCount()` (Flow do Room).

`SettingsRepository` (em `data/settings/SettingsRepository.kt`) e um singleton com SharedPreferences que expoe a flag `capture_enabled` (default `true`) via `StateFlow`. Compose (aba Perfil) e `MessageRepository` observam a mesma instancia. Quando desligado, `saveIfSuspicious`, `analyzeManualMessage` e `processPendingMessages` retornam cedo sem tocar Room nem HTTP.

## Camada de pre-processamento local

Pacote: `com.example.antifraudagent.data.local.preprocessing`
Classe: `LocalMessagePreprocessor` (Kotlin `object`, estado compartilhado entre servicos)

Responsabilidade: higiene de dados antes de chegar ao backend.

- Normaliza espacos, tabs e quebras de linha.
- Remove caracteres invisiveis/de formatacao (zero-width space, ZWNJ/ZWJ, BOM,
  soft hyphen, controles) usados para quebrar palavras e escapar da deteccao
  (ex: `p<zwsp>ix` -> `pix`).
- Rejeita texto vazio apos normalizacao.
- Rejeita horario isolado (ex: `12:35`, `08:01 AM`).
- Rejeita data ou separador de chat (ex: `Hoje`, `Ontem`, `25/12`, `12 de maio`).
- Rejeita ruidos de sistema conhecidos (backup, sincronizacao, WhatsApp Web,
  procurando mensagens, criptografia de ponta a ponta, conectado/desconectado,
  notificacoes genericas de midia como `foto`/`audio`/`video`).
- Rejeita texto que seja exatamente igual ao nome do remetente.
- Rejeita texto muito curto (< 10 chars) sem sinais de conteudo relevante
  (link, Pix, CPF, banco, senha, codigo, boleto, valor em R$, urgencia financeira).
  A deteccao de sinal relevante e insensivel a acento (ex: `codigo`/`código`,
  `premio`/`prêmio`), errando sempre para o lado de preservar a mensagem.
- Deduplica capturas com mesmo conteudo normalizado dentro de janela de 15s,
  evitando que NotificationListener e AccessibilityService enviem a mesma
  mensagem duas vezes. O fingerprint usa o conteudo normalizado COMPLETO (sem
  truncamento), para nao colapsar mensagens distintas que compartilham um
  prefixo longo (ex: template de golpe com link/conta no final).

Retorno: `PreprocessResult.Accepted(normalizedText)` ou `PreprocessResult.Rejected(reason)`.

Regras criticas da camada:

- Nao calcula score de fraude. A pontuacao oficial vem do backend Python.
- Nao monta payload nem chama API.
- Nao grava no Room nem no DynamoDB.
- A aba manual `Analisar` NAO passa por este filtro; `analyzeManualMessage()`
  chama `/detect` diretamente.
- Integrada apos `SettingsRepository.isCaptureEnabled()` em `MessageRepository.saveIfSuspicious()`
  para cobrir os tres caminhos automaticos com um unico ponto.

## Anti-vazamento de janela no FraudAccessibilityService

`FraudAccessibilityService` valida que `rootInActiveWindow.packageName` ainda
corresponde ao pacote que originou o evento antes de ler a arvore de
acessibilidade. Isso fecha a race condition do debounce de 600ms quando o
usuario troca de app dentro dessa janela (ex: voltar ao BlessGuardian, abrir
notificacao de Google News). Eventos com `packageName == BuildConfig.APPLICATION_ID`
sao tambem ignorados como defesa em profundidade.

Nota de build: o uso de `BuildConfig.APPLICATION_ID` exige `buildConfig = true`
em `app/build.gradle.kts` -> `buildFeatures`. Desde AGP 8 essa classe nao e
mais gerada por default; sem a flag o `compileDebugKotlin` falha com
`Unresolved reference 'BuildConfig'`.

### Filtro de ruido estrutural por viewId

`collectMessages` varre os nos folha da janela. Para nao capturar nome de
contato, titulo da conversa, status e caixa de digitacao como se fossem
mensagens, ele consulta `node.viewIdResourceName` contra a DENYLIST
`NOISE_VIEW_IDS` e descarta os nos conhecidos como ruido. Requer
`flagReportViewIds` no `accessibility_service_config.xml` (ja ativo).

Estrategia subtrativa e **fail-open**: a lista so REMOVE nos sabidamente ruido,
nunca restringe a captura a uma allowlist. Logo, um viewId desatualizado no
maximo deixa o ruido voltar a passar — jamais descarta uma mensagem real
(garante a regra de "nao pular mensagem relevante"). Os ids variam por app/versao;
ative `DEBUG_CAPTURE` e use `adb logcat -s FraudAccessibility` para ver o par
`viewId -> texto` em device e estender a lista.

## Contrato com backend

### POST /detect

Payload enviado pelo Android:

```json
{
  "device_id": "uuid-anonimo-do-aparelho",
  "message_content": "texto da mensagem capturada",
  "source": "sms"
}
```

### Proteção de ligações (automática)

Não existe botão de iniciar. A proteção fica armada e só capta áudio em ligação **recebida e atendida**.

```text
FraudAccessibilityService.onServiceConnected
-> CallStateMonitor (TelephonyCallback, uma por SIM ativo; precisa READ_PHONE_STATE)
-> RINGING -> OFFHOOK = recebida e atendida (IDLE -> OFFHOOK = feita pelo usuario: ignorada)
-> CallOverlays.showSpeakerPrompt ("Ative o viva-voz") ANTES de iniciar o servico
-> CallRecordingService (FGS microphone) -> CallAudioCapture (AudioRecord VOICE_RECOGNITION 16 kHz)
-> pipe -> CallSpeechTranscriber (SpeechRecognizer + EXTRA_AUDIO_SOURCE + EXTRA_SEGMENTED_SESSION)
-> cada trecho: CallRiskRules (alerta local imediato) + checkpoints POST /detect source=call
-> IDLE: para captura/transcricao, analise final, registro em call_transcripts (Room v3)
```

Por que assim (AOSP `AudioPolicyService::updateUidStates_l`, CDD 5.4.5):

- Durante ligação de operadora o Android silencia a captura de apps comuns. A exceção é o **uid com
  serviço de acessibilidade ativo**, e só com a fonte `VOICE_RECOGNITION`. Por isso a acessibilidade
  é requisito da proteção de ligações; `MIC`, `VOICE_COMMUNICATION`, `VOICE_CALL` não servem.
- O `SpeechRecognizer` grava no processo do reconhecedor (outro uid) e seria silenciado: nunca deixe
  ele abrir o microfone durante a ligação. Quem grava é o app; o reconhecedor recebe o PCM pelo pipe.
- Só há um cliente de microfone (sem `MediaRecorder` junto). Nenhum áudio é salvo em disco.
- O vínculo da acessibilidade (BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS) e o aviso visível do viva-voz
  liberam o FGS de microfone em segundo plano (Android 14+). Mesmo assim `startForeground` fica em try/catch.
- App comum não consegue ligar o viva-voz de ligação de operadora: só sugerir. A rota é lida por
  `AudioManager.getCommunicationDevice()` (+ `isSpeakerphoneOn`) e o aviso some quando vira alto-falante.
- `CallScreeningService` só guarda o número (não recebe contatos/números ocultos): não é gatilho.

Regras de análise (cada POST /detect grava um registro no DynamoDB):

- Alerta local imediato quando `CallRiskRules` chega a HIGH (combinação de categorias).
- Análise ao vivo: a cada trecho reconhecido (>= 6 palavras na conversa e >= 3 novas), a conversa
  acumulada vai para o /detect. Um envio por vez; o que chega durante o envio vai no próximo. Para
  quando o servidor confirma golpe (teto de 60 por ligação).
- Esses registros `source=call` ficam fora do Histórico/Início de mensagens
  (`MessageRepository.getConfirmedFrauds` filtra); a aba Ligações tem o histórico próprio (Room).
- Final só se nada foi enviado ou houver >= 5 palavras novas; conversa com < 8 palavras fica só no aparelho.
- Kill switch do Perfil (`capture_enabled`) desliga os envios; a análise local continua.
- Reenvio automático só para falha de conexão (`PENDING`). Timeout/5xx/envio interrompido = `FAILED`
  (pode ter gravado no servidor; não repetir).
- Pop-up de golpe: `TYPE_ACCESSIBILITY_OVERLAY` (fallback `TYPE_APPLICATION_OVERLAY`), notificação de
  alta prioridade e vibração. O alerta é fechável e nunca é desfeito por uma análise "segura" posterior.

Alertas de mensagem seguem o mesmo padrão (`SuspiciousMessageAlert`): pop-up via overlay de
acessibilidade + notificação + vibração; mensagens com mais de 5 min (fila offline) só notificam.
O pop-up só aparece em `RiskLevel.HIGH` ("Alta probabilidade de golpe"); `ATTENTION` gera apenas
notificação de atenção. O texto é genérico, para o usuário leigo.

Pendências para o backend (Mitchell): aceitar `session_id` para fazer upsert das análises da mesma
ligação e persistir `raciocinio`/`indicadores` (hoje só `veredito_curto` vai para `explanation`).

Resposta esperada (status 201):

```json
{
  "status_db": true,
  "user_id": "uuid-v5-derivado-do-device-id",
  "analise": {
    "tentativa_fraude": true,
    "score": 0.87,
    "categoria": "phishing",
    "indicadores": ["link encurtado", "urgencia"],
    "veredito_curto": "Provavel golpe de phishing"
  }
}
```

Regras:

- Usar `device_id`, nunca `user_id`, no payload novo.
- `device_id` vem de `DeviceIdentityProvider`.
- `source` deve ser enviado em minusculas: `sms`, `whatsapp`, `telegram`, `instagram`, `manual` ou `unknown`. `FraudApiClient` aplica `source.name.lowercase()` automaticamente.
- `FraudApiClient.DEFAULT_BASE_URL` aponta para o endpoint do backend no AWS API Gateway (`*.execute-api.us-east-1.amazonaws.com`), apos a migracao de ECS para API Gateway + Lambda.
- O backend deriva `user_id = uuid5(NAMESPACE_OID, device_id)` (deterministico). Android nunca cria nem envia `user_id`.

### GET /logs

Consulta o historico oficial. Query params:

- `device_id` (obrigatorio na pratica para evitar scan caro)
- `limit` (default 50)
- `offset` (default 0)

Resposta (status 200):

```json
{
  "status": "success",
  "total_logs": 42,
  "data": [
    {
      "id": "uuid-v4",
      "user_id": "uuid-v5",
      "device_id": "...",
      "content": "...",
      "source": "whatsapp",
      "is_fraud": true,
      "risk_score": 0.87,
      "explanation": "...",
      "detected_at": "2026-05-25T14:41:15.517122+00:00"
    }
  ]
}
```

### GET /health

`{ "status": "healthy" }`. Usado para checagem rapida de disponibilidade do backend.

## Protecao reforcada

Base do futuro modo idoso/crianca (`SettingsRepository.reinforcedProtection`, opcao no Perfil).
Provisoria ate existir login: depois quem liga/desliga e o responsavel, pela conta dele.

- So age em `RiskLevel.HIGH`. Regras locais da ligacao sozinhas e `ATTENTION` nunca derrubam a
  ligacao nem fecham app (alarme falso nao pode cortar a conversa de um parente).
- Ligacao: checkpoint do servidor com HIGH -> `CallTerminator.endCall` + aviso "Ligação de GOLPE
  encerrada" (`CallRecordingService.terminateIfReinforced`).
- Mensagem recente com HIGH -> `GLOBAL_ACTION_HOME` pelo servico de acessibilidade (fecha o app da
  conversa) + aviso "Mensagem de GOLPE bloqueada" (`SuspiciousMessageAlert`).
- `CallTerminator`: 1) `TelecomManager.endCall()` com `ANSWER_PHONE_CALLS` (depreciado, mas
  testado e funcionando no S24 / One UI, targetSdk 36, ligacao atendida); 2) reserva: acessibilidade
  toca no botao de encerrar do discador. A permissao so e pedida ao ligar a opcao.
- Build debug: `DebugEndCallReceiver` testa sem golpe real:
  `adb shell am broadcast -n com.example.antifraudagent/.debug.DebugEndCallReceiver` (encerra a
  ligacao) e `... --es mode message` (simula mensagem HIGH no WhatsApp).
- Falta (depende de login/backend): vincular responsavel pelo nome de usuario e notifica-lo na hora.

## Contatos confiaveis

Lista local (Room v4, tabela `trusted_contacts`; `TrustedContactRepository`). Nunca vai ao backend.

- Mensagem de contato confiavel continua sendo enviada ao `/detect`, mas so gera alerta em
  `RiskLevel.HIGH` (os dois algoritmos concordam), com aviso de possivel conta clonada.
  `ATTENTION` de contato confiavel nao alerta (checagem em `MessageRepository.analyzeAndPersist`).
- Comparacao (`TrustedContactRepository.matches`): notificacao de WhatsApp/Telegram/Instagram traz
  o NOME (comparado sem acento/maiusculas); SMS traz o NUMERO (ultimos 8 digitos, ignorando +55,
  DDD e o 9 extra). Por isso cada contato guarda nome e numero.
- Captura pela acessibilidade (usuario dentro da conversa) nao conhece o remetente: nao e filtrada.
- Ligacoes nao usam a lista (o alerta de ligacao ja e so em risco alto).
- "Escolher da agenda" usa `ACTION_PICK` em `Phone.CONTENT_URI`: o app recebe acesso so ao numero
  escolhido. NAO pedir `READ_CONTACTS`.
- Sincronizacao com backend fica para depois do login (responsavel gerenciar a lista); entra no
  repositorio sem mudar telas nem captura.

## Room / SQLite

Room nao e historico oficial.

Use Room apenas para:

- guardar mensagens `PENDING` quando nao ha internet;
- manter mensagens que falharam ao gravar no historico oficial;
- reenviar pendencias quando a conexao voltar.

Remover uma mensagem do Room somente quando a resposta do backend trouxer:

```text
status_db=true
```

Se `status_db=false`, a mensagem deve continuar pendente.

## UI Android

A tela principal deve:

- usar navegacao inferior com `Inicio`, `Historico`, `Analisar` e `Perfil`;
- mostrar indice de vulnerabilidade, mensagens analisadas e golpes bloqueados;
- mostrar quantidade de pendencias offline;
- ter aba `Analisar` para envio manual de mensagens suspeitas com `source=manual`;
- consultar historico oficial via `GET /logs?device_id=...`;
- nao usar Room como fonte do historico.

O `vulnerability_score` exibido no `VulnerabilityCard` e calculado **client-side**
em `MainActivity.kt` (`vulnerabilityScore(logs)`) a partir da media de `risk_score` dos logs
retornados pelo backend. Nao existe endpoint que devolva esse agregado; nao tentar buscar do backend.

O backend hibrido ainda grava `score=0`. Zero e tratado como "sem pontuacao" (`meaningfulScore()`):
o app nao mostra "0%"/"0.00"; o card mostra "—" / "em calculo" e o risco atual vem do pior veredito
dos ultimos 7 dias (`currentRiskLevel`). Quando o backend mandar score real, ele volta a aparecer.

### Nivel de risco (RiskLevel)

`RiskLevel` (em `FraudApiClient.kt`) e a fonte unica do nivel exibido em cards, filtros, contadores,
detalhe e pop-up. Vem do `status_final` do veredito hibrido:

- `fraude` (LLM e modelo local concordam) -> `HIGH` ("Alto risco" / "Alta probabilidade de golpe");
- `aviso` (so um acusou) -> `ATTENTION` ("Atenção"); nao conta como golpe confirmado;
- `seguro` -> `SAFE`;
- sem veredito hibrido -> `is_fraud` decide (HIGH ou SAFE).

Nao usar `isFraud` direto na UI: o backend grava `is_fraud=true` tambem para `aviso`.

### Modo tecnico

O bloco "Como foi decidido" (votos do LLM e do modelo local) so aparece com o modo tecnico ligado
(`SettingsRepository.technicalMode`, exposto via `LocalTechnicalMode`). Desligado por padrao.
Liga/desliga com 7 toques seguidos no logo do topo; ligado, aparece o painel "Modo tecnico" no Perfil
para desligar. Solucao provisoria ate existir login: depois, so administrador.

## Analise manual

A aba `Analisar` chama `POST /detect` com:

```json
{
  "device_id": "uuid-anonimo-do-aparelho",
  "message_content": "texto colado pelo usuario",
  "source": "manual"
}
```

Como o endpoint `/detect` persiste no DynamoDB, a analise manual entra no historico oficial e deve aparecer depois na aba `Historico`.

## Build

Quebras de build conhecidas e como evitar:

- **Alinhamento Kotlin x KSP**: em `gradle/libs.versions.toml`, a versao do KSP
  segue o formato `<versao-kotlin>-<versao-ksp>` (ex: `2.0.21-1.0.28`). Usar um
  KSP de outro branch do Kotlin causa `[ksp] IllegalStateException: unexpected
  jvm signature V` no `:app:kspDebugKotlin`.
- **`buildConfig = true`**: obrigatorio em `app/build.gradle.kts` -> `buildFeatures`
  porque o codigo usa `BuildConfig.APPLICATION_ID`. AGP 8+ nao gera mais a classe
  por default.
- **AGP Upgrade Assistant**: o Android Studio pode propor bumpar AGP/Gradle/Kotlin
  de uma vez (`gradle.properties`, `gradle-wrapper.properties`, `libs.versions.toml`).
  Nao aceitar sem PR e revisao da equipe. Se cair em estado inconsistente, reverter
  com `git restore gradle.properties gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.properties`.

## Regras criticas

- Nao tratar Room como historico oficial.
- Nao substituir `device_id` por `user_id`.
- Nao remover fila offline sem alternativa.
- Nao logar mensagens sensiveis inteiras em producao.
- Nao ampliar permissoes Android sem justificar impacto ao usuario.
- Captura de ligacao depende do viva-voz e da acessibilidade ativa; testar em aparelho real (Samsung pode diferir do AOSP).
- Preservar debounce/deduplicacao do `FraudAccessibilityService`.
- Nao adicionar `<?xml version="1.0"?>` em `accessibility_service_config.xml`.
- Captura passiva, analise manual e fila offline respeitam `SettingsRepository.isCaptureEnabled()`. Nao bypassar essa flag em novos pontos de envio.
- `vulnerability_score` e client-side; nao migrar para o backend sem decisao explicita da equipe.

## Checklist antes de entregar

- Gradle Sync funcionando no Android Studio.
- App compila com JDK embutido do Android Studio.
- Permissoes de SMS, notificacoes e acessibilidade aparecem corretamente.
- Captura por notificacao, SMS e acessibilidade ainda funciona.
- Offline cria registros `PENDING`.
- Reonline processa fila pendente.
- Historico carrega na hora mesmo com fila grande; card mostra "enviando" durante o envio.
- Pendencia so sai do Room com `status_db=true`.
- `Atualizar` consulta `/logs?device_id=...`.
- `Historico` mostra dados vindos do backend AWS (DynamoDB).
- `Analisar` envia `source=manual`, mostra score/explicacao e grava no DynamoDB.
- Kill switch da aba Perfil pausa envio e nao acumula em Room enquanto desligado.
- `LocalMessagePreprocessor` rejeita ruido conhecido antes de chegar ao backend; aba `Analisar` ignora esse filtro.
- `FraudAccessibilityService` nao captura conteudo do proprio app nem de apps fora do alvo (validacao em `processWindow`).

## Formato esperado de resposta

Quando atuar neste repositorio, responda preferencialmente com:

```markdown
## Diagnostico Android
## Alteracoes propostas
## Arquivos impactados
## Riscos
## Testes
## Criterios de aceite
```
