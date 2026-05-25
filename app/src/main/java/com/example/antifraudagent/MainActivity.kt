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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.antifraudagent.data.remote.FraudAnalysisResult
import com.example.antifraudagent.data.remote.RemoteFraudLog
import com.example.antifraudagent.data.repository.MessageRepository
import com.example.antifraudagent.data.settings.SettingsRepository
import com.example.antifraudagent.services.FraudAccessibilityService
import com.example.antifraudagent.services.MessageListenerService
import com.example.antifraudagent.ui.theme.AntiFraudAgentTheme
import com.example.antifraudagent.ui.theme.BlessBackground
import com.example.antifraudagent.ui.theme.BlessBorder
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessDangerSoft
import com.example.antifraudagent.ui.theme.BlessMuted
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessPrimarySoft
import com.example.antifraudagent.ui.theme.BlessSafe
import com.example.antifraudagent.ui.theme.BlessSafeSoft
import com.example.antifraudagent.ui.theme.BlessSurface
import com.example.antifraudagent.ui.theme.BlessSurfaceElevated
import com.example.antifraudagent.ui.theme.BlessText
import com.example.antifraudagent.ui.theme.BlessWarning
import com.example.antifraudagent.ui.theme.BlessWarningSoft
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        window.statusBarColor = android.graphics.Color.parseColor("#050B18")
        window.navigationBarColor = android.graphics.Color.parseColor("#071023")
        requestPermissionsIfNeeded()

        setContent {
            AntiFraudAgentTheme {
                val repository = remember { MessageRepository(applicationContext) }
                val settings = remember { SettingsRepository.getInstance(applicationContext) }
                val captureEnabled by settings.captureEnabled.collectAsState()
                val scope = rememberCoroutineScope()
                var selectedTab by remember { mutableStateOf(AppTab.Home) }
                var fraudLogs by remember { mutableStateOf<List<RemoteFraudLog>>(emptyList()) }
                var pendingCount by remember { mutableStateOf(0) }
                var isLoading by remember { mutableStateOf(false) }
                var feedback by remember { mutableStateOf<String?>(null) }
                var manualText by remember { mutableStateOf("") }
                var manualResult by remember { mutableStateOf<FraudAnalysisResult?>(null) }
                var manualError by remember { mutableStateOf<String?>(null) }
                var isAnalyzing by remember { mutableStateOf(false) }

                fun refreshData() {
                    scope.launch {
                        isLoading = true
                        feedback = null
                        try {
                            repository.processPendingMessages()
                            pendingCount = repository.getPendingMessages().size
                            fraudLogs = repository.getConfirmedFrauds()
                                .sortedByDescending { it.detectedAt }
                            feedback = "Historico atualizado pelo servidor."
                        } catch (e: Exception) {
                            pendingCount = repository.getPendingMessages().size
                            feedback = "Nao foi possivel consultar o servidor: ${e.message ?: "erro desconhecido"}"
                        } finally {
                            isLoading = false
                        }
                    }
                }

                fun analyzeManualMessage() {
                    val content = manualText.trim()
                    if (content.isBlank()) {
                        manualError = "Cole uma mensagem suspeita para analisar."
                        manualResult = null
                        return
                    }

                    scope.launch {
                        isAnalyzing = true
                        manualError = null
                        manualResult = null
                        try {
                            manualResult = repository.analyzeManualMessage(content)
                            fraudLogs = repository.getConfirmedFrauds()
                                .sortedByDescending { it.detectedAt }
                            pendingCount = repository.getPendingMessages().size
                        } catch (e: Exception) {
                            manualError = e.message ?: "Nao foi possivel analisar a mensagem."
                        } finally {
                            isAnalyzing = false
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    refreshData()
                }

                BlessGuardianApp(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    notificationEnabled = isNotificationEnabled,
                    accessibilityEnabled = isAccessibilityEnabled,
                    captureEnabled = captureEnabled,
                    pendingCount = pendingCount,
                    logs = fraudLogs,
                    isLoading = isLoading,
                    feedback = feedback,
                    manualText = manualText,
                    manualResult = manualResult,
                    manualError = manualError,
                    isAnalyzing = isAnalyzing,
                    onManualTextChange = { manualText = it },
                    onAnalyzeManual = { analyzeManualMessage() },
                    onRefresh = { refreshData() },
                    onRequestNotification = { openNotificationListenerSettings() },
                    onRequestAccessibility = { openAccessibilitySettings() },
                    onCaptureEnabledChange = { settings.setCaptureEnabled(it) }
                )
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

enum class AppTab(val label: String, val icon: ImageVector) {
    Home("Inicio", Icons.Filled.Security),
    History("Historico", Icons.Filled.History),
    Analyze("Analisar", Icons.Filled.Search),
    Profile("Perfil", Icons.Filled.Person)
}

enum class RiskFilter(val label: String) {
    All("Todos"),
    High("Alto risco"),
    Medium("Medio"),
    Safe("Seguro")
}

data class RiskVisual(
    val label: String,
    val color: Color,
    val softColor: Color
)

@Composable
fun BlessGuardianApp(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    notificationEnabled: Boolean,
    accessibilityEnabled: Boolean,
    captureEnabled: Boolean,
    pendingCount: Int,
    logs: List<RemoteFraudLog>,
    isLoading: Boolean,
    feedback: String?,
    manualText: String,
    manualResult: FraudAnalysisResult?,
    manualError: String?,
    isAnalyzing: Boolean,
    onManualTextChange: (String) -> Unit,
    onAnalyzeManual: () -> Unit,
    onRefresh: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onCaptureEnabledChange: (Boolean) -> Unit
) {
    Scaffold(
        containerColor = BlessBackground,
        bottomBar = {
            BlessBottomBar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
        }
    ) { padding ->
        when (selectedTab) {
            AppTab.Home -> HomeScreen(
                padding = padding,
                logs = logs,
                pendingCount = pendingCount,
                captureEnabled = captureEnabled,
                isLoading = isLoading,
                feedback = feedback,
                onRefresh = onRefresh
            )

            AppTab.History -> HistoryScreen(
                padding = padding,
                logs = logs,
                isLoading = isLoading,
                feedback = feedback,
                onRefresh = onRefresh
            )

            AppTab.Analyze -> AnalyzeScreen(
                padding = padding,
                manualText = manualText,
                manualResult = manualResult,
                manualError = manualError,
                isAnalyzing = isAnalyzing,
                onManualTextChange = onManualTextChange,
                onAnalyzeManual = onAnalyzeManual
            )

            AppTab.Profile -> ProfileScreen(
                padding = padding,
                notificationEnabled = notificationEnabled,
                accessibilityEnabled = accessibilityEnabled,
                captureEnabled = captureEnabled,
                pendingCount = pendingCount,
                logs = logs,
                onRequestNotification = onRequestNotification,
                onRequestAccessibility = onRequestAccessibility,
                onCaptureEnabledChange = onCaptureEnabledChange
            )
        }
    }
}

@Composable
fun BlessBottomBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF071023),
        tonalElevation = 0.dp,
        modifier = Modifier.border(1.dp, BlessBorder.copy(alpha = 0.5f))
    ) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BlessPrimary,
                    selectedTextColor = BlessPrimary,
                    unselectedIconColor = BlessMuted.copy(alpha = 0.45f),
                    unselectedTextColor = BlessMuted.copy(alpha = 0.45f),
                    indicatorColor = BlessPrimarySoft.copy(alpha = 0.35f)
                )
            )
        }
    }
}

