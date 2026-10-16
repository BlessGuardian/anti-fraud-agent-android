package com.example.antifraudagent

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.antifraudagent.calls.CallTerminator
import com.example.antifraudagent.data.guardian.GuardianRepository
import com.example.antifraudagent.data.guardian.GuardianRepository.AddResult
import com.example.antifraudagent.data.guardian.ProtectedPerson
import com.example.antifraudagent.data.remote.RemoteFraudLog
import com.example.antifraudagent.data.remote.RiskLevel
import com.example.antifraudagent.data.settings.SettingsRepository
import com.example.antifraudagent.ui.theme.BlessBackground
import com.example.antifraudagent.ui.theme.BlessBorder
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessMuted
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessText
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

/**
 * Painel "IDOSOS E CRIANCAS" do Perfil (modo idoso/crianca, versao com codigo de vinculo).
 * - "Este celular e de uma pessoa protegida": liga a protecao reforcada e mostra o codigo/QR.
 * - "Pessoas que eu protejo": lado do responsavel; recebe notificacao de golpe da pessoa vinculada.
 */
@Composable
fun GuardianPanel() {
    val context = LocalContext.current
    val guardian = remember { GuardianRepository.getInstance(context) }
    val settings = remember { SettingsRepository.getInstance(context) }
    val protectedDevice by guardian.protectedDevice.collectAsState()
    val people by guardian.people.collectAsState()
    var showCode by remember { mutableStateOf(false) }
    var showPeople by remember { mutableStateOf(false) }

    val requestCallPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        settings.setReinforcedProtection(true)
        guardian.setProtectedDevice(true)
        showCode = true
    }

    GlassPanel {
        PanelLabel("IDOSOS E CRIANÇAS")
        ProtectionToggleRow(
            title = "Este celular é de uma pessoa protegida",
            subtitle = if (protectedDevice) {
                "Proteção reforçada ligada. O responsável recebe os alertas"
            } else {
                "Para o celular do idoso ou da criança: liga a proteção reforçada e gera o código de vínculo"
            },
            checked = protectedDevice,
            onClick = {
                when {
                    protectedDevice -> guardian.setProtectedDevice(false)
                    CallTerminator.hasPermission(context) -> {
                        settings.setReinforcedProtection(true)
                        guardian.setProtectedDevice(true)
                        showCode = true
                    }
                    else -> requestCallPermission.launch(Manifest.permission.ANSWER_PHONE_CALLS)
                }
            }
        )
        if (protectedDevice) {
            TextButton(onClick = { showCode = true }) {
                Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Mostrar código de vínculo")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.FamilyRestroom, contentDescription = null, tint = BlessPrimary, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Pessoas que eu protejo", fontWeight = FontWeight.Bold, color = BlessText)
                Text(
                    text = when (people.size) {
                        0 -> "Nenhuma. Vincule pelo código do celular da pessoa"
                        1 -> "1 pessoa: ${people.first().name}"
                        else -> "${people.size} pessoas"
                    },
                    color = BlessMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = { showPeople = true }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Abrir pessoas protegidas", tint = BlessMuted)
            }
        }
    }

    if (showCode) LinkCodeSheet(code = guardian.ownLinkCode(), onDismiss = { showCode = false })
    if (showPeople) ProtectedPeopleSheet(guardian = guardian, people = people, onDismiss = { showPeople = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkCodeSheet(code: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val qr = remember(code) { qrBitmap(code) }
    GuardianSheet(onDismiss) {
        Text("Código de vínculo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "No celular do responsável, abra Perfil → Pessoas que eu protejo → Ler QR code. " +
                "Ele passa a receber um aviso quando o BlessGuardian detectar golpe neste celular.",
            color = BlessMuted,
            style = MaterialTheme.typography.bodyMedium
        )
        Image(
            bitmap = qr.asImageBitmap(),
            contentDescription = "QR code de vínculo",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(16.dp)
        )
        Text(code, fontFamily = FontFamily.Monospace, color = BlessText, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                    Intent.EXTRA_TEXT,
                    "Meu código de vínculo do BlessGuardian: $code"
                )
                context.startActivity(Intent.createChooser(send, "Enviar código"))
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enviar o código por mensagem")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtectedPeopleSheet(guardian: GuardianRepository, people: List<ProtectedPerson>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    GuardianSheet(onDismiss) {
        Text("Pessoas que eu protejo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Você recebe uma notificação quando o BlessGuardian detectar golpe no celular delas. " +
                "A verificação roda a cada ~15 minutos e sempre que você abre o app.",
            color = BlessMuted,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = { scanQr(context) { pendingCode = it } },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BlessPrimary)
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ler QR code", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { pendingCode = clipboardText(context).orEmpty() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Digitar ou colar o código")
        }

        if (people.isEmpty()) {
            EmptyCard("Ninguém vinculado ainda.")
        } else {
            people.forEach { person ->
                ProtectedPersonCard(person, guardian, onRemove = { guardian.remove(person) })
            }
            TextButton(
                onClick = {
                    checking = true
                    scope.launch {
                        guardian.checkForNewAlerts()
                        checking = false
                        toast(context, "Verificado. Golpes novos aparecem como notificação.")
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (checking) "Verificando..." else "Verificar agora")
            }
        }
    }

    pendingCode?.let { initial ->
        AddPersonDialog(
            initialCode = initial,
            onDismiss = { pendingCode = null },
            onConfirm = { name, code ->
                scope.launch {
                    val message = when (guardian.add(name, code)) {
                        AddResult.ADDED -> { pendingCode = null; "$name vinculado(a)." }
                        AddResult.INVALID_CODE -> "Código inválido. Confira e tente de novo."
                        AddResult.OWN_DEVICE -> "Esse é o código deste celular. Use o código do celular da pessoa."
                        AddResult.ALREADY_LINKED -> { pendingCode = null; "Essa pessoa já está vinculada." }
                        AddResult.OFFLINE -> "Sem conexão com o servidor. Tente de novo."
                    }
                    toast(context, message)
                }
            }
        )
    }
}

@Composable
private fun ProtectedPersonCard(person: ProtectedPerson, guardian: GuardianRepository, onRemove: () -> Unit) {
    val scope = rememberCoroutineScope()
    var alerts by remember(person.userId) { mutableStateOf<List<RemoteFraudLog>?>(null) }
    var loading by remember(person.userId) { mutableStateOf(false) }
    var error by remember(person.userId) { mutableStateOf(false) }

    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(person.name, fontWeight = FontWeight.Bold, color = BlessText)
                Text("Vinculado por código", color = BlessMuted, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Desvincular", tint = BlessDanger)
            }
        }
        TextButton(
            onClick = {
                loading = true
                error = false
                scope.launch {
                    alerts = try {
                        guardian.history(person).filter { it.riskLevel != RiskLevel.SAFE }.take(5)
                    } catch (_: Exception) {
                        error = true
                        null
                    }
                    loading = false
                }
            }
        ) {
            Text(if (alerts == null) "Ver alertas recentes" else "Atualizar alertas")
        }
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            error -> Text("Não foi possível consultar agora.", color = BlessDanger, style = MaterialTheme.typography.bodySmall)
            alerts?.isEmpty() == true -> Text("Nenhum alerta recente.", color = BlessMuted, style = MaterialTheme.typography.bodySmall)
        }
        alerts?.forEach { log ->
            val visual = logVisual(log)
            Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RiskBadge(visual = visual)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(log.content, color = BlessText, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${sourceLabel(log.source)} · ${compactDate(log.detectedAt)}",
                        color = BlessMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AddPersonDialog(initialCode: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(initialCode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vincular pessoa") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome (ex.: Vó Maria)") }, singleLine = true)
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Código de vínculo") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, code) }) { Text("Vincular") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuardianSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
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
        ) { content() }
    }
}

/** Leitor de QR do Google Play Services: sem permissao de camera no app. */
private fun scanQr(context: Context, onResult: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { barcode -> barcode.rawValue?.let(onResult) }
        .addOnFailureListener { toast(context, "Não foi possível abrir o leitor de QR. Use o código.") }
}

private fun clipboardText(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()
        ?.let { text -> Regex("""BG1:[0-9a-fA-F-]{36}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}""").find(text)?.value }

private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
