package com.belleui.keyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * A two-row keyboard for game pads. While it is on screen it takes the pad's keys before the app does:
 * D-pad moves along the keys, A types, B deletes, X is space, Y is shift, L1 / R1 move the text cursor,
 * R2 is enter and L2 switches between letters and symbols.
 */
class BelleIme : InputMethodService() {
    private lateinit var kv: KeyboardView
    private val prefs by lazy { getSharedPreferences("keyboard", Context.MODE_PRIVATE) }
    private val handled = HashSet<Int>()
    private val predictor by lazy { Predictor(this) }
    private var predicting = false
    private var lastShiftTap = 0L

    private val qwerty get() = prefs.getInt("layout", 0) == 1
    private val autoCap get() = prefs.getBoolean("autocap", true)
    private val clicks get() = prefs.getBoolean("click", true)
    private val predict get() = prefs.getBoolean("predict", true)
    private val buzz get() = prefs.getBoolean("buzz", false)

    override fun onCreateInputView(): View {
        kv = KeyboardView(this)
        kv.listener = { press(it) }
        kv.onSuggestion = { accept(it) }
        Thread { predictor.load(); kv.post { updateSuggestions() } }.start()
        return kv
    }

    override fun onEvaluateInputViewShown() = true

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else 0
        kv.selRow = 0
        kv.selCol = 0
        kv.shift = 0
        kv.setLayout(page, qwerty)
        if (page == 0) autoCapitalise()
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        predicting = predict && cls == InputType.TYPE_CLASS_TEXT && v != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD && v != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS && v != InputType.TYPE_TEXT_VARIATION_URI &&
            info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        if (predictor.ready) predictor.reloadLearned()
        updateSuggestions()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (::kv.isInitialized) updateSuggestions()
    }

    /** The letters just before the cursor that make up the word being typed. */
    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString() ?: return ""
        return Regex("[A-Za-z']+$").find(before)?.value ?: ""
    }

    private fun updateSuggestions() {
        if (!::kv.isInitialized) return
        if (!predicting || kv.page != 0) {
            kv.setSuggestions(emptyList())
            return
        }
        val w = currentWord()
        val list = predictor.suggest(w).map { s ->
            when {
                w.length > 1 && w.all { it.isUpperCase() || it == '\'' } -> s.uppercase()
                w.first().isUpperCase() -> s.replaceFirstChar { it.uppercaseChar() }
                else -> s
            }
        }
        kv.setSuggestions(list)
    }

    /** Replaces the word being typed with [word] and adds a space. */
    private fun accept(word: String) {
        val ic = currentInputConnection ?: return
        feedback()
        val w = currentWord()
        ic.beginBatchEdit()
        if (w.isNotEmpty()) ic.deleteSurroundingText(w.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        predictor.learn(word)
        kv.selRow = 0
        kv.selCol = 0
        autoCapitalise()
        updateSuggestions()
    }

    /** What was typed counts as a known word once it is finished with a space or punctuation. */
    private fun learnCurrent() {
        if (!predicting) return
        val w = currentWord()
        if (w.length >= 2) predictor.learn(w)
    }

    /** One-shot shift at the start of a sentence, when the field asks for it. */
    private fun autoCapitalise() {
        if (!autoCap || kv.page != 0 || kv.shift == 2) return
        val info = currentInputEditorInfo ?: return
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        kv.shift = if (caps != 0) 1 else 0
        kv.invalidate()
    }

    private fun feedback() {
        if (clicks) (getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        if (buzz) kv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun press(k: Key) {
        val ic = currentInputConnection ?: return
        feedback()
        when (k.kind) {
            K_CHAR -> {
                if (!k.text[0].isLetter() && k.text != "'") learnCurrent()
                val t = if (kv.page == 0 && kv.shift > 0) k.text.uppercase() else k.text
                ic.commitText(t, 1)
                if (kv.shift == 1) kv.shift = 0
                autoCapitalise()
            }
            K_SPACE -> {
                learnCurrent()
                ic.commitText(" ", 1)
                autoCapitalise()
            }
            K_BACKSPACE -> {
                val sel = ic.getSelectedText(0)
                if (sel != null && sel.isNotEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
                autoCapitalise()
            }
            K_ENTER -> enter()
            K_SHIFT -> cycleShift()
            K_PAGE -> kv.setLayout(k.text.toInt(), qwerty)
        }
        kv.invalidate()
        updateSuggestions()
    }

    private fun cycleShift() {
        if (kv.page != 0) {
            kv.setLayout(0, qwerty)
            return
        }
        val now = System.currentTimeMillis()
        kv.shift = if (kv.shift == 0) 1 else if (kv.shift == 1 && now - lastShiftTap < 600) 2 else 0
        lastShiftTap = now
    }

    private fun enter() {
        learnCurrent()
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiline = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!multiline && !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun cursor(dir: Int) {
        sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    private fun pressSelected() {
        kv.flash()
        val sug = kv.selectedSuggestion()
        if (sug != null) accept(sug) else press(kv.selected())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) return super.onKeyDown(keyCode, event)
        val first = event.repeatCount == 0
        val used = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { kv.move(-1, 0); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { kv.move(1, 0); true }
            KeyEvent.KEYCODE_DPAD_UP -> { kv.move(0, -1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { kv.move(0, 1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> { if (first) pressSelected(); true }
            KeyEvent.KEYCODE_BUTTON_B -> { press(Key("", K_BACKSPACE)); true } // repeats while held
            KeyEvent.KEYCODE_BUTTON_X -> { if (first) press(Key("", K_SPACE)); true }
            KeyEvent.KEYCODE_BUTTON_Y -> { if (first) press(Key("", K_SHIFT)); true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { cursor(-1); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { cursor(1); true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { if (first) { val top = kv.suggestions.firstOrNull(); if (top != null) accept(top) else enter() }; true }
            KeyEvent.KEYCODE_BUTTON_L2 -> { if (first) kv.setLayout(if (kv.page == 0) 1 else 0, qwerty); true }
            KeyEvent.KEYCODE_BUTTON_START -> { if (first) enter(); true }
            KeyEvent.KEYCODE_BUTTON_SELECT -> { if (first) requestHideSelf(0); true }
            else -> false
        }
        if (used) {
            handled.add(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handled.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }
}