@Composable
fun HomeScreen(
    padding: PaddingValues,
    logs: List<RemoteFraudLog>,
    pendingCount: Int,
    captureEnabled: Boolean,
    isLoading: Boolean,
    feedback: String?,
    onRefresh: () -> Unit
) {
    val analyzedCount = logs.size
    val blockedCount = logs.count { it.isFraud }
    val vulnerability = logs.takeIf { it.isNotEmpty() }
        ?.map { it.riskScore }
        ?.average()
        ?.toFloat()
        ?: 0f
    val recentAlerts = logs.filter { it.isFraud }.take(3)

    ScreenColumn(padding = padding) {
        item {
            TopHandle()
            VulnerabilityCard(
                score = vulnerability,
                pendingCount = pendingCount,
                captureEnabled = captureEnabled
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.CheckCircle,
                    iconColor = BlessSafe,
                    value = analyzedCount.toString(),
                    label = "msgs analisadas"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Shield,
                    iconColor = BlessPrimary,
                    value = blockedCount.toString(),
                    label = "golpes bloqueados"
                )
            }
        }

        item {
            SectionTitle(
                title = "Alertas recentes",
                action = if (isLoading) "Atualizando" else "Atualizar",
                onAction = onRefresh
            )
        }

        if (feedback != null && recentAlerts.isEmpty()) {
            item {
                InlineStatusCard(text = feedback)
            }
        }

        if (recentAlerts.isEmpty()) {
            item {
                EmptyCard(text = if (isLoading) "Consultando alertas..." else "Nenhuma ameaca confirmada ate agora.")
            }
        } else {
            items(recentAlerts, key = { it.id }) { log ->
                RecentAlertRow(log = log)
            }
        }
    }
}

