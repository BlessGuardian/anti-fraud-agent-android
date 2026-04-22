# BlessGuardian | Android

> Detecção em tempo real de golpes digitais humanos e gerados por IA, diretamente no dispositivo.

---

## Sobre o projeto

O AntiFraud Agent é um aplicativo Android desenvolvido como Trabalho de Conclusão de Curso (TCC) do curso de Ciências da Computação do **Instituto Mauá de Tecnologia (IMT)**.

O Brasil é um dos países com maior incidência de golpes digitais do mundo. O elo mais fraco da cadeia de segurança é o humano — pessoas que recebem mensagens fraudulentas via WhatsApp, Telegram, SMS e Instagram e, por pressão emocional ou falta de informação, acabam sendo enganadas.

Este app nasce para preencher essa lacuna: **proteger o usuário em tempo real, no momento em que a mensagem chega, antes de ele tomar uma decisão prejudicial.**

---

## Equipe

| Nome | Papel |
|---|---|
| Ramon | Desenvolvedor |
| Luiz Miguel | Desenvolvedor |
| Mitchell Miyake | Desenvolvedor |

---

## Arquitetura

```
[Celular Android]
      │
      ├── Camada de Captura
      │     ├── MessageListenerService    → notificações (app em background)
      │     ├── FraudAccessibilityService → tela aberta (app em foreground)
      │     └── SmsReceiver               → SMS direto
      │
      ├── Camada 1 — Heurística Local (offline, sem internet)
      │     └── Score 0.0 a 1.0
      │           • score < 0.4  → descartado em memória
      │           • score ≥ 0.4  → salvo no Room DB como PENDING
      │
      ├── Room DB (SQLite local)
      │     └── Fila de PENDING (máx. 100 registros)
      │
      └── Quando há internet
            └── HTTP POST → Servidor FastAPI (AWS)
                      │
                      └── Retorna: score, fraudType, explanation
```

---

## Funcionalidades implementadas

| # | Funcionalidade | Status |
|---|---|---|
| 1 | Captura via NotificationListenerService (WhatsApp, Telegram, Instagram) | ✅ |
| 2 | Captura via SmsReceiver (SMS) | ✅ |
| 3 | Captura via AccessibilityService (tela aberta) | ✅ |
| 4 | Room Database local com fila de pendentes e evicção por score | ✅ |
| 5 | Popup de ligação com overlay e ativação automática do viva-voz | 🔄 |
| 6 | Heurística local — padrões de golpe brasileiros (Camada 1) | 🔄 |
| 7 | Integração com servidor FastAPI na AWS (Camada 2) | 🔄 |
| 8 | Modo de análise retroativa (colar texto / compartilhar / OCR) | 🔄 |
| 9 | Interface completa — dashboard, alertas, histórico, perfil de risco | 🔄 |

---

## Stack

- **Linguagem:** Kotlin 2.0.21
- **UI:** Jetpack Compose
- **Banco local:** Room 2.6.1 (SQLite)
- **Assincronismo:** Coroutines 1.7.3
- **Build:** AGP 8.13.2, KSP 2.0.21-1.0.29
- **Min SDK:** API 31 (Android 12)
- **Target SDK:** API 36

---

## Estrutura do projeto

```
app/src/main/
├── AndroidManifest.xml
└── java/com/example/antifraudagent/
    ├── MainActivity.kt
    ├── data/
    │   ├── local/
    │   │   ├── entity/AnalyzedMessage.kt       → entidade Room
    │   │   ├── dao/AnalyzedMessageDao.kt        → queries do banco
    │   │   └── database/AppDatabase.kt          → singleton
    │   └── repository/MessageRepository.kt      → lógica de negócio
    ├── receivers/
    │   └── SmsReceiver.kt
    ├── services/
    │   ├── MessageListenerService.kt
    │   └── FraudAccessibilityService.kt
    └── ui/theme/
```

---

## Banco de dados local

A tabela `analyzed_messages` armazena apenas mensagens com score ≥ 0.4 — mensagens sem suspeita são descartadas em memória e nunca persistidas.

| Campo | Tipo | Descrição |
|---|---|---|
| id | Long PK | Identificador único |
| sender | String | App de origem (ex: com.whatsapp) |
| content | String | Conteúdo da mensagem |
| source | Enum | WHATSAPP, TELEGRAM, INSTAGRAM, SMS, UNKNOWN |
| layer1Score | Float | Score da heurística local (0.0 a 1.0) |
| layer2Score | Float? | Score do servidor Python (null se pendente) |
| riskScore | Float? | Score final combinado (null se pendente) |
| fraudType | String? | Tipo do golpe detectado |
| explanation | String? | Justificativa do algoritmo |
| status | Enum | PENDING, CONFIRMED_FRAUD, DISMISSED |
| capturedAt | Long | Timestamp Unix em ms |

**Limite da fila PENDING:** 100 registros. Quando cheia, o registro com menor `layer1Score` é removido para dar lugar ao novo (prioriza as suspeitas mais graves).

---

## Permissões necessárias

```xml
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Além disso, o usuário deve habilitar manualmente:
- **Acesso a notificações** → para o `MessageListenerService`
- **Acessibilidade** → para o `FraudAccessibilityService`

---

## Como executar

1. Clone o repositório
```bash
git clone https://github.com/BlessGuard/anti-fraud-agent-android.git
```

2. Abra no **Android Studio**

3. Sincronize o Gradle (`Sync Now`)

4. Conecte um dispositivo com **API 31+** via USB com depuração ativada

5. Execute com `Shift+F10`

6. No dispositivo, conceda as permissões de notificação e acessibilidade nas configurações

---

## Repositório relacionado

Este repositório contém apenas o app Android. O servidor Python (FastAPI + AWS) e o painel de inteligência coletiva (Streamlit) estão em repositório separado na organização [BlessGuard](https://github.com/BlessGuard).

---

## Contexto acadêmico

**Instituição:** Instituto Mauá de Tecnologia (IMT)  
**Curso:** Ciências da Computação  
**Tipo:** Trabalho de Conclusão de Curso (TCC) — 2026
