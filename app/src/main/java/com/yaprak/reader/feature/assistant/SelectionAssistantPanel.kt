package com.yaprak.reader.feature.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.assistant.BookAnswerFailure
import com.yaprak.reader.domain.assistant.BookAnswerStatus

/** Embedded in the current selection's scrolling body; copy and close remain outside it. */
@Composable internal fun SelectionAssistantPanel(state: SelectionAssistantState?, isCurrent: () -> Boolean,
    onOpen: () -> Unit, onPrepare: (SelectionAssistantState) -> Unit, onSend: (SelectionAssistantState) -> Unit,
    onClose: (SelectionAssistantState) -> Unit, onQuestion: (SelectionAssistantState, String) -> Unit,
    onEndpoint: (SelectionAssistantState, String) -> Unit, onToken: (SelectionAssistantState, String) -> Unit) {
    val live by rememberUpdatedState(state)
    val currentGuard by rememberUpdatedState(isCurrent)
    val openAction by rememberUpdatedState(onOpen)
    val lease = remember { SelectionPanelLease() }
    DisposableEffect(lease) { onDispose { lease.active = false } }
    fun current() = lease.active && live === state && currentGuard()
    if (state == null) {
        key(onOpen, isCurrent) {
            val actionLease = remember { SelectionPanelLease() }
            DisposableEffect(actionLease) { onDispose { actionLease.active = false } }
            TextButton({ if (actionLease.active && current() && openAction === onOpen) onOpen() },
                Modifier.fillMaxWidth().testTag("selected-ai-open")) {
                Text(stringResource(R.string.selected_ai_open), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
        return
    }
    // Keep fields/IME alive while editing; only a different selection replaces their ownership.
    key(SelectionPanelIdentityKey(state.request)) {
        val actionLease = remember { SelectionPanelLease() }
        DisposableEffect(actionLease) { onDispose { actionLease.active = false } }
        fun fresh() = actionLease.active && current()
        Column(Modifier.fillMaxWidth().testTag("selected-ai-panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.selected_ai_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.selected_ai_scope), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            if (state.preparing) Text(stringResource(R.string.selected_ai_preparing), Modifier.fillMaxWidth())
            state.sources?.let { sources ->
                Text(sources.book.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                Text(sources.request.pdfSourceAnchor?.let { stringResource(R.string.selected_ai_pdf_source, it.pageIndex + 1) }
                    ?: sources.request.anchor?.resourceHref.orEmpty(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                sources.references.forEach { reference ->
                    Text(reference.label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                    Text(reference.text, Modifier.fillMaxWidth().testTag("selected-ai-source-${reference.label}"))
                }
            }
            val explain = stringResource(R.string.selected_ai_default_question)
            val simplify = stringResource(R.string.selected_ai_simple_question)
            SelectionPanelButton("selected-ai-explain", R.string.selected_ai_explain, !state.busy) {
                if (fresh()) onQuestion(state, explain)
            }
            SelectionPanelButton("selected-ai-simplify", R.string.selected_ai_simplify, !state.busy) {
                if (fresh()) onQuestion(state, simplify)
            }
            SelectionPanelField(state.question, R.string.assistant_question, "selected-ai-question", !state.busy) {
                if (fresh()) onQuestion(state, it)
            }
            Text(stringResource(R.string.assistant_connection_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            SelectionPanelField(state.endpoint, R.string.assistant_endpoint, "selected-ai-endpoint", !state.busy, singleLine = true) {
                if (fresh()) onEndpoint(state, it)
            }
            SelectionPanelField(state.accessToken, R.string.assistant_token, "selected-ai-token", !state.busy, password = true) {
                if (fresh()) onToken(state, it)
            }
            Text(stringResource(R.string.selected_ai_send_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            if (state.endpoint.isNotBlank()) Text(state.endpoint, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            SelectionPanelButton("selected-ai-prepare", R.string.selected_ai_prepare, !state.busy) { if (fresh()) onPrepare(state) }
            SelectionPanelButton("selected-ai-send", R.string.assistant_send, state.canSend) { if (fresh() && live?.canSend == true) onSend(state) }
            if (state.sending) Text(stringResource(R.string.selected_ai_sending), Modifier.fillMaxWidth())
            state.failure?.let { Text(stringResource(selectionAssistantFailureText(it)), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().testTag("selected-ai-failure")) }
            state.answer?.let { answer ->
                Text(stringResource(R.string.selected_ai_answer), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                if (answer.status == BookAnswerStatus.INSUFFICIENT) Text(answer.message, Modifier.fillMaxWidth())
                else answer.statements.forEach { statement ->
                    Text(statement.text, Modifier.fillMaxWidth())
                    statement.citations.forEach { citation ->
                        Text("${citation.source.label} · ${citation.quote}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
                Text(stringResource(if (state.request.sourceFormat == com.yaprak.reader.domain.library.BookFormat.PDF)
                    R.string.selected_ai_pdf_answer_help else R.string.selected_ai_answer_help), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            }
            SelectionPanelButton("selected-ai-close", R.string.selected_ai_close, true) { if (fresh()) onClose(state) }
        }
    }
}

private class SelectionPanelLease { var active = true }
@Composable private fun SelectionPanelField(value: String, labelId: Int, tag: String, enabled: Boolean,
    singleLine: Boolean = false, password: Boolean = false, onValue: (String) -> Unit) {
    val label = stringResource(labelId)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.fillMaxWidth())
        OutlinedTextField(value, onValue, Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            enabled = enabled, singleLine = singleLine || password, maxLines = if (singleLine || password) 1 else 5,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text))
    }
}
@Composable private fun SelectionPanelButton(tag: String, label: Int, enabled: Boolean, onClick: () -> Unit) {
    key(SelectionPanelIdentityKey(onClick)) {
        val lease = remember { SelectionPanelLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        TextButton({ if (lease.active) onClick() }, Modifier.fillMaxWidth().testTag(tag), enabled = enabled) {
            Text(stringResource(label), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}
private class SelectionPanelIdentityKey(private val value: Any) {
    override fun equals(other: Any?) = other is SelectionPanelIdentityKey && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}
private fun selectionAssistantFailureText(reason: BookAnswerFailure): Int = when (reason) {
    BookAnswerFailure.CONFIGURATION -> R.string.assistant_error_config
    BookAnswerFailure.SOURCE -> R.string.selected_ai_source_error
    BookAnswerFailure.LIMIT -> R.string.selected_ai_limit_error
    BookAnswerFailure.INVALID_INPUT -> R.string.assistant_error_input
    BookAnswerFailure.UNAUTHORIZED -> R.string.assistant_error_auth
    BookAnswerFailure.BUSY -> R.string.assistant_error_busy
    BookAnswerFailure.UNAVAILABLE -> R.string.assistant_error_unavailable
    BookAnswerFailure.NETWORK -> R.string.assistant_error_network
    BookAnswerFailure.TIMEOUT -> R.string.assistant_error_timeout
    BookAnswerFailure.REFUSED -> R.string.assistant_error_refused
    BookAnswerFailure.INCOMPLETE -> R.string.assistant_error_incomplete
    BookAnswerFailure.INVALID_RESPONSE -> R.string.assistant_error_response
}
