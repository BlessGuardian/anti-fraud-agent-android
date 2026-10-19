# BlessGuardian | Android

> Detecção em tempo real de golpes digitais humanos e gerados por IA, diretamente no dispositivo.

---

## Sobre o projeto

O BlessGuardian é um aplicativo Android desenvolvido como Trabalho de Conclusão de Curso (TCC) do curso de Ciências da Computação do **Instituto Mauá de Tecnologia (IMT)**.

O Brasil é um dos países com maior incidência de golpes digitais do mundo. O elo mais fraco da cadeia de segurança é o humano — pessoas que recebem mensagens fraudulentas via WhatsApp, Telegram, SMS e Instagram e, por pressão emocional ou falta de informação, acabam sendo enganadas.

Este app nasce para preencher essa lacuna: **proteger o usuário em tempo real, no momento em que a mensagem chega, antes de ele tomar uma decisão prejudicial.** Idosos e crianças podem ter proteção reforçada, com um responsável avisado quando um golpe é detectado.

---

## Equipe

| Nome | Papel |
|---|---|
| Ramon Santos Pereira | Desenvolvedor (app Android) |
| Luiz Miguel Seixeiro | Desenvolvedor |
| Mitchell Miyake | Desenvolvedor (backend) |

**Orientador:** Prof. Rodrigo Bossini Tavares

---

## Arquitetura

```
[Celular Android]
      │
      ├── Captura
      │     ├── MessageListenerService    → notificações (WhatsApp, Telegram, Instagram, SMS)
      │     ├── FraudAccessibilityService → mensagens na tela aberta + proteção de ligações
      │     └── SmsReceiver               → SMS direto
      │
      ├── Pré-processamento local (LocalMessagePreprocessor)
      │     └── descarta ruído (horários, datas, avisos de sistema, resumos de notificação)
      │         e duplicatas — não calcula score
      │
      ├── Room DB (SQLite local, v4)
      │     ├── fila PENDING (sem internet; máx. 100)
      │     ├── histórico de ligações
      │     └── contatos confiáveis
      │
      └── Backend FastAPI (AWS API Gateway + Lambda)
            ├── POST /detect   → análise híbrida: LLM + modelo ONNX local
            │                     fraude (os dois acusam) · aviso (só um) · seguro
            ├── GET  /logs     → histórico oficial (DynamoDB)
            └── POST /register e /login → conta (JWT)
```

O nível de risco mostrado ao usuário vem do veredito híbrido: **alto risco** só quando os dois algoritmos concordam; **atenção** quando só um acusa.

---

## Funcionalidades

| Funcionalidade | Status |
|---|---|
| Captura por notificação, acessibilidade (tela aberta) e SMS | ✅ |
| Pré-processamento local: higiene, ruído e deduplicação antes do servidor | ✅ |
| Fila offline (Room) que não reenvia a fila restaurada do backup | ✅ |
| Análise híbrida no servidor com nível de risco: alto risco · atenção · seguro | ✅ |
| Alerta para o leigo (pop-up só em alto risco) e relatório completo no detalhe | ✅ |
| Modo técnico ("Como foi decidido": voto de cada algoritmo) para administrador | ✅ |
| Análise manual: colar o texto da mensagem | ✅ |
| Análise de print e "compartilhar com o BlessGuardian" | ⏳ botões ainda desativados |
| Proteção de ligações: transcrição ao vivo no viva-voz e alerta de golpe | ✅ |
| Contatos confiáveis, com seleção pela agenda (sem permissão de ler contatos) | ✅ |
| Proteção reforçada: em golpe confirmado, encerra a ligação e fecha o app da conversa | ✅ |
| Idosos e crianças: responsável cadastrado por QR e avisado por SMS em golpe confirmado | ✅ |
| Aviso ao responsável por e-mail e no app dele | ⏳ aguardando backend |
| Login obrigatório com a conta BlessGuardian (histórico e índice exigem token) | ✅ |
| Índice de vulnerabilidade estilo Serasa: valor do servidor, variação, motivo e histórico | ✅ |
| Leaderboard de golpes frequentes | ⏳ aguardando backend |

### Proteção de ligações

Automática: não há botão de iniciar. Quando uma ligação **recebida** é atendida, o app pede o viva-voz, transcreve a conversa ao vivo e manda trechos para análise. Regras locais (Pix, senha, token, urgência, acesso remoto) dão um alerta imediato. **Nenhum áudio é salvo**: só a transcrição fica no aparelho e vai ao servidor com `source=call`.

Exige o serviço de acessibilidade ativo: durante ligações, o Android só libera o microfone para apps com acessibilidade.

### Idosos e crianças

- **No celular do responsável:** Perfil → "Proteger alguém" → preencha nome, celular e e-mail. O app mostra um QR com esses dados.
- **No celular da pessoa protegida:** Perfil → "Este celular é de uma pessoa protegida" (liga a proteção reforçada) → "Meus responsáveis" → ler o QR de cada responsável.
- Quando um golpe de **alto risco** é confirmado, cada responsável recebe um **SMS na hora**, sem o texto do golpe (no máximo 1 a cada 10 minutos por responsável).

O e-mail do responsável já fica guardado; o envio por e-mail e o aviso no app do responsável dependem do backend.

### Índice de vulnerabilidade

