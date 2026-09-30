package com.example.antifraudagent.calls

import java.text.Normalizer

enum class CallRiskLevel { LOW, MEDIUM, HIGH }

data class CallRiskResult(
    val score: Float,
    val indicators: List<String>,
    val categories: Set<CallRiskRules.Category>
) {
    val level: CallRiskLevel
        get() = when {
            score >= HIGH_RISK_THRESHOLD -> CallRiskLevel.HIGH
            score >= MEDIUM_RISK_THRESHOLD -> CallRiskLevel.MEDIUM
            else -> CallRiskLevel.LOW
        }
    val isHighRisk: Boolean get() = level == CallRiskLevel.HIGH

    companion object {
        const val HIGH_RISK_THRESHOLD = 0.7f
        const val MEDIUM_RISK_THRESHOLD = 0.4f
        val NONE = CallRiskResult(0f, emptyList(), emptySet())
    }
}

/**
 * Sinais locais para alertar DURANTE a ligacao, sem esperar o servidor (~5-10s por analise).
 * Um sinal isolado quase nunca basta: o alerta vem da combinacao de categorias, que e o padrao
 * dos golpes por telefone (falsa central + pedido de codigo, acesso remoto, motoboy...).
 */
object CallRiskRules {

    enum class Category(val weight: Float) {
        CREDENTIAL(0.35f),     // senha, token, codigo, dados do cartao
        PAYMENT(0.25f),        // pix, transferencia, boleto, taxa
        IMPERSONATION(0.25f),  // central do banco, gerente, policia, INSS
        REMOTE_ACCESS(0.55f),  // anydesk, acesso remoto, instalar app
        URGENCY(0.15f),        // bloqueio, urgente, prazo
        COURIER(0.55f),        // motoboy vai buscar o cartao
        EXTORTION(0.55f)       // sequestro, ameaca
    }

    private data class Signal(val term: String, val category: Category, val label: String)

    private val signals = listOf(
        Signal("senha", Category.CREDENTIAL, "pedido de senha"),
        Signal("token", Category.CREDENTIAL, "pedido de token"),
        Signal("codigo de seguranca", Category.CREDENTIAL, "código de segurança"),
        Signal("codigo que chegou", Category.CREDENTIAL, "código recebido por SMS"),
        Signal("codigo que voce recebeu", Category.CREDENTIAL, "código recebido por SMS"),
        Signal("codigo de verificacao", Category.CREDENTIAL, "código de verificação"),
        Signal("numero do cartao", Category.CREDENTIAL, "dados do cartão"),
        Signal("dados do cartao", Category.CREDENTIAL, "dados do cartão"),
        Signal("cvv", Category.CREDENTIAL, "código do cartão (CVV)"),
        Signal("confirmar seus dados", Category.CREDENTIAL, "confirmação de dados"),
        Signal("pix", Category.PAYMENT, "Pix"),
        Signal("transferencia", Category.PAYMENT, "transferência"),
        Signal("transferir", Category.PAYMENT, "transferência"),
        Signal("boleto", Category.PAYMENT, "boleto"),
        Signal("deposito", Category.PAYMENT, "depósito"),
        Signal("pagar uma taxa", Category.PAYMENT, "cobrança de taxa"),
        Signal("conta segura", Category.PAYMENT, "\"conta segura\""),
        Signal("central de seguranca", Category.IMPERSONATION, "falsa central de segurança"),
        Signal("central de atendimento", Category.IMPERSONATION, "falsa central de atendimento"),
        Signal("setor de fraude", Category.IMPERSONATION, "falso setor de fraudes"),
        Signal("compra suspeita", Category.IMPERSONATION, "\"compra suspeita\""),
        Signal("gerente", Category.IMPERSONATION, "se passa por gerente"),
        Signal("do seu banco", Category.IMPERSONATION, "se passa pelo banco"),
        Signal("policia", Category.IMPERSONATION, "se passa pela polícia"),
        Signal("delegado", Category.IMPERSONATION, "se passa pela polícia"),
        Signal("receita federal", Category.IMPERSONATION, "se passa pela Receita"),
        Signal("inss", Category.IMPERSONATION, "se passa pelo INSS"),
        Signal("acesso remoto", Category.REMOTE_ACCESS, "acesso remoto"),
        Signal("anydesk", Category.REMOTE_ACCESS, "AnyDesk"),
        Signal("teamviewer", Category.REMOTE_ACCESS, "TeamViewer"),
        Signal("compartilhe a tela", Category.REMOTE_ACCESS, "compartilhamento de tela"),
        Signal("compartilhar a tela", Category.REMOTE_ACCESS, "compartilhamento de tela"),
        Signal("instale o aplicativo", Category.REMOTE_ACCESS, "instalação de aplicativo"),
        Signal("baixe o aplicativo", Category.REMOTE_ACCESS, "instalação de aplicativo"),
        Signal("bloque", Category.URGENCY, "ameaça de bloqueio"),
        Signal("urgente", Category.URGENCY, "urgência"),
        Signal("agora mesmo", Category.URGENCY, "urgência"),
        Signal("clonado", Category.URGENCY, "\"cartão clonado\""),
        Signal("motoboy", Category.COURIER, "motoboy para buscar o cartão"),
        Signal("buscar o cartao", Category.COURIER, "retirada do cartão"),
        Signal("cortar o cartao", Category.COURIER, "pedido para cortar o cartão"),
        Signal("sequestr", Category.EXTORTION, "ameaça de sequestro"),
        Signal("seu filho esta", Category.EXTORTION, "ameaça envolvendo familiar")
    )

    fun analyze(text: String): CallRiskResult {
        if (text.isBlank()) return CallRiskResult.NONE
        val normalized = fold(text)
        val matched = signals.filter { normalized.contains(it.term) }
        if (matched.isEmpty()) return CallRiskResult.NONE

        val categories = matched.map { it.category }.toSet()
        var score = categories.sumOf { it.weight.toDouble() }.toFloat()
        // Combinacao classica: alguem se passando por instituicao pedindo codigo/senha/pagamento.
        if (Category.CREDENTIAL in categories &&
            (Category.IMPERSONATION in categories || Category.PAYMENT in categories)
        ) {
            score += 0.2f
        }
        return CallRiskResult(
            score = score.coerceAtMost(1f),
            indicators = matched.map { it.label }.distinct(),
            categories = categories
        )
    }

    private fun fold(value: String): String = Normalizer
        .normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}
