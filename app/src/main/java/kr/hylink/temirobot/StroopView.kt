package kr.hylink.temirobot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 스트룹 게임. 글자의 뜻이 아니라 글자 색깔을 누른다. Temi 혼자 진행하고 반응시간도 Temi가 잰다.
 * 반응시간 = 글자가 화면에 그려진 뒤 첫 프레임 시각부터 버튼을 누른(손이 닿은) 시각까지.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class StroopView(
    context: Context,
    private val gameId: Int,
    level: Int,
    private val onFinished: (JSONObject) -> Unit,
) : LinearLayout(context) {

    private data class Ink(val name: String, val color: Int, val textColor: Int)

    private data class Trial(val word: Ink, val ink: Ink) {
        val congruent get() = word == ink
    }

    private val inks = listOf(
        Ink("빨강", Color.rgb(0xD3, 0x2F, 0x2F), Color.WHITE),
        Ink("파랑", Color.rgb(0x19, 0x76, 0xD2), Color.WHITE),
        Ink("초록", Color.rgb(0x2E, 0x7D, 0x32), Color.WHITE),
        Ink("노랑", Color.rgb(0xF9, 0xC8, 0x00), Color.BLACK),
    )
    private val trials = buildTrials(12 + 4 * (level.coerceIn(1, 4) - 1))
    private val main = Handler(Looper.getMainLooper())
    private val word = TextView(context)
    private val hint = TextView(context)
    private val buttons = LinearLayout(context)
    private val start = Button(context)
    private val rtMs = mutableListOf<Int>()
    private val correct = mutableListOf<Boolean>()
    private val raw = JSONArray()
    private var index = -1
    private var shownAt = 0L
    private val timeout = Runnable { answer(null, SystemClock.uptimeMillis()) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.WHITE)
        setPadding(0, dp(56), 0, 0)

        hint.textSize = 34f
        hint.setTextColor(Color.rgb(0x33, 0x33, 0x33))
        hint.gravity = Gravity.CENTER
        hint.text = "글자의 뜻이 아니라\n글자의 색깔을 눌러 주세요"
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        word.textSize = 120f
        word.typeface = Typeface.DEFAULT_BOLD
        word.gravity = Gravity.CENTER
        word.setTextColor(inks[1].color)
        word.text = "빨강"
        addView(word, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        buttons.orientation = HORIZONTAL
        buttons.gravity = Gravity.CENTER
        for (ink in inks) buttons.addView(colorButton(ink), LayoutParams(0, dp(150), 1f).apply {
            setMargins(dp(12), dp(12), dp(12), dp(48))
        })
        buttons.visibility = View.INVISIBLE
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        start.text = "시작하기"
        start.textSize = 36f
        start.setOnClickListener { begin() }
        addView(start, LayoutParams(dp(360), dp(120)).apply { setMargins(0, 0, 0, dp(40)) })
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacksAndMessages(null)
    }

    private fun begin() {
        start.visibility = View.GONE
        hint.text = "글자 색깔을 누르세요"
        buttons.visibility = View.VISIBLE
        nextTrial()
    }

    private fun nextTrial() {
        index += 1
        if (index >= trials.size) return finish()
        shownAt = 0L
        word.text = "+"
        word.setTextColor(Color.GRAY)
        main.postDelayed({ show(trials[index]) }, FIXATION_MS)
    }

    private fun show(trial: Trial) {
        Log.i(TAG, "trial ${index + 1}/${trials.size} word=${trial.word.name} ink=${trial.ink.name}")
        word.text = trial.word.name
        word.setTextColor(trial.ink.color)
        word.postOnAnimation {
            shownAt = SystemClock.uptimeMillis()
            main.postDelayed(timeout, LIMIT_MS)
        }
    }

    private fun colorButton(ink: Ink): View {
        val button = TextView(context)
        button.text = ink.name
        button.textSize = 36f
        button.gravity = Gravity.CENTER
        button.setTextColor(ink.textColor)
        button.background = GradientDrawable().apply {
            setColor(ink.color)
            cornerRadius = dp(20).toFloat()
        }
        button.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && shownAt > 0) answer(ink, event.eventTime)
            true
        }
        return button
    }

    private fun answer(chosen: Ink?, at: Long) {
        if (shownAt == 0L) return
        main.removeCallbacks(timeout)
        val trial = trials[index]
        val rt = if (chosen == null) LIMIT_MS.toInt() else (at - shownAt).toInt()
        shownAt = 0L
        rtMs.add(rt)
        correct.add(chosen == trial.ink)
        raw.put(JSONObject()
            .put("word", trial.word.name)
            .put("ink", trial.ink.name)
            .put("congruent", trial.congruent)
            .put("chosen", chosen?.name ?: JSONObject.NULL)
            .put("rt_ms", rt))
        word.text = ""
        main.postDelayed({ nextTrial() }, GAP_MS)
    }

    private fun finish() {
        buttons.visibility = View.INVISIBLE
        word.setTextColor(Color.rgb(0x33, 0x33, 0x33))
        word.textSize = 64f
        word.text = "수고하셨어요"
        hint.text = ""
        onFinished(JSONObject()
            .put("game_id", gameId)
            .put("rt_ms", JSONArray(rtMs))
            .put("correct", JSONArray(correct))
            .put("raw", JSONObject().put("trials", raw).put("limit_ms", LIMIT_MS)))
    }

    private fun buildTrials(count: Int): List<Trial> = List(count) { i ->
        val ink = inks[Random.nextInt(inks.size)]
        val congruent = i % 2 == 0
        val word = if (congruent) ink else inks.filter { it != ink }.random()
        Trial(word, ink)
    }.shuffled()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "StroopView"
        private const val FIXATION_MS = 700L
        private const val GAP_MS = 400L
        private const val LIMIT_MS = 4_000L
    }
}
