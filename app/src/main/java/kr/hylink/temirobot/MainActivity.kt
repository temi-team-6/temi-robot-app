package kr.hylink.temirobot

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class MainActivity : Activity(), ServerLink.Listener, RobotGateway.Events {

    private lateinit var link: ServerLink
    private lateinit var robot: RobotGateway
    private lateinit var stage: FrameLayout
    private lateinit var speech: TextView
    private lateinit var status: TextView
    private lateinit var setup: LinearLayout
    private lateinit var setupInfo: TextView
    private var connected = false
    private var lastCommand = "-"
    private var notice = ""
    private var demo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())

        robot = RobotGateway({ type, payload, replyTo -> link.send(type, payload, replyTo) }, this)
        robot.voiceMode = savedVoiceMode()
        robot.speechInput = savedSpeechInput()
        robot.voiceBaseUrl = httpBase() + "/twin/voice"
        link = ServerLink(BuildConfig.SERVER_URL, BuildConfig.TEMI_TOKEN, this)

        robot.attach()
        link.start()
        loadVoiceManifest()
        showIdle()
        applyVoiceExtra(intent)
        renderStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyVoiceExtra(intent)
    }

    override fun onDestroy() {
        link.stop()
        robot.detach()
        super.onDestroy()
    }

    override fun onConnected() {
        connected = true
        notice = ""
        if (!demo) showIdle()
        renderStatus()
    }

    override fun onDisconnected(reason: String) {
        connected = false
        robot.resetSpeech()
        notice = "서버 연결 끊김: $reason"
        renderStatus()
    }

    override fun onMessage(msg: JSONObject) {
        val type = msg.optString("type")
        val id = msg.optString("id")
        val p = msg.optJSONObject("payload") ?: JSONObject()
        if (type != "pong") {
            lastCommand = type
            Log.i(TAG, "<- $type $p")
        }
        when (type) {
            "hello", "pong" -> Unit
            "error" -> notice = "서버 오류: ${p.optString("message")}"
            "temi.say" -> robot.say(id, p.optString("text"))
            "temi.ask" -> robot.ask(id, p.optString("text"))
            "temi.show" -> show(p)
            "temi.goto" -> robot.goTo(p.optString("location"))
            "temi.video_call" -> robot.videoCall(p.optInt("staff_id"), p.optString("name"))
            else -> notice = "모르는 명령: $type"
        }
        renderStatus()
    }

    override fun onReady() {
        robot.hideTopBar()
        runCatching { robot.startSkill(packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)) }
            .onFailure { Log.w(TAG, "robot.onStart failed", it) }
        renderStatus()
    }

    override fun onSaying(text: String?) {
        if (text != null) speech.text = text
        speech.visibility = if (text == null) View.INVISIBLE else View.VISIBLE
    }

    override fun onHeard(text: String) {
        notice = "들은 말: $text"
        renderStatus()
    }

    override fun onCall(name: String?, state: String?) {
        if (name == null) return
        if (state == "ringing") showMessage("$name 선생님께\n연결하고 있어요")
    }

    override fun onNotice(text: String) {
        notice = text
        renderStatus()
    }

    private fun show(p: JSONObject) {
        demo = false
        when (p.optString("screen")) {
            "idle" -> showIdle()
            "walk" -> showWalk(p.optString("condition"), p.optInt("attempt", 1))
            "game" -> showGame(p.optInt("game_id"), p.optString("game_type"), p.optInt("level", 1))
            else -> notice = "모르는 화면: ${p.optString("screen")}"
        }
    }

    private fun showIdle() = showMessage("걷기 검사", "카드를 찍으면 시작해요")

    private fun showWalk(condition: String, attempt: Int) {
        val title = CONDITIONS[condition] ?: condition
        showMessage(title, if (attempt > 1) "한 번 더 해 볼게요 (${attempt}회째)" else "신호가 나면 끝선까지 걸어가세요")
    }

    private fun showGame(gameId: Int, gameType: String, level: Int) {
        when (gameType) {
            "stroop" -> {
                stage.removeAllViews()
                stage.addView(StroopView(this, gameId, level) { result ->
                    if (gameId > 0) link.send("game.completed", result)
                    else Log.i(TAG, "demo stroop result (not sent): $result")
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            "whack" -> showMessage("두더지 잡기", "앞에 있는 버튼 패드로 해 주세요")
            else -> {
                showMessage("준비 중인 게임", gameType)
                notice = "이 앱에서 아직 만들지 않은 게임: $gameType"
            }
        }
    }

    private fun showMessage(title: String, body: String = "") {
        stage.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
        }
        box.addView(text(title, 64f, bold = true))
        if (body.isNotEmpty()) box.addView(text(body, 36f, color = Color.rgb(0x55, 0x55, 0x55)).apply {
            setPadding(0, dp(24), 0, 0)
        })
        stage.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun renderStatus() {
        val server = if (connected) "서버 연결됨" else "서버 연결 중"
        val robotState = if (robot.ready) "로봇 준비됨" else "로봇 준비 중"
        val missing = if (robot.ready) {
            val saved = robot.locations()
            REQUIRED_LOCATIONS.filter { name -> saved.none { it.equals(name, ignoreCase = true) } }
        } else emptyList()
        val locations = if (missing.isEmpty()) "" else " · 저장 안 된 위치: ${missing.joinToString()}"
        val voice = if (robot.voiceMode == VoiceMode.TEMI_TTS) "Temi 음성" else "서버 음성"
        val input = if (robot.speechInput == SpeechInput.PI_MIC) "듣기: Pi 마이크" else "듣기: Temi"
        status.text = "$server · $robotState · $voice · $input · 마지막 명령 $lastCommand$locations" +
            if (notice.isEmpty()) "" else "\n$notice"
        if (setup.visibility == View.VISIBLE) renderSetup()
    }

    // 설정(상태 줄을 길게 누르면 열림): 측정 위치 저장, 음성 방식, 키오스크

    private fun renderSetup() {
        setupInfo.text = "저장된 위치: ${robot.locations().joinToString().ifEmpty { "없음" }}\n" +
            "음성: ${if (robot.voiceMode == VoiceMode.TEMI_TTS) "Temi 음성(기본)" else "서버 음성 파일(없는 문장은 Temi 음성)"}\n" +
            "듣기: ${if (robot.speechInput == SpeechInput.PI_MIC) "Pi 마이크(기본, Temi는 말만 함)" else "Temi 내장 인식"}"
    }

    private fun buildSetup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.argb(0xF2, 0xFF, 0xFF, 0xFF))
        setPadding(dp(40), dp(32), dp(40), dp(32))
        visibility = View.GONE
        addView(text("설정", 40f, bold = true))
        setupInfo = text("", 24f, color = Color.DKGRAY).apply { gravity = Gravity.START }
        addView(setupInfo)
        addView(text("Temi를 그 자리에 세운 뒤 누르세요", 24f, color = Color.DKGRAY).apply {
            gravity = Gravity.START
            setPadding(0, dp(16), 0, 0)
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in REQUIRED_LOCATIONS) row.addView(button("$name 저장") {
            notice = if (robot.saveLocation(name)) "위치 저장함: $name" else "위치 저장 실패: $name"
            renderStatus()
        })
        addView(row)
        val more = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        more.addView(button("음성 방식 바꾸기") {
            setVoiceMode(if (robot.voiceMode == VoiceMode.TEMI_TTS) VoiceMode.SERVER_AUDIO else VoiceMode.TEMI_TTS)
        })
        more.addView(button("듣기 방식 바꾸기") {
            setSpeechInput(if (robot.speechInput == SpeechInput.PI_MIC) SpeechInput.TEMI else SpeechInput.PI_MIC)
        })
        more.addView(button("키오스크 앱으로 지정") { robot.requestKiosk() })
        more.addView(button("닫기") { visibility = View.GONE })
        addView(more)
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this)
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        speech = text("", 40f, color = Color.WHITE).apply {
            setBackgroundColor(Color.argb(0xE6, 0x17, 0x70, 0x6F))
            setPadding(dp(32), dp(20), dp(32), dp(20))
            visibility = View.INVISIBLE
        }
        root.addView(speech, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        status = text("", 16f, color = Color.rgb(0x66, 0x66, 0x66)).apply {
            gravity = Gravity.START
            setBackgroundColor(Color.argb(0xCC, 0xF5, 0xF5, 0xF5))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnLongClickListener {
                setup.visibility = View.VISIBLE
                renderSetup()
                true
            }
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setup = buildSetup()
        root.addView(setup, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = Color.rgb(0x1B, 0x2A, 0x2F)) =
        TextView(this).apply {
            text = value
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 22f
        setOnClickListener { onClick() }
    }

    private fun savedVoiceMode(): VoiceMode {
        val saved = getPreferences(MODE_PRIVATE).getString(PREF_VOICE, null)
        return VoiceMode.values().firstOrNull { it.name == saved } ?: VoiceMode.TEMI_TTS
    }

    private fun setVoiceMode(mode: VoiceMode) {
        robot.voiceMode = mode
        getPreferences(MODE_PRIVATE).edit().putString(PREF_VOICE, mode.name).apply()
        renderStatus()
    }

    private fun savedSpeechInput(): SpeechInput {
        val saved = getPreferences(MODE_PRIVATE).getString(PREF_INPUT, null)
        return SpeechInput.values().firstOrNull { it.name == saved } ?: SpeechInput.PI_MIC
    }

    private fun setSpeechInput(input: SpeechInput) {
        robot.speechInput = input
        getPreferences(MODE_PRIVATE).edit().putString(PREF_INPUT, input.name).apply()
        renderStatus()
    }

    /**
     * adb로 바꿀 수 있게 한다: am start -n kr.hylink.temirobot/.MainActivity --es voice server|temi --es input pi|temi
     * --es demo stroop: 서버 없이 스트룹 화면을 띄운다. 결과는 서버로 보내지 않고 로그에만 남긴다.
     */
    private fun applyVoiceExtra(intent: Intent?) {
        when (intent?.getStringExtra("voice")) {
            "server" -> setVoiceMode(VoiceMode.SERVER_AUDIO)
            "temi" -> setVoiceMode(VoiceMode.TEMI_TTS)
        }
        when (intent?.getStringExtra("input")) {
            "pi" -> setSpeechInput(SpeechInput.PI_MIC)
            "temi" -> setSpeechInput(SpeechInput.TEMI)
        }
        if (intent?.getStringExtra("demo") == "stroop") {
            demo = true
            showGame(0, "stroop", 1)
        }
    }

    private fun loadVoiceManifest() {
        val request = Request.Builder().url("${robot.voiceBaseUrl}/manifest.json").build()
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "voice manifest failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) it.body?.string() else null } ?: return
                val json = runCatching { JSONObject(body) }.getOrNull() ?: return
                val voices = json.keys().asSequence().associateWith { json.getString(it) }
                runOnUiThread { robot.voices = voices }
            }
        })
    }

    private fun httpBase(): String = BuildConfig.SERVER_URL
        .replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
        .substringBefore("/ws/")

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TemiRobot"
        private const val PREF_VOICE = "voice_mode"
        private const val PREF_INPUT = "speech_input"
        val REQUIRED_LOCATIONS = listOf("보행_측정", "보행_시작", "보행_끝")
        val CONDITIONS = mapOf(
            "single" to "평소처럼 걸어 주세요",
            "animals" to "동물 이름을 말하며 걸어 주세요",
            "countdown" to "100부터 3씩 거꾸로 세며 걸어 주세요",
        )
    }
}
