# Roteiro de demonstração — BlessGuardian (app Android)

Roteiro para banca, feira (Eureka) e reuniões com o orientador. Tempo estimado: **8 a 10 minutos**.
Tudo aqui foi testado no Galaxy S24 em 19/10/2026 contra o backend na AWS (índice no formato 0 a 1000 conferido em 20/10).

---

## 1. Preparação (no dia, antes de apresentar)

### Aparelhos
- **Ideal:** dois celulares.
  - **Celular A, "Vó Maria" (pessoa protegida):** com o app instalado via cabo (build debug).
  - **Celular B, "Responsável":** só precisa receber SMS. O app é opcional; serve para mostrar o QR de "Proteger alguém".
- **Com um celular só:** o próprio celular faz os dois papéis, cadastrando o seu número como responsável. Foi assim que testamos.

### Build
- Instale o **build debug** pelo cabo (`gradlew assembleDebug` + `adb install -r`). Os gatilhos de teste e os 7 toques no logo **só existem no debug**.
- Mantenha a tela acesa: Opções do desenvolvedor → **Permanecer ativo** (só vale no carregador).

### Conta
- **Entre no app no dia**: o token vale 24 h e não há renovação, e o login é obrigatório.
- Não há "esqueci minha senha". Confira a senha antes.

### Permissões (no celular A)
1. Perfil → **Proteção contínua**: ative **Acesso a notificações** e **Acessibilidade** do BlessGuardian. O topo deve mudar de "Sem proteção" para **"Protegido"**.
2. Aba **Ligações** → ativar a proteção de ligações (pede microfone e telefone).
3. Perfil → **Envio para o servidor** ligado.

### Backend
- `GET https://e5skpalp7g.execute-api.us-east-1.amazonaws.com/health` deve responder `{"status":"healthy"}`.
- Para o **índice mudar** na demonstração, peça ao Mitchell um recálculo (`POST /admin/calculate-scores`) **depois** de gerar alguns golpes de teste. Ainda não há recálculo agendado.

### Textos de golpe prontos (para colar na aba Analisar)
- **Alto risco (os dois algoritmos concordam):**
  `URGENTE Banco: seu CPF foi bloqueado. Pague a taxa de R$ 89,90 via Pix em bit.ly/regulariza-cpf até hoje ou sua conta bancária será encerrada.`
- **Mensagem segura:**
  `Oi, chego aí às 19h pra gente jantar, beleza?`

---

## 2. Roteiro

### Cena 1 — O problema (30 s)
"O Brasil é um dos países com mais golpes digitais. O elo mais fraco é a pessoa que recebe a mensagem. O BlessGuardian avisa **no momento em que a mensagem chega**, antes de a pessoa agir."

### Cena 2 — Login e tela inicial (1 min)
- Abra o app: tela de login (obrigatório). Explique que a proteção funciona no aparelho e que a conta guarda o histórico e o índice no servidor.
- **Início:** índice de vulnerabilidade, mensagens analisadas, golpes bloqueados e alertas recentes.

### Cena 3 — Análise de golpe e alerta para o leigo (2 min)
1. Aba **Analisar** → cole o texto de **alto risco** → "Analisar agora". Resultado: **"Alta probabilidade de golpe"**.
2. Toque no resultado: **Detalhes** com o "Por quê?" e o "O que fazer agora", numa linguagem simples para o leigo.
3. **Modo técnico** (7 toques no logo): reabra os Detalhes e mostre **"Como foi decidido"**: o voto da IA (LLM) e do modelo local (ONNX).
   - Os dois acusam → **alto risco**. Só um → **atenção**. Nenhum → seguro.
   - Para usuário comum isso fica escondido; o administrador vê.
4. Cole a **mensagem segura** e mostre "baixo risco".

### Cena 4 — Alerta em tempo real (1 min)
- Com a proteção contínua ligada, mande uma mensagem de golpe pelo WhatsApp **de outro celular** para o celular A. Aparece o pop-up e a notificação.
- **Sem segundo celular, use o gatilho de teste (debug):**
  ```bash
  adb shell am broadcast -n com.example.antifraudagent/.debug.DebugEndCallReceiver --es mode message
  ```
  Simula um golpe de alto risco no WhatsApp: pop-up "⚠ Alta probabilidade de GOLPE" e notificação. A mensagem leva o marcador "[TESTE]".

### Cena 5 — Contatos confiáveis (1 min)
- Perfil → **Contatos confiáveis** → "Escolher da agenda" (não pede acesso à agenda inteira).
- Explique: de contato conhecido, "atenção" não alerta, mas **alto risco alerta mesmo assim**, porque a conta pode ter sido clonada.

