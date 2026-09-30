package com.example.antifraudagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.antifraudagent.calls.CallProtectionSnapshot
import com.example.antifraudagent.data.local.call.CallTranscript
import com.example.antifraudagent.data.local.call.CallTranscriptSyncStatus
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessSafe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CallProtectionScreen(
    padding: PaddingValues,
    enabled: Boolean,
    screeningEnabled: Boolean,
    overlayEnabled: Boolean,
    liveCall: CallProtectionSnapshot,
    transcripts: List<CallTranscript>,
    onEnabledChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onRequestScreening: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRefresh: () -> Unit
) {
    ScreenColumn(padding = padding) {
        item {
            PageIntro("Transcreva chamadas no viva-voz e receba alertas de golpe.")
        }
        item {
            GlassPanel {
                PanelLabel("PROTECAO DE CHAMADAS")
                ProtectionToggleRow(
                    title = "Monitorar ligacoes",
                    subtitle = "Mostra uma notificacao para iniciar a transcricao quando houver chamada.",
                    checked = enabled,
                    onClick = { onEnabledChange(!enabled) }
                )
                Text(
                    text = if (screeningEnabled) "Deteccao de chamada concedida." else "Conceda acesso para detectar chamadas telefonicas.",
                    color = if (screeningEnabled) BlessSafe else BlessDanger,
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (!screeningEnabled) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        onClick = onRequestScreening
                    ) { Text("Permitir deteccao de chamadas") }
                }
                if (!overlayEnabled) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onClick = onRequestOverlay
                    ) { Text("Permitir alerta sobre a chamada") }
                }
                Button(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    enabled = enabled && !liveCall.active,
                    colors = ButtonDefaults.buttonColors(containerColor = BlessPrimary),
                    onClick = onStart
                ) {
                    Icon(Icons.Filled.Phone, contentDescription = null)
                    Text(" Iniciar protecao agora")
                }
                Text(
                    text = "Antes de iniciar, habilite o viva-voz na tela nativa do telefone. O audio fica temporariamente no aparelho e e apagado ao finalizar; somente a transcricao e salva.",
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        item {
            GlassPanel {
                PanelLabel(if (liveCall.active) "TRANSCRICAO AO VIVO" else "ULTIMA SESSAO")
                Text(liveCall.status, fontWeight = FontWeight.SemiBold)
                if (liveCall.transcript.isBlank()) {
                    Text("Nenhuma fala transcrita ainda.", modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text(liveCall.transcript, modifier = Modifier.padding(top = 8.dp), maxLines = 10, overflow = TextOverflow.Ellipsis)
                }
                if (liveCall.riskScore >= 0.75f) {
                    Text("Alerta de alto risco emitido nesta sessao.", color = BlessDanger, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            SectionTitle(title = "Transcricoes salvas", action = "Atualizar", onAction = onRefresh)
        }
        if (transcripts.isEmpty()) {
            item { EmptyCard("As transcricoes finalizadas aparecerao aqui para leitura.") }
        } else {
            items(transcripts, key = { it.sessionId }) { transcript ->
                CallTranscriptCard(transcript)
            }
        }
    }
}

@Composable
private fun CallTranscriptCard(transcript: CallTranscript) {
    GlassPanel {
        val date = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
            .format(Date(transcript.endedAt))
        Text("Chamada de $date", fontWeight = FontWeight.Bold)
        Text(
            transcript.content,
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 7,
            overflow = TextOverflow.Ellipsis
        )
        val status = when (transcript.syncStatus) {
            CallTranscriptSyncStatus.ANALYZED -> if (transcript.isFraud == true) "Possivel fraude" else "Analise concluida"
            CallTranscriptSyncStatus.PENDING -> "Aguardando envio"
            CallTranscriptSyncStatus.FAILED -> "Falha no envio"
        }
        Text(
            text = transcript.explanation ?: transcript.syncError ?: status,
            color = if (transcript.isFraud == true) BlessDanger else BlessSafe,
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
