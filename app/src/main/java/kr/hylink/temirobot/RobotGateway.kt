package kr.hylink.temirobot

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.robotemi.sdk.listeners.OnTelepresenceStatusChangedListener
import com.robotemi.sdk.navigation.listener.OnCurrentPositionChangedListener
import com.robotemi.sdk.navigation.model.Position
import com.robotemi.sdk.telepresence.CallState
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

enum class VoiceMode { TEMI_TTS, SERVER_AUDIO }

/** 서버 명령을 Temi SDK 호출로 바꾸고, SDK 결과를 서버 메시지로 돌려준다. 판단 로직은 두지 않는다. */
class RobotGateway(
    private val send: (type: String, payload: JSONObject, replyTo: String?) -> Unit,
    private val events: Events,
) : OnRobotReadyListener, Robot.TtsListener, Robot.AsrListener, OnGoToLocationStatusChangedListener,
    OnCurrentPositionChangedListener {

    interface Events {
        fun onReady()
        fun onSaying(text: String?)
        fun onHeard(text: String)
        fun onCall(name: String?, state: String?)
        fun onNotice(text: String)
    }

    private sealed class Line(val id: String, val text: String) {
        class Say(id: String, text: String) : Line(id, text)
        class Ask(id: String, text: String) : Line(id, text)
    }

    private val robot = Robot.getInstance()
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<Line>()
    private var speaking: Line? = null
    private var speakingTts: UUID? = null
    private var player: MediaPlayer? = null
    private var pendingAsk: String? = null
    private var goingTo: String? = null
    private var tiltBeforeMove: Int? = null
    private var lastTilt: Int? = null
    private var call: CallWatch? = null

    var ready = false
        private set
    var voiceMode = VoiceMode.TEMI_TTS
    var voices: Map<String, String> = emptyMap()
    var voiceBaseUrl = ""

    fun attach() {
        robot.addOnRobotReadyListener(this)
        robot.addTtsListener(this)
        robot.addAsrListener(this)
        robot.addOnGoToLocationStatusChangedListener(this)
        robot.addOnCurrentPositionChangedListener(this)
    }

    fun detach() {
        robot.removeOnRobotReadyListener(this)
        robot.removeTtsListener(this)
        robot.removeAsrListener(this)
        robot.removeOnGoToLocationStatusChangedListener(this)
        robot.removeOnCurrentPositionChangedListener(this)
        player?.release()
        player = null
    }

    override fun onRobotReady(isReady: Boolean) {
        ready = isReady
        if (isReady) events.onReady()
    }

    fun locations(): List<String> = if (ready) robot.locations else emptyList()

    fun saveLocation(name: String): Boolean = ready && robot.saveLocation(name)

    fun requestKiosk() = robot.requestToBeKioskApp()

    fun hideTopBar() = robot.hideTopBar()

    fun startSkill(info: android.content.pm.ActivityInfo) = robot.onStart(info)

    // 말하기: temi.say와 temi.ask를 받은 순서대로 하나씩 처리한다. 앞 문장을 끊지 않는다.

    fun say(id: String, text: String) {
        lines.add(Line.Say(id, text))
        next()
    }

    fun ask(id: String, text: String) {
        lines.add(Line.Ask(id, text))
        next()
    }

    /** 연결이 끊기면 서버가 이전 명령을 잊으므로, 남은 말과 질문을 버린다. */
    fun resetSpeech() {
        lines.clear()
        speaking = null
        speakingTts = null
        pendingAsk = null
        player?.release()
        player = null
        robot.cancelAllTtsRequests()
        events.onSaying(null)
    }

    private fun next() {
        if (speaking != null) return
        val line = lines.poll() ?: return
        speaking = line
        events.onSaying(line.text)
        when (line) {
            is Line.Ask -> {
                pendingAsk = line.id
                robot.askQuestion(line.text)
                finishLine()
            }
            is Line.Say -> {
                val file = voices[line.text]
                if (voiceMode == VoiceMode.SERVER_AUDIO && file != null) playServerAudio(line, "$voiceBaseUrl/$file")
                else speakWithTemi(line)
            }
        }
    }

    private fun speakWithTemi(line: Line.Say) {
        val request = TtsRequest.create(line.text, false, TtsRequest.Language.KO_KR)
        speakingTts = request.id
        robot.speak(request)
    }

    private fun playServerAudio(line: Line.Say, url: String) {
        val mp = MediaPlayer()
        player = mp
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mp.setOnPreparedListener { it.start() }
        mp.setOnCompletionListener { sayDone(line) }
        mp.setOnErrorListener { _, what, _ ->
            Log.w(TAG, "server audio failed ($what), falling back to Temi TTS")
            mp.release()
            player = null
            speakWithTemi(line)
            true
        }
        mp.setDataSource(url)
        mp.prepareAsync()
    }

    override fun onTtsStatusChanged(ttsRequest: TtsRequest) {
        val line = speaking as? Line.Say ?: return
        if (ttsRequest.id != speakingTts) return
        when (ttsRequest.status) {
            TtsRequest.Status.COMPLETED, TtsRequest.Status.ERROR, TtsRequest.Status.NOT_ALLOWED,
            TtsRequest.Status.CANCELED -> {
                if (ttsRequest.status != TtsRequest.Status.COMPLETED) {
                    events.onNotice("Temi 말하기 ${ttsRequest.status}: ${line.text}")
                }
                sayDone(line)
            }
            else -> Unit
        }
    }

    private fun sayDone(line: Line.Say) {
        if (speaking !== line) return
        player?.release()
        player = null
        send("temi.say_done", JSONObject(), line.id)
        finishLine()
    }

    private fun finishLine() {
        speaking = null
        speakingTts = null
        events.onSaying(null)
        main.post { next() }
    }

    override fun onAsrResult(asrResult: String) {
        val text = asrResult.trim()
        robot.finishConversation()
        if (text.isEmpty()) return
        events.onHeard(text)
        send("speech.heard", JSONObject().put("text", text), pendingAsk)
        pendingAsk = null
    }

    fun goTo(location: String) {
        val saved = locations().firstOrNull { it.equals(location, ignoreCase = true) }
        if (saved == null) {
            send("temi.goto_failed", JSONObject().put("location", location).put("reason", "저장된 위치가 아님"), null)
            events.onNotice("저장된 위치가 아닙니다: $location")
            return
        }
        goingTo = location
        tiltBeforeMove = lastTilt
        robot.goTo(saved)
    }

    override fun onGoToLocationStatusChanged(location: String, status: String, descriptionId: Int,
                                             description: String) {
        val target = goingTo ?: return
        if (!location.equals(target, ignoreCase = true)) return
        when (status) {
            OnGoToLocationStatusChangedListener.COMPLETE -> {
                goingTo = null
                // 이동하면 고개가 내려간 채로 멈춘다. 이동 전 각도로 되돌린다.
                tiltBeforeMove?.let { robot.tiltAngle(it) }
                send("temi.arrived", JSONObject().put("location", target), null)
            }
            OnGoToLocationStatusChangedListener.ABORT -> {
                goingTo = null
                send("temi.goto_failed", JSONObject().put("location", target).put("reason", description), null)
            }
        }
    }

    override fun onCurrentPositionChanged(position: Position) {
        if (goingTo == null) lastTilt = position.tiltAngle
    }

    // 영상통화: Temi 내장 통화. 서버의 요양사 이름과 같은 이름의 Temi 연락처로 건다.

    fun videoCall(staffId: Int, name: String) {
        call?.finish(false)
        val contact = if (ready) robot.allContact.firstOrNull { it.name.trim() == name.trim() } else null
        if (contact == null) {
            events.onNotice("Temi 연락처에 '$name'이(가) 없습니다")
            send("video_call.result", JSONObject().put("staff_id", staffId).put("answered", false), null)
            return
        }
        events.onCall(name, "ringing")
        val sessionId = robot.startTelepresence(name, contact.userId)
        call = CallWatch(staffId, name, sessionId).also { it.watch() }
    }

    private inner class CallWatch(val staffId: Int, val name: String, sessionId: String) :
        OnTelepresenceStatusChangedListener(sessionId) {
        private var reported = false
        private val timeout = Runnable { finish(false) }

        fun watch() {
            robot.addOnTelepresenceStatusChangedListener(this)
            main.postDelayed(timeout, CALL_TIMEOUT_MS)
        }

        override fun onTelepresenceStatusChanged(callState: CallState) {
            main.post {
                when (callState.state) {
                    CallState.State.STARTED -> finish(true)
                    CallState.State.DECLINED, CallState.State.ENDED -> {
                        if (!reported) finish(false)
                        events.onCall(null, null)
                    }
                    null -> Unit
                }
            }
        }

        fun finish(answered: Boolean) {
            if (reported) return
            reported = true
            main.removeCallbacks(timeout)
            if (!answered) {
                robot.removeOnTelepresenceStatusChangedListener(this)
                events.onCall(null, null)
            } else {
                events.onCall(name, "answered")
            }
            send("video_call.result", JSONObject().put("staff_id", staffId).put("answered", answered), null)
        }
    }

    companion object {
        private const val TAG = "RobotGateway"
        private const val CALL_TIMEOUT_MS = 40_000L
    }
}
