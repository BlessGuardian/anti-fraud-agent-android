package com.example.antifraudagent

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.util.Log
import com.example.antifraudagent.data.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AntiFraudAgentApp : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        registerNetworkCallback()
        processPendingMessages()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().build()

        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    processPendingMessages()
                }
            }
        )
    }

    private fun processPendingMessages() {
        appScope.launch {
            try {
                MessageRepository(applicationContext).processPendingMessages()
            } catch (e: Exception) {
                Log.w("AntiFraudAgentApp", "Falha ao processar fila PENDING", e)
            }
        }
    }
}
