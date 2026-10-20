package com.example.antifraudagent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antifraudagent.data.remote.RiskLevel
import com.example.antifraudagent.data.score.ScoreEntry
import com.example.antifraudagent.data.score.ScoreRepository
import com.example.antifraudagent.data.score.ScoreStatus
import com.example.antifraudagent.ui.theme.BlessBackground
import com.example.antifraudagent.ui.theme.BlessBorder
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessMuted
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessSafe
import com.example.antifraudagent.ui.theme.BlessSurfaceElevated
import com.example.antifraudagent.ui.theme.BlessText
import com.example.antifraudagent.ui.theme.BlessWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val ptBR = Locale("pt", "BR")
private val dateTime = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", ptBR)
private val dayMonth = SimpleDateFormat("dd/MM", ptBR)
private val longDate = SimpleDateFormat("d 'de' MMMM 'de' yyyy", ptBR)
private val shortDate = SimpleDateFormat("dd MMM yyyy", ptBR)

/** Escala do Serasa: o servidor manda 0..1 e o app mostra de 0 a 1000 pontos. */
fun scorePoints(value: Float): Int = (value.coerceIn(0f, 1f) * 1000).roundToInt()

data class ScoreBand(val label: String, val color: Color)

/** Faixas alinhadas com riskVisual (0.4 e 0.75). Aqui subir e ruim: mais golpes que os demais. */
fun scoreBand(points: Int): ScoreBand = when {
    points >= 750 -> ScoreBand("Vulnerabilidade alta", BlessDanger)
    points >= 400 -> ScoreBand("Vulnerabilidade média", BlessWarning)
    else -> ScoreBand("Vulnerabilidade baixa", BlessSafe)
}

/**
 * Indice de vulnerabilidade estilo Serasa: pontos de 0 a 1000 num velocimetro com as faixas e a
 * ultima variacao. Toque abre o historico (barras + variacoes com o motivo).
 */
