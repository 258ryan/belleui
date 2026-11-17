package com.belleui.kit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Short, soft navigation sounds in the Symbian manner: a light tick for moving, a two-note click for
 * choosing, a falling click for going back. They are made in code (no sound files) and kept in memory
 * as small static AudioTracks, played at a low volume on the system-sound stream so silent mode mutes them.
 * Anything going wrong here only means silence, never a crash.
 */
object Sounds {
    const val TICK = 0
    const val SELECT = 1
    const val BACK = 2
    const val OPEN = 3
    const val ERROR = 4

    private val tracks = arrayOfNulls<AudioTrack>(5)
    @Volatile
    private var ready = false
    private var started = false
    private var last = 0L
    private const val RATE = 22050

    @Synchronized
    fun init(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        if (started) return
        started = true
        // built off the main thread so start-up never waits for it
        Thread {
            try {
                for (i in tracks.indices) {
                    val pcm = synth(i)
                    val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                    t.write(pcm, 0, pcm.size)
                    tracks[i] = t
                }
                ready = true
            } catch (_: Throwable) {
            }
        }.start()
    }

    fun play(kind: Int) {
        if (!Belle.soundsOn || !ready) return
        val t = tracks[kind] ?: return
        val now = SystemClock.uptimeMillis()
        // fast scrolling must not turn into a buzz
        if (kind == TICK && now - last < 45) return
        last = now
        try {
            val v = when (kind) { TICK -> 0.28f; SELECT -> 0.42f; BACK -> 0.36f; OPEN -> 0.36f; else -> 0.4f }
            t.stop()
            t.reloadStaticData()
            t.setVolume(v)
            t.play()
        } catch (_: Throwable) {
        }
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
}
