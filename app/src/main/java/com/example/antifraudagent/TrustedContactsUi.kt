package com.example.antifraudagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.antifraudagent.data.local.trusted.TrustedContact
import com.example.antifraudagent.data.local.trusted.TrustedContactOrigin
import com.example.antifraudagent.data.trusted.TrustedContactRepository
import com.example.antifraudagent.ui.theme.BlessBackground
import com.example.antifraudagent.ui.theme.BlessBorder
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessMuted
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessSafe
import com.example.antifraudagent.ui.theme.BlessText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Painel "Contatos confiaveis" do Perfil. Mensagens desses contatos continuam analisadas, mas so
 * geram alerta em alto risco (conta clonada). A lista fica so no aparelho.
 */
@Composable
fun TrustedContactsPanel() {
    val context = LocalContext.current
    val repository = remember { TrustedContactRepository(context) }
    val contacts by remember { repository.observeAll() }.collectAsState(initial = emptyList())
    var showSheet by remember { mutableStateOf(false) }

    GlassPanel(onClick = { showSheet = true }) {
        PanelLabel("CONTATOS CONFIÁVEIS")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = BlessSafe, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (contacts.size) {
                        0 -> "Nenhum contato cadastrado"
                        1 -> "1 contato confiável"
                        else -> "${contacts.size} contatos confiáveis"
                    },
                    fontWeight = FontWeight.Bold,
                    color = BlessText
                )
                Text(
                    text = "Mensagens deles só geram alerta se o risco for alto.",
                    color = BlessMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = "Gerenciar", tint = BlessMuted)
        }
    }

    if (showSheet) {
        TrustedContactsSheet(
            contacts = contacts,
            repository = repository,
            onDismiss = { showSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrustedContactsSheet(
    contacts: List<TrustedContact>,
    repository: TrustedContactRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }

    // Seletor da agenda: o usuario escolhe UM numero e o app recebe acesso so a ele
    // (sem READ_CONTACTS; a agenda inteira nunca e lida).
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = readPickedPhone(context, uri)
            if (picked == null) {
                toast(context, "Não foi possível ler esse contato.")
                return@launch
            }
            val added = repository.add(picked.first, picked.second, TrustedContactOrigin.CONTACTS_APP)
            toast(context, if (added) "${picked.first ?: picked.second} adicionado" else "Esse contato já está na lista.")
        }
    }

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
        ) {
            Text("Contatos confiáveis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = "Mensagens de quem está nesta lista não geram alerta de atenção. " +
                    "Se uma delas parecer golpe com certeza, você ainda é avisado: a conta pode ter sido clonada. " +
                    "A lista fica só no seu celular.",
                color = BlessMuted,
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    try {
                        pickContact.launch(intent)
                    } catch (_: Exception) {
                        toast(context, "Nenhum app de contatos encontrado.")
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BlessPrimary)
            ) {
                Icon(Icons.Filled.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Escolher da agenda", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Adicionar manualmente")
            }

            if (contacts.isEmpty()) {
                EmptyCard(text = "Nenhum contato ainda. Adicione familiares e amigos que você conhece bem.")
            } else {
                contacts.forEach { contact ->
                    TrustedContactRow(contact = contact, onRemove = { scope.launch { repository.remove(contact) } })
                }
            }
        }
    }

    if (showAddDialog) {
        AddTrustedContactDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, phone ->
                scope.launch {
                    val added = repository.add(name, phone, TrustedContactOrigin.MANUAL)
                    if (added) {
                        showAddDialog = false
                    } else {
                        toast(context, "Informe um nome ou número válido que ainda não esteja na lista.")
                    }
                }
            }
        )
    }
}

@Composable
private fun TrustedContactRow(contact: TrustedContact, onRemove: () -> Unit) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(contact.name ?: contact.phone.orEmpty(), fontWeight = FontWeight.Bold, color = BlessText)
                if (contact.name != null && contact.phone != null) {
                    Text(contact.phone, color = BlessMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = if (contact.origin == TrustedContactOrigin.CONTACTS_APP) "Da agenda" else "Adicionado manualmente",
                    color = BlessMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remover", tint = BlessDanger)
            }
        }
    }
}

@Composable
private fun AddTrustedContactDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo contato confiável") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome (como está na agenda)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Número com DDD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Text(
                    "WhatsApp, Telegram e Instagram mostram o nome; SMS mostra o número. Preencha os dois quando puder.",
                    color = BlessMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, phone) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Le nome e numero do contato escolhido no seletor (acesso temporario so a essa linha). */
private suspend fun readPickedPhone(context: Context, uri: Uri): Pair<String?, String>? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val number = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: return@use null
            cursor.getString(0)?.takeIf { it.isNotBlank() } to number
        }
    } catch (_: Exception) {
        null
    }
}

private fun toast(context: Context, text: String) =
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
