package com.dzustudy.academy.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dzustudy.academy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int = 0) : Exception(message)

/** A file picked by the user, to be uploaded (receipt photo, avatar). */
class UploadFile(val name: String, val mime: String, val bytes: ByteArray)

/** Login state, stored encrypted on the phone. We keep an app password, never the real password. */
object Session {
    private lateinit var prefs: SharedPreferences
    var username: String = ""; private set
    var appPassword: String = ""; private set
    var user: JSONObject? = null; private set

    val loggedIn get() = username.isNotEmpty() && appPassword.isNotEmpty()
    val isInstructor get() = user?.optBoolean("is_instructor", false) == true
    val userId get() = user?.optInt("id", 0) ?: 0

    fun init(ctx: Context) {
        prefs = try {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, "ostudy_session", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            ctx.getSharedPreferences("ostudy_session_plain", Context.MODE_PRIVATE)
        }
        username = prefs.getString("u", "") ?: ""
        appPassword = prefs.getString("p", "") ?: ""
        user = try { JSONObject(prefs.getString("user", "{}") ?: "{}") } catch (e: Exception) { null }
    }

    fun save(u: String, p: String, usr: JSONObject?) {
        username = u; appPassword = p; user = usr
        prefs.edit().putString("u", u).putString("p", p).putString("user", usr?.toString() ?: "{}").apply()
    }

    fun updateUser(usr: JSONObject) {
        user = usr
        prefs.edit().putString("user", usr.toString()).apply()
    }

    fun clear() {
        username = ""; appPassword = ""; user = null
        prefs.edit().clear().apply()
    }
}

object Api {
    private val base = BuildConfig.SITE_URL.trimEnd('/') + "/wp-json/ostudy/v1"
    val siteUrl: String = BuildConfig.SITE_URL.trimEnd('/')

    /** Called when the server says our login is no longer valid. */
    var onUnauthorized: (() -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val b = chain.request().newBuilder()
                .header("Accept", "application/json")
                .header("Accept-Language", Locale.getDefault().language)
                .header("User-Agent", "OstudyAndroid/1.0")
            if (Session.loggedIn) b.header("Authorization", Credentials.basic(Session.username, Session.appPassword))
            chain.proceed(b.build())
        }
        .build()

    private fun url(path: String, query: List<Pair<String, String>>): okhttp3.HttpUrl {
        val b = (base + path).toHttpUrl().newBuilder()
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build()
    }

    private suspend fun exec(req: Request): JSONObject = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json: JSONObject = try {
                    val t = text.trim()
                    if (t.startsWith("[")) JSONObject().put("items", JSONArray(t)) else JSONObject(t)
                } catch (e: Exception) {
                    if (resp.isSuccessful) JSONObject() else throw ApiException("Server error (${resp.code})", resp.code)
                }
                if (!resp.isSuccessful) {
                    if (resp.code == 401 && Session.loggedIn && json.optString("code") != "invalid_login") {
                        onUnauthorized?.invoke()
                    }
                    var msg = json.optString("message")
                    if (msg.isBlank()) msg = "Error (${resp.code})"
                    throw ApiException(msg, resp.code)
                }
                json
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: java.io.IOException) {
            throw ApiException("No connection. Check your internet and try again.", 0)
        }
    }

    suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()): JSONObject =
        exec(Request.Builder().url(url(path, query)).get().build())

    suspend fun post(path: String, fields: Map<String, String> = emptyMap(), file: UploadFile? = null, fileField: String = "file"): JSONObject {
        val body = if (file != null) {
            val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
            fields.forEach { (k, v) -> mb.addFormDataPart(k, v) }
            mb.addFormDataPart(fileField, file.name, file.bytes.toRequestBody(file.mime.toMediaTypeOrNull()))
            mb.build()
        } else {
            val fb = FormBody.Builder()
            fields.forEach { (k, v) -> fb.add(k, v) }
            fb.build()
        }
        return exec(Request.Builder().url(url(path, emptyList())).post(body).build())
    }

    suspend fun delete(path: String): JSONObject =
        exec(Request.Builder().url(url(path, emptyList())).delete().build())

    /** Download a protected file (payment receipt) to bytes. */
    suspend fun bytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url(path, emptyList())).get().build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiException("Could not load the file (${r.code})", r.code)
                r.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: java.io.IOException) {
            throw ApiException("No connection.", 0)
        }
    }

    // ---- helpers for the screens -------------------------------------------------

    suspend fun login(login: String, password: String): JSONObject {
        val r = post("/auth/login", mapOf("login" to login, "password" to password, "device_name" to android.os.Build.MODEL))
        Session.save(r.optString("username"), r.optString("app_password"), r.optJSONObject("user"))
        return r
    }

    suspend fun logout() {
        try { post("/auth/logout") } catch (e: Exception) { /* ignore: we clear locally anyway */ }
        Session.clear()
    }

    suspend fun refreshMe() {
        val u = get("/me")
        if (u.has("id")) Session.updateUser(u)
    }

    /** Opens a page of the website already logged in (for features not built natively). */
    suspend fun webSessionUrl(nextPath: String): String {
        val r = post("/auth/web-session", mapOf("next" to (siteUrl + nextPath)))
        return r.optString("url", siteUrl + nextPath)
    }
}

// ---- tiny JSON helpers -------------------------------------------------------

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
fun JSONObject.obj(k: String): JSONObject = optJSONObject(k) ?: JSONObject()
fun JSONObject.list(k: String): List<JSONObject> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
fun JSONObject.strings(k: String): List<String> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
}
fun JSONObject.dbl(k: String): Double = optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
