package com.davidagb.telegrafo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private enum class Mode { TEXT, MORSE }

    private val ui = Handler(Looper.getMainLooper())
    private val tone = Tone()
    private lateinit var torch: Torch
    private lateinit var player: Player
    private lateinit var prefs: SharedPreferences
    private var vibrator: Vibrator? = null

    // Ajustes
    private var wpm = 15
    private var twpm = 10
    private var freq = 650
    private var sound = true
    private var light = true
    private var vibe = false
    private var torchOn = false
    private var echo = true
    private var autogap = true

    // Estado
    private var mode = Mode.TEXT
    private val drafts = mutableMapOf(Mode.TEXT to "HOLA MUNDO", Mode.MORSE to "... --- ...")
    private var tokens: List<Morse.Token> = emptyList()
    private var decodedBase: CharSequence = ""
    private var decodedStarts = IntArray(0)
    private var programmatic = false
    private var pendingEcho: Char? = null
    private var mainPlayback = false
    private var pressAt = 0L

    // Vistas
    private lateinit var scroll: ScrollView
    private lateinit var lampShape: GradientDrawable
    private lateinit var knobShape: GradientDrawable
    private lateinit var btnText: TextView
    private lateinit var btnMorse: TextView
    private lateinit var inLabel: TextView
    private lateinit var outLabel: TextView
    private lateinit var input: EditText
    private lateinit var emptyHint: TextView
    private lateinit var tape: TapeView
    private lateinit var plain: TextView
    private lateinit var decoded: TextView
    private lateinit var btnPlay: TextView
    private lateinit var pad: View
    private lateinit var txtWpm: TextView
    private lateinit var txtTwpm: TextView
    private lateinit var txtFreq: TextView

    private val letterGapRunnable = Runnable { letterGap() }
    private val wordGapRunnable = Runnable { wordGap() }

    private val morseFilter = InputFilter { source, start, end, _, _, _ ->
        val sb = StringBuilder()
        for (i in start until end) {
            val c = Morse.clean(source[i])
            when {
                c == '.' || c == '-' || c == '/' -> sb.append(c)
                c.isWhitespace() -> sb.append(' ')
            }
        }
        if (sb.toString() == source.subSequence(start, end).toString()) null else sb.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("telegrafo", MODE_PRIVATE)
        loadPrefs()
        torch = Torch(this)
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        player = Player(object : Player.Listener {
            override fun onSignal(on: Boolean, durationMs: Long) = emitSignal(on, durationMs)
            override fun onToken(generation: Int, index: Int) {
                ui.post { if (generation == player.generation && mainPlayback) highlight(index) }
            }
            override fun onDone(generation: Int) {
                ui.post { if (generation == player.generation) playbackEnded() }
            }
        })

        bindViews()
        applyInsets()
        bindSettings()
        bindPad()
        buildAlphabet()
        setMode(mode, null, saveCurrent = false)
    }

    override fun onResume() {
        super.onResume()
        tone.freq = freq.toDouble()
        if (sound) tone.start()
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
        cancelGaps()
        if (pressAt != 0L) releasePaddle(record = false)
        tone.stop()
        torch.set(false)
        savePrefs()
    }

    // ---------- Preferencias ----------

    private fun loadPrefs() {
        wpm = prefs.getInt("wpm", wpm)
        twpm = prefs.getInt("twpm", twpm)
        freq = prefs.getInt("freq", freq)
        sound = prefs.getBoolean("sound", sound)
        light = prefs.getBoolean("light", light)
        vibe = prefs.getBoolean("vibe", vibe)
        torchOn = prefs.getBoolean("torch", torchOn)
        echo = prefs.getBoolean("echo", echo)
        autogap = prefs.getBoolean("autogap", autogap)
        drafts[Mode.TEXT] = prefs.getString("draft_text", drafts[Mode.TEXT])!!
        drafts[Mode.MORSE] = prefs.getString("draft_morse", drafts[Mode.MORSE])!!
        mode = if (prefs.getString("mode", "text") == "morse") Mode.MORSE else Mode.TEXT
    }

    private fun savePrefs() {
        drafts[mode] = input.text.toString()
        prefs.edit()
            .putInt("wpm", wpm).putInt("twpm", twpm).putInt("freq", freq)
            .putBoolean("sound", sound).putBoolean("light", light).putBoolean("vibe", vibe)
            .putBoolean("torch", torchOn).putBoolean("echo", echo).putBoolean("autogap", autogap)
            .putString("draft_text", drafts[Mode.TEXT]).putString("draft_morse", drafts[Mode.MORSE])
            .putString("mode", if (mode == Mode.MORSE) "morse" else "text")
            .apply()
    }

    // ---------- Vistas ----------

    private fun bindViews() {
        scroll = findViewById(R.id.scroll)
        btnText = findViewById(R.id.btnText)
        btnMorse = findViewById(R.id.btnMorse)
        inLabel = findViewById(R.id.inLabel)
        outLabel = findViewById(R.id.outLabel)
        input = findViewById(R.id.input)
        emptyHint = findViewById(R.id.emptyHint)
        tape = findViewById(R.id.tape)
        plain = findViewById(R.id.plain)
        decoded = findViewById(R.id.decoded)
        btnPlay = findViewById(R.id.btnPlay)
        pad = findViewById(R.id.pad)

        lampShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), getColor(R.color.line))
        }
        findViewById<View>(R.id.lamp).background = lampShape
        knobShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(3), getColor(R.color.bg))
        }
        findViewById<View>(R.id.knob).background = knobShape
        setLamp(false)
        setKnob(false)

        btnText.setOnClickListener { if (mode != Mode.TEXT) setMode(Mode.TEXT, null) }
        btnMorse.setOnClickListener { if (mode != Mode.MORSE) setMode(Mode.MORSE, null) }
        findViewById<View>(R.id.btnSwap).setOnClickListener {
            if (mode == Mode.TEXT) setMode(Mode.MORSE, Morse.toMorseString(tokens))
            else setMode(Mode.TEXT, Morse.toTextString(tokens))
        }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            stopPlayback()
            cancelGaps()
            input.setText("")
        }
        btnPlay.setOnClickListener {
            if (mainPlayback) {
                stopPlayback()
            } else if (tokens.none { it.code != null }) {
                toast("Escribe algo primero")
            } else {
                play(tokens, main = true)
            }
        }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copyOutput() }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                // Los teclados escriben por composición: una letra nueva = el texto crece en uno
                if (!programmatic && count - before == 1) pendingEcho = s[start + count - 1]
            }
            override fun afterTextChanged(s: Editable?) {
                render()
                val c = pendingEcho ?: return
                pendingEcho = null
                if (mode == Mode.TEXT) Morse.codeFor(c)?.let { echoCode(it) }
                else if (c == '.' || c == '-') echoCode(c.toString())
            }
        })

        setupSection(R.id.setHeader, R.id.setBody, R.id.setSign)
        setupSection(R.id.abcHeader, R.id.abcBody, R.id.abcSign)
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    private fun setupSection(headerId: Int, bodyId: Int, signId: Int) {
        val body = findViewById<View>(bodyId)
        val sign = findViewById<TextView>(signId)
        findViewById<View>(headerId).setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            sign.text = if (open) "−" else "+"
        }
    }

    private fun bindSettings() {
        txtWpm = findViewById(R.id.txtWpm)
        txtTwpm = findViewById(R.id.txtTwpm)
        txtFreq = findViewById(R.id.txtFreq)

        fun seek(id: Int, value: Int, onChange: (Int) -> Unit) {
            findViewById<SeekBar>(id).apply {
                progress = value
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) onChange(p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
        }
        seek(R.id.seekWpm, wpm) { wpm = it; showSettings() }
        seek(R.id.seekTwpm, twpm) { twpm = it; showSettings() }
        seek(R.id.seekFreq, freq / 10) {
            freq = it * 10
            tone.freq = freq.toDouble()
            showSettings()
        }
        findViewById<SeekBar>(R.id.seekFreq).setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) echoCode(".-")
            false.also { if (e.action == MotionEvent.ACTION_UP) v.performClick() }
        }

        fun check(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
            findViewById<CheckBox>(id).apply {
                isChecked = value
                setOnCheckedChangeListener { _, c -> onChange(c) }
            }
        }
        check(R.id.chkSound, sound) {
            sound = it
            if (it) tone.start() else tone.stop()
        }
        check(R.id.chkLight, light) { light = it }
        check(R.id.chkVibe, vibe) { vibe = it }
        check(R.id.chkTorch, torchOn) {
            stopPlayback()
            torchOn = it
            if (!it) torch.set(false)
            showSettings()
        }
        if (vibrator?.hasVibrator() != true) {
            vibe = false
            findViewById<View>(R.id.chkVibe).visibility = View.GONE
        }
        if (torch.id == null) {
            torchOn = false
            findViewById<View>(R.id.chkTorch).visibility = View.GONE
            findViewById<View>(R.id.rowTwpm).visibility = View.GONE
        }

        findViewById<Switch>(R.id.swEcho).apply {
            isChecked = echo
            setOnCheckedChangeListener { _, c -> echo = c }
        }
        findViewById<Switch>(R.id.swAutogap).apply {
            isChecked = autogap
            setOnCheckedChangeListener { _, c -> autogap = c }
        }
        showSettings()
    }

    private fun showSettings() {
        txtWpm.text = "$wpm ppm · punto ${(1200.0 / wpm).roundToInt()} ms"
        txtTwpm.text = "$twpm ppm · punto ${(1200.0 / twpm).roundToInt()} ms"
        txtFreq.text = "$freq Hz"
    }

    private fun unitMs(): Double = 1200.0 / (if (torchOn && torch.id != null) min(wpm, twpm) else wpm)

    // ---------- Modo y traducción ----------

    private fun setMode(m: Mode, value: String?, saveCurrent: Boolean = true) {
        stopPlayback()
        cancelGaps()
        if (saveCurrent) drafts[mode] = input.text.toString()
        mode = m
        val text = m == Mode.TEXT

        listOf(btnText to text, btnMorse to !text).forEach { (b, on) ->
            b.background = if (on) getDrawable(R.drawable.bg_seg_on) else null
            b.setTextColor(getColor(if (on) R.color.ink else R.color.muted))
        }
        inLabel.text = if (text) "TU MENSAJE" else "CÓDIGO MORSE"
        outLabel.text = if (text) "EN MORSE" else "TRADUCCIÓN"
        emptyHint.text = if (text) "Toca el recuadro de arriba y escribe: aquí aparece el Morse."
        else "Pulsa punto y raya, o usa el manipulador. Aquí aparece el texto."

        if (text) {
            input.filters = arrayOf()
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            input.showSoftInputOnFocus = true
            input.typeface = Typeface.DEFAULT
            input.letterSpacing = 0f
            input.hint = "Toca aquí para escribir…"
            pad.visibility = View.GONE
        } else {
            input.filters = arrayOf(morseFilter)
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            input.showSoftInputOnFocus = false
            input.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            input.letterSpacing = 0.1f
            input.hint = ".... --- .-.. .-"
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
            pad.visibility = View.VISIBLE
        }
        tape.visibility = if (text) View.VISIBLE else View.GONE
        plain.visibility = if (text) View.VISIBLE else View.GONE
        decoded.visibility = if (text) View.GONE else View.VISIBLE

        programmatic = true
        input.setText(value ?: drafts[m])
        programmatic = false
        input.setSelection(input.length())
    }

    private fun render() {
        val v = input.text.toString()
        tokens = if (mode == Mode.TEXT) Morse.fromText(v) else Morse.fromMorse(v)
        emptyHint.visibility = if (tokens.isEmpty()) View.VISIBLE else View.GONE
        if (mode == Mode.TEXT) {
            tape.setTokens(tokens)
            tape.visibility = if (tokens.isEmpty()) View.GONE else View.VISIBLE
            plain.text = Morse.toMorseString(tokens)
            plain.visibility = if (tokens.isEmpty()) View.GONE else View.VISIBLE
        } else {
            val sb = SpannableStringBuilder()
            decodedStarts = IntArray(tokens.size) { -1 }
            tokens.forEachIndexed { i, t ->
                if (t.gap) {
                    sb.append(' ')
                    return@forEachIndexed
                }
                decodedStarts[i] = sb.length
                if (t.ch == null) {
                    val s = sb.length
                    sb.append("□")
                    sb.setSpan(ForegroundColorSpan(getColor(R.color.danger)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    sb.append(t.ch)
                }
            }
            decodedBase = sb
            decoded.text = sb
            decoded.visibility = if (tokens.isEmpty()) View.GONE else View.VISIBLE
        }
        drafts[mode] = v
    }

    private fun highlight(index: Int) {
        if (mode == Mode.TEXT) {
            tape.setHot(index)
            tape.cellRect(index)?.let { tape.requestRectangleOnScreen(it) }
        } else {
            val s = decodedStarts.getOrNull(index) ?: return
            if (s < 0) return
            val end = s + (tokens[index].ch ?: "□").length
            val sb = SpannableStringBuilder(decodedBase)
            sb.setSpan(BackgroundColorSpan(getColor(R.color.accent_soft)), s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(getColor(R.color.accent)), s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            decoded.text = sb
        }
    }

    private fun clearHighlight() {
        tape.setHot(-1)
        if (mode == Mode.MORSE) decoded.text = decodedBase
    }

    // ---------- Edición en el cursor ----------

    private fun caret(): Pair<Int, Int> {
        val len = input.length()
        val a = input.selectionStart
        val b = input.selectionEnd
        if (a < 0 || b < 0 || !input.hasFocus()) return len to len
        return min(a, b) to max(a, b)
    }

    private fun beforeCaret(): String = input.text.substring(0, caret().first)

    private fun insert(s: String) {
        val (a, b) = caret()
        programmatic = true
        input.text.replace(a, b, s)
        programmatic = false
        input.setSelection(min(a + s.length, input.length()))
    }

    private fun backspace() {
        var (a, b) = caret()
        if (a == b) {
            if (a == 0) return
            val before = input.text.substring(0, a)
            a -= if (mode == Mode.MORSE && before.endsWith(" / ")) 3
            else Character.charCount(Character.codePointBefore(before, a))
        }
        programmatic = true
        input.text.delete(a, b)
        programmatic = false
        input.setSelection(a)
    }

    private fun morseSymbol(s: String) {
        cancelGaps()
        if (beforeCaret().endsWith("/")) insert(" ")
        insert(s)
    }

    private fun letterGap() {
        val b = beforeCaret()
        if (b.endsWith(".") || b.endsWith("-")) insert(" ")
    }

    private fun wordGap() {
        val b = beforeCaret()
        if (b.none { it == '.' || it == '-' } || b.trimEnd().endsWith("/")) return
        if (b.endsWith(" ")) {
            val (a, end) = caret()
            programmatic = true
            input.text.replace(a - 1, end, " / ")
            programmatic = false
            input.setSelection(min(a + 2, input.length()))
        } else {
            insert(" / ")
        }
    }

    private fun cancelGaps() {
        ui.removeCallbacks(letterGapRunnable)
        ui.removeCallbacks(wordGapRunnable)
    }

    // ---------- Teclado Morse y manipulador ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun bindPad() {
        fun keyLabel(id: Int, symbol: String, label: String) {
            val s = SpannableString("$symbol\n$label")
            s.setSpan(RelativeSizeSpan(2.1f), 0, symbol.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(RelativeSizeSpan(0.7f), symbol.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(TypefaceSpan("monospace"), symbol.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(getColor(R.color.muted)), symbol.length + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            findViewById<TextView>(id).apply {
                text = s
                textSize = 15f
                setLineSpacing(0f, 0.85f)
            }
        }
        keyLabel(R.id.keyDot, "·", "PUNTO")
        keyLabel(R.id.keyDash, "—", "RAYA")

        findViewById<View>(R.id.keyDot).setOnClickListener { morseSymbol("."); echoCode(".") }
        findViewById<View>(R.id.keyDash).setOnClickListener { morseSymbol("-"); echoCode("-") }
        findViewById<View>(R.id.keyLetter).setOnClickListener { cancelGaps(); letterGap() }
        findViewById<View>(R.id.keyWord).setOnClickListener { cancelGaps(); wordGap() }

        // Borrar se repite mientras se mantiene pulsado
        val repeat = object : Runnable {
            override fun run() {
                backspace()
                ui.postDelayed(this, 70)
            }
        }
        findViewById<View>(R.id.keyBack).setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    cancelGaps()
                    backspace()
                    ui.postDelayed(repeat, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    ui.removeCallbacks(repeat)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }

        findViewById<View>(R.id.paddle).setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopPlayback()
                    cancelGaps()
                    pressAt = SystemClock.uptimeMillis()
                    setKnob(true)
                    emitSignal(true, 0)
                    if (vibe) vibrate(5000)
                }
                MotionEvent.ACTION_UP -> {
                    releasePaddle(record = true)
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> releasePaddle(record = true)
            }
            true
        }
    }

    private fun releasePaddle(record: Boolean) {
        if (pressAt == 0L) return
        val held = SystemClock.uptimeMillis() - pressAt
        pressAt = 0L
        setKnob(false)
        emitSignal(false, 0)
        if (vibe) vibrator?.cancel()
        if (!record) return
        val u = unitMs()
        morseSymbol(if (held < u * 2) "." else "-")
        if (autogap) {
            ui.postDelayed(letterGapRunnable, (u * 3).toLong())
            ui.postDelayed(wordGapRunnable, (u * 7).toLong())
        }
    }

    // ---------- Emisión: sonido, luz, vibración y linterna ----------

    /** Se llama desde el hilo del reproductor o desde la UI. */
    private fun emitSignal(on: Boolean, durationMs: Long) {
        tone.gate = on && sound
        if (torchOn) torch.set(on)
        if (on && vibe && durationMs > 0) vibrate(durationMs)
        val lit = on && light
        ui.post { setLamp(lit) }
    }

    private fun vibrate(ms: Long) {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Algunos fabricantes limitan la vibración; no es crítico
        }
    }

    private fun play(list: List<Morse.Token>, main: Boolean) {
        val (segments, total) = Morse.timeline(list, unitMs())
        if (segments.isEmpty()) return
        stopPlayback()
        mainPlayback = main
        if (main) {
            btnPlay.text = "Detener"
            btnPlay.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        player.play(segments, total)
    }

    private fun echoCode(code: String) {
        if (echo) play(listOf(Morse.Token(ch = "", code = code)), main = false)
    }

    private fun stopPlayback() {
        player.stop()
        emitSignal(false, 0)
        if (vibe) vibrator?.cancel()
        playbackEnded()
    }

    private fun playbackEnded() {
        if (mainPlayback) {
            btnPlay.text = "Reproducir"
            btnPlay.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        mainPlayback = false
        clearHighlight()
    }

    private fun setLamp(on: Boolean) {
        lampShape.setColor(getColor(if (on) R.color.lamp else R.color.lamp_off))
        lampShape.setStroke(dp(2), getColor(if (on) R.color.lamp else R.color.line))
    }

    private fun setKnob(on: Boolean) {
        knobShape.setColor(getColor(if (on) R.color.lamp else R.color.lamp_off))
    }

    // ---------- Otros ----------

    private fun copyOutput() {
        val s = if (mode == Mode.TEXT) Morse.toMorseString(tokens) else Morse.toTextString(tokens).trim()
        if (s.isEmpty()) {
            toast("Todavía no hay nada que copiar")
            return
        }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(if (mode == Mode.TEXT) "Morse" else "Texto", s))
        // Desde Android 13 el sistema ya muestra su propio aviso al copiar
        if (Build.VERSION.SDK_INT < 33) toast("Copiado")
    }

    private fun buildAlphabet() {
        val body = findViewById<LinearLayout>(R.id.abcBody)
        val cols = 3
        Morse.TABLE.entries.chunked(cols).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (k, code) ->
                val cell = FrameLayout(this).apply {
                    background = getDrawable(R.drawable.bg_ref)
                    isClickable = true
                    contentDescription = "$k: $code"
                    setOnClickListener { play(listOf(Morse.Token(ch = k.toString(), code = code)), main = false) }
                }
                val t = TapeView(this).apply {
                    captionStyle(16f, getColor(R.color.ink), markScale = 0.7f)
                    setTokens(listOf(Morse.Token(ch = k.toString(), code = code)))
                }
                cell.addView(t, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                ))
                line.addView(cell, LinearLayout.LayoutParams(0, dp(66), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            repeat(cols - row.size) {
                line.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            body.addView(line)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
