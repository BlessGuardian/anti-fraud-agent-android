package com.example.antifraudagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antifraudagent.calls.CallAudioRoute
import com.example.antifraudagent.calls.CallProtectionSnapshot
import com.example.antifraudagent.calls.CallRiskLevel
import com.example.antifraudagent.data.local.call.CallTranscript
import com.example.antifraudagent.data.local.call.CallTranscriptSyncStatus
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessDangerSoft
import com.example.antifraudagent.ui.theme.BlessMuted
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessPrimarySoft
import com.example.antifraudagent.ui.theme.BlessSafe
import com.example.antifraudagent.ui.theme.BlessSafeSoft
import com.example.antifraudagent.ui.theme.BlessSurfaceElevated
import com.example.antifraudagent.ui.theme.BlessText
import com.example.antifraudagent.ui.theme.BlessWarning
import com.example.antifraudagent.ui.theme.BlessWarningSoft

/** O que a protecao de chamadas precisa para funcionar sozinha. */
data class CallRequirements(
    val protectionEnabled: Boolean,
    /** Microfone + estado do telefone. */
    val permissionsGranted: Boolean,
    /** Acessibilidade: libera o microfone durante a ligacao e os avisos sobre a tela da chamada. */
    val accessibilityEnabled: Boolean,
    val overlayGranted: Boolean,
    /** Opcional: identifica o numero de quem liga. */
    val screeningEnabled: Boolean
) {
    val ready: Boolean get() = protectionEnabled && permissionsGranted && accessibilityEnabled
}

@Composable
fun CallProtectionScreen(
    padding: PaddingValues,
    requirements: CallRequirements,
    liveCall: CallProtectionSnapshot,
    transcripts: List<CallTranscript>,
    onEnabledChange: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestScreening: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRefresh: () -> Unit
) {
    val history = transcripts.filter { !(liveCall.active && it.sessionId == liveCall.sessionId) }
    val scams = history.count { it.alerted }
    val calm = history.count { !it.alerted && it.content.isNotBlank() }

    ScreenColumn(padding = padding) {
        item { PageIntro("Quando você atende uma ligação no viva-voz, o BlessGuardian ouve a conversa e avisa na hora se for golpe.") }

        item { ProtectionHero(requirements, onEnabledChange) }

        if (requirements.protectionEnabled && !requirements.ready) {
            item {
                GlassPanel {
                    PanelLabel("FALTA POUCO")
                    RequirementRow(
                        title = "Microfone e chamadas",
                        subtitle = "Para ouvir a ligação e saber quando você atende e desliga.",
                        done = requirements.permissionsGranted,
                        action = "Permitir",
                        onAction = onRequestPermissions
                    )
                    RequirementRow(
                        title = "Acessibilidade do BlessGuardian",
                        subtitle = "O Android só libera o microfone durante ligações para apps com acessibilidade ativa.",
                        done = requirements.accessibilityEnabled,
                        action = "Ativar",
                        onAction = onRequestAccessibility
                    )
                }
            }
        }

        if (requirements.ready && (!requirements.overlayGranted || !requirements.screeningEnabled)) {
            item {
                GlassPanel {
                    PanelLabel("RECOMENDADO")
                    if (!requirements.screeningEnabled) {
                        RequirementRow(
                            title = "Identificar quem liga",
                            subtitle = "Mostra o número no histórico de ligações.",
                            done = false,
                            action = "Permitir",
                            onAction = onRequestScreening
                        )
                    }
                    if (!requirements.overlayGranted) {
                        RequirementRow(
                            title = "Alertas sobre outros apps",
                            subtitle = "Reserva para o pop-up de golpe caso a acessibilidade seja desligada.",
                            done = false,
                            action = "Permitir",
                            onAction = onRequestOverlay
                        )
                    }
                }
            }
        }

        if (liveCall.active) {
            item { LiveCallPanel(liveCall) }
        } else if (requirements.ready) {
            item { HowItWorks() }
        }

        item {
            SectionTitle(title = "Histórico de ligações", action = "Atualizar", onAction = onRefresh)
        }

        if (history.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Warning,
                        iconColor = BlessDanger,
                        value = scams.toString(),
                        label = if (scams == 1) "golpe evitado" else "golpes evitados"
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Shield,
                        iconColor = BlessSafe,
                        value = calm.toString(),
                        label = if (calm == 1) "ligação tranquila" else "ligações tranquilas"
                    )
                }
            }
        }

        if (history.isEmpty()) {
            item { EmptyCard("As ligações que você atender com a proteção ativa aparecem aqui, com o veredito de cada uma.") }
        } else {
            items(history, key = { it.sessionId }) { transcript ->
                CallHistoryCard(transcript)
            }
        }
    }
}

@Composable
private fun ProtectionHero(requirements: CallRequirements, onEnabledChange: (Boolean) -> Unit) {
    val (title, body, color, soft) = when {
        !requirements.protectionEnabled -> HeroText(
            "Proteção de ligações desligada",
            "Ligue para o BlessGuardian ouvir as ligações que você atender e avisar sobre golpes.",
            BlessWarning,
            BlessWarningSoft
        )
        !requirements.ready -> HeroText(
            "Falta pouco para proteger suas ligações",
            "Libere os itens abaixo para a proteção funcionar sozinha.",
            BlessWarning,
            BlessWarningSoft
        )
        else -> HeroText(
            "Você está protegido para ligações",
            "Ao atender, ative o viva-voz. A conversa é analisada em tempo real e nenhum áudio é guardado.",
            BlessSafe,
            BlessSafeSoft
        )
    }
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(soft)
                    .border(1.dp, color.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp, color = BlessText)
                Spacer(modifier = Modifier.height(4.dp))
                Text(body, color = BlessMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ProtectionToggleRow(
            title = "Proteger ligações recebidas",
            subtitle = "Começa sozinho quando você atende e para quando a ligação termina.",
            checked = requirements.protectionEnabled,
            onClick = { onEnabledChange(!requirements.protectionEnabled) }
        )
    }
}

