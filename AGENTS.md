# AGENTS.md - BlessGuardian Android

## Projeto

Repositorio Android do BlessGuardian.

Objetivo: aplicativo Android em Kotlin/Jetpack Compose para capturar mensagens suspeitas em tempo real, enviar ao backend FastAPI (AWS API Gateway + Lambda) e consultar historico oficial no DynamoDB.

## Contexto tecnico

- Linguagem: Kotlin
- UI: Jetpack Compose
- Minimum SDK: API 31
- Target SDK: API 36
- Package: `com.example.antifraudagent`
- Banco local: Room/SQLite (v4) para fila offline, historico de ligacoes e contatos confiaveis;
  o historico oficial de mensagens e o DynamoDB
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
-> historico vem de GET /logs com o token da conta (login obrigatorio)
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

`SettingsRepository` (em `data/settings/SettingsRepository.kt`) e um singleton com SharedPreferences
(`antifraud_settings`) exposto via `StateFlow`. Guarda:

- `capture_enabled` (default `true`): kill switch de envio. Desligado, `saveIfSuspicious`,
  `analyzeManualMessage` e `processPendingMessages` retornam cedo sem tocar Room nem HTTP;
- `call_protection_enabled`: protecao de ligacoes (aba Ligacoes);
- `reinforced_protection`: protecao reforcada (ver secao propria);
- `technical_mode`: modo tecnico de demonstracao (so vale no build debug);
- `protected_since`: quando a protecao continua (notificacoes + acessibilidade) foi ligada.

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
- Rejeita resumo de notificacoes, sempre (ex: `4 mensagens de 2 conversas`, `3 novas mensagens`,
  `5 mensagens não lidas`, `4 messages from 2 chats`). Texto que so comeca com numero passa.
- Rejeita o SMS de aviso que o proprio BlessGuardian manda ao responsavel
  (`GuardianRepository.smsText`), senao o celular do responsavel dava alarme falso. Exige o texto
  exato do modelo e nenhum link (`isOwnGuardianAlert`), para golpista nao escapar so com o prefixo.
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
    "veredito_curto": "Veredito Híbrido: AVISO. LLM: Fraude | Modelo Local: Seguro. Justificativa LLM: ..."
  },
  "detalhes_hibridos": {"status_final": "aviso", "decisao_llm": 1, "decisao_onnx": 0}
}
```

O backend e hibrido: LLM (LM Studio) + modelo ONNX local. `status_final` = `fraude` (os dois
acusam), `aviso` (so um) ou `seguro`. Hoje o backend devolve e grava `score=0` (ver `RiskLevel` e
"Pendencias do backend").

Regras:

- No `POST /detect`, enviar `device_id`, nunca `user_id`. O app manda tambem o token quando ha sessao
  (`Authorization: Bearer`); hoje o backend ignora, futuro: gravar pelo usuario da conta.
- `device_id` vem de `DeviceIdentityProvider`.
- `source` deve ser enviado em minusculas: `sms`, `whatsapp`, `telegram`, `instagram`, `manual`, `call` (ligacao) ou `unknown`. `FraudApiClient` aplica `source.name.lowercase()` automaticamente.
- `FraudApiClient.DEFAULT_BASE_URL` aponta para o endpoint do backend no AWS API Gateway (`*.execute-api.us-east-1.amazonaws.com`), apos a migracao de ECS para API Gateway + Lambda.
- O backend deriva `user_id = uuid5(NAMESPACE_OID, device_id)` (deterministico). O app nunca cria
  nem envia `user_id`: o usuario das consultas vem do token.

### GET /logs

Historico oficial da conta logada. **Exige token** (`Authorization: Bearer <access_token>`) desde
18/10/2026: o backend ignora `device_id`/`user_id` da query e usa o `user_id` do token. Sem token ou
com token vencido: 401 -> o app chama `AuthRepository.expire()` e volta para a tela de login.

Query params: `limit` (sem limite se omitido; o app manda 200) e `offset` (default 0).

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
      "explanation": "Veredito Híbrido: ... Justificativa LLM: ...",
      "category": "phishing",
      "detected_at": "2026-05-25T14:41:15.517122+00:00"
    }
  ]
}
```

### GET /score

Indice de vulnerabilidade da conta (0..1), **exige token**. Pre-calculado no servidor por
`POST /admin/calculate-scores` (contagem de logs `is_fraud` por usuario normalizada pela media +- 3
desvios de todos os usuarios). 200 `{"status","user_id","vulnerability_score"}`; 404 sem registro
(o app trata como sem valor); 401 token. Usado por `ScoreRepository` (ver "Indice de vulnerabilidade").

