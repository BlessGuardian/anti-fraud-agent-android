# AGENTS.md - BlessGuardian Android

## Projeto

Repositorio Android do BlessGuardian / AntiFraud Agent.

Objetivo: aplicativo Android em Kotlin/Jetpack Compose para capturar mensagens suspeitas em tempo real, enviar ao backend FastAPI hospedado em AWS ECS e consultar historico oficial no DynamoDB.

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

O fluxo correto e:

```text
Mensagem capturada
-> SettingsRepository.isCaptureEnabled() (kill switch da aba Perfil)
-> filtro minimo local
-> se online: POST /detect
-> se offline: Room PENDING
-> backend grava no DynamoDB
-> historico vem de GET /logs?device_id=...
```

`SettingsRepository` (em `data/settings/SettingsRepository.kt`) e um singleton com SharedPreferences que expoe a flag `capture_enabled` (default `true`) via `StateFlow`. Compose (aba Perfil) e `MessageRepository` observam a mesma instancia. Quando desligado, `saveIfSuspicious`, `analyzeManualMessage` e `processPendingMessages` retornam cedo sem tocar Room nem HTTP.

## Contrato com backend

O Android deve enviar:

```json
{
  "device_id": "uuid-anonimo-do-aparelho",
  "message_content": "texto da mensagem capturada",
  "source": "sms"
}
```

Regras:

- Usar `device_id`, nunca `user_id`, no payload novo.
- `device_id` vem de `DeviceIdentityProvider`.
- `source` deve ser enviado em minusculas: `sms`, `whatsapp`, `telegram`, `instagram`, `manual` ou `unknown`.
- `FraudApiClient.DEFAULT_BASE_URL` aponta para o endpoint AWS ECS fixo (`bl-*.ecs.us-east-1.on.aws`).

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

- mostrar status das permissoes;
- mostrar quantidade de pendencias offline;
- ter secao `Alertas gravados`;
- ter aba `Analisar` para envio manual de mensagens suspeitas com `source=MANUAL`;
- consultar historico via `GET /logs?device_id=...`;
- nao usar Room como fonte do historico.

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

## Checklist antes de entregar

- Gradle Sync funcionando no Android Studio.
- App compila com JDK embutido do Android Studio.
- Permissoes de SMS, notificacoes e acessibilidade aparecem corretamente.
- Captura por notificacao, SMS e acessibilidade ainda funciona.
- Offline cria registros `PENDING`.
- Reonline processa fila pendente.
- Pendencia so sai do Room com `status_db=true`.
- `Atualizar` consulta `/logs?device_id=...`.
- `Alertas gravados` mostra dados vindos do backend AWS (DynamoDB).
- Kill switch da aba Perfil pausa envio e nao acumula em Room enquanto desligado.

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
