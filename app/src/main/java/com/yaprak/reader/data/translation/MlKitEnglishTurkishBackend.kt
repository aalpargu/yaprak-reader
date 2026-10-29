package com.yaprak.reader.data.translation

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal interface TranslationNativeClient : AutoCloseable {
    fun downloadOverWifi(done: (Result<Unit>) -> Unit)
    fun translate(text: String, done: (Result<String>) -> Unit)
}
internal interface TranslationNativeModels {
    fun ready(done: (Result<Boolean>) -> Unit)
    fun deleteTurkish(done: (Result<Unit>) -> Unit)
}

/** SDK only in the adapter; factories create no translator until explicit download or translation. */
internal class MlKitEnglishTurkishBackend(
    private val modelsFactory: () -> TranslationNativeModels = { GoogleTranslationModels() },
    private val clientFactory: () -> TranslationNativeClient = { GoogleTranslationClient() },
) : TranslationBackend {
    override fun models(done: (Result<Boolean>) -> Unit) {
        try { modelsFactory().ready(done) } catch (error: Exception) { done(Result.failure(error)) }
    }
    override fun deleteTurkishModel(done: (Result<Unit>) -> Unit) {
        try { modelsFactory().deleteTurkish(done) } catch (error: Exception) { done(Result.failure(error)) }
    }
    override fun downloadOverWifi(done: (Result<Unit>) -> Unit) = withClient(done) { client, finish -> client.downloadOverWifi(finish) }
    override fun translate(text: String, done: (Result<String>) -> Unit) = withClient(done) { client, finish -> client.translate(text, finish) }
    private fun <T> withClient(done: (Result<T>) -> Unit, start: (TranslationNativeClient, (Result<T>) -> Unit) -> Unit) {
        val client = try { clientFactory() } catch (error: Exception) { done(Result.failure(error)); return }
        val completed = AtomicBoolean(false)
        fun finish(value: Result<T>) {
            if (!completed.compareAndSet(false, true)) return
            val closed = try { client.close(); value } catch (error: Exception) { Result.failure(error) }
            done(closed)
        }
        try { start(client, ::finish) } catch (error: Exception) { finish(Result.failure(error)) }
    }
}

private val directCompletion = Executor { it.run() }
private fun <T> Task<T>.complete(done: (Result<T>) -> Unit) {
    addOnCompleteListener(directCompletion) { task ->
        if (task.isSuccessful) done(Result.success(task.result))
        else done(Result.failure(task.exception ?: IllegalStateException("Translation task failed")))
    }
}
private class GoogleTranslationClient : TranslationNativeClient {
    private val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.TURKISH).build())
    override fun downloadOverWifi(done: (Result<Unit>) -> Unit) {
        client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).complete { done(it.map { Unit }) }
    }
    override fun translate(text: String, done: (Result<String>) -> Unit) { client.translate(text).complete(done) }
    override fun close() = client.close()
}
private class GoogleTranslationModels : TranslationNativeModels {
    private val manager = RemoteModelManager.getInstance()
    private val english = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
    private val turkish = TranslateRemoteModel.Builder(TranslateLanguage.TURKISH).build()
    override fun ready(done: (Result<Boolean>) -> Unit) {
        // Check both models as required by Translator's public contract; never download here.
        Tasks.whenAllSuccess<Boolean>(manager.isModelDownloaded(english), manager.isModelDownloaded(turkish))
            .complete { done(it.map { available -> available.size == 2 && available.all { present -> present } }) }
    }
    override fun deleteTurkish(done: (Result<Unit>) -> Unit) {
        manager.deleteDownloadedModel(turkish).complete { done(it.map { Unit }) }
    }
}
