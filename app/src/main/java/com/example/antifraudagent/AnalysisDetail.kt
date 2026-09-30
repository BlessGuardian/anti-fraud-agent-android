package com.example.antifraudagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antifraudagent.data.local.call.CallTranscript
import com.example.antifraudagent.data.local.call.CallTranscriptSyncStatus
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.remote.HybridVerdict
import com.example.antifraudagent.data.remote.RemoteFraudLog
import com.example.antifraudagent.ui.theme.BlessBorder
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessDangerSoft
import com.example.antifraudagent.ui.theme.BlessMuted
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessSurface
import com.example.antifraudagent.ui.theme.BlessSurfaceElevated
import com.example.antifraudagent.ui.theme.BlessText
import com.example.antifraudagent.ui.theme.BlessWarning
import com.example.antifraudagent.ui.theme.BlessWarningSoft
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

// -----------------------------------------------------------------------------
// Modelo unico para o detalhe de qualquer card (historico, inicio, analise, ligacao)
// -----------------------------------------------------------------------------

enum class DetailKind { MESSAGE, MANUAL, CALL }

enum class DetailVerdict { FRAUD, SUSPICIOUS, SAFE, PENDING }

data class AnalysisDetail(
    val kind: DetailKind,
    val title: String,
    val subtitle: String?,
    val verdict: DetailVerdict,
    val score: Float?,
    val headline: String,
    val justification: String?,
    val reasoning: String?,
    val indicators: List<String>,
    val localIndicators: List<String>,
    val hybrid: HybridVerdict?,
    val excerpt: String?,
    val contentLabel: String,
    val content: String,
    val facts: List<Pair<String, String>>,
    val notice: String?
)

private val ptBR = Locale("pt", "BR")

fun sourceLabel(source: String): String = when (source.lowercase()) {
    "whatsapp" -> "WhatsApp"
    "instagram" -> "Instagram"
    "telegram" -> "Telegram"
    "sms" -> "SMS"
    "manual" -> "Análise manual"
    "call" -> "Ligação"
    "" , "unknown" -> "Origem desconhecida"
    else -> source.replaceFirstChar { it.titlecase(ptBR) }
}

/** "2026-09-30T12:33:05.123-03:00" -> "30/09/2026 às 12:33". */
fun formatBackendDateTime(raw: String): String = try {
    OffsetDateTime.parse(raw).format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", ptBR))
} catch (_: Exception) {
    compactDate(raw)
}

fun formatMillis(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", ptBR).format(Date(millis))

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}min ${seconds}s" else "${seconds}s"
}

private fun verdictFor(isFraud: Boolean, hybrid: HybridVerdict?): DetailVerdict = when {
    hybrid?.status?.equals("AVISO", ignoreCase = true) == true -> DetailVerdict.SUSPICIOUS
    isFraud -> DetailVerdict.FRAUD
    else -> DetailVerdict.SAFE
}

private fun headlineFor(verdict: DetailVerdict, isCall: Boolean): String = when (verdict) {
    DetailVerdict.FRAUD -> if (isCall) "Golpe detectado nesta ligação" else "Golpe detectado"
    DetailVerdict.SUSPICIOUS -> "Suspeita de golpe"
    DetailVerdict.SAFE -> if (isCall) "Ligação tranquila" else "Nenhum sinal de golpe"
    DetailVerdict.PENDING -> "Aguardando análise"
}

/** O veredito_curto hibrido e so a estrutura; a justificativa e o texto depois de "Justificativa". */
private fun readableJustification(hybrid: HybridVerdict?, raw: String?): String? =
    hybrid?.justification?.takeIf { it.isNotBlank() }
        ?: raw?.takeIf { it.isNotBlank() && HybridVerdict.parse(it) == null }

fun RemoteFraudLog.toDetail(): AnalysisDetail {
    val hybrid = hybrid
    val verdict = verdictFor(isFraud, hybrid)
    return AnalysisDetail(
        kind = if (source.equals("call", true)) DetailKind.CALL else DetailKind.MESSAGE,
        title = sourceLabel(source),
        subtitle = formatBackendDateTime(detectedAt),
        verdict = verdict,
        score = riskScore,
        headline = headlineFor(verdict, source.equals("call", true)),
        justification = readableJustification(hybrid, explanation),
        reasoning = null,
        indicators = emptyList(),
        localIndicators = emptyList(),
        hybrid = hybrid,
        excerpt = null,
        contentLabel = if (source.equals("call", true)) "Transcrição analisada" else "Mensagem completa",
        content = content.ifBlank { "Conteúdo não retornado pelo servidor." },
        facts = listOfNotNull(
            "Origem" to sourceLabel(source),
            "Data e hora" to formatBackendDateTime(detectedAt),
            "Pontuação de risco" to scorePercent(riskScore)
        ),
        notice = null
    )
}