@Composable
fun HistoryScreen(
    padding: PaddingValues,
    logs: List<RemoteFraudLog>,
    isLoading: Boolean,
    feedback: String?,
    onRefresh: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf(RiskFilter.All) }
    val filteredLogs = logs.filter { log ->
        when (selectedFilter) {
            RiskFilter.All -> true
            RiskFilter.High -> log.riskScore >= 0.75f
            RiskFilter.Medium -> log.riskScore in 0.4f..<0.75f
            RiskFilter.Safe -> log.riskScore < 0.4f
        }
    }

    ScreenColumn(padding = padding) {
        item {
            PageHeader(
                icon = Icons.Filled.Shield,
                title = "Historico",
                subtitle = "Todas as mensagens analisadas"
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(RiskFilter.High, RiskFilter.Medium, RiskFilter.Safe).forEach { filter ->
                    RiskFilterChip(
                        label = filter.label,
                        selected = selectedFilter == filter,
                        visual = when (filter) {
                            RiskFilter.High -> RiskVisual("Alto risco", BlessDanger, BlessDangerSoft)
                            RiskFilter.Medium -> RiskVisual("Medio risco", BlessWarning, BlessWarningSoft)
                            RiskFilter.Safe -> RiskVisual("Seguro", BlessSafe, BlessSafeSoft)
                            RiskFilter.All -> RiskVisual("Todos", BlessPrimary, BlessPrimarySoft)
                        },
                        onClick = {
                            selectedFilter = if (selectedFilter == filter) RiskFilter.All else filter
                        }
                    )
                }
            }
        }

        item {
            SectionTitle(
                title = if (selectedFilter == RiskFilter.All) "Registros" else selectedFilter.label,
                action = if (isLoading) "Atualizando" else "Atualizar",
                onAction = onRefresh
            )
        }

        if (feedback != null) {
            item { InlineStatusCard(text = feedback) }
        }

        if (filteredLogs.isEmpty()) {
            item {
                EmptyCard(text = if (isLoading) "Consultando historico..." else "Nenhum registro para este filtro.")
            }
        } else {
            items(filteredLogs, key = { it.id }) { log ->
                HistoryLogCard(log = log)
            }
        }
    }
}

