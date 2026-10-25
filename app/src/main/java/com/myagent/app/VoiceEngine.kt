package com.myagent.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoiceEngine(
    private val context: Context,
    private val onStart: () -> Unit = {},
    private val onDone: () -> Unit = {}
) {
    companion object {
        const val SHERPA = "org.woheller69.ttsengine"
        const val RATE = 1.0f
        const val PITCH = 1.0f
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<Pair<Int, String>>()
    private val pending = AtomicInteger(0)
    private val buf = StringBuilder()
    private val delimiter = Regex("[.!?؟؛\\n]+\\s")
    private val junk = Regex("[*_#`>~]+")

    @Volatile private var gen = 0
    @Volatile private var ready = false
    @Volatile private var talking = false
    @Volatile private var latch = CountDownLatch(0)
    private lateinit var tts: TextToSpeech

    init {
        createTts(SHERPA)
        Thread({ playLoop() }, "voice-player").apply { isDaemon = true; start() }
    }

    private fun createTts(engine: String?) {
        ready = false
        tts = TextToSpeech(context.applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
            } else if (engine != null) {
                try { tts.shutdown() } catch (e: Exception) {}
                createTts(null)
            }
        }, engine)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { latch.countDown() }
            override fun onError(id: String?) { latch.countDown() }
            override fun onStop(id: String?, interrupted: Boolean) { latch.countDown() }
        })
    }

    fun speak(text: String) { stop(); feed(text); flush() }

    @Synchronized fun feed(chunk: String) {
        buf.append(chunk)
        while (true) {
            val m = delimiter.find(buf) ?: break
            val end = m.range.last + 1
            enqueue(buf.substring(0, end))
            buf.delete(0, end)
        }
    }

    @Synchronized fun flush() {
        if (buf.isNotBlank()) enqueue(buf.toString())
        buf.setLength(0)
    }

    @Synchronized fun stop() {
        gen++
        talking = false
        buf.setLength(0)
        while (true) {
            queue.poll() ?: break
            pending.decrementAndGet()
        }
        try { tts.stop() } catch (e: Exception) {}
    }

    fun isSpeaking(): Boolean = pending.get() > 0

    fun shutdown() { stop(); try { tts.shutdown() } catch (e: Exception) {} }

    private fun enqueue(raw: String) {
        val text = junk.replace(raw, "").trim()
        if (text.none { it.isLetterOrDigit() }) return
        pending.incrementAndGet()
        queue.put(gen to text)
    }

    private fun playLoop() {
        while (true) {
            val (g, text) = queue.take()
            if (g == gen && !talking) {
                talking = true
                main.post { if (g == gen) onStart() }
            }
            try { if (g == gen) speakNow(g, text) } catch (e: Exception) {}
            val left = pending.decrementAndGet()
            if (g == gen && left == 0 && talking) {
                talking = false
                main.post { if (g == gen) onDone() }
            }
        }
    }

    private fun speakNow(g: Int, text: String) {
        var waited = 0
        while (!ready && waited < 50) { Thread.sleep(100); waited++ }
        if (!ready || g != gen) return
        val arabic = text.any { it in '\u0600'..'\u06FF' }
        tts.setLanguage(if (arabic) Locale("ar") else Locale.US)
        tts.setSpeechRate(RATE)
        tts.setPitch(PITCH)
        latch = CountDownLatch(1)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
        latch.await(60, TimeUnit.SECONDS)
    }
}