fun FraudAnalysisResult.toDetail(analyzedText: String, analyzedAt: Long): AnalysisDetail {
    val verdictKind = verdictFor(isFraud, hybrid)
    return AnalysisDetail(
        kind = DetailKind.MANUAL,
        title = "Análise manual",
        subtitle = formatMillis(analyzedAt),
        verdict = verdictKind,
        score = score,
        headline = headlineFor(verdictKind, isCall = false),
        justification = readableJustification(hybrid, verdict) ?: explanation.takeIf { it.isNotBlank() },
        reasoning = reasoning.takeIf { it.isNotBlank() },
        indicators = indicators,
        localIndicators = emptyList(),
        hybrid = hybrid,
        excerpt = null,
        contentLabel = "Mensagem analisada",
        content = analyzedText,
        facts = listOfNotNull(
            "Categoria" to categoryLabel(category),
            "Pontuação de risco" to scorePercent(score),
            if (dbSynced) "Histórico" to "Gravado no histórico oficial" else null
        ),
        notice = null
    )
}

fun CallTranscript.toDetail(): AnalysisDetail {
    val hybridVerdict = HybridVerdict.parse(verdict) ?: HybridVerdict.parse(explanation)
    val verdictKind = when {
        alerted -> DetailVerdict.FRAUD
        content.isBlank() || syncStatus == CallTranscriptSyncStatus.PENDING ||
            syncError == com.example.antifraudagent.calls.CallTranscriptRepository.SENDING_NOTE -> DetailVerdict.PENDING
        else -> verdictFor(isFraud == true, hybridVerdict)
    }
    val headline = when {
        verdictKind == DetailVerdict.PENDING && content.isBlank() -> "Nenhuma fala transcrita"
        else -> headlineFor(verdictKind, isCall = true)
    }
    return AnalysisDetail(
        kind = DetailKind.CALL,
        title = callerNumber?.let { "Ligação de $it" } ?: "Ligação recebida",
        subtitle = formatMillis(startedAt),
        verdict = verdictKind,
        score = riskScore,
        headline = headline,
        // Veredito legivel da ligacao (ex.: motivo do alerta local) vem antes da justificativa do servidor.
        justification = verdict?.takeIf { it.isNotBlank() && HybridVerdict.parse(it) == null }
            ?: hybridVerdict?.justification,
        reasoning = reasoning?.takeIf { it.isNotBlank() },
        indicators = indicatorList,
        localIndicators = localIndicatorList,
        hybrid = hybridVerdict,
        excerpt = alertExcerpt,
        contentLabel = "Transcrição da ligação",
        content = content.ifBlank { "Nenhuma fala foi transcrita nesta ligação." },
        facts = listOfNotNull(
            "Número" to (callerNumber ?: "Não identificado"),
            "Atendida em" to formatMillis(startedAt),
            "Duração" to formatDuration(endedAt - startedAt),
            "Viva-voz" to if (speakerUsed) "Ativado" else "Não ativado",
            alertAtMillis?.let { "Alerta emitido" to "${formatDuration(it - startedAt)} após atender" },
            alertSource?.let { "Detectado por" to if (it == "local") "Regras no aparelho" else "Análise da IA" },
            riskScore?.let { "Pontuação de risco" to scorePercent(it) }
        ),
        notice = captureIssue ?: syncError
    )
}

private fun scorePercent(score: Float): String = "${(score.coerceIn(0f, 1f) * 100).toInt()}%"

private fun categoryLabel(category: String): String = when (category.lowercase()) {
    "phishing" -> "Phishing (roubo de dados)"
    "scam" -> "Golpe"
    "spam" -> "Spam"
    "seguro" -> "Segura"
    else -> "Não informada"
}

data class DetailVisual(val color: androidx.compose.ui.graphics.Color, val softColor: androidx.compose.ui.graphics.Color, val icon: ImageVector)

fun detailVisual(verdict: DetailVerdict, score: Float?): DetailVisual = when (verdict) {
    DetailVerdict.FRAUD -> DetailVisual(BlessDanger, BlessDangerSoft, Icons.Filled.Warning)
    DetailVerdict.SUSPICIOUS -> DetailVisual(BlessWarning, BlessWarningSoft, Icons.Filled.GppMaybe)
    DetailVerdict.PENDING -> DetailVisual(BlessMuted, BlessSurfaceElevated, Icons.Filled.HourglassTop)
    DetailVerdict.SAFE -> riskVisual(score ?: 0f).let { DetailVisual(it.color, it.softColor, Icons.Filled.CheckCircle) }
}

