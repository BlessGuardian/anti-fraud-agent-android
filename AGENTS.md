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

`SettingsRepository` (em `data/settings/SettingsRepository.kt`) e um singleton com SharedPreferences que expoe a flag `capture_enabled` (default `true`) via `StateFlow`. Compose (aba Perfil) e `MessageRepository` observam a mesma instancia. Quando desligado, `saveIfSuspicious`, `analyzeManualMessage` e `processPendingMessages` retornam cedo sem tocar Room nem HTTP.

## Camada de pre-processamento local

Pacote: `com.example.antifraudagent.data.local.preprocessing`
Classe: `LocalMessagePreprocessor` (Kotlin `object`, estado compartilhado entre servicos)

Responsabilidade: higiene de dados antes de chegar ao backend.

- Normaliza espacos, tabs e quebras de linha.
- Rejeita texto vazio apos normalizacao.
- Rejeita horario isolado (ex: `12:35`, `08:01 AM`).
- Rejeita data ou separador de chat (ex: `Hoje`, `Ontem`, `25/12`, `12 de maio`).
- Rejeita ruidos de sistema conhecidos (backup, sincronizacao, WhatsApp Web,
  procurando mensagens, criptografia de ponta a ponta, conectado/desconectado,
  notificacoes genericas de midia como `foto`/`audio`/`video`).
- Rejeita texto que seja exatamente igual ao nome do remetente.
- Rejeita texto muito curto (< 10 chars) sem sinais de conteudo relevante
  (link, Pix, CPF, banco, senha, codigo, boleto, valor em R$, urgencia financeira).
- Deduplica capturas com mesmo conteudo normalizado dentro de janela de 15s,
  evitando que NotificationListener e AccessibilityService enviem a mesma
  mensagem duas vezes.

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
em `MainActivity.kt` a partir da media de `risk_score` dos logs retornados pelo
backend (`logs.map { riskScore }.average()`). Nao existe endpoint que devolva
esse agregado; nao tentar buscar do backend.

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
- Nao prometer captura de audio de chamadas como implementada.
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
