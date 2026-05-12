package com.example.antifraudagent

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.antifraudagent.data.remote.RemoteFraudLog
import com.example.antifraudagent.data.repository.MessageRepository
import com.example.antifraudagent.services.FraudAccessibilityService
import com.example.antifraudagent.services.MessageListenerService
import com.example.antifraudagent.ui.theme.AntiFraudAgentTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var isNotificationEnabled by mutableStateOf(false)
    private var isAccessibilityEnabled by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (perm, granted) ->
            if (!granted) {
                android.util.Log.w("MainActivity", "Permissao negada: $perm")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()

        setContent {
            AntiFraudAgentTheme {
                val repository = remember { MessageRepository(applicationContext) }
                val scope = rememberCoroutineScope()
                var fraudLogs by remember { mutableStateOf<List<RemoteFraudLog>>(emptyList()) }
                var pendingCount by remember { mutableStateOf(0) }
                var isLoading by remember { mutableStateOf(false) }
                var feedback by remember { mutableStateOf<String?>(null) }

                fun refreshData() {
                    scope.launch {
                        isLoading = true
                        feedback = null
                        try {
                            repository.processPendingMessages()
                            pendingCount = repository.getPendingMessages().size
                            fraudLogs = repository.getConfirmedFrauds()
                                .filter { it.isFraud }
                                .sortedByDescending { it.detectedAt }
                            feedback = "Historico atualizado pelo Aiven."
                        } catch (e: Exception) {
                            pendingCount = repository.getPendingMessages().size
                            feedback = "Nao foi possivel consultar o servidor: ${e.message ?: "erro desconhecido"}"
                        } finally {
                            isLoading = false
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    refreshData()
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        notificationEnabled = isNotificationEnabled,
                        accessibilityEnabled = isAccessibilityEnabled,
                        pendingCount = pendingCount,
                        fraudLogs = fraudLogs,
                        isLoading = isLoading,
                        feedback = feedback,
                        onRequestNotification = { openNotificationListenerSettings() },
                        onRequestAccessibility = { openAccessibilitySettings() },
                        onRefresh = { refreshData() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isNotificationEnabled = isNotificationListenerEnabled()
        isAccessibilityEnabled = FraudAccessibilityService.isEnabled(this)
    }

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

@Composable
fun MainScreen(
    notificationEnabled: Boolean,
    accessibilityEnabled: Boolean,
    pendingCount: Int,
    fraudLogs: List<RemoteFraudLog>,
    isLoading: Boolean,
    feedback: String?,
    onRequestNotification: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HeaderSection()
        }

        item {
            PermissionCard(
                title = "Monitoramento por notificacoes",
                description = "Captura mensagens quando o app esta em segundo plano.",
                isGranted = notificationEnabled,
                onRequest = onRequestNotification
            )
        }

        item {
            PermissionCard(
                title = "Monitoramento por acessibilidade",
                description = "Captura mensagens quando o app esta aberto na tela.",
                isGranted = accessibilityEnabled,
                onRequest = onRequestAccessibility
            )
        }

        item {
            StatusCard(
                allActive = notificationEnabled && accessibilityEnabled,
                pendingCount = pendingCount
            )
        }

        item {
            HistoryHeader(
                isLoading = isLoading,
                feedback = feedback,
                onRefresh = onRefresh
            )
        }

        if (fraudLogs.isEmpty()) {
            item {
                EmptyHistoryCard(isLoading = isLoading)
            }
        } else {
            items(fraudLogs, key = { it.id }) { fraud ->
                FraudLogCard(fraud = fraud)
            }
        }
    }
}

@Composable
fun HeaderSection() {
    Column {
        Text(
            text = "AntiFraud Agent",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Protecao em tempo real contra golpes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isGranted) "Ativo" else "Permissao pendente",
                style = MaterialTheme.typography.labelLarge,
                color = if (isGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )

            if (!isGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Conceder permissao")
                }
            }
        }
    }
}

@Composable
fun StatusCard(allActive: Boolean, pendingCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (allActive) "Protecao ativa" else "Protecao incompleta",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Mensagens pendentes de envio offline: $pendingCount",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "O historico oficial e consultado no Aiven via backend.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun HistoryHeader(
    isLoading: Boolean,
    feedback: String?,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Alertas gravados",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Golpes confirmados retornados do Aiven.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "Atualizando" else "Atualizar")
                }
            }

            if (feedback != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun EmptyHistoryCard(isLoading: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = if (isLoading) {
                    "Consultando historico..."
                } else {
                    "Nenhum golpe confirmado encontrado para este aparelho."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun FraudLogCard(fraud: RemoteFraudLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Golpe confirmado",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${(fraud.riskScore * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = fraud.content.ifBlank { "Mensagem sem conteudo retornado." },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Fonte: ${fraud.source.ifBlank { "UNKNOWN" }}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Detectado em: ${fraud.detectedAt.ifBlank { "nao informado" }}",
                style = MaterialTheme.typography.bodySmall
            )

            if (fraud.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = fraud.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
