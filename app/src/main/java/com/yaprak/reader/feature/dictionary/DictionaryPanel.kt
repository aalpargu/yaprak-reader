package com.yaprak.reader.feature.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.dictionary.DictionaryFailure

/** Embedded inside the reader's existing scroll body; responses are plain, literal text. */
@Composable
fun DictionaryPanel(state: DictionaryLookupState?, isCurrent: () -> Boolean, onLookup: () -> Unit,
    onRetry: (DictionaryLookupState) -> Unit) {
    val live by rememberUpdatedState(state)
    val gate by rememberUpdatedState(isCurrent)
    val lookup by rememberUpdatedState(onLookup)
    val retry by rememberUpdatedState(onRetry)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.dictionary_transfer_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        key(DictionaryActionKey(state), onLookup) {
            val lease = actionLease()
            TextButton({ if (lease.active && gate() && live === state && lookup === onLookup) onLookup() },
                enabled = state?.loading != true, modifier = Modifier.fillMaxWidth().testTag("dictionary-lookup")) {
                Text(stringResource(R.string.dictionary_action), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
        if (state?.loading == true) Text(stringResource(R.string.dictionary_loading), Modifier.fillMaxWidth())
        state?.failure?.let { failure ->
            Text(stringResource(failureText(failure)), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
            if (state.word != null) key(DictionaryActionKey(state), onRetry) {
                val lease = actionLease()
                TextButton({ if (lease.active && gate() && live === state && retry === onRetry) onRetry(state) },
                    Modifier.fillMaxWidth().testTag("dictionary-retry")) {
                    Text(stringResource(R.string.dictionary_retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
        state?.result?.let { result ->
            Text(stringResource(R.string.dictionary_provider), Modifier.fillMaxWidth())
            result.entries.forEach { entry ->
                Text(entry.word, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                entry.phonetic?.let { Text(it, Modifier.fillMaxWidth()) }
                entry.definitions.forEach { definition ->
                    Text(definition.partOfSpeech, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge)
                    Text(definition.definition, Modifier.fillMaxWidth())
                    definition.example?.let { Text(stringResource(R.string.dictionary_example, it), Modifier.fillMaxWidth()) }
                }
                entry.license?.let { license ->
                    Text(stringResource(R.string.dictionary_license, license.name), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                    license.url?.let { Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall) }
                }
                entry.sources.forEach { Text(stringResource(R.string.dictionary_source, it), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private class DictionaryActionLease { var active = true }
@Composable private fun actionLease(): DictionaryActionLease {
    val lease = remember { DictionaryActionLease() }
    DisposableEffect(lease) { onDispose { lease.active = false } }
    return lease
}
private class DictionaryActionKey(private val state: DictionaryLookupState?) {
    override fun equals(other: Any?) = other is DictionaryActionKey && state === other.state
    override fun hashCode() = System.identityHashCode(state)
}
private fun failureText(failure: DictionaryFailure): Int = when (failure) {
    DictionaryFailure.INVALID_WORD -> R.string.dictionary_invalid_word
    DictionaryFailure.NOT_FOUND -> R.string.dictionary_not_found
    DictionaryFailure.NETWORK -> R.string.dictionary_network
    DictionaryFailure.TIMEOUT -> R.string.dictionary_timeout
    DictionaryFailure.PROVIDER -> R.string.dictionary_provider_error
    DictionaryFailure.TOO_LARGE -> R.string.dictionary_too_large
    DictionaryFailure.BUSY -> R.string.dictionary_busy
}
