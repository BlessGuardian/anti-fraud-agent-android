package com.example.antifraudagent.data.auth

/**
 * Token JWT da sessao atual, lido pelo FraudApiClient para o header `Authorization: Bearer`.
 * Mantido pelo AuthRepository (login, logout, sessao expirada). Fica em memoria; o valor
 * persistido esta em `auth_session.xml`, fora do backup.
 */
object AuthToken {
    @Volatile
    var current: String? = null
}
