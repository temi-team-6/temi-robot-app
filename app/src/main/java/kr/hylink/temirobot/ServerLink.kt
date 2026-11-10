package kr.hylink.temirobot

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 서버(/ws/device)와의 WebSocket. 끊기면 1~5초 간격으로 다시 붙고, 25초마다 ping을 보낸다.
 * 콜백은 모두 메인 스레드에서 부른다. 끊긴 동안 받지 못한 명령은 서버가 다시 보내지 않는다(규약).
 */
class ServerLink(
    private val url: String,
    private val token: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessage(msg: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private var attempt = 0
    private var stopped = false

    private val ping = object : Runnable {
        override fun run() {
            send("ping", JSONObject())
            main.postDelayed(this, PING_MS)
        }
    }

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        socket?.close(1000, "app stopped")
        socket = null
    }

    fun send(type: String, payload: JSONObject, replyTo: String? = null): Boolean {
        val msg = JSONObject()
            .put("type", type)
            .put("id", UUID.randomUUID().toString())
            .put("ts", now())
            .put("payload", payload)
        if (replyTo != null) msg.put("reply_to", replyTo)
        val sent = socket?.send(msg.toString()) ?: false
        if (type != "ping") Log.i(TAG, "-> $type ${if (sent) "" else "(not connected) "}$payload")
        return sent
    }

    private fun connect() {
        if (stopped) return
        val request = Request.Builder().url("$url?token=$token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (webSocket !== socket) return@post
                    attempt = 0
                    main.removeCallbacks(ping)
                    main.postDelayed(ping, PING_MS)
                    listener.onConnected()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                main.post { if (webSocket === socket) listener.onMessage(msg) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (webSocket === socket) retry("closed $code $reason") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
                main.post { if (webSocket === socket) retry(why) }
            }
        })
    }

    private fun retry(reason: String) {
        Log.w(TAG, "disconnected: $reason")
        main.removeCallbacks(ping)
        socket = null
        listener.onDisconnected(reason)
        if (stopped) return
        attempt += 1
        main.postDelayed({ connect() }, minOf(attempt, 5) * 1000L)
    }

    private fun now(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    companion object {
        private const val TAG = "ServerLink"
        private const val PING_MS = 25_000L
    }
}