@Composable
fun VulnerabilityCard(currentLevel: RiskLevel) {
    val context = LocalContext.current
    val repository = remember { ScoreRepository.getInstance(context) }
    val history by repository.history.collectAsState()
    val status by repository.status.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    val latest = history.firstOrNull()
    val currentVisual = levelVisual(currentLevel, latest?.value)

    GlassPanel(onClick = { if (latest != null) showDetails = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "ÍNDICE DE VULNERABILIDADE",
                style = MaterialTheme.typography.labelLarge,
                color = BlessMuted,
                modifier = Modifier.weight(1f)
            )
            if (latest != null) Icon(Icons.Filled.ChevronRight, contentDescription = "Ver histórico", tint = BlessPrimary)
        }
        Spacer(modifier = Modifier.height(12.dp))
        ScoreGaugeWithValue(
            points = latest?.let { scorePoints(it.value) },
            width = 220.dp,
            emptyLabel = when (status) {
                ScoreStatus.NOT_CALCULATED -> "Ainda não calculado"
                ScoreStatus.ERROR -> "Não foi possível consultar"
                else -> "aguardando o servidor"
            }
        )
        latest?.let { entry ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${shortChange(entry)} · atualizado em ${dayMonth.format(Date(entry.observedAt))}",
                color = changeColor(entry),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        statusNote(status, hasValue = latest != null)?.let { note ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = note,
                color = BlessMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        // Status de conexao/fila fica no ProtectionStatusChip do topo; aqui so o risco.
        StatusPill(
            modifier = Modifier.fillMaxWidth(),
            label = currentVisual.label.lowercase(),
            // Pior alerta recente; o indice acima e outra medida (comparacao com os demais usuarios).
            value = "alertas dos últimos 7 dias",
            visual = currentVisual
        )
    }

    if (showDetails) ScoreDetailsSheet(history = history, onDismiss = { showDetails = false })
}

/** Velocimetro com os pontos no centro e o nome da faixa embaixo. */
@Composable
private fun ScoreGaugeWithValue(points: Int?, width: Dp, emptyLabel: String = "aguardando o servidor") {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomCenter) {
            ScoreGauge(points = points, width = width)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = points?.toString() ?: "—",
                    color = points?.let { scoreBand(it).color } ?: BlessMuted,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 42.sp
                )
                Text("de 1000", color = BlessMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = points?.let { scoreBand(it).label } ?: emptyLabel,
            color = points?.let { scoreBand(it).color } ?: BlessMuted,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Meia-lua com as tres faixas (baixa, media, alta) e um marcador na posicao do indice. */
@Composable
private fun ScoreGauge(points: Int?, width: Dp) {
    Canvas(modifier = Modifier.size(width = width, height = width / 2 + 12.dp)) {
        val stroke = 14.dp.toPx()
        val radius = (size.width - stroke) / 2f
        val center = Offset(size.width / 2f, stroke / 2f + radius)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        val gap = 2f
        listOf(
            Triple(0f, 0.4f, BlessSafe),
            Triple(0.4f, 0.75f, BlessWarning),
            Triple(0.75f, 1f, BlessDanger)
        ).forEach { (from, to, color) ->
            drawArc(
                color = color.copy(alpha = if (points == null) 0.25f else 0.85f),
                startAngle = 180f + 180f * from + gap / 2,
                sweepAngle = 180f * (to - from) - gap,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke)
            )
        }
        if (points != null) {
            val angle = Math.toRadians(180.0 + 180.0 * points / 1000.0)
            val marker = Offset(
                center.x + radius * cos(angle).toFloat(),
                center.y + radius * sin(angle).toFloat()
            )
            drawCircle(color = BlessBackground, radius = stroke * 0.95f, center = marker)
            drawCircle(color = BlessText, radius = stroke * 0.7f, center = marker)
            drawCircle(color = scoreBand(points).color, radius = stroke * 0.4f, center = marker)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreDetailsSheet(history: List<ScoreEntry>, onDismiss: () -> Unit) {
    val latest = history.first()
    var expanded by remember { mutableStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BlessBackground,
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Índice de vulnerabilidade", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            GlassPanel {
                ScoreGaugeWithValue(points = scorePoints(latest.value), width = 240.dp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Atualizado em ${dateTime.format(Date(latest.observedAt))}",
                    color = BlessMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            GlassPanel {
                Text("Histórico", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val bars = history.take(MAX_BARS).reversed()
                Text(
                    if (bars.size == 1) "Sua primeira leitura do índice" else "Suas últimas ${bars.size} leituras do índice",
                    color = BlessMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(16.dp))
                ScoreBars(bars)
                Spacer(modifier = Modifier.height(8.dp))
                history.forEachIndexed { index, entry ->
                    HorizontalDivider(color = BlessBorder)
                    VariationRow(
                        entry = entry,
                        expanded = expanded == index,
                        onClick = { expanded = if (expanded == index) -1 else index }
                    )
                }
            }

            Text("COMO FUNCIONA", color = BlessMuted, style = MaterialTheme.typography.labelLarge)
            Text(
                "O índice vai de 0 a 1000. Quanto maior, mais golpes chegam até você em comparação com os " +
                    "demais usuários do BlessGuardian. Ao contrário do Serasa, aqui subir é ruim. O servidor " +
                    "recalcula em um período fixo e o motivo de cada variação é montado a partir do seu " +
                    "histórico de golpes.",
                color = BlessMuted,
                style = MaterialTheme.typography.bodyMedium
            )
            BandLegend("0 a 399", "Vulnerabilidade baixa", BlessSafe)
            BandLegend("400 a 749", "Vulnerabilidade média", BlessWarning)
            BandLegend("750 a 1000", "Vulnerabilidade alta", BlessDanger)
        }
    }
}

/** Barras das ultimas leituras (mais antiga a esquerda); a atual em destaque, como no Serasa. */
@Composable
private fun ScoreBars(entries: List<ScoreEntry>) {
    val maxBar = 96.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        entries.forEachIndexed { index, entry ->
            val points = scorePoints(entry.value)
            val current = index == entries.lastIndex
            val color = if (current) scoreBand(points).color else BlessBorder
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                Text(
                    text = points.toString(),
                    color = if (current) BlessText else BlessMuted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(BlessSurfaceElevated)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height((maxBar * (points / 1000f)).coerceAtLeast(6.dp))
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(color)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (current) "Índice atual" else shortDate.format(Date(entry.observedAt)),
                    color = if (current) BlessText else BlessMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

/** Linha "Subiu X pontos / data" que abre o motivo, como a lista de variacoes do Serasa. */
@Composable
private fun VariationRow(entry: ScoreEntry, expanded: Boolean, onClick: () -> Unit) {
    val (icon, color) = variationIcon(entry)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(variationTitle(entry), color = BlessText, style = MaterialTheme.typography.bodyLarge)
                Text(longDate.format(Date(entry.observedAt)), color = BlessMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Esconder motivo" else "Ver motivo",
                tint = BlessMuted
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 34.dp, top = 8.dp)) {
                val now = scorePoints(entry.value)
                Text(
                    entry.previous?.let { "De ${scorePoints(it)} para $now pontos." } ?: "Índice de $now pontos.",
                    color = BlessMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(entry.reason, color = BlessText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BandLegend(range: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(range, color = BlessText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(92.dp))
        Text(label, color = BlessMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val MAX_BARS = 4

/** Explica por que o card esta sem valor (ou desatualizado), em vez de "aguardando" para sempre. */
private fun statusNote(status: ScoreStatus, hasValue: Boolean): String? = when (status) {
    ScoreStatus.NOT_CALCULATED -> if (hasValue) null else
        "Seu índice aparece depois do próximo recálculo do servidor."
    ScoreStatus.ERROR -> if (hasValue) "Não foi possível atualizar agora; mostrando a última leitura." else
        "Toque em Atualizar para tentar de novo."
    else -> null
}

/** Diferenca em pontos; 0 quando nao ha anterior ou a mudanca some no arredondamento. */
private fun pointsDiff(entry: ScoreEntry): Int =
    entry.previous?.let { scorePoints(entry.value) - scorePoints(it) } ?: 0

private fun pointsLabel(n: Int) = if (n == 1) "1 ponto" else "$n pontos"

private fun variationTitle(entry: ScoreEntry): String {
    if (entry.previous == null) return "Primeira leitura"
    val diff = pointsDiff(entry)
    return when {
        diff > 0 -> "Subiu ${pointsLabel(diff)}"
        diff < 0 -> "Caiu ${pointsLabel(abs(diff))}"
        else -> "Se manteve o mesmo"
    }
}

private fun shortChange(entry: ScoreEntry): String {
    if (entry.previous == null) return "Primeira leitura"
    val diff = pointsDiff(entry)
    return when {
        diff > 0 -> "▲ subiu ${pointsLabel(diff)}"
        diff < 0 -> "▼ caiu ${pointsLabel(abs(diff))}"
        else -> "se manteve o mesmo"
    }
}

/** Subir e ruim (mais vulneravel); cair e bom. */
private fun changeColor(entry: ScoreEntry): Color {
    val diff = pointsDiff(entry)
    return when {
        entry.previous == null || diff == 0 -> BlessMuted
        diff > 0 -> BlessDanger
        else -> BlessSafe
    }
}

private fun variationIcon(entry: ScoreEntry): Pair<ImageVector, Color> {
    val diff = pointsDiff(entry)
    return when {
        entry.previous == null || diff == 0 -> Icons.Filled.Remove to BlessMuted
        diff > 0 -> Icons.Filled.ArrowUpward to BlessDanger
        else -> Icons.Filled.ArrowDownward to BlessSafe
    }
}
