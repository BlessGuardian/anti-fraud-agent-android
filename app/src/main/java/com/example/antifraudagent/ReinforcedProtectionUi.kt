package com.example.antifraudagent

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.antifraudagent.calls.CallTerminator
import com.example.antifraudagent.data.settings.SettingsRepository

/**
 * Opcao "Protecao reforcada" do Perfil (base do modo idoso/crianca). Em ALTO risco o app derruba
 * a ligacao e fecha o app da conversa. A permissao de encerrar ligacoes so e pedida ao ligar.
 */
@Composable
fun ReinforcedProtectionRow() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository.getInstance(context) }
    val enabled by settings.reinforcedProtection.collectAsState()

    val requestCallPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        settings.setReinforcedProtection(true)
        if (!granted) {
            Toast.makeText(
                context,
                "Sem a permissão de ligações, o BlessGuardian avisa mas não encerra a chamada.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    ProtectionToggleRow(
        title = "Proteção reforçada",
        subtitle = if (enabled) {
            "Em golpe confirmado, encerra a ligação e fecha o app da conversa"
        } else {
            "Para idosos e crianças: em golpe confirmado, encerra a ligação e fecha o app"
        },
        checked = enabled,
        onClick = {
            when {
                enabled -> settings.setReinforcedProtection(false)
                CallTerminator.hasPermission(context) -> settings.setReinforcedProtection(true)
                else -> requestCallPermission.launch(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }
    )
}
