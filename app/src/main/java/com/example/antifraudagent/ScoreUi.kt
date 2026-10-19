package com.example.antifraudagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antifraudagent.data.remote.RiskLevel
import com.example.antifraudagent.data.score.ScoreEntry
import com.example.antifraudagent.data.score.ScoreRepository
import com.example.antifraudagent.ui.theme.BlessBackground
import com.example.antifraudagent.ui.theme.BlessBorder
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessMuted
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessSafe
import com.example.antifraudagent.ui.theme.BlessText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateTime = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR"))
private val dayMonth = SimpleDateFormat("dd/MM", Locale("pt", "BR"))

/**
 * Indice de vulnerabilidade estilo Serasa: valor do servidor (GET /score), data da ultima mudanca e
 * variacao. Toque abre os detalhes (anterior x atual, motivo e historico).
 */
@Composable
fun VulnerabilityCard(currentLevel: RiskLevel) {
    val context = LocalContext.current
    val history by remember { ScoreRepository.getInstance(context).history }.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    val latest = history.firstOrNull()
    val score = latest?.value
    val currentVisual = levelVisual(currentLevel, score)

    GlassPanel(onClick = { if (latest != null) showDetails = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "ÍNDICE DE VULNERABILIDADE",
                style = MaterialTheme.typography.labelLarge,
                color = BlessMuted,
                modifier = Modifier.weight(1f)
            )
            if (latest != null) Icon(Icons.Filled.ChevronRight, contentDescription = "Ver detalhes", tint = BlessPrimary)
        }
        Spacer(modifier = Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp),
            contentAlignment = Alignment.Center
        ) {
            GaugeArc(score = score ?: 0f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = score?.let { formatScore(it) } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    color = BlessPrimary
                )
                Text(
                    text = score?.let { riskName(it) } ?: "aguardando o servidor",
                    style = MaterialTheme.typography.bodySmall,
                    color = BlessMuted
                )
            }
        }
        latest?.let { entry ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = changeLine(entry),
                color = changeColor(entry),
                style = MaterialTheme.typography.bodySmall,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreDetailsSheet(history: List<ScoreEntry>, onDismiss: () -> Unit) {
    val latest = history.first()
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
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Anterior", color = BlessMuted, style = MaterialTheme.typography.labelMedium)
                        Text(
                            latest.previous?.let { formatScore(it) } ?: "—",
                            color = BlessMuted,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("→", color = BlessMuted, fontSize = 26.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("Atual", color = BlessMuted, style = MaterialTheme.typography.labelMedium)
                        Text(formatScore(latest.value), color = riskVisual(latest.value).color, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(changeLine(latest), color = changeColor(latest), style = MaterialTheme.typography.bodyMedium)
            }

            Text("MOTIVO", color = BlessMuted, style = MaterialTheme.typography.labelLarge)
            Text(latest.reason, color = BlessText, fontSize = 18.sp, fontWeight = FontWeight.Medium)

            Text("COMO FUNCIONA", color = BlessMuted, style = MaterialTheme.typography.labelLarge)
            Text(
                "O índice vai de 0 a 1: quanto maior, mais golpes chegam até você em comparação com os " +
                    "demais usuários do BlessGuardian. O servidor recalcula em um período fixo e o valor só muda " +
                    "quando há um motivo.",
                color = BlessMuted,
                style = MaterialTheme.typography.bodyMedium
            )

            if (history.size > 1) {
                Text("HISTÓRICO", color = BlessMuted, style = MaterialTheme.typography.labelLarge)
                history.forEach { entry ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.width(92.dp)) {
                            Text(formatScore(entry.value), color = riskVisual(entry.value).color, fontWeight = FontWeight.Bold)
                            Text(dayMonth.format(Date(entry.observedAt)), color = BlessMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(entry.reason, color = BlessText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun changeLine(entry: ScoreEntry): String {
    val previous = entry.previous ?: return "Primeira leitura em ${dateTime.format(Date(entry.observedAt))}"
    val diff = entry.value - previous
    val arrow = if (diff > 0) "▲ subiu" else "▼ caiu"
    val amount = formatScore(kotlin.math.abs(diff))
    return "$arrow $amount · atualizado em ${dateTime.format(Date(entry.observedAt))}"
}

/** Subir e ruim (mais vulneravel); cair e bom. */
private fun changeColor(entry: ScoreEntry): Color {
    val previous = entry.previous ?: return BlessMuted
    return if (entry.value > previous) BlessDanger else BlessSafe
}
