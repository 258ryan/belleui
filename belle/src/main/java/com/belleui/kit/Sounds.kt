package com.belleui.kit

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Short, soft navigation sounds in the Symbian manner: a light tick for moving, a two-note click for
 * choosing, a falling click for going back. They are made in code (no sound files shipped) and played
 * through a SoundPool at a low volume, on the system-sound stream so silent mode mutes them.
 */
object Sounds {
    const val TICK = 0
    const val SELECT = 1
    const val BACK = 2
    const val OPEN = 3
    const val ERROR = 4

    private var pool: SoundPool? = null
    private val ids = IntArray(5)
    private val loaded = BooleanArray(5)
    private var last = 0L
    private const val RATE = 22050

    @Synchronized
    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) for (i in ids.indices) if (ids[i] == id) loaded[i] = true }
        pool = p
        val dir = File(ctx.cacheDir, "belle_sounds").also { it.mkdirs() }
        val names = arrayOf("tick", "select", "back", "open", "error")
        for (i in names.indices) {
            val f = File(dir, "${names[i]}_v1.wav")
            if (!f.exists()) f.writeBytes(wav(synth(i)))
            ids[i] = p.load(f.path, 1)
        }
    }

    fun play(kind: Int) {
        if (!Belle.soundsOn) return
        val p = pool ?: return
        if (!loaded[kind]) return
        val now = SystemClock.uptimeMillis()
        // fast scrolling must not turn into a buzz
        if (kind == TICK && now - last < 45) return
        last = now
        val v = when (kind) { TICK -> 0.28f; SELECT -> 0.42f; BACK -> 0.36f; OPEN -> 0.36f; else -> 0.4f }
        p.play(ids[kind], v, v, 1, 0, 1f)
    }

    /** A decaying sine burst; [f0] glides to [f1]. */
    private fun burst(out: ArrayList<Short>, ms: Int, f0: Double, f1: Double, decay: Double, amp: Double) {
        val n = RATE * ms / 1000
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val f = f0 + (f1 - f0) * t
            phase += 2 * PI * f / RATE
            val env = exp(-i / (RATE * decay)) * (if (i < 80) i / 80.0 else 1.0)
            out.add((sin(phase) * env * amp * 32767).toInt().coerceIn(-32767, 32767).toShort())
        }
    }

    private fun synth(kind: Int): ShortArray {
        val s = ArrayList<Short>()
        when (kind) {
            TICK -> burst(s, 24, 2300.0, 2100.0, 0.006, 0.55)
            SELECT -> {
                burst(s, 30, 1500.0, 1500.0, 0.010, 0.5)
                burst(s, 50, 2200.0, 2200.0, 0.016, 0.5)
            }
            BACK -> burst(s, 60, 1500.0, 800.0, 0.018, 0.5)
            OPEN -> burst(s, 70, 800.0, 1500.0, 0.025, 0.45)
            else -> {
                burst(s, 70, 330.0, 330.0, 0.030, 0.6)
                burst(s, 70, 260.0, 260.0, 0.030, 0.6)
            }
        }
        return s.toShortArray()
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val o = ByteArrayOutputStream()
        fun i32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v shr 24 and 255) }
        fun i16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
        o.write("RIFF".toByteArray()); i32(36 + data); o.write("WAVEfmt ".toByteArray())
        i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(data)
        for (v in pcm) i16(v.toInt())
        return o.toByteArray()
    }
}
