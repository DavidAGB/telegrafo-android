package com.davidagb.telegrafo

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tono continuo con compuerta: un hilo escribe siempre en el AudioTrack y la compuerta
 * decide si suena seno o silencio. Así el manipulador y la reproducción responden sin
 * crear pistas nuevas, y las rampas de 5 ms evitan chasquidos.
 */
class Tone {
    @Volatile var freq = 650.0
    @Volatile var gate = false
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "morse-tone").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        gate = false
        thread?.join(300)
        thread = null
    }

    private fun loop() {
        val rate = 44100
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val chunk = ShortArray(128)
        val ramp = 1.0 / (rate * 0.005)
        var phase = 0.0
        var amp = 0.0
        track.play()
        while (running) {
            val target = if (gate) 1.0 else 0.0
            val inc = 2 * PI * freq / rate
            for (i in chunk.indices) {
                amp = if (amp < target) min(target, amp + ramp) else max(target, amp - ramp)
                chunk[i] = (sin(phase) * amp * 0.5 * Short.MAX_VALUE).toInt().toShort()
                phase += inc
                if (phase > 2 * PI) phase -= 2 * PI
            }
            track.write(chunk, 0, chunk.size)
        }
        track.pause()
        track.flush()
        track.release()
    }
}

/** Flash trasero mediante CameraManager.setTorchMode, que no requiere permiso de cámara. */
class Torch(context: Context) {
    private val cm = context.getSystemService(CameraManager::class.java)
    @Volatile private var state = false

    val id: String? = try {
        cm.cameraIdList
            .filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            .minByOrNull {
                if (cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) 0 else 1
            }
    } catch (e: Exception) {
        null
    }

    fun set(on: Boolean) {
        val camera = id ?: return
        if (state == on) return
        state = on
        try {
            cm.setTorchMode(camera, on)
        } catch (e: Exception) {
            state = false
        }
    }
}

/** Recorre la línea de tiempo en su propio hilo y avisa de cada encendido y apagado. */
class Player(private val listener: Listener) {

    interface Listener {
        fun onSignal(on: Boolean, durationMs: Long)
        fun onToken(generation: Int, index: Int)
        fun onDone(generation: Int)
    }

    @Volatile var generation = 0
        private set
    private var thread: Thread? = null

    fun play(segments: List<Morse.Segment>, totalMs: Long) {
        stop()
        val gen = ++generation
        thread = Thread({ run(gen, segments, totalMs) }, "morse-player").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        generation++
        t.interrupt()
        try {
            t.join(300)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun run(gen: Int, segments: List<Morse.Segment>, totalMs: Long) {
        val t0 = SystemClock.uptimeMillis() + 30
        try {
            for (s in segments) {
                sleepUntil(t0 + s.onMs)
                if (s.first) listener.onToken(gen, s.tokenIndex)
                listener.onSignal(true, s.offMs - s.onMs)
                sleepUntil(t0 + s.offMs)
                listener.onSignal(false, 0)
            }
            sleepUntil(t0 + totalMs)
        } catch (e: InterruptedException) {
            listener.onSignal(false, 0)
        } finally {
            listener.onDone(gen)
        }
    }

    private fun sleepUntil(t: Long) {
        val d = t - SystemClock.uptimeMillis()
        if (d > 0) Thread.sleep(d)
    }
}
