package com.example.antifraudagent.calls

import java.text.Normalizer

data class CallRiskResult(val score: Float, val indicators: List<String>) {
    val isHighRisk: Boolean get() = score >= HIGH_RISK_THRESHOLD

    companion object {
        const val HIGH_RISK_THRESHOLD = 0.75f
    }
}

/** Sinais locais para alertar durante a chamada sem esperar o backend. */
object CallRiskRules {
    private val signals = listOf(
        "pix" to Pair(0.20f, "Pix"),
        "senha" to Pair(0.28f, "senha"),
        "token" to Pair(0.28f, "token"),
        "codigo" to Pair(0.22f, "codigo de seguranca"),
        "bloque" to Pair(0.16f, "ameaca de bloqueio"),
        "urgente" to Pair(0.14f, "urgencia"),
        "transfer" to Pair(0.22f, "transferencia"),
        "central de seguranca" to Pair(0.18f, "falsa central"),
        "acesso remoto" to Pair(0.36f, "acesso remoto"),
        "anydesk" to Pair(0.42f, "AnyDesk"),
        "teamviewer" to Pair(0.42f, "TeamViewer"),
        "compartilhe a tela" to Pair(0.30f, "compartilhamento de tela")
    )

    fun analyze(text: String): CallRiskResult {
        val normalized = fold(text)
        val found = mutableListOf<String>()
        var score = 0f
        signals.forEach { (term, value) ->
            if (normalized.contains(term)) {
                score += value.first
                found += value.second
            }
        }
        if (found.any { it in setOf("senha", "token", "codigo de seguranca") } &&
            found.any { it in setOf("Pix", "transferencia", "falsa central") }) {
            score += 0.22f
        }
        return CallRiskResult(score.coerceAtMost(1f), found.distinct())
    }

    private fun fold(value: String): String = Normalizer
        .normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}
