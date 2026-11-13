package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import ai.hyundaehwa.car.BuildConfig
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Official Intent/Callback contract. Sender identity is not asserted by the public guide. */
object GleoInterface {
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    /** This verifies the response destination, not the identity of an incoming activity caller. */
    fun canAcceptActions(context: Context): Boolean = runCatching {
        val packageName = BuildConfig.GLEO_RECIPIENT_PACKAGE
        val pin = BuildConfig.GLEO_RECIPIENT_SHA256
        if (!GleoRecipientTrust.configured(packageName, pin)) return@runCatching false
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val digests = info.signingInfo?.apkContentsSigners.orEmpty().map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        GleoRecipientTrust.matches(packageName, pin, digests)
    }.getOrDefault(false)

    fun parse(intent: Intent): GleoTalkInput? {
        val action = intent.action ?: return null
        if (action !in GleoTalkActions.names) return null
        return runCatching {
            val rawValue = intent.extras?.get("ACTION_PARAM")
            require(rawValue == null || rawValue is String)
            val raw = rawValue as? String
            val parameters = if (raw == null) null else {
                require(raw.length <= 2048)
                fun decodeWhole(value: String): Any {
                    val parser = JSONTokener(value)
                    val decoded = parser.nextValue()
                    require(parser.nextClean() == '\u0000')
                    return decoded
                }
                when (val decoded = decodeWhole(raw)) {
                    is JSONObject -> decoded
                    is String -> decodeWhole(decoded) as? JSONObject ?: error("object required")
                    else -> error("object required")
                }
            }
            val type = if (parameters != null) parameters.opt("topicType") else intent.extras?.get("topicType")
            val enabled = if (parameters != null) parameters.opt("enabled") else intent.extras?.get("enabled")
            val answer = if (parameters != null) parameters.opt("answer") else intent.extras?.get("answer")
            require(answer == null || answer is String && answer.length <= 120)
            require(type == null || type is String && type.length <= 40)
            require(enabled == null || enabled is Boolean)
            GleoTalkInput(action, type as? String, enabled as? Boolean, answer = answer as? String)
        }.getOrElse { GleoTalkInput(action, malformed = true) }
    }
    /** Context can reveal derived location or answers: never use the sample's implicit broadcast. */
    fun respond(context: Context, result: GleoTalkResult): Boolean = runCatching {
        if (!canAcceptActions(context)) return@runCatching false
        val json = JSONObject().put("functionName", result.functionName).put("status", result.status)
            .put("message", result.message).put("mode", result.mode)
        if (result.data.isNotEmpty()) json.put("data", JSONArray().put(JSONObject(result.data)).toString())
        context.sendBroadcast(Intent(RESPONSE_ACTION).setPackage(BuildConfig.GLEO_RECIPIENT_PACKAGE).putExtra("packageName", context.packageName).putExtra("response", json.toString()))
        true
    }.getOrDefault(false)
}