// -----------------------------------------------------------------------------
// Folha de detalhes
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisDetailSheet(detail: AnalysisDetail, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visual = detailVisual(detail.verdict, detail.score)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = com.example.antifraudagent.ui.theme.BlessBackground,
        contentColor = BlessText,
        dragHandle = { BottomSheetDefaults.DragHandle(color = BlessBorder) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            DetailHeader(detail)
            VerdictBanner(detail, visual)

            detail.justification?.let { text ->
                DetailSection(title = "Por que?") {
                    Text(
                        text = text,
                        color = BlessText,
                        fontSize = 19.sp,
                        lineHeight = 29.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            detail.excerpt?.let { excerpt ->
                DetailSection(title = "Trecho que acendeu o alerta") {
                    QuoteBlock(excerpt, visual.color)
                }
            }

            detail.hybrid?.let { hybrid ->
                DetailSection(title = "Como foi decidido") {
                    DecisionRow("Inteligência artificial", hybrid.llm)
                    DecisionRow("Modelo local", hybrid.localModel)
                    DecisionRow("Veredito final", hybrid.status?.lowercase()?.replaceFirstChar { it.titlecase(ptBR) })
                }
            }

            detail.reasoning?.takeIf { it != detail.justification }?.let { reasoning ->
                DetailSection(title = "Análise detalhada") {
                    Text(text = reasoning, color = BlessText, fontSize = 16.sp, lineHeight = 25.sp)
                }
            }

            if (detail.indicators.isNotEmpty()) {
                DetailSection(title = "Sinais encontrados") {
                    detail.indicators.forEach { BulletRow(it, visual.color) }
                }
            }

            if (detail.localIndicators.isNotEmpty()) {
                DetailSection(title = "Sinais percebidos durante a ligação") {
                    detail.localIndicators.forEach { BulletRow(it, visual.color) }
                }
            }

            if (detail.verdict == DetailVerdict.FRAUD || detail.verdict == DetailVerdict.SUSPICIOUS) {
                SafetyTips(detail.kind)
            }

            DetailSection(title = detail.contentLabel) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BlessSurfaceElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = detail.content,
                            color = BlessText,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            detail.notice?.let { notice ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = BlessMuted, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(notice, color = BlessMuted, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (detail.facts.isNotEmpty()) {
                DetailSection(title = "Detalhes") {
                    detail.facts.forEach { (label, value) -> FactRow(label, value) }
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BlessPrimary)
            ) {
                Text("Fechar", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun DetailHeader(detail: AnalysisDetail) {
    val icon = when (detail.kind) {
        DetailKind.CALL -> Icons.Filled.PhoneInTalk
        DetailKind.MANUAL -> Icons.Filled.Search
        DetailKind.MESSAGE -> Icons.Filled.Chat
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(BlessSurfaceElevated)
                .border(1.dp, BlessBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = BlessPrimary, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(detail.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            detail.subtitle?.let { Text(it, color = BlessMuted, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun VerdictBanner(detail: AnalysisDetail, visual: DetailVisual) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = visual.softColor,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, visual.color.copy(alpha = 0.8f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(visual.icon, contentDescription = null, tint = visual.color, modifier = Modifier.size(38.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detail.headline,
                    color = visual.color,
                    fontSize = 21.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                detail.score?.takeIf { detail.verdict != DetailVerdict.PENDING }?.let {
                    Text(
                        text = "Risco ${(it.coerceIn(0f, 1f) * 100).toInt()}%",
                        color = BlessText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(ptBR),
            color = BlessMuted,
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 0.8.sp
        )
        content()
    }
}

@Composable
private fun QuoteBlock(text: String, accent: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(BlessSurface)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent)
        )
        Text(
            text = "“${text.trim()}”",
            color = BlessText,
            fontSize = 17.sp,
            lineHeight = 26.sp,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun DecisionRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val isFraud = value.contains("fraude", ignoreCase = true) || value.contains("golpe", ignoreCase = true) ||
        value.equals("aviso", ignoreCase = true)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = BlessText, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            color = if (isFraud) BlessDanger else com.example.antifraudagent.ui.theme.BlessSafe,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun BulletRow(text: String, accent: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, color = BlessText, fontSize = 16.sp, lineHeight = 23.sp)
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = BlessMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.width(12.dp))
        Text(value, color = BlessText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SafetyTips(kind: DetailKind) {
    val tips = if (kind == DetailKind.CALL) {
        listOf(
            "Bancos nunca pedem senha, token ou código por telefone.",
            "Não faça Pix nem transferências pedidas na ligação.",
            "Desligue e ligue você mesmo para o número oficial do banco ou empresa."
        )
    } else {
        listOf(
            "Não clique em links nem baixe arquivos dessa mensagem.",
            "Não informe senhas, códigos recebidos por SMS ou dados do cartão.",
            "Confirme a história por outro canal antes de pagar qualquer coisa."
        )
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = BlessSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BlessBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("O que fazer agora", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            tips.forEach { BulletRow(it, BlessPrimary) }
        }
    }
}
