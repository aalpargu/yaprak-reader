package com.yaprak.reader.feature.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yaprak.reader.reader.ReadAloudChunk
import com.yaprak.reader.reader.ReadAloudSource
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ReadAloudStatus { IDLE, STARTING, SPEAKING, FAILED }

/**
 * On-device speech for the open book. Speaks one chunk at a time, moves the page along, and fetches
 * the next batch when a batch ends. Any stop request, pause or source change ends speech.
 */
internal class ReadAloudController(private val context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(ReadAloudStatus.IDLE)
    val status = mutable.asStateFlow()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var source: ReadAloudSource? = null
    private var queue: List<ReadAloudChunk> = emptyList()
    private var index = 0
    private var session = 0L
    private var job: Job? = null

    val active: Boolean get() = status.value == ReadAloudStatus.STARTING || status.value == ReadAloudStatus.SPEAKING

    fun toggle(target: ReadAloudSource) { if (active) stop() else start(target) }

    fun start(target: ReadAloudSource) {
        stop()
        val id = ++session
        source = target
        mutable.value = ReadAloudStatus.STARTING
        val engine = tts
        if (engine != null && ready) { begin(id, target); return }
        tts = TextToSpeech(context.applicationContext) { result ->
            scope.launch {
                if (id != session) return@launch
                if (result != TextToSpeech.SUCCESS) { fail(); return@launch }
                ready = true
                tts?.setOnUtteranceProgressListener(listener)
                begin(id, target)
            }
        }
    }

    private fun begin(id: Long, target: ReadAloudSource) {
        val engine = tts ?: return fail()
        target.language?.let { tag ->
            val locale = Locale.forLanguageTag(tag)
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        }
        load(id) { target.fromCurrent() }
    }

    private fun load(id: Long, fetch: suspend () -> List<ReadAloudChunk>) {
        job?.cancel()
        job = scope.launch {
            val batch = try { fetch() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (id != session) return@launch
            when {
                batch == null -> fail()
                batch.isEmpty() -> stop() // end of the book
                else -> { queue = batch; index = 0; speakCurrent(id) }
            }
        }
    }

    private fun speakCurrent(id: Long) {
        val engine = tts ?: return fail()
        val chunk = queue.getOrNull(index) ?: return
        source?.show(chunk)
        mutable.value = ReadAloudStatus.SPEAKING
        if (engine.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, null, "$id:$index") != TextToSpeech.SUCCESS) fail()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            scope.launch {
                val (id, at) = utteranceId?.split(':')?.let { it[0].toLongOrNull() to it.getOrNull(1)?.toIntOrNull() } ?: return@launch
                if (id != session || at != index || !active) return@launch
                index++
                if (index < queue.size) speakCurrent(session)
                else { val last = queue.last(); val target = source ?: return@launch; load(session) { target.next(last) } }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { scope.launch { if (utteranceId?.startsWith("$session:") == true) fail() } }
    }

    private fun fail() { stopSpeech(); mutable.value = ReadAloudStatus.FAILED }

    fun stop() {
        stopSpeech()
        if (mutable.value != ReadAloudStatus.FAILED) mutable.value = ReadAloudStatus.IDLE
    }

    private fun stopSpeech() {
        session++
        job?.cancel(); job = null
        queue = emptyList(); index = 0; source = null
        tts?.stop()
    }

    fun clearFailure() { if (mutable.value == ReadAloudStatus.FAILED) mutable.value = ReadAloudStatus.IDLE }

    fun release() { stop(); tts?.shutdown(); tts = null; ready = false }
}
