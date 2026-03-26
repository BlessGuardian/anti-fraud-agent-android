package com.example.antifraudagent

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.antifraudagent.services.FraudAccessibilityService
import com.example.antifraudagent.services.MessageListenerService
import com.example.antifraudagent.ui.theme.AntiFraudAgentTheme

class MainActivity : ComponentActivity() {

    // -------------------------------------------------------------------------
    // Estado reativo — atualizado no onResume para refletir mudanças feitas
    // nas telas de configuração do sistema
    // -------------------------------------------------------------------------

    private var isNotificationEnabled by mutableStateOf(false)
    private var isAccessibilityEnabled by mutableStateOf(false)

    // -------------------------------------------------------------------------
    // Permissões runtime (SMS, Notificações)
    // -------------------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (perm, granted) ->
            if (!granted) {
                android.util.Log.w("MainActivity", "Permissão negada: $perm")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()

        setContent {
            AntiFraudAgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        notificationEnabled  = isNotificationEnabled,
                        accessibilityEnabled = isAccessibilityEnabled,
                        onRequestNotification  = { openNotificationListenerSettings() },
                        onRequestAccessibility = { openAccessibilitySettings() }
                    )
                }
            }
        }
    }

    /**
     * Chamado toda vez que o usuário volta para o app — incluindo após sair
     * das telas de configurações do sistema. Atualiza os estados reativos para
     * que a UI reflita as permissões atuais sem precisar reiniciar o app.
     */
    override fun onResume() {
        super.onResume()
        isNotificationEnabled  = isNotificationListenerEnabled()
        isAccessibilityEnabled = FraudAccessibilityService.isEnabled(this)
    }

    // -------------------------------------------------------------------------
    // Helpers de permissão
    // -------------------------------------------------------------------------

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat.isNullOrBlank()) return false
        val myComponent = ComponentName(this, MessageListenerService::class.java)
        return flat.split(":").any {
            ComponentName.unflattenFromString(it) == myComponent
        }
    }

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

// =============================================================================
// UI — Composables
// =============================================================================

@Composable
fun MainScreen(
    notificationEnabled  : Boolean,
    accessibilityEnabled : Boolean,
    onRequestNotification  : () -> Unit,
    onRequestAccessibility : () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Título
        Text(
            text  = "AntiFraud Agent",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text  = "Proteção em tempo real contra golpes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Card: Permissão de Notificações
        PermissionCard(
            title       = "Monitoramento por Notificações",
            description = "Captura mensagens quando o app está em segundo plano.",
            isGranted   = notificationEnabled,
            onRequest   = onRequestNotification
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Card: Permissão de Acessibilidade
        PermissionCard(
            title       = "Monitoramento por Acessibilidade",
            description = "Captura mensagens quando o app está aberto na tela.",
            isGranted   = accessibilityEnabled,
            onRequest   = onRequestAccessibility
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Status geral
        val allActive = notificationEnabled && accessibilityEnabled
        StatusBanner(allActive = allActive)
    }
}

@Composable
fun PermissionCard(
    title       : String,
    description : String,
    isGranted   : Boolean,
    onRequest   : () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = if (isGranted)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = if (isGranted) "✅" else "⚠️",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text  = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick  = onRequest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Conceder permissão")
                }
            }
        }
    }
}

@Composable
fun StatusBanner(allActive: Boolean) {
    val (emoji, text, color) = if (allActive) {
        Triple(
            "🛡️",
            "Proteção completa ativa!\nMensagens monitoradas em tempo real.",
            MaterialTheme.colorScheme.primary
        )
    } else {
        Triple(
            "🔓",
            "Proteção incompleta.\nConceda as permissões acima para ativar o monitoramento.",
            MaterialTheme.colorScheme.error
        )
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = emoji, style = MaterialTheme.typography.displaySmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text      = text,
            style     = MaterialTheme.typography.bodyMedium,
            color     = color,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
