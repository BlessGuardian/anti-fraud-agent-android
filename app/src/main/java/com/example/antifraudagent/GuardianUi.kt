package com.example.antifraudagent

import android.Manifest
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.antifraudagent.calls.CallTerminator
import com.example.antifraudagent.data.auth.AuthRepository
import com.example.antifraudagent.data.guardian.GuardianContact
import com.example.antifraudagent.data.guardian.GuardianRepository
import com.example.antifraudagent.data.guardian.LinkCode
import com.example.antifraudagent.data.settings.SettingsRepository
import com.example.antifraudagent.ui.theme.BlessBackground
import com.example.antifraudagent.ui.theme.BlessBorder
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessMuted
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessSafe
import com.example.antifraudagent.ui.theme.BlessText
import com.example.antifraudagent.ui.theme.BlessWarning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Painel "IDOSOS E CRIANCAS" do Perfil.
 * - Celular da pessoa protegida: liga a protecao reforcada e le o QR de cada responsavel; em golpe
 *   de alto risco, o responsavel recebe SMS na hora.
 * - Celular do responsavel: "Proteger alguém" gera o QR com o contato dele.
 */
@Composable
fun GuardianPanel() {
    val context = LocalContext.current
    val guardian = remember { GuardianRepository.getInstance(context) }
    val settings = remember { SettingsRepository.getInstance(context) }
    val protectedDevice by guardian.protectedDevice.collectAsState()
    val guardians by guardian.guardians.collectAsState()
    var showGuardians by remember { mutableStateOf(false) }
    var showMyQr by remember { mutableStateOf(false) }

    val requestCallPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        settings.setReinforcedProtection(true)
        guardian.setProtectedDevice(true)
        showGuardians = true
    }

    GlassPanel {
        PanelLabel("IDOSOS E CRIANÇAS")
        ProtectionToggleRow(
            title = "Este celular é de uma pessoa protegida",
            subtitle = if (protectedDevice) {
                "Proteção reforçada ligada. Os responsáveis são avisados por SMS em golpe confirmado"
            } else {
                "Para o celular do idoso ou da criança: liga a proteção reforçada e avisa os responsáveis"
            },
            checked = protectedDevice,
            onClick = {
                when {
                    protectedDevice -> guardian.setProtectedDevice(false)
                    CallTerminator.hasPermission(context) -> {
                        settings.setReinforcedProtection(true)
                        guardian.setProtectedDevice(true)
                        showGuardians = true
                    }
                    else -> requestCallPermission.launch(Manifest.permission.ANSWER_PHONE_CALLS)
                }
            }
        )
        if (protectedDevice) {
            PanelRow(
                icon = { Icon(Icons.Filled.Sms, contentDescription = null, tint = BlessPrimary, modifier = Modifier.size(22.dp)) },
                title = "Meus responsáveis",
                subtitle = when (guardians.size) {
                    0 -> "Nenhum ainda. Leia o QR do celular do responsável"
                    1 -> "1 responsável: ${guardians.first().name}"
                    else -> "${guardians.size} responsáveis"
                },
                onClick = { showGuardians = true }
            )
        }
        PanelRow(
            icon = { Icon(Icons.Filled.FamilyRestroom, contentDescription = null, tint = BlessPrimary, modifier = Modifier.size(22.dp)) },
            title = "Proteger alguém",
            subtitle = "Mostre seu QR no celular do idoso ou da criança",
            onClick = { showMyQr = true }
        )
    }

    if (showGuardians) GuardiansSheet(guardian, guardians, onDismiss = { showGuardians = false })
    if (showMyQr) MyQrSheet(guardian, onDismiss = { showMyQr = false })
}

@Composable
private fun PanelRow(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = BlessText)
            Text(subtitle, color = BlessMuted, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onClick) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Abrir $title", tint = BlessMuted)
        }
    }
}

