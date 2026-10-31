package app.bilidownload.next.account

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class QrLoginCode(val url: String, val key: String)
data class QrLoginPoll(val state: State, val message: String) {
    enum class State { WAITING, SCANNED, SUCCESS, EXPIRED }
}

/** Bilibili's official web QR login endpoints; credentials are persisted only through CookieVault. */
class BiliQrLoginRepository(context: Context) {
    private val vault = CookieVault(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun createQrCode(): QrLoginCode {
        val request = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        val root = executeJson(request)
        if (root.optInt("code") != 0) error(root.optString("message", "二维码获取失败"))
        val data = root.optJSONObject("data") ?: error("二维码数据为空")
        val url = data.optString("url")
        val key = data.optString("qrcode_key")
        if (url.isBlank() || key.isBlank()) error("二维码信息不完整")
        return QrLoginCode(url, key)
    }

    fun poll(key: String): QrLoginPoll {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("passport.bilibili.com")
            .addPathSegments("x/passport-login/web/qrcode/poll")
            .addQueryParameter("qrcode_key", key)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) error("登录轮询失败：HTTP ${it.code}")
            val root = JSONObject(it.body?.string().orEmpty())
            if (root.optInt("code") != 0) error(root.optString("message", "登录状态读取失败"))
            val data = root.optJSONObject("data") ?: error("登录状态为空")
            return when (data.optInt("code")) {
                0 -> {
                    val cookies = it.headers.values("Set-Cookie")
                        .map { value -> value.substringBefore(";") }
                        .filter { value -> value.contains("=") && !value.startsWith("Path=") }
                    val cookie = cookies.joinToString("; ")
                    if (cookie.isBlank()) error("扫码成功，但服务器未返回 Cookie，请重新扫码")
                    vault.save(cookie)
                    QrLoginPoll(QrLoginPoll.State.SUCCESS, "登录成功，Cookie 已加密保存")
                }
                86090 -> QrLoginPoll(QrLoginPoll.State.SCANNED, "已扫码，请在 Bilibili 确认登录")
                86101 -> QrLoginPoll(QrLoginPoll.State.WAITING, "等待扫码")
                86038 -> QrLoginPoll(QrLoginPoll.State.EXPIRED, "二维码已过期")
                else -> QrLoginPoll(QrLoginPoll.State.WAITING, data.optString("message", "等待确认"))
            }
        }
    }

    fun accountName(): String? {
        val cookie = vault.read() ?: return null
        val request = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/nav")
            .header("Cookie", cookie)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        val root = executeJson(request)
        val data = root.optJSONObject("data") ?: return null
        return if (data.optBoolean("isLogin")) data.optString("uname").takeIf { it.isNotBlank() } else null
    }

    fun logout() = vault.clear()
    fun hasSavedCookie(): Boolean = !vault.read().isNullOrBlank()

    private fun executeJson(request: Request): JSONObject =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("请求失败：HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"
    }
}
