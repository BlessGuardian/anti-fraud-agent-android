package com.example.antifraudagent.data.guardian

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modo idoso/crianca: vinculo com os responsaveis e aviso a eles.
 *
 * - Celular do RESPONSAVEL: "Proteger alguém" mostra um QR com o contato dele (`LinkCode`).
 * - Celular da PESSOA PROTEGIDA: "Este celular é de uma pessoa protegida" liga a protecao reforcada;
 *   le o QR de cada responsavel e guarda o contato aqui.
 * - Golpe de ALTO risco na pessoa protegida -> `notifyGuardians` manda SMS na hora para cada
 *   responsavel (sem backend). E-mail e aviso no app do responsavel dependem do backend (SES e
 *   endpoint de vinculo); o e-mail ja fica guardado para isso.
 */
class GuardianRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _protectedDevice = MutableStateFlow(prefs.getBoolean(KEY_PROTECTED_DEVICE, false))
    val protectedDevice: StateFlow<Boolean> = _protectedDevice.asStateFlow()

    private val _guardians = MutableStateFlow(loadGuardians())
    val guardians: StateFlow<List<GuardianContact>> = _guardians.asStateFlow()

    fun setProtectedDevice(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROTECTED_DEVICE, enabled).apply()
        _protectedDevice.value = enabled
    }

    /** Nome da pessoa protegida, usado no SMS ("no celular da Maria"). */
    fun protectedName(): String = prefs.getString(KEY_PROTECTED_NAME, null).orEmpty()

    fun setProtectedName(name: String) = prefs.edit().putString(KEY_PROTECTED_NAME, name.trim()).apply()

    /** Contato deste celular quando ele e o RESPONSAVEL (preenche o "Proteger alguém"). */
    fun myContact(): GuardianContact? = prefs.getString(KEY_MY_CONTACT, null)?.let { LinkCode.decode(it) }

    fun saveMyContact(contact: GuardianContact) = prefs.edit().putString(KEY_MY_CONTACT, LinkCode.encode(contact)).apply()

    /** @return false se ja havia um responsavel com o mesmo telefone. */
    fun addGuardian(contact: GuardianContact): Boolean {
        if (_guardians.value.any { samePhone(it.phone, contact.phone) }) return false
        saveGuardians(_guardians.value + contact)
        return true
    }

    fun removeGuardian(contact: GuardianContact) =
        saveGuardians(_guardians.value.filterNot { samePhone(it.phone, contact.phone) })

    fun canSendSms(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Golpe de ALTO risco detectado neste celular. So age se ele for de uma pessoa protegida.
     * No maximo 1 SMS a cada [SMS_INTERVAL_MS] por responsavel, para nao virar enxurrada nem custo.
     * O SMS nao leva o texto do golpe, so o aviso.
     */
    fun notifyGuardians(what: String) {
        if (!_protectedDevice.value || _guardians.value.isEmpty()) return
        if (!canSendSms()) {
            Log.w(TAG, "Sem permissao SEND_SMS; responsaveis nao avisados")
            return
        }
        val now = System.currentTimeMillis()
        val text = smsText(protectedName(), what, now)
        val sms = appContext.getSystemService(SmsManager::class.java) ?: return
        _guardians.value.forEach { guardian ->
            val key = KEY_LAST_SMS_PREFIX + digits(guardian.phone)
            if (now - prefs.getLong(key, 0L) < SMS_INTERVAL_MS) return@forEach
            try {
                sms.sendMultipartTextMessage(digits(guardian.phone), null, sms.divideMessage(text), null, null)
                prefs.edit().putLong(key, now).apply()
                Log.i(TAG, "SMS de alerta enviado ao responsavel ${guardian.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao enviar SMS ao responsavel ${guardian.name}", e)
            }
        }
    }

    private fun saveGuardians(list: List<GuardianContact>) {
        val json = JSONArray()
        list.forEach { json.put(JSONObject().put("name", it.name).put("phone", it.phone).put("email", it.email ?: "")) }
        prefs.edit().putString(KEY_GUARDIANS, json.toString()).apply()
        _guardians.value = list
    }

    private fun loadGuardians(): List<GuardianContact> = try {
        val json = JSONArray(prefs.getString(KEY_GUARDIANS, "[]"))
        (0 until json.length()).map { i ->
            val item = json.getJSONObject(i)
            GuardianContact(
                name = item.optString("name", "Responsável"),
                phone = item.getString("phone"),
                email = item.optString("email").takeIf { it.isNotBlank() }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val TAG = "GuardianRepository"
        private const val PREFS_NAME = "guardian_links"
        private const val KEY_PROTECTED_DEVICE = "protected_device"
        private const val KEY_PROTECTED_NAME = "protected_name"
        private const val KEY_GUARDIANS = "guardians"
        private const val KEY_MY_CONTACT = "my_contact"
        private const val KEY_LAST_SMS_PREFIX = "last_sms_"
        private const val SMS_INTERVAL_MS = 10 * 60 * 1000L

        @Volatile
        private var instance: GuardianRepository? = null

        fun getInstance(context: Context): GuardianRepository =
            instance ?: synchronized(this) {
                instance ?: GuardianRepository(context).also { instance = it }
            }

        fun smsText(protectedName: String, what: String, atMillis: Long): String {
            val who = protectedName.ifBlank { "da pessoa que você protege" }.let {
                if (protectedName.isBlank()) it else "de $it"
            }
            val time = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(atMillis))
            return "BlessGuardian: possível golpe ($what) detectado no celular $who às $time. " +
                "Converse com a pessoa antes de qualquer Pix, senha ou código."
        }

        private fun digits(phone: String) = phone.filter { it.isDigit() || it == '+' }

        private fun samePhone(a: String, b: String): Boolean =
            a.filter { it.isDigit() }.takeLast(8) == b.filter { it.isDigit() }.takeLast(8)
    }
}