@Composable
fun AnalyzeScreen(
    padding: PaddingValues,
    manualText: String,
    manualResult: FraudAnalysisResult?,
    manualError: String?,
    isAnalyzing: Boolean,
    onManualTextChange: (String) -> Unit,
    onAnalyzeManual: () -> Unit
) {
    ScreenColumn(padding = padding) {
        item {
            PageHeader(
                icon = Icons.Filled.Shield,
                title = "Analise Retroativa",
                subtitle = "Cole uma mensagem suspeita para verificar"
            )
        }

        item {
            GlassPanel {
                Text(
                    text = "TEXTO DA MENSAGEM",
                    style = MaterialTheme.typography.labelLarge,
                    color = BlessMuted
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = manualText,
                    onValueChange = onManualTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 118.dp),
                    placeholder = {
                        Text("Cole aqui a mensagem que voce recebeu e quer verificar...")
                    },
                    minLines = 4
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.CameraAlt,
                    label = "Print"
                )
                SecondaryActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Share,
                    label = "Compartilhar"
                )
            }
        }

        item {
            Button(
                onClick = onAnalyzeManual,
                enabled = !isAnalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BlessPrimary,
                    contentColor = BlessBackground
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = BlessBackground
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analisando")
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analisar agora", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (manualError != null) {
            item {
                FeedbackCard(
                    text = manualError,
                    isError = true
                )
            }
        }

        if (manualResult != null) {
            item {
                ManualResultCard(result = manualResult)
            }
        }
    }
}

@Composable
fun ProfileScreen(
    padding: PaddingValues,
    notificationEnabled: Boolean,
    accessibilityEnabled: Boolean,
    captureEnabled: Boolean,
    pendingCount: Int,
    logs: List<RemoteFraudLog>,
    onRequestNotification: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onCaptureEnabledChange: (Boolean) -> Unit
) {
    val analyzedCount = logs.size
    val blockedCount = logs.count { it.isFraud }
    val vulnerability = logs.takeIf { it.isNotEmpty() }
        ?.map { it.riskScore }
        ?.average()
        ?.toFloat()
        ?: 0f

    ScreenColumn(padding = padding) {
        item {
            PageHeader(
                icon = Icons.Filled.Shield,
                title = "Perfil",
                subtitle = "Configure seu nivel de vigilancia"
            )
        }

        item {
            GlassPanel {
                PanelLabel("MODO DE PROTECAO")
                ProtectionToggleRow(
                    title = "Protecao continua",
                    subtitle = "Ativa notificacoes e acessibilidade",
                    checked = notificationEnabled && accessibilityEnabled,
                    onClick = {
                        if (!notificationEnabled) onRequestNotification() else onRequestAccessibility()
                    }
                )
                ProtectionToggleRow(
                    title = "Alertas de ligacao",
                    subtitle = "Recurso planejado para chamadas suspeitas",
                    checked = false,
                    enabled = false,
                    onClick = {}
                )
                ProtectionToggleRow(
                    title = "Modo supervisionado",
                    subtitle = "Planejado para avisar responsavel em golpes graves",
                    checked = false,
                    enabled = false,
                    onClick = {}
                )
            }
        }

        item {
            GlassPanel {
                PanelLabel("PRIVACIDADE")
                ProtectionToggleRow(
                    title = "Envio para o servidor",
                    subtitle = if (captureEnabled) {
                        "Mensagens capturadas sao enviadas ao backend para analise."
                    } else {
                        "Pausado. Nada e enviado nem salvo na fila offline."
                    },
                    checked = captureEnabled,
                    onClick = { onCaptureEnabledChange(!captureEnabled) }
                )
                if (!captureEnabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Use ao testar com dados sensiveis no celular pessoal. " +
                            "Lembre de reativar antes de demonstrar o app.",
                        color = BlessWarning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            GlassPanel {
                PanelLabel("SENSIBILIDADE DO ALERTA")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RiskFilterChip(
                        label = "Padrao",
                        selected = true,
                        visual = RiskVisual("Padrao", BlessPrimary, BlessPrimarySoft),
                        onClick = {}
                    )
                    RiskFilterChip(
                        label = "Alta",
                        selected = false,
                        visual = RiskVisual("Alta", BlessMuted, BlessSurfaceElevated),
                        onClick = {}
                    )
                    RiskFilterChip(
                        label = "Maxima",
                        selected = false,
                        visual = RiskVisual("Maxima", BlessMuted, BlessSurfaceElevated),
                        onClick = {}
                    )
                }
            }
        }

        item {
            GlassPanel {
                PanelLabel("RESUMO DE ATIVIDADE")
                SummaryRow("Protegido desde", todayLabel())
                SummaryRow("Mensagens analisadas", analyzedCount.toString())
                SummaryRow("Golpes bloqueados", "$blockedCount confirmados", BlessDanger)
                SummaryRow(
                    "Pendencias offline",
                    pendingCount.toString(),
                    if (pendingCount > 0) BlessWarning else BlessSafe
                )
                SummaryRow(
                    "Indice de vulnerabilidade",
                    "${formatScore(vulnerability)} - ${riskName(vulnerability)}",
                    riskVisual(vulnerability).color
                )
            }
        }
    }
}

@Composable
fun ScreenColumn(
    padding: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BlessBackground),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = padding.calculateTopPadding() + 28.dp,
            end = 24.dp,
            bottom = padding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
fun TopHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(5.dp)
                .clip(CircleShape)
                .background(BlessPrimarySoft.copy(alpha = 0.55f))
        )
    }
}