### GET /stats

Contagem por categoria (`{"escopo":"usuario|global","stats":[{"categoria","quantidade"}]}`), token
opcional (sem token = global). E do leaderboard do site (Luiz); o app nao usa.

### POST /register e POST /login (conta)

```json
POST /register  {"device_id": "...", "email": "a@b.com", "password": "..."}
-> 201 {"status": "success", "message": "...", "user_id": "uuid5-do-device_id"}
-> 400 {"detail": "..."}            422 = e-mail invalido

POST /login     {"email": "a@b.com", "password": "..."}
-> 200 {"access_token": "<JWT>", "token_type": "bearer", "user_id": "...", "role": "default|admin"}
-> 401 {"detail": "E-mail ou senha incorretos"}
```

- **Login obrigatorio** (19/10/2026): sem sessao o app mostra `LoginGate` (entrar/criar conta) no
  lugar das abas. A captura e o `/detect` continuam funcionando antes do login.
- O `user_id` da conta e o uuid5 do `device_id` do celular onde ela foi criada. Em outro celular,
  o historico mostrado continua sendo o do celular de cadastro (ver pendencias do backend).
- `AuthRepository` guarda e-mail, `user_id`, papel e token em `auth_session.xml`, FORA do backup
  (`data_extraction_rules.xml`), e publica o token em `AuthToken.current`, que o `FraudApiClient`
  manda em todas as chamadas. `register` cria a conta e ja faz login.
- O token vale 24h e nao ha renovacao: vencido ao abrir o app ou 401 do servidor -> `expire()`
  volta ao login com o e-mail preenchido (`last_email`) e o aviso de sessao expirada.
- Papel `admin` liga o modo tecnico (comite de algoritmos) automaticamente. Os 7 toques no logo so
  funcionam no build debug.

### Pendencias do backend (Mitchell, atualizado em 19/10/2026)

Mensagem enviada ao Mitchell em 19/10, em ordem de prioridade. O app ja esta pronto para cada item;
quando o backend mudar, normalmente nada (ou so o repositorio indicado) precisa mudar no app.

Urgente:
- Subir no GitHub as correcoes feitas direto na AWS em 19/10: cadastro (`'vulnerability_score': 0.0`
  -> `Decimal('0')`, dava "Float types are not supported") e geracao do token (dava 500 no /login).
  O ultimo commit do repositorio ainda e o de 18/10; uma imagem nova a partir do repo traz os erros de volta.
- Cada conta com o proprio `user_id`: hoje `user_id = uuid5(device_id)`, entao toda conta criada no
  mesmo celular ve o mesmo historico e cada cadastro sobrescreve o anterior. Proposta: `/register`
  gera uuid4 e recusa e-mail repetido; `/detect` grava no `user_id` do token (o app ja manda o token),
  guardando o `device_id` so como informacao. Resolve tambem o historico em mais de um celular.

Seguranca (bloqueia uso publico):
- `DELETE /logs/all` e `DELETE /logs` sem token: qualquer um apaga o historico de todos.
- `/register`: `ConditionExpression='attribute_not_exists(id)'` olha atributo inexistente (a chave e
  `user_id`): recadastrar sobrescreve e-mail/senha (tomada de conta). Sem `device_id` da 500.
- `POST /admin/calculate-scores` sem token (checagem de admin comentada) e com scan da tabela inteira;
  `update_item` cria usuarios "fantasma" para aparelhos sem conta.
- `POST /detect` sem limite de tamanho de `message_content`; `categoria` do LLM sem validacao.
- LGPD: grava o texto de toda mensagem, inclusive as seguras (codigos 2FA), sem TTL.

Score:
- Agendar o recalculo (EventBridge) no periodo fixo combinado; hoje so muda quando alguem chama
  `/admin/calculate-scores`. Opcional: `/score` devolver valor anterior, data e motivo (o app ja
  monta o motivo; trocaria em `ScoreRepository`).
- Conta `is_fraud=true`, que inclui `aviso`; avaliar contar so `fraude` ou dar peso menor.

Login:
- Token de 24h sem renovacao: aumentar (ex.: 30 dias) ou refresh token, porque o login e obrigatorio.
- "Esqueci minha senha": `POST /forgot-password` (codigo de 6 digitos por e-mail via SES, ~15 min) e
  `POST /reset-password` (e-mail + codigo + senha nova). A tela do app so entra quando existirem.

