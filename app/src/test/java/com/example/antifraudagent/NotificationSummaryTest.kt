package com.example.antifraudagent

import com.example.antifraudagent.data.local.preprocessing.LocalMessagePreprocessor.isNotificationSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSummaryTest {

    @Test
    fun `resumos do WhatsApp sao reconhecidos`() {
        listOf(
            "4 mensagens de 2 conversas",
            "1 mensagem",
            "12 mensagens",
            "3 novas mensagens",
            "5 mensagens não lidas",
            "2 mensagens nao lidas",
            "7 Mensagens de 1 conversa",
            "4 messages from 2 chats",
            "3 new messages"
        ).forEach { assertTrue(it, isNotificationSummary(it)) }
    }

    @Test
    fun `mensagens reais que comecam com numero passam`() {
        listOf(
            "2 mensagens suas foram bloqueadas, clique no link",
            "4 mensagens de 2 conversas https://golpe.example",
            "Você tem 3 mensagens novas no banco",
            "10 reais pra você agora",
            "mensagens de 2 conversas"
        ).forEach { assertFalse(it, isNotificationSummary(it)) }
    }
}