@Composable
fun PageHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Column {
        TopHandle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.blessguardian_logo),
                contentDescription = "BlessGuardian",
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BlessPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            color = BlessMuted,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun VulnerabilityCard(score: Float, pendingCount: Int, captureEnabled: Boolean) {
    GlassPanel {
        Text(
            text = "INDICE DE VULNERABILIDADE",
            style = MaterialTheme.typography.labelLarge,
            color = BlessMuted
        )
        Spacer(modifier = Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp),
            contentAlignment = Alignment.Center
        ) {
            GaugeArc(score = score)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatScore(score),
                    style = MaterialTheme.typography.titleLarge,
                    color = BlessPrimary
                )
                Text(
                    text = riskName(score),
                    style = MaterialTheme.typography.bodySmall,
                    color = BlessMuted
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val statusVisual = when {
                !captureEnabled -> RiskVisual("Pausado", BlessWarning, BlessWarningSoft)
                pendingCount == 0 -> RiskVisual("Seguro", BlessSafe, BlessSafeSoft)
                else -> RiskVisual("Pendente", BlessWarning, BlessWarningSoft)
            }
            val statusValue = when {
                !captureEnabled -> "pausado"
                pendingCount == 0 -> "online"
                else -> "offline"
            }
            val statusLabel = when {
                !captureEnabled -> "sem envio"
                pendingCount == 0 -> "protegido"
                else -> "$pendingCount pendencias"
            }
            StatusPill(
                modifier = Modifier.weight(1f),
                label = statusLabel,
                value = statusValue,
                visual = statusVisual
            )
            StatusPill(
                modifier = Modifier.weight(1f),
                label = riskName(score),
                value = "risco atual",
                visual = riskVisual(score)
            )
        }
    }
}