### Cena 6 — Idosos e crianças (2 min) ⭐
1. **Celular B (responsável):** Perfil → **Proteger alguém** → nome e celular → aparece o **QR**.
2. **Celular A (Vó Maria):** Perfil → **Este celular é de uma pessoa protegida**. Liga a **proteção reforçada** e abre **Meus responsáveis** → **Ler QR do responsável** (ou "Digitar os dados").
3. Gere um golpe de alto risco no celular A (Cena 4).
4. Resultado no celular A: o app **fecha o WhatsApp** e mostra "⛔ Mensagem de GOLPE bloqueada".
5. Resultado no celular B: chega o **SMS na hora**:
   > *BlessGuardian: possível golpe (mensagem no WhatsApp) detectado no celular de Vó Maria às 14:49. Converse com a pessoa antes de qualquer Pix, senha ou código.*
   - O SMS não leva o texto do golpe (privacidade).
   - No máximo 1 SMS a cada 10 min por responsável.
   - Se o celular B tiver o BlessGuardian, ele **não** acusa esse SMS como golpe.

### Cena 7 — Ligação de golpe (1 min, opcional)
- Com a proteção de ligações ligada, atenda uma ligação no **viva-voz** e fale um roteiro de golpe ("aqui é do banco, preciso do código que chegou por SMS para cancelar o Pix…").
- A transcrição aparece ao vivo e o alerta sai durante a ligação. Com a proteção reforçada, o app **encerra a ligação** quando o servidor confirma alto risco.
- **Gatilho de teste** (encerra a ligação em andamento):
  ```bash
  adb shell am broadcast -n com.example.antifraudagent/.debug.DebugEndCallReceiver
  ```

### Cena 8 — Índice de vulnerabilidade, estilo Serasa (1 min)
- No Início, mostre o **velocímetro de 0 a 1000** com as faixas (baixa, média, alta) e a última variação ("▼ caiu 375 pontos · atualizado em 18/10").
- Toque no card → **Histórico**: barras das últimas leituras (a atual em destaque) e a lista "Subiu/Caiu N pontos" com a data. Toque numa variação para mostrar o **motivo** (ex.: "Subiu porque você teve 2 golpes novos desde 15/10 (phishing).").
- Explique: o índice compara o usuário com os demais e só muda quando há motivo. **Ao contrário do Serasa, aqui subir é ruim.**
- Se o índice ainda não mudou nenhuma vez, o histórico mostra só a "Primeira leitura". Para ter variações, peça o recálculo antes (ver Preparação).

### Encerramento (30 s)
- "Nenhum áudio de ligação é salvo; a lista de contatos não sai do celular; o SMS ao responsável não leva o texto do golpe."
- Próximos passos (ver abaixo).

---

## 3. Se algo der errado (plano B)

| Sintoma | O que fazer |
|---|---|
| Topo mostra **"Sem proteção"** | Perfil → Proteção contínua (notificações + acessibilidade) |
| App pediu **login de novo** | Token venceu (24 h). Entre de novo |
| "Não foi possível consultar o servidor" | Testar `/health`. Se o backend caiu, demonstre as Cenas 4, 5 e 6 com os gatilhos (não dependem do histórico) |
| Análise demora | O LLM leva alguns segundos; aguarde ou use os gatilhos de teste |
| SMS não chegou | Conferir "SMS: ativo" em Meus responsáveis; respeitar o intervalo de 10 min |
| Pop-up não aparece | Acessibilidade desligada: ative em Proteção contínua |
| Índice não mudou | Ainda não há recálculo agendado: pedir `POST /admin/calculate-scores` |

---

## 4. Limitações conhecidas (para citar como trabalho futuro)
- O histórico é ligado ao **celular de cadastro**: contas criadas no mesmo aparelho compartilham o histórico, e golpes detectados em outro celular não entram na conta.
- Recálculo do índice ainda manual; token sem renovação; sem "esqueci minha senha".
- Aviso ao responsável por **e-mail** e **no app** do responsável: aguardando o backend (hoje, SMS).
- Segurança do backend a reforçar antes de uso público (endpoints de exclusão sem login, validação do cadastro, limites no `/detect`) e retenção de dados (LGPD).
- Leaderboard de golpes e busca do número em vazamentos: no site (Luiz).

Detalhes técnicos e lista completa: [AGENTS.md](../AGENTS.md) → "Pendencias do backend".

---

## 5. Depois da demonstração
- Perfil → desligar "Este celular é de uma pessoa protegida" (e a proteção reforçada) e remover os responsáveis de teste, senão cada golpe real gera SMS.
- Desligar o modo técnico (7 toques no logo).