private data class HeroText(val title: String, val body: String, val color: Color, val soft: Color)

@Composable
private fun RequirementRow(title: String, subtitle: String, done: Boolean, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = BlessText)
            Text(subtitle, color = BlessMuted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.width(10.dp))
        if (done) {
            Text("Pronto", color = BlessSafe, fontWeight = FontWeight.Bold)
        } else {
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun HowItWorks() {
    GlassPanel {
        PanelLabel("COMO FUNCIONA")
        StepRow(1, "Atenda a ligação normalmente.")
        StepRow(2, "Toque em Alto-falante na tela da chamada. O BlessGuardian avisa se você esquecer.")
        StepRow(3, "Se a conversa tiver sinais de golpe, um alerta aparece por cima da ligação.")
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(BlessPrimarySoft),
            contentAlignment = Alignment.Center
        ) {
            Text(number.toString(), color = BlessText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, color = BlessText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun LiveCallPanel(live: CallProtectionSnapshot) {
    val danger = live.alertShown || live.riskLevel == CallRiskLevel.HIGH
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhoneInTalk, contentDescription = null, tint = if (danger) BlessDanger else BlessPrimary)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("LIGAÇÃO EM ANDAMENTO", color = BlessMuted, style = MaterialTheme.typography.labelLarge)
                Text(live.callerNumber ?: "Número não identificado", fontWeight = FontWeight.Bold, color = BlessText)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (routeLabel, routeIcon, routeColor) = when (live.route) {
                CallAudioRoute.SPEAKER -> Triple("Viva-voz ativo", Icons.Filled.VolumeUp, BlessSafe)
                CallAudioRoute.HEADSET -> Triple("Fone conectado", Icons.Filled.Headset, BlessWarning)
                else -> Triple("Ative o viva-voz", Icons.Filled.VolumeUp, BlessWarning)
            }
            LiveChip(routeLabel, routeIcon, routeColor)
            if (live.hearingAudio) LiveChip("Ouvindo", Icons.Filled.Mic, BlessSafe)
            else LiveChip("Sem som", Icons.Filled.MicOff, BlessMuted)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(live.status, color = if (danger) BlessDanger else BlessText, fontWeight = FontWeight.SemiBold)
        live.alertReason?.let {
            Text(it, color = BlessDanger, modifier = Modifier.padding(top = 6.dp))
        }
        val shown = (live.transcript + " " + live.partial).trim()
        Text(
            text = if (shown.isBlank()) "A transcrição aparece aqui enquanto vocês conversam." else "…${shown.takeLast(420)}",
            color = if (shown.isBlank()) BlessMuted else BlessText,
            fontStyle = if (shown.isBlank()) FontStyle.Italic else FontStyle.Normal,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun LiveChip(label: String, icon: ImageVector, color: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CallHistoryCard(transcript: CallTranscript) {
    val openDetail = LocalOpenDetail.current
    val interrupted = transcript.content.isBlank() && transcript.endedAt == transcript.startedAt
    val (statusLabel, color, soft) = when {
        transcript.alerted -> Triple("Golpe detectado", BlessDanger, BlessDangerSoft)
        interrupted -> Triple("Interrompida", BlessMuted, BlessSurfaceElevated)
        transcript.content.isBlank() -> Triple("Sem fala", BlessMuted, BlessSurfaceElevated)
        transcript.syncStatus == CallTranscriptSyncStatus.PENDING ||
            transcript.syncError == com.example.antifraudagent.calls.CallTranscriptRepository.SENDING_NOTE ->
            Triple("Enviando", BlessPrimary, BlessPrimarySoft)
        else -> Triple("Tranquila", BlessSafe, BlessSafeSoft)
    }
    GlassPanel(onClick = { openDetail(transcript.toDetail()) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(soft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (transcript.alerted) Icons.Filled.Warning else Icons.Filled.PhoneInTalk,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    transcript.callerNumber ?: "Número não identificado",
                    fontWeight = FontWeight.Bold,
                    color = BlessText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${compactMillis(transcript.startedAt)} · ${formatDuration(transcript.endedAt - transcript.startedAt)}",
                    color = BlessMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                statusLabel,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(soft)
                    .border(1.dp, color.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        val preview = when {
            transcript.alerted && !transcript.alertExcerpt.isNullOrBlank() -> "“${transcript.alertExcerpt}”"
            transcript.content.isNotBlank() -> transcript.content
            interrupted -> "A proteção foi interrompida antes do fim da ligação."
            else -> transcript.captureIssue ?: "Nenhuma fala foi transcrita nesta ligação."
        }
        Text(
            preview,
            color = if (transcript.alerted) BlessText else BlessMuted,
            fontStyle = if (transcript.alerted) FontStyle.Italic else FontStyle.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (transcript.speakerUsed) "Viva-voz ativado" else "Sem viva-voz",
                color = BlessMuted,
                style = MaterialTheme.typography.bodySmall
            )
            DetailsHint()
        }
    }
}

private fun compactMillis(millis: Long): String =
    java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(millis))