@Composable
fun GaugeArc(score: Float) {
    Canvas(modifier = Modifier.size(width = 142.dp, height = 82.dp)) {
        val strokeWidth = 12.dp.toPx()
        val arcSize = Size(size.width, size.height * 1.8f)
        drawArc(
            color = BlessPrimarySoft.copy(alpha = 0.65f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawArc(
            color = BlessPrimary,
            startAngle = 180f,
            sweepAngle = 180f * score.coerceIn(0f, 1f),
            useCenter = false,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun MetricCard(
    modifier: Modifier,
    icon: ImageVector,
    iconColor: Color,
    value: String,
    label: String
) {
    GlassPanel(modifier = modifier.height(104.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = BlessMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun SectionTitle(
    title: String,
    action: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        TextButton(onClick = onAction) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(action)
        }
    }
}

@Composable
fun RecentAlertRow(log: RemoteFraudLog) {
    val visual = riskVisual(log.riskScore)
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RiskCircle(visual = visual)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = log.source.ifBlank { "UNKNOWN" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    RiskBadge(visual = visual)
                }
                Text(
                    text = log.content.ifBlank { "Mensagem sem conteudo." },
                    color = BlessMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = compactDate(log.detectedAt),
                color = BlessMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HistoryLogCard(log: RemoteFraudLog) {
    val visual = riskVisual(log.riskScore)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, visual.color.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = BlessSurface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${log.source.ifBlank { "UNKNOWN" }} - ${if (log.isFraud) "confirmado" else "analisado"}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                ScorePill(score = log.riskScore, visual = visual)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = log.content.ifBlank { "Mensagem sem conteudo retornado." },
                color = BlessText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (log.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = log.explanation,
                    color = BlessMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = compactDate(log.detectedAt),
                color = BlessMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ManualResultCard(result: FraudAnalysisResult) {
    val visual = riskVisual(result.score)
    GlassPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (result.isFraud) "Possivel golpe detectado" else "Mensagem com baixo risco",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = result.category.ifBlank { "categoria nao informada" },
                    color = BlessMuted
                )
            }
            ScorePill(score = result.score, visual = visual)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = result.explanation,
            color = BlessText
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Gravado no historico oficial.",
            color = BlessSafe,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun ProtectionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = BlessMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { onClick() }
        )
    }
}

@Composable
fun SummaryRow(
    label: String,
    value: String,
    valueColor: Color = BlessText
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = BlessMuted)
        Text(text = value, color = valueColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PanelLabel(text: String) {
    Text(
        text = text,
        color = BlessMuted,
        style = MaterialTheme.typography.labelLarge
    )
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, BlessBorder, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = BlessSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun EmptyCard(text: String) {
    GlassPanel {
        Text(text = text, color = BlessMuted)
    }
}

@Composable
fun InlineStatusCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = BlessSurfaceElevated
    ) {
        Text(
            text = text,
            color = BlessMuted,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
fun FeedbackCard(text: String, isError: Boolean) {
    val color = if (isError) BlessDanger else BlessSafe
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.8f), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = if (isError) BlessDangerSoft else BlessSafeSoft
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isError) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = color
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = text, color = BlessText)
        }
    }
}

@Composable
fun SecondaryActionButton(
    modifier: Modifier,
    icon: ImageVector,
    label: String
) {
    OutlinedButton(
        onClick = {},
        enabled = false,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
fun StatusPill(
    modifier: Modifier,
    label: String,
    value: String,
    visual: RiskVisual
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = visual.softColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, visual.color.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, color = visual.color, style = MaterialTheme.typography.bodySmall)
            Text(text = label, color = BlessText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun RiskFilterChip(
    label: String,
    selected: Boolean,
    visual: RiskVisual,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) visual.softColor else BlessSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) visual.color else BlessBorder
        )
    ) {
        Text(
            text = label,
            color = if (selected) visual.color else BlessMuted,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun RiskCircle(visual: RiskVisual) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(visual.softColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = visual.color,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun RiskBadge(visual: RiskVisual) {
    Surface(
        shape = CircleShape,
        color = visual.softColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, visual.color.copy(alpha = 0.65f))
    ) {
        Text(
            text = visual.label.lowercase(),
            color = visual.color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun ScorePill(score: Float, visual: RiskVisual) {
    Surface(
        shape = CircleShape,
        color = visual.softColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, visual.color.copy(alpha = 0.65f))
    ) {
        Text(
            text = formatScore(score),
            color = visual.color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontWeight = FontWeight.Bold
        )
    }
}

fun riskVisual(score: Float): RiskVisual = when {
    score >= 0.75f -> RiskVisual("Alto risco", BlessDanger, BlessDangerSoft)
    score >= 0.4f -> RiskVisual("Medio risco", BlessWarning, BlessWarningSoft)
    else -> RiskVisual("Seguro", BlessSafe, BlessSafeSoft)
}

fun riskName(score: Float): String = riskVisual(score).label.lowercase()

fun formatScore(score: Float): String = String.format(Locale.US, "%.2f", score.coerceIn(0f, 1f))

fun compactDate(value: String): String {
    if (value.isBlank()) return "agora"
    return value
        .replace("T", " ")
        .replace("Z", "")
        .take(16)
}

fun todayLabel(): String {
    return SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date())
}
