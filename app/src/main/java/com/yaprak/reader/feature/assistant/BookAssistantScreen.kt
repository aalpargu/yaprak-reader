package com.yaprak.reader.feature.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yaprak.reader.R
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.BookSourceSection
import com.yaprak.reader.domain.search.BookSourceChapter
import com.yaprak.reader.domain.search.BookSourceFailure
import com.yaprak.reader.domain.search.BookSourceReference
import com.yaprak.reader.domain.search.supportsBookSourceRetrieval
import com.yaprak.reader.domain.library.BookFormat

@Composable
internal fun BookAssistantScreen(state: BookAssistantState,
    onKeywords: (String) -> Unit, onQuestion: (String) -> Unit,
    onEndpoint: (String) -> Unit, onToken: (String) -> Unit,
    onPrepare: (Long) -> Unit, onSend: (Long) -> Unit, onCancel: (Long) -> Unit,
    onOpen: (BookAnswerCitation, Long) -> Unit, onBack: () -> Unit,
    onMode: (BookAssistantSourceMode, Long) -> Unit = { _, _ -> },
    onLoadSections: (Long) -> Unit = {},
    onSection: (BookSourceSection, Long) -> Unit = { _, _ -> },
    onStudy: (BookStudyTask, Long) -> Unit = { _, _ -> },
    onLoadChapters: (Long) -> Unit = {},
    onChapter: (BookSourceChapter, Long) -> Unit = { _, _ -> },
    onOpenIndex: ((Long) -> Unit)? = null,
    onPrepareIndex: ((Long) -> Unit)? = null,
) {
    val prefixMode = state.sourceMode in setOf(BookAssistantSourceMode.PREFIX, BookAssistantSourceMode.PREFIX_KEYWORDS)
    val live by rememberUpdatedState(state)
    val lease = remember { AssistantScreenLease() }
    DisposableEffect(lease) { onDispose { lease.active = false } }
    val revision = state.revision
    fun current() = lease.active && live.active && live.revision == revision
    var connectionExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(bottomBar = {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp)) {
            if (state.busy) AssistantActionButton(revision, "assistant-cancel", R.string.assistant_cancel, state.active) {
                if (current()) onCancel(revision)
            }
            AssistantActionButton(revision, "assistant-back", R.string.assistant_back, state.active) { if (current()) onBack() }
        }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("assistant-list"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("title") { Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.headlineMedium) }
            item("book") { state.book?.let { Text(it.title, style = MaterialTheme.typography.titleLarge) } }
            if (state.book != null && state.indexState == com.yaprak.reader.domain.search.LibraryTextBookState.NOT_INDEXED) item("index-needed") {
                Text(stringResource(R.string.assistant_index_needed), style = MaterialTheme.typography.bodySmall)
                if (onPrepareIndex != null) AssistantActionButton(revision, "assistant-prepare-index", R.string.assistant_prepare_book_index,
                    state.active && !state.busy) {
                    if (current() && !live.busy && live.indexState == com.yaprak.reader.domain.search.LibraryTextBookState.NOT_INDEXED)
                        onPrepareIndex(revision)
                }
                if (onOpenIndex != null) AssistantActionButton(revision, "assistant-open-index", R.string.assistant_open_index,
                    state.active && !state.busy) {
                    if (current() && !live.busy && live.indexState == com.yaprak.reader.domain.search.LibraryTextBookState.NOT_INDEXED)
                        onOpenIndex(revision)
                }
            }
            item("mode-keywords") {
                AssistantActionButton(revision, "assistant-mode-keywords", R.string.assistant_mode_keywords, state.active && !state.busy) {
                    if (current()) onMode(BookAssistantSourceMode.KEYWORDS, revision)
                }
            }
            if (state.book?.let { supportsBookSourceRetrieval(it.format) } == true) item("mode-section") {
                AssistantActionButton(revision, "assistant-mode-section", sectionString(state.book?.format,
                    R.string.assistant_mode_section, R.string.assistant_mode_pdf_page, R.string.assistant_mode_txt_part, R.string.assistant_mode_fb2_part, R.string.assistant_mode_docx_document), state.active && !state.busy) {
                    if (current()) onMode(BookAssistantSourceMode.SECTION, revision)
                }
            }
            if (state.book?.format == BookFormat.EPUB) item("mode-chapter") {
                AssistantActionButton(revision, "assistant-mode-chapter", R.string.assistant_mode_chapter, state.active && !state.busy) {
                    if (current()) onMode(BookAssistantSourceMode.CHAPTER, revision)
                }
            }
            if (state.book?.format == BookFormat.EPUB) item("mode-prefix") {
                AssistantActionButton(revision, "assistant-mode-prefix", R.string.assistant_mode_prefix, state.active && !state.busy) {
                    if (current()) onMode(BookAssistantSourceMode.PREFIX, revision)
                }
            }
            if (state.book?.format == BookFormat.EPUB) item("mode-prefix-keywords") {
                AssistantActionButton(revision, "assistant-mode-prefix-keywords", R.string.assistant_mode_prefix_keywords, state.active && !state.busy) {
                    if (current()) onMode(BookAssistantSourceMode.PREFIX_KEYWORDS, revision)
                }
            }
            item("scope") { Text(stringResource(when (state.sourceMode) {
                BookAssistantSourceMode.SECTION -> sectionString(state.book?.format, R.string.assistant_section_scope,
                    R.string.assistant_pdf_page_scope, R.string.assistant_txt_part_scope, R.string.assistant_fb2_part_scope, R.string.assistant_docx_document_scope)
                BookAssistantSourceMode.CHAPTER -> R.string.assistant_chapter_scope
                BookAssistantSourceMode.PREFIX -> R.string.assistant_prefix_scope
                BookAssistantSourceMode.PREFIX_KEYWORDS -> R.string.assistant_prefix_query_scope
                BookAssistantSourceMode.KEYWORDS -> when (state.book?.format) {
                    BookFormat.PDF -> R.string.assistant_pdf_scope
                    BookFormat.TXT -> R.string.assistant_txt_scope
                    BookFormat.FB2 -> R.string.assistant_fb2_scope
                    BookFormat.DOCX -> R.string.assistant_docx_scope
                    else -> R.string.assistant_scope
                }
            }), style = MaterialTheme.typography.bodySmall) }
            if (state.sourceMode in setOf(BookAssistantSourceMode.KEYWORDS, BookAssistantSourceMode.PREFIX_KEYWORDS)) item("keywords") {
                AssistantField(state.keywords, R.string.assistant_keywords, "assistant-keywords",
                    state.active && !state.busy, onValue = { if (current()) onKeywords(it) })
            }
            if (state.sourceMode == BookAssistantSourceMode.SECTION) {
                item("load-sections") {
                    AssistantActionButton(revision, "assistant-load-sections", sectionString(state.book?.format,
                        R.string.assistant_load_sections, R.string.assistant_load_pdf_pages, R.string.assistant_load_txt_parts, R.string.assistant_load_fb2_parts, R.string.assistant_load_docx_document),
                        state.active && state.book != null && !state.busy) { if (current()) onLoadSections(revision) }
                }
                if (state.sectionsLoaded && state.sections.isEmpty()) item("no-sections") {
                    Text(stringResource(sectionString(state.book?.format, R.string.assistant_no_sections,
                        R.string.assistant_no_pdf_pages, R.string.assistant_no_txt_parts, R.string.assistant_no_fb2_parts, R.string.assistant_no_docx_document)))
                }
                state.sections.forEach { section -> item("section:${section.generation}:${section.readingOrderIndex}") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (section.sourceFormat == BookFormat.PDF) stringResource(R.string.assistant_pdf_page_title,
                            section.readingOrderIndex + 1) else section.chapterTitle ?: section.resourceHref,
                            style = MaterialTheme.typography.titleSmall)
                        if (section.sourceFormat == BookFormat.EPUB && section.chapterTitle != null)
                            Text(section.resourceHref, style = MaterialTheme.typography.bodySmall)
                        Text(if (section.sourceFormat == BookFormat.DOCX) stringResource(R.string.assistant_docx_document_parts, section.chunkCount)
                            else stringResource(sectionString(section.sourceFormat, R.string.assistant_section_parts,
                                R.string.assistant_pdf_page_parts, R.string.assistant_txt_part_parts, R.string.assistant_fb2_part_parts, R.string.assistant_docx_document_parts), section.readingOrderIndex + 1, section.chunkCount))
                        if (!section.fitsSingleRequest) Text(stringResource(sectionString(section.sourceFormat,
                            R.string.assistant_section_too_large, R.string.assistant_complete_part_too_large, R.string.assistant_complete_part_too_large, R.string.assistant_complete_part_too_large, R.string.assistant_complete_part_too_large)))
                        AssistantActionButton(revision, "assistant-section-${section.readingOrderIndex}",
                            if (state.section == section) sectionString(section.sourceFormat, R.string.assistant_section_selected,
                                R.string.assistant_pdf_page_selected, R.string.assistant_txt_part_selected, R.string.assistant_fb2_part_selected, R.string.assistant_docx_document_selected)
                            else sectionString(section.sourceFormat, R.string.assistant_section_choose,
                                R.string.assistant_pdf_page_choose, R.string.assistant_txt_part_choose, R.string.assistant_fb2_part_choose, R.string.assistant_docx_document_choose),
                            state.active && !state.busy) { if (current()) onSection(section, revision) }
                        HorizontalDivider()
                    }
                } }
            }
            if (state.sourceMode in setOf(BookAssistantSourceMode.CHAPTER, BookAssistantSourceMode.PREFIX, BookAssistantSourceMode.PREFIX_KEYWORDS)) {
                item("load-chapters") {
                    AssistantActionButton(revision, "assistant-load-chapters", if (prefixMode) R.string.assistant_prefix_load else R.string.assistant_load_chapters,
                        state.active && state.book != null && !state.busy) { if (current()) onLoadChapters(revision) }
                }
                if (state.chaptersLoaded && state.chapters.isEmpty()) item("no-chapters") {
                    Text(stringResource(R.string.assistant_no_chapters))
                }
                state.chapters.forEach { chapter -> item("chapter:" + chapter.generation + ":" + chapter.tocIndex) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(chapter.title, style = MaterialTheme.typography.titleSmall)
                        if (prefixMode) Text(stringResource(if (state.sourceMode == BookAssistantSourceMode.PREFIX_KEYWORDS) R.string.assistant_prefix_query_choose_help else R.string.assistant_prefix_choose_help))
                        else {
                            Text(stringResource(R.string.assistant_chapter_parts, chapter.depth, chapter.sourceCount))
                            if (chapter.sourceCount == 0) Text(stringResource(R.string.assistant_chapter_no_text))
                            else if (!chapter.fitsSingleRequest) Text(stringResource(R.string.assistant_chapter_too_large))
                        }
                        AssistantActionButton(revision, "assistant-chapter-" + chapter.tocIndex,
                            if (prefixMode) {
                                if (state.chapter == chapter) R.string.assistant_prefix_selected else R.string.assistant_prefix_choose
                            } else if (state.chapter == chapter) R.string.assistant_chapter_selected else R.string.assistant_chapter_choose,
                            state.active && !state.busy && (prefixMode || chapter.sourceCount != 0)) { if (current()) onChapter(chapter, revision) }
                        HorizontalDivider()
                    }
                } }
            }
            item("prepare") {
                AssistantActionButton(revision, "assistant-prepare", R.string.assistant_prepare, state.canPrepare) {
                    if (current() && live.canPrepare) onPrepare(revision)
                }
            }
            state.sources?.let { sources ->
                sources.prefixQuery?.let { prefix -> item("chosen-prefix-query-title") {
                    Text(stringResource(R.string.assistant_prefix_query_chosen, prefix.throughChapter.title), style = MaterialTheme.typography.titleLarge)
                } }
                sources.prefix?.let { prefix -> item("chosen-prefix-title") {
                    Text(stringResource(R.string.assistant_prefix_chosen, prefix.throughChapter.title), style = MaterialTheme.typography.titleLarge)
                } }
                sources.chapter?.let { chapter -> item("chosen-chapter-title") {
                    Text(chapter.title, style = MaterialTheme.typography.titleLarge)
                } }
                item("count") {
                    if (sources.prefixQuery != null) Text(pluralStringResource(R.plurals.assistant_prefix_query_source_count,
                        sources.totalMatches, sources.references.size, sources.totalMatches))
                    else if (sources.prefix != null) Text(pluralStringResource(R.plurals.assistant_prefix_source_count,
                        sources.references.size, sources.references.size, sources.totalMatches))
                    else Text(stringResource(if (sources.chapter != null) R.string.assistant_chapter_source_count
                        else if (sources.section != null) sectionString(sources.section.sourceFormat, R.string.assistant_section_source_count,
                            R.string.assistant_pdf_page_source_count, R.string.assistant_txt_part_source_count, R.string.assistant_fb2_part_source_count, R.string.assistant_docx_document_source_count)
                        else if (sources.references.isEmpty()) R.string.assistant_no_sources else R.string.assistant_source_count,
                        sources.references.size, sources.totalMatches))
                }
                sources.references.forEach { source -> item("source:${source.label}") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceReferenceTitle(source)
                        Text(source.text, Modifier.fillMaxWidth().testTag("assistant-source-${source.label}"))
                        HorizontalDivider()
                    }
                } }
            }
            if (state.sources?.section != null || state.sources?.chapter != null || state.sources?.prefix != null) {
                item("summary") {
                    AssistantActionButton(revision, "assistant-study-summary",
                        if (prefixMode) R.string.assistant_prefix_summary
                        else if (state.sourceMode == BookAssistantSourceMode.CHAPTER) R.string.assistant_chapter_study_summary
                        else sectionString(state.book?.format, R.string.assistant_study_summary,
                            R.string.assistant_pdf_page_summary, R.string.assistant_txt_part_summary, R.string.assistant_fb2_part_summary, R.string.assistant_docx_document_summary), state.canStudy) {
                        if (current() && live.canStudy) onStudy(BookStudyTask.SUMMARY, revision)
                    }
                }
                item("practice") {
                    AssistantActionButton(revision, "assistant-study-practice", R.string.assistant_study_practice, state.canStudy) {
                        if (current() && live.canStudy) onStudy(BookStudyTask.PRACTICE, revision)
                    }
                }
                item("study-help") { Text(stringResource(R.string.assistant_study_help), style = MaterialTheme.typography.bodySmall) }
            }
            item("question") {
                AssistantField(state.question, R.string.assistant_question, "assistant-question", state.active && !state.busy,
                    onValue = { if (current()) onQuestion(it) })
            }
            item("connection-toggle") {
                AssistantActionButton(revision, "assistant-connection", R.string.assistant_connection, state.active && !state.busy) {
                    if (current()) connectionExpanded = !connectionExpanded
                }
            }
            if (connectionExpanded) {
                item("connection-help") { Text(stringResource(R.string.assistant_connection_help), style = MaterialTheme.typography.bodySmall) }
                item("endpoint") {
                    AssistantField(state.endpoint, R.string.assistant_endpoint, "assistant-endpoint", state.active && !state.busy,
                        onValue = { if (current()) onEndpoint(it) })
                }
                item("token") {
                    AssistantField(state.accessToken, R.string.assistant_token, "assistant-token", state.active && !state.busy,
                        password = true, onValue = { if (current()) onToken(it) })
                }
            }
            item("send-help") {
                Text(stringResource(when (state.sourceMode) {
                    BookAssistantSourceMode.SECTION -> sectionString(state.book?.format, R.string.assistant_section_send_help,
                        R.string.assistant_complete_part_send_help, R.string.assistant_complete_part_send_help, R.string.assistant_complete_part_send_help, R.string.assistant_docx_document_send_help)
                    BookAssistantSourceMode.CHAPTER -> R.string.assistant_chapter_send_help
                    BookAssistantSourceMode.PREFIX -> R.string.assistant_prefix_send_help
                    BookAssistantSourceMode.PREFIX_KEYWORDS -> R.string.assistant_prefix_query_send_help
                    BookAssistantSourceMode.KEYWORDS -> if (state.book?.format == BookFormat.DOCX) R.string.assistant_docx_keyword_send_help else R.string.assistant_send_help
                }),
                    Modifier.testTag("assistant-send-help"), style = MaterialTheme.typography.bodySmall)
                if (state.endpoint.isNotBlank()) Text(state.endpoint, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            }
            item("send") {
                AssistantActionButton(revision, "assistant-send", R.string.assistant_send, state.canSend) {
                    if (current() && live.canSend) onSend(revision)
                }
            }
            if (state.busy) item("busy") {
                CircularProgressIndicator()
                Text(stringResource(R.string.assistant_busy), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.sourceFailure != null) item("source-error") {
                Text(stringResource(if (state.sourceFailure == BookSourceFailure.BUSY) R.string.assistant_index_busy
                else if (state.sourceFailure == BookSourceFailure.LIMIT &&
                    (state.sourceMode == BookAssistantSourceMode.KEYWORDS || state.indexState == com.yaprak.reader.domain.search.LibraryTextBookState.LIMIT))
                    R.string.assistant_source_preparation_limit
                else if (state.sourceFailure == BookSourceFailure.UNSUPPORTED && state.indexState == com.yaprak.reader.domain.search.LibraryTextBookState.NO_TEXT)
                    R.string.assistant_index_no_text
                else if (state.sourceMode == BookAssistantSourceMode.PREFIX_KEYWORDS) R.string.assistant_prefix_query_failure
                else if (prefixMode) R.string.assistant_prefix_failure
                else if (state.sourceMode == BookAssistantSourceMode.CHAPTER) {
                    when (state.sourceFailure) {
                        BookSourceFailure.LIMIT -> R.string.assistant_chapter_limit_error
                        BookSourceFailure.UNSUPPORTED -> R.string.assistant_chapter_unsupported
                        else -> R.string.assistant_source_error
                    }
                } else if (state.sourceFailure == BookSourceFailure.LIMIT) sectionString(state.book?.format,
                    R.string.assistant_section_limit_error, R.string.assistant_complete_part_limit_error, R.string.assistant_complete_part_limit_error, R.string.assistant_complete_part_limit_error, R.string.assistant_docx_document_limit_error)
                else R.string.assistant_source_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.failure?.let { failure -> item("error") {
                Text(stringResource(when (failure) {
                    BookAnswerFailure.CONFIGURATION -> R.string.assistant_error_config
                    BookAnswerFailure.INVALID_INPUT -> R.string.assistant_error_input
                    BookAnswerFailure.UNAUTHORIZED -> R.string.assistant_error_auth
                    BookAnswerFailure.BUSY -> R.string.assistant_error_busy
                    BookAnswerFailure.UNAVAILABLE -> R.string.assistant_error_unavailable
                    BookAnswerFailure.NETWORK -> R.string.assistant_error_network
                    BookAnswerFailure.TIMEOUT -> R.string.assistant_error_timeout
                    BookAnswerFailure.REFUSED -> R.string.assistant_error_refused
                    BookAnswerFailure.INCOMPLETE -> R.string.assistant_error_incomplete
                    BookAnswerFailure.LIMIT -> R.string.assistant_error_limit
                    BookAnswerFailure.SOURCE -> R.string.assistant_source_error
                    BookAnswerFailure.INVALID_RESPONSE -> R.string.assistant_error_response
                }), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } }
            state.answer?.let { answer ->
                item("answer-title") { Text(stringResource(R.string.assistant_answer), style = MaterialTheme.typography.titleLarge) }
                item("answer-help") { Text(stringResource(R.string.assistant_answer_help), style = MaterialTheme.typography.bodySmall) }
                if (answer.status == BookAnswerStatus.INSUFFICIENT) item("insufficient") {
                    Text(stringResource(R.string.assistant_insufficient)); Text(answer.message)
                }
                answer.statements.forEachIndexed { index, statement ->
                    item("statement:$index") {
                        Text(statement.text, Modifier.fillMaxWidth())
                    }
                    statement.citations.forEachIndexed { citationIndex, citation ->
                        item("citation:$index:$citationIndex") {
                            SourceReferenceTitle(citation.source)
                            Text(citation.quote, Modifier.fillMaxWidth())
                        }
                        item("open:$index:$citationIndex") {
                            val openLabel = when (citation.source.hit.target.sourceFormat) {
                                BookFormat.PDF -> R.string.assistant_pdf_open
                                BookFormat.TXT -> R.string.assistant_txt_open
                                BookFormat.FB2 -> R.string.assistant_fb2_open
                                BookFormat.DOCX -> R.string.assistant_docx_open
                                else -> if (state.sourceMode in setOf(BookAssistantSourceMode.CHAPTER, BookAssistantSourceMode.PREFIX, BookAssistantSourceMode.PREFIX_KEYWORDS)) R.string.assistant_chapter_open else R.string.assistant_open
                            }
                            AssistantActionButton(revision, "assistant-open-$index-${citation.source.label}", openLabel, state.active && !state.busy) {
                                if (current() && !live.busy && live.answer === answer) onOpen(citation, revision)
                            }
                        }
                    }
                    item("divider:$index") { HorizontalDivider() }
                }
            }
        }
    }
}
private fun sectionString(format: BookFormat?, epub: Int, pdf: Int, txt: Int, fb2: Int, docx: Int): Int = when (format) {
    BookFormat.PDF -> pdf
    BookFormat.TXT -> txt
    BookFormat.FB2 -> fb2
    BookFormat.DOCX -> docx
    else -> epub
}
@Composable private fun SourceReferenceTitle(source: BookSourceReference) {
    val hit = source.hit
    val title = when (hit.target.sourceFormat) {
        BookFormat.PDF -> hit.target.pdfPageIndex?.takeIf { it >= 0 }?.let { stringResource(R.string.assistant_pdf_source, it + 1) }
            ?: stringResource(R.string.assistant_unknown_source)
        BookFormat.TXT, BookFormat.FB2, BookFormat.DOCX -> hit.chapterTitle ?: stringResource(R.string.assistant_unknown_source)
        BookFormat.EPUB -> hit.chapterTitle ?: hit.target.resourceHref
        BookFormat.CBZ, BookFormat.CBR -> stringResource(R.string.assistant_unknown_source)
    }
    Text("${source.label} · $title", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
}
private class AssistantScreenLease { var active = true }

/** Material's retained semantics callback forwards its latest onClick. Give every rendered
 * action a separate widget/disposal identity so an old event cannot target the new request. */
@Composable private fun AssistantActionButton(revision: Long, tag: String, label: Int, enabled: Boolean, action: () -> Unit) {
    key(revision) {
        val lease = remember { AssistantScreenLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        TextButton(onClick = { if (lease.active) action() }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text(stringResource(label), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun AssistantField(value: String, labelId: Int, tag: String, enabled: Boolean,
    password: Boolean = false, onValue: (String) -> Unit) {
    val label = stringResource(labelId)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(value = value, onValueChange = onValue, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            singleLine = password, visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text))
    }
}