/** Celular da pessoa protegida: responsaveis cadastrados pelo QR deles. */
@Composable
private fun GuardiansSheet(guardian: GuardianRepository, guardians: List<GuardianContact>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var smsAllowed by remember { mutableStateOf(guardian.canSendSms()) }
    var pending by remember { mutableStateOf<GuardianContact?>(null) }
    var manual by remember { mutableStateOf(false) }
    var protectedName by remember { mutableStateOf(guardian.protectedName()) }

    val requestSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        smsAllowed = granted
        if (!granted) toast(context, "Sem a permissão de SMS, os responsáveis não são avisados.")
    }

    fun add(contact: GuardianContact) {
        val added = guardian.addGuardian(contact)
        toast(context, if (added) "${contact.name} vai receber os avisos." else "Esse responsável já está cadastrado.")
        if (added && !smsAllowed) requestSms.launch(Manifest.permission.SEND_SMS)
    }

    GuardianSheet(onDismiss) {
        Text("Meus responsáveis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Quando o BlessGuardian confirmar um golpe neste celular, cada responsável recebe um SMS na hora. " +
                "O SMS não leva o texto da mensagem, só o aviso.",
            color = BlessMuted,
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = protectedName,
            onValueChange = { protectedName = it; guardian.setProtectedName(it) },
            label = { Text("Seu nome (aparece no SMS)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                scanQr(context) { raw ->
                    val contact = LinkCode.decode(raw)
                    if (contact == null) toast(context, "Esse QR não é de um responsável do BlessGuardian.") else pending = contact
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BlessPrimary)
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ler QR do responsável", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { manual = true },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Digitar os dados")
        }

        if (guardians.isNotEmpty() && !smsAllowed) {
            Text("Falta permitir o envio de SMS.", color = BlessWarning, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { requestSms.launch(Manifest.permission.SEND_SMS) }) { Text("Permitir SMS") }
        }

        if (guardians.isEmpty()) {
            EmptyCard("Nenhum responsável ainda.")
        } else {
            guardians.forEach { contact ->
                GlassPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(contact.name, fontWeight = FontWeight.Bold, color = BlessText)
                            Text(contact.phone, color = BlessMuted, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = if (smsAllowed) "SMS: ativo" else "SMS: falta permissão",
                                color = if (smsAllowed) BlessSafe else BlessWarning,
                                style = MaterialTheme.typography.labelSmall
                            )
                            contact.email?.let {
                                Text(
                                    "E-mail $it: aviso por e-mail quando o servidor tiver esse envio",
                                    color = BlessMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        IconButton(onClick = { guardian.removeGuardian(contact) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remover", tint = BlessDanger)
                        }
                    }
                }
            }
        }
    }

    pending?.let { contact ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Adicionar responsável?") },
            text = { Text("${contact.name}\n${contact.phone}${contact.email?.let { "\n$it" } ?: ""}") },
            confirmButton = { TextButton(onClick = { add(contact); pending = null }) { Text("Adicionar") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancelar") } }
        )
    }
    if (manual) {
        ContactDialog(
            title = "Novo responsável",
            initial = null,
            onDismiss = { manual = false },
            onConfirm = { add(it); manual = false }
        )
    }
}

/** Celular do responsavel: QR com o proprio contato. */
@Composable
private fun MyQrSheet(guardian: GuardianRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sessionEmail = remember { AuthRepository.getInstance(context).session.value?.email }
    var contact by remember { mutableStateOf(guardian.myContact()) }
    var editing by remember { mutableStateOf(contact == null) }

    GuardianSheet(onDismiss) {
        Text("Proteger alguém", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "No celular do idoso ou da criança: Perfil → Este celular é de uma pessoa protegida → " +
                "Meus responsáveis → Ler QR do responsável.",
            color = BlessMuted,
            style = MaterialTheme.typography.bodyMedium
        )
        val current = contact
        if (current != null) {
            val code = remember(current) { LinkCode.encode(current) }
            val qr = remember(code) { qrBitmap(code) }
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "QR do responsável",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .padding(16.dp)
            )
            Text("${current.name} · ${current.phone}${current.email?.let { " · $it" } ?: ""}", color = BlessText)
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                        Intent.EXTRA_TEXT,
                        "Código de responsável do BlessGuardian (cole em Meus responsáveis): $code"
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
            TextButton(onClick = { editing = true }) { Text("Alterar meus dados") }
        } else {
            Icon(Icons.Filled.QrCode2, contentDescription = null, tint = BlessMuted, modifier = Modifier.size(48.dp))
            Text("Preencha seus dados para gerar o QR.", color = BlessMuted)
        }
    }

    if (editing) {
        ContactDialog(
            title = "Seus dados de responsável",
            initial = contact ?: GuardianContact("", "", sessionEmail),
            onDismiss = { editing = false },
            onConfirm = {
                guardian.saveMyContact(it)
                contact = it
                editing = false
            }
        )
    }
}

@Composable
private fun ContactDialog(
    title: String,
    initial: GuardianContact?,
    onDismiss: () -> Unit,
    onConfirm: (GuardianContact) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Celular com DDD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-mail (opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val contact = GuardianContact(
                    name = name.trim().ifBlank { "Responsável" },
                    phone = phone.trim(),
                    email = email.trim().takeIf { it.contains('@') }
                )
                // Valida pelo mesmo caminho do QR (telefone com DDD).
                if (LinkCode.decode(LinkCode.encode(contact)) == null) {
                    toast(context, "Informe o celular com DDD.")
                } else {
                    onConfirm(contact)
                }
            }) { Text("Salvar") }
        },
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
        .addOnFailureListener { toast(context, "Não foi possível abrir o leitor de QR. Use \"Digitar os dados\".") }
}

private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
