package com.example.antifraudagent

import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.antifraudagent.data.auth.AuthRepository
import com.example.antifraudagent.data.auth.AuthSession
import com.example.antifraudagent.data.remote.FraudApiHttpException
import com.example.antifraudagent.ui.theme.BlessBackground
import com.example.antifraudagent.ui.theme.BlessBorder
import com.example.antifraudagent.ui.theme.BlessDanger
import com.example.antifraudagent.ui.theme.BlessMuted
import com.example.antifraudagent.ui.theme.BlessPrimary
import com.example.antifraudagent.ui.theme.BlessPrimarySoft
import com.example.antifraudagent.ui.theme.BlessSafe
import com.example.antifraudagent.ui.theme.BlessSurfaceElevated
import com.example.antifraudagent.ui.theme.BlessText
import com.example.antifraudagent.ui.theme.BlessWarning
import kotlinx.coroutines.launch
import java.io.IOException

/** Abre a folha de conta (entrar, criar conta, sair) a partir de qualquer tela. */
val LocalOpenAccount = staticCompositionLocalOf<() -> Unit> { {} }

private const val MIN_PASSWORD_LENGTH = 6

/** Avatar do topo: inicial do e-mail quando ha conta; icone de pessoa quando nao ha. */
@Composable
fun AccountAvatar(session: AuthSession?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (session != null) BlessPrimarySoft else BlessSurfaceElevated)
            .border(1.dp, if (session != null) BlessPrimary else BlessBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (session != null && session.email.isNotBlank()) {
            Text(
                text = session.email.first().uppercase(),
                color = BlessText,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )
        } else {
            Icon(Icons.Filled.Person, contentDescription = "Conta", tint = BlessMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/** Painel "Conta" do Perfil. */
@Composable
fun AccountPanel() {
    val context = LocalContext.current
    val session by remember { AuthRepository.getInstance(context).session }.collectAsState()
    val openAccount = LocalOpenAccount.current
    GlassPanel(onClick = openAccount) {
        PanelLabel("CONTA")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = if (session != null) BlessSafe else BlessMuted,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session?.email ?: "Entrar ou criar conta",
                    fontWeight = FontWeight.Bold,
                    color = BlessText
                )
                Text(
                    text = when {
                        session == null -> "Opcional: o app continua protegendo sem conta."
                        session!!.isAdmin -> "Administrador"
                        else -> "Conta ligada a este celular"
                    },
                    color = BlessMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = "Abrir conta", tint = BlessMuted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { AuthRepository.getInstance(context) }
    val session by auth.session.collectAsState()

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
            val current = session
            if (current != null) {
                LoggedInContent(current, onLogout = { auth.logout() })
            } else {
                AuthForm(auth)
            }
        }
    }
}

@Composable
private fun LoggedInContent(session: AuthSession, onLogout: () -> Unit) {
    Text("Sua conta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    GlassPanel {
        Text(session.email, fontWeight = FontWeight.Bold, color = BlessText)
        Text(
            text = if (session.isAdmin) "Administrador: vê os detalhes técnicos das análises" else "Usuário",
            color = BlessMuted,
            style = MaterialTheme.typography.bodySmall
        )
        if (session.isExpired) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "A sessão expirou. Saia e entre de novo quando o app pedir.",
                color = BlessWarning,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    Text(
        "Em breve: cadastrar a pessoa de quem você é responsável (idoso ou criança) e receber os alertas dela.",
        color = BlessMuted,
        style = MaterialTheme.typography.bodyMedium
    )
    OutlinedButton(
        onClick = onLogout,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = BlessDanger, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Sair", color = BlessDanger)
    }
}

@Composable
private fun AuthForm(auth: AuthRepository) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text(
        if (creating) "Criar conta" else "Entrar",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        if (creating) {
            "A conta fica ligada a este celular e ao histórico de análises dele."
        } else {
            "Entre com a conta BlessGuardian. Sem conta, o app continua protegendo normalmente."
        },
        color = BlessMuted,
        style = MaterialTheme.typography.bodyMedium
    )

    OutlinedTextField(
        value = email,
        onValueChange = { email = it; error = null },
        label = { Text("E-mail") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; error = null },
        label = { Text("Senha") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    if (creating) {
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it; error = null },
            label = { Text("Confirme a senha") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
    }

    error?.let { Text(it, color = BlessDanger, style = MaterialTheme.typography.bodyMedium) }

    Button(
        onClick = {
            error = validate(email, password, confirm, creating)
            if (error != null) return@Button
            loading = true
            scope.launch {
                error = try {
                    if (creating) auth.register(email, password) else auth.login(email, password)
                    null
                } catch (e: Exception) {
                    friendlyError(e, creating)
                }
                loading = false
            }
        },
        enabled = !loading,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BlessPrimary)
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BlessText)
        } else {
            Text(if (creating) "Criar conta" else "Entrar", fontWeight = FontWeight.Bold)
        }
    }
    TextButton(
        onClick = { creating = !creating; error = null; confirm = "" },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (creating) "Já tenho conta: entrar" else "Não tenho conta: criar")
    }
}

private fun validate(email: String, password: String, confirm: String, creating: Boolean): String? = when {
    !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Digite um e-mail válido."
    password.length < MIN_PASSWORD_LENGTH -> "A senha precisa ter pelo menos $MIN_PASSWORD_LENGTH caracteres."
    creating && password != confirm -> "As senhas não conferem."
    else -> null
}

private fun friendlyError(error: Exception, creating: Boolean): String = when {
    error is FraudApiHttpException && error.code == 401 -> "E-mail ou senha incorretos."
    error is FraudApiHttpException && error.code == 422 -> "Confira o e-mail digitado."
    error is FraudApiHttpException && error.code == 400 ->
        error.detail ?: if (creating) "Não foi possível criar a conta." else "Não foi possível entrar."
    error is FraudApiHttpException -> "O servidor não respondeu (HTTP ${error.code}). Tente de novo."
    error is IOException -> "Sem conexão com o servidor. Verifique a internet."
    else -> "Algo deu errado. Tente de novo."
}
