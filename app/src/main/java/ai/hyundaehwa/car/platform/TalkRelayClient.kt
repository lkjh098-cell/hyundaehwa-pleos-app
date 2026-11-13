package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.TalkRelayEndpoint
import ai.hyundaehwa.core.TalkTurn
import ai.hyundaehwa.car.BuildConfig
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class RelayConnection(val url: String = "", val token: String = "") {
    val valid get() = TalkRelayEndpoint.validate(url, BuildConfig.DEBUG) != null && token.length in 24..512 && token.none { it.isWhitespace() }
}
/** Only the relay address and per-install token are stored; the Gemini API key stays on the server. */
class RelayConnectionStore(context: Context) {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(context, "talk-relay", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun read(): RelayConnection = runCatching { RelayConnection(prefs.getString("url", "").orEmpty(), prefs.getString("token", "").orEmpty()) }.getOrDefault(RelayConnection())
    fun save(connection: RelayConnection): Boolean = runCatching { require(connection.valid); prefs.edit().putString("url", connection.url.trimEnd('/')).putString("token", connection.token).commit() }.getOrDefault(false)
    fun clear() = runCatching { prefs.edit().clear().commit() }.getOrDefault(false)
}

class TalkRelayClient {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var connection: HttpURLConnection? = null
    private var pending: Future<*>? = null
    @Volatile private var generation = 0L
    fun request(settings: RelayConnection, message: String, history: List<TalkTurn>, context: Map<String, String>, done: (String, String) -> Unit, failed: (String) -> Unit) {
        cancel(); val token = generation
        pending = worker.submit {
            var call: HttpURLConnection? = null
            try {
                require(settings.valid)
                val endpoint = checkNotNull(TalkRelayEndpoint.validate(settings.url, BuildConfig.DEBUG))
                call = URL("$endpoint/api/talk").openConnection() as HttpURLConnection
                if (generation != token) return@submit
                connection = call
                call.requestMethod = "POST"; call.instanceFollowRedirects = false
                call.connectTimeout = 6_000; call.readTimeout = 20_000; call.doOutput = true
                call.setRequestProperty("Content-Type", "application/json")
                call.setRequestProperty("Authorization", "Bearer ${settings.token}")
                val turns = JSONArray(); history.takeLast(10).forEach { turns.put(JSONObject().put("role", it.role).put("text", it.text.take(800))) }
                val ctx = JSONObject(); context.forEach { (key, value) -> ctx.put(key, value.take(160)) }
                val bytes = JSONObject().put("message", message.take(1000)).put("history", turns).put("context", ctx).toString().toByteArray(Charsets.UTF_8)
                call.setFixedLengthStreamingMode(bytes.size); call.outputStream.use { it.write(bytes) }
                val code = call.responseCode
                if (code != 200) { if (generation == token) failed(when (code) {
                    401, 403 -> "연결 코드가 맞지 않아요. 연결 설정을 확인해 주세요."
                    429 -> "무료 사용 한도 또는 대화 횟수 제한에 도달했어요. 잠시 후 다시 눌러 주세요."
                    503 -> "Gemini 연결이 준비되지 않았어요. 서버의 API 키 설정을 확인해 주세요."
                    504 -> "답변을 기다리는 시간이 길어졌어요. 다시 눌러 주세요."
                    else -> "답변을 받지 못했어요. 서버 연결을 확인해 주세요."
                }); return@submit }
                val raw = call.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 32_768); output.write(buffer, 0, n) }
                    output.toString("UTF-8")
                }
                val result = JSONObject(raw); val reply = result.optString("reply").trim().take(800)
                require(reply.isNotBlank())
                if (generation == token) done(reply, result.optString("model").take(100))
            } catch (_: Exception) { if (generation == token) failed("서버에 연결하지 못했어요. 인터넷과 연결 주소를 확인해 주세요.") }
            finally { call?.disconnect(); if (connection === call) connection = null }
        }
    }
    fun cancel() { ++generation; pending?.cancel(true); pending = null; connection?.disconnect(); connection = null }
    fun close() { cancel(); worker.shutdownNow() }
}