Valor de 0 a 1 calculado no servidor, comparando o usuário com os demais. Como no Serasa, ele só muda quando há um motivo: o card mostra se subiu ou caiu e quando, e "Detalhes" mostra o valor anterior, o atual, o motivo e o histórico.

---

## Stack

- **Linguagem:** Kotlin 2.0.21
- **UI:** Jetpack Compose (Material 3)
- **Banco local:** Room 2.6.1 (SQLite)
- **Assincronismo:** Coroutines 1.7.3
- **QR code:** ZXing (geração) e Google Code Scanner (leitura, sem permissão de câmera)
- **Build:** AGP 8.13.2, KSP 2.0.21-1.0.28
- **Min SDK:** API 31 (Android 12)
- **Target SDK:** API 36

---

## Estrutura do projeto

```
app/src/main/java/com/example/antifraudagent/
├── MainActivity.kt                  → abas Início, Histórico, Analisar, Ligações e Perfil
├── BlessGuardianApplication.kt      → envia a fila ao voltar a internet
├── AnalysisDetail.kt                → detalhe de qualquer alerta
├── AccountUi.kt                     → entrar, criar conta, sair
├── GuardianUi.kt                    → idosos e crianças (QR do responsável, SMS)
├── ScoreUi.kt                       → índice de vulnerabilidade e detalhes
├── TrustedContactsUi.kt             → contatos confiáveis
├── ReinforcedProtectionUi.kt        → proteção reforçada
├── CallProtectionScreen.kt          → aba Ligações
├── calls/                           → proteção de ligações, alertas e encerramento de chamada
├── data/
│   ├── auth/                        → sessão da conta (token fora do backup)
│   ├── guardian/                    → vínculo com o responsável e verificação periódica
│   ├── local/                       → Room (fila, ligações, contatos) e pré-processamento
│   ├── remote/FraudApiClient.kt     → contrato com o backend e nível de risco
│   ├── repository/MessageRepository.kt → captura → análise → alerta
│   ├── settings/                    → preferências do Perfil
│   └── trusted/                     → regra dos contatos confiáveis
├── receivers/SmsReceiver.kt
├── services/                        → MessageListenerService e FraudAccessibilityService
└── ui/theme/
```

Detalhes de arquitetura, contratos e regras de negócio para quem for alterar o código: [AGENTS.md](AGENTS.md).

---

## Banco de dados local

O histórico oficial de mensagens fica no servidor (DynamoDB). No aparelho, o Room guarda:

| Tabela | Conteúdo |
|---|---|
| `analyzed_messages` | Fila `PENDING`: mensagens capturadas sem internet. Máx. 100; quando cheia, sai a de menor prioridade |
| `call_transcripts` | Histórico de ligações: transcrição, veredito e trecho que gerou o alerta |
| `trusted_contacts` | Contatos confiáveis (nome e número). Nunca vai ao servidor |

---

## Permissões

| Permissão | Para quê |
|---|---|
| `RECEIVE_SMS`, `READ_SMS` | Analisar SMS recebidos |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Enviar para análise e saber quando há conexão |
| `POST_NOTIFICATIONS`, `VIBRATE` | Avisar sobre golpes |
| `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Transcrever ligações (nenhum áudio é salvo) |
| `READ_PHONE_STATE` | Saber quando uma ligação é atendida |
| `ANSWER_PHONE_CALLS` | Proteção reforçada: encerrar ligação de golpe (pedida só ao ligar a opção) |
| `SEND_SMS` | Avisar o responsável por SMS em golpe confirmado (pedida só ao cadastrar o primeiro responsável) |
| `SYSTEM_ALERT_WINDOW` | Aviso por cima da tela quando a acessibilidade não está ativa |

Além disso, o usuário habilita manualmente:
- **Acesso a notificações** → `MessageListenerService`
- **Acessibilidade** → `FraudAccessibilityService` (mensagens na tela, ligações e proteção reforçada)

---

## Como executar

1. Clone o repositório
```bash
git clone https://github.com/BlessGuardian/anti-fraud-agent-android.git
```

2. Abra no **Android Studio** e sincronize o Gradle (`Sync Now`)

3. Conecte um dispositivo com **API 31+** via USB com depuração ativada

4. Execute com `Shift+F10`

5. No app, ative **Perfil → Proteção contínua** (notificações e acessibilidade)

Testes unitários:
```bash
./gradlew testDebugUnitTest
```

### Demonstração (build debug)

Roteiro completo da apresentação (preparação, cenas, plano B e limitações): [docs/ROTEIRO_DEMONSTRACAO.md](docs/ROTEIRO_DEMONSTRACAO.md).

- **7 toques no logo** ligam e desligam o modo técnico.
- O mesmo celular pode ser responsável e pessoa protegida ao mesmo tempo (cadastre o próprio número em "Meus responsáveis" para receber o SMS de teste).
- Simular golpe sem uma mensagem real:
```bash
adb shell am broadcast -n com.example.antifraudagent/.debug.DebugEndCallReceiver --es mode message
```

---

## Repositório relacionado

Este repositório contém apenas o app Android. O servidor Python (FastAPI + AWS) está em [fraud_message_detection](https://github.com/BlessGuardian/fraud_message_detection), na organização [BlessGuardian](https://github.com/BlessGuardian).

---

## Contexto acadêmico

**Instituição:** Instituto Mauá de Tecnologia (IMT)  
**Curso:** Ciências da Computação  
**Tipo:** Trabalho de Conclusão de Curso (TCC) — 2026