Responsavel:
- E-mail: endpoint para salvar o e-mail do responsavel na conta e envio por SES quando o `/detect`
  der "fraude" (o app ja guarda o e-mail lido do QR). Aviso no app do responsavel: vinculo e/ou push (FCM).

Menores:
- `/stats`: contar so golpes (`is_fraud`; hoje 175 aparecem como "Desconhecido") e trazer um exemplo
  por categoria (leaderboard do site, Luiz).
- Ligacoes: `session_id` para juntar as analises da mesma ligacao; persistir `raciocinio`/`indicadores`.
- Limpar os registros duplicados gerados pela fila restaurada do backup (antes do PR #9).
- Pipeline de deploy (GitHub Actions: build da imagem -> ECR -> atualizar a Lambda) com role IAM via
  OIDC, para corrigir sem depender de deploy manual. Confirmar se o backend continua na Lambda ou vai
  para EC2 antes de escrever o workflow.

### GET /health

`{ "status": "healthy" }`. Usado para checagem rapida de disponibilidade do backend.

## Proteção de ligações (automática)

Não existe botão de iniciar. A proteção fica armada e só capta áudio em ligação **recebida e atendida**.

```text
FraudAccessibilityService.onServiceConnected
-> CallStateMonitor (TelephonyCallback, uma por SIM ativo; precisa READ_PHONE_STATE)
-> RINGING -> OFFHOOK = recebida e atendida (IDLE -> OFFHOOK = feita pelo usuario: ignorada)
-> CallOverlays.showSpeakerPrompt ("Ative o viva-voz") ANTES de iniciar o servico
-> CallRecordingService (FGS microphone) -> CallAudioCapture (AudioRecord VOICE_RECOGNITION 16 kHz)
-> pipe -> CallSpeechTranscriber (SpeechRecognizer + EXTRA_AUDIO_SOURCE + EXTRA_SEGMENTED_SESSION)
-> cada trecho: CallRiskRules (alerta local imediato) + checkpoints POST /detect source=call
-> IDLE: para captura/transcricao, analise final, registro em call_transcripts (Room)
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

Um alerta por mensagem: `MessageAlertDedup` guarda a impressão digital (SHA-256 do texto
normalizado, nunca o texto) de cada mensagem que já alertou, por 24h, em `alerted_messages.xml`
(máximo de 500). A mesma mensagem capturada de novo (acessibilidade ao abrir a conversa depois da
notificação, reinício do app, notificação atualizada) não repete notificação, pop-up, vibração nem SMS
ao responsável. O `/detect` continua sendo chamado (o histórico pode ter registros repetidos).

## Protecao reforcada

Parte local do modo idoso/crianca (`SettingsRepository.reinforcedProtection`, opcao no Perfil).
Ligada tambem por "Este celular é de uma pessoa protegida" (ver "Idosos e criancas"). Quando o
backend tiver vinculo por conta, quem liga/desliga passa a ser o responsavel.

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
- O vinculo com o responsavel e o aviso para ele estao em "Idosos e criancas".

## Idosos e criancas (vinculo com o responsavel)

`data/guardian`, painel "IDOSOS E CRIANÇAS" no Perfil. Funciona sem backend.

- Celular do RESPONSAVEL: "Proteger alguém" -> preenche nome, celular e e-mail -> QR
  `BG2:<base64url(JSON {"n","p","e"})>` (`LinkCode`), tambem enviavel por mensagem.
- Celular da PESSOA PROTEGIDA: "Este celular é de uma pessoa protegida" liga a protecao reforcada
  (pede `ANSWER_PHONE_CALLS`) -> "Meus responsáveis" -> "Ler QR do responsável" (Google code
  scanner, sem permissao de camera) ou "Digitar os dados". Pede `SEND_SMS` ao cadastrar o primeiro.
- Golpe de ALTO risco na pessoa protegida -> `GuardianRepository.notifyGuardians` manda SMS na hora
  para cada responsavel (mensagem recente em `SuspiciousMessageAlert`; ligacao com HIGH do servidor
  em `CallRecordingService`, 1 vez por ligacao). Maximo 1 SMS a cada 10 min por responsavel. O SMS
  nao leva o texto do golpe.
- E-mail do responsavel fica guardado, mas o envio depende do backend (SES). Aviso no app do
  responsavel depende de endpoint de vinculo/push. A versao antiga (`BG1:<user_id>` + consulta
  periodica a `GET /logs?user_id=`) parou de funcionar com o token no /logs e foi removida.

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

Room (`antifraud_database`, v4, migracoes 1->2->3->4 sem perda) NAO e o historico oficial de
mensagens (esse e o DynamoDB via `GET /logs`). Tabelas:

- `analyzed_messages`: so a fila `PENDING` (sem internet ou falha ao gravar no servidor; reenvio
  quando a conexao voltar; maximo de 100, sai a de menor `layer1Score`);
- `call_transcripts`: historico de ligacoes da aba Ligacoes (transcricao, veredito, trecho do alerta);
- `trusted_contacts`: contatos confiaveis (lista local).

Nunca trocar o nome do banco nem das SharedPreferences: o usuario perderia fila e configuracoes.

Remover uma mensagem do Room somente quando a resposta do backend trouxer:

```text
status_db=true
```

Se `status_db=false`, a mensagem deve continuar pendente.

## UI Android

A tela principal deve:

- usar navegacao inferior com `Início`, `Histórico`, `Analisar`, `Ligações` e `Perfil`;
- mostrar indice de vulnerabilidade, mensagens analisadas e golpes bloqueados;
- mostrar quantidade de pendencias offline;
- ter aba `Analisar` para envio manual de mensagens suspeitas com `source=manual` (os botoes
  "Print" e "Compartilhar" sao placeholders desativados: `SecondaryActionButton` com
  `enabled = false`; nao ha OCR nem intent `ACTION_SEND` no manifest);
- no topo, `ProtectionStatusChip` mostra "Sem proteção" quando notificacoes ou acessibilidade estao
  desligadas (nada e capturado), antes de "Pausado"/"Protegido";
- no Perfil, nao manter controles sem funcao: "Proteção de ligações" reflete o estado real e abre a
  aba Ligações; "Protegido desde" vem de `SettingsRepository.protectedSinceMillis()` (gravado ao
  ligar a protecao continua, apagado ao desligar);
- sem sessao, mostrar so o `LoginGate`; avatar do topo e painel "CONTA" do Perfil abrem a conta
  (dados da conta e sair);
- Perfil, de cima para baixo: Conta, Modo de proteção (proteção contínua, de ligações, reforçada),
  Privacidade (envio para o servidor), Idosos e crianças, Contatos confiáveis, Resumo de atividade
  e, so no debug com modo tecnico ligado, Modo técnico;
- consultar historico oficial via `GET /logs` com o token;
- nao usar Room como fonte do historico.

### Indice de vulnerabilidade (estilo Serasa)

`ScoreRepository` + `ScoreUi.kt`. O valor vem do `GET /score` (0..1, por conta, periodo fixo
definido pelo backend) e e buscado junto com o historico (`refreshData`). A tela mostra de **0 a
1000 pontos** (`scorePoints`), com as faixas baixa (0-399), media (400-749) e alta (750-1000),
alinhadas com `riskVisual`. Aqui subir e ruim.

- O backend so devolve o valor atual. O app guarda cada valor visto em `score_history` (vai no
  backup; limpo no logout) e, quando muda, cria uma entrada com valor anterior, data em que o app
  percebeu e o **motivo**, montado a partir dos golpes confirmados (`is_fraud`, a mesma base do
  `calculate_scores`) desde a mudanca anterior (`ScoreRepository.reasonFor`, testado). Quatro
  casos: subiu com golpes ("Subiu porque voce teve N golpes novos..."), subiu sem golpes (os
  demais receberam proporcionalmente menos), caiu sem golpes e caiu mesmo com golpes (os demais
  receberam proporcionalmente mais). O score depende da media de todos os usuarios, entao pode
  mudar sem golpe novo do usuario. Se o backend passar a mandar o motivo, so o repositorio muda.
- `ScoreRepository.status` (`ScoreStatus`): 404 do `/score` vira "Ainda não calculado" (com aviso do
  proximo recalculo) e outros erros viram "Não foi possível consultar"; o 401 continua levando ao
  login. No build debug, `getScore` registra no logcat (`BlessScore`) o codigo HTTP e o corpo da
  resposta, nunca o token: `adb logcat -s BlessScore:D`.
- Card na Inicio (estilo Serasa): velocimetro com as tres faixas e marcador, pontos "de 1000",
  faixa e "▲ subiu / ▼ caiu N pontos". Toque abre o "Historico": barras das ultimas 4 leituras
  (atual em destaque), lista "Subiu/Caiu N pontos / Se manteve o mesmo" com data que abre o
  motivo, "Como funciona" e legenda das faixas.
- `risk_score` de cada log continua 0 no backend: zero e tratado como "sem pontuacao"
  (`meaningfulScore()`) nos cards de mensagem.

### Nivel de risco (RiskLevel)

`RiskLevel` (em `FraudApiClient.kt`) e a fonte unica do nivel exibido em cards, filtros, contadores,
detalhe e pop-up. Vem do `status_final` do veredito hibrido:

- `fraude` (LLM e modelo local concordam) -> `HIGH` ("Alto risco" / "Alta probabilidade de golpe");
- `aviso` (so um acusou) -> `ATTENTION` ("Atenção"); nao conta como golpe confirmado;
- `seguro` -> `SAFE`;
- sem veredito hibrido -> `is_fraud` decide (HIGH ou SAFE).

Nao usar `isFraud` direto na UI: o backend grava `is_fraud=true` tambem para `aviso`.

### Modo tecnico

O bloco "Como foi decidido" (votos do LLM e do modelo local) aparece para todos os usuarios, dentro
do expansor "Detalhes técnicos" do detalhe da analise (`TechnicalDetails`), recolhido por padrao.
Com o modo tecnico ligado (`SettingsRepository.technicalMode`, exposto via `LocalTechnicalMode`) ele
ja abre expandido; conta com papel `admin` liga o modo tecnico automaticamente. No build debug, 7 toques seguidos no logo
ligam/desligam o modo (demonstracao); ligado, aparece o painel "Modo tecnico" no Perfil. No build
de release os toques nao fazem nada.

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

Pela linha de comando (Windows), use o JDK do Android Studio e o SDK:
`JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` e `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`
(nao ha `local.properties` no repositorio). Comando: `gradlew assembleDebug testDebugUnitTest`.

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
- Indice de vulnerabilidade vem do `GET /score`; nao recalcular no app nem chamar
  `/admin/calculate-scores` (recalcula todos os usuarios; e tarefa agendada do backend).
- Nunca trocar `applicationId`/pacote `com.example.antifraudagent`, o banco `antifraud_database` nem
  as SharedPreferences: o Android trataria como outro app ou o usuario perderia dados.
- `DEBUG_CAPTURE` do `FraudAccessibilityService` fica `false` no codigo publicado.

## Checklist antes de entregar

- Gradle Sync funcionando no Android Studio.
- App compila com JDK embutido do Android Studio.
- Permissoes de SMS, notificacoes e acessibilidade aparecem corretamente.
- Captura por notificacao, SMS e acessibilidade ainda funciona.
- Offline cria registros `PENDING`.
- Reonline processa fila pendente.
- Historico carrega na hora mesmo com fila grande; card mostra "enviando" durante o envio.
- Pendencia so sai do Room com `status_db=true`.
- `Atualizar` consulta `/logs` com o token; 401 volta para o login.
- `Historico` mostra dados vindos do backend AWS (DynamoDB).
- `Analisar` envia `source=manual`, mostra score/explicacao e grava no DynamoDB.
- Kill switch da aba Perfil pausa envio e nao acumula em Room enquanto desligado.
- `LocalMessagePreprocessor` rejeita ruido conhecido antes de chegar ao backend; aba `Analisar` ignora esse filtro.
- `FraudAccessibilityService` nao captura conteudo do proprio app nem de apps fora do alvo (validacao em `processWindow`).
- `./gradlew :app:testDebugUnitTest` passa (contatos confiaveis, resumo de notificacoes, `LinkCode`,
  alerta unico por mensagem).
- Golpe recebido por notificacao e depois lido na conversa alerta uma unica vez.
- Reinstalar o app nao reenvia a fila antiga (log "PENDING restaurados descartados").
- Entrar / criar conta / sair funcionam; papel `admin` mostra "Como foi decidido".
- Contato confiavel: atencao nao alerta; alto risco alerta com aviso de conta clonada.
- Protecao reforcada (debug): `DebugEndCallReceiver` encerra ligacao e simula mensagem HIGH.
- Sem sessao aparece o login; entrar/sair/sessao expirada funcionam.
- Indice: card com velocimetro de 0 a 1000; mudanca gera entrada com motivo no "Historico" (barras + variacoes).
- Responsavel: QR do responsavel lido no celular protegido; golpe HIGH manda SMS (debug: `--es mode message`).

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
