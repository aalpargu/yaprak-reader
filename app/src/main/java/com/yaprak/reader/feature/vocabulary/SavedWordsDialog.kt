package com.yaprak.reader.feature.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.vocabulary.SavedWord
import com.yaprak.reader.domain.vocabulary.SavedWordRepository
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

@Composable
internal fun SavedWordsRoute(book: Book, repository: SavedWordRepository, onDismiss: () -> Unit,
    prefillEnglish: String? = null, onCopy: ((text: String) -> Unit)? = null,
    prefillTranslation: TranslatedWordDraft? = null) {
    val model: SavedWordsViewModel = viewModel(key = "saved-words-${book.id}-${book.sha256}", factory = viewModelFactory {
        initializer { SavedWordsViewModel(book.id, book.sha256, repository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(prefillEnglish, prefillTranslation) {
        if (prefillTranslation != null) model.offerTranslationDraft(prefillTranslation)
        else prefillEnglish?.let(model::prefillEnglish)
    }
    val copyWord: ((String) -> Unit)? = onCopy?.let { copyText ->
        { id: String ->
            val current = model.state.value
            val entry = current.entries.singleOrNull { it.id == id }
            check(entry != null && !current.loading && !current.loadFailed && !current.busy &&
                current.pendingEdit == null && current.pendingDelete == null && current.pendingTranslationDraft == null) {
                "Saved word is unavailable for copying"
            }
            copyText(entry.english + " — " + entry.turkish)
        }
    }
    SavedWordsDialog(
        state = state,
        onEditEnglish = model::editEnglish,
        onEditTurkish = model::editTurkish,
        onSave = model::add,
        onReload = model::reload,
        onRequestDelete = model::requestDelete,
        onDelete = model::delete,
        onCancelDelete = model::cancelDelete,
        onDismiss = {
            if (!state.busy) {
                if (prefillTranslation != null) model.dismissTranslationPrefill()
                else if (prefillEnglish != null) model.clearAddDraft()
                model.clearPresentation()
                onDismiss()
            }
        },
        onRequestEdit = model::requestEdit,
        onEditSelectedEnglish = model::editSelectedEnglish,
        onEditSelectedTurkish = model::editSelectedTurkish,
        onSaveEdit = model::saveEdit,
        onCancelEdit = model::cancelEdit,
        onSearch = model::search,
        onSort = model::sort,
        onCopy = copyWord,
        currentState = { model.state.value },
        onUseTranslationDraft = { model.useTranslationDraft(it) },
        onKeepExistingDraft = { model.keepExistingDraft(it) },
    )
}

private data class SavedWordCopyFeedback(val entryId: String, val succeeded: Boolean)

@Composable
internal fun SavedWordsDialog(
    state: SavedWordsState,
    onEditEnglish: (String) -> Unit,
    onEditTurkish: (String) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDismiss: () -> Unit,
    onRequestEdit: (String) -> Unit,
    onEditSelectedEnglish: (String) -> Unit,
    onEditSelectedTurkish: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSearch: (String) -> Unit = {},
    onSort: (SavedWordSort) -> Unit = {},
    onCopy: ((id: String) -> Unit)? = null,
    currentState: (() -> SavedWordsState)? = null,
    practiceRandom: Random = Random.Default,
    onUseTranslationDraft: (TranslatedWordDraft) -> Unit = {},
    onKeepExistingDraft: (TranslatedWordDraft) -> Unit = {},
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var copyFeedback by remember(state.entries) { mutableStateOf<SavedWordCopyFeedback?>(null) }
    val copyEnabled = !state.loading && !state.loadFailed && !state.busy &&
        state.pendingEdit == null && state.pendingDelete == null && state.pendingTranslationDraft == null
    val latestState = rememberUpdatedState(state)
    val latestStateProvider = rememberUpdatedState(currentState)
    var practice by remember { mutableStateOf<SavedWordPractice?>(null) }
    var reverse by remember { mutableStateOf(false) }
    val renderedReverse = reverse
    val availablePracticeEntries = savedWordPracticeEntries(state.entries)
    val practiceEnabled = state.canPractice() && availablePracticeEntries.isNotEmpty()
    val activePractice = if (practiceEnabled) practice?.reconcile(state.entries) else null
    val practiceEntries = activePractice?.cards(state.entries) ?: availablePracticeEntries
    val renderedOrderIds = practiceEntries.map { it.id }
    val renderedExplicitOrder = activePractice?.orderIds?.isNotEmpty() == true
    val practiceWord = activePractice?.current(state.entries)
    SideEffect {
        if (practice != activePractice) practice = activePractice
    }
    fun changePractice(renderedWord: SavedWord, renderedMode: Boolean,
        change: (SavedWordPractice, List<SavedWord>) -> SavedWordPractice?) {
        val current = latestStateProvider.value?.invoke() ?: latestState.value
        if (!current.canPractice()) { practice = null; return }
        val entries = savedWordPracticeEntries(current.entries)
        val normalized = practice?.reconcile(entries)
        practice = if (normalized != null && normalized.current(entries) == renderedWord && reverse == renderedMode &&
            normalized.orderIds.isNotEmpty() == renderedExplicitOrder &&
            normalized.cards(entries).map { it.id } == renderedOrderIds)
            change(normalized, entries)
            else normalized
    }
    AlertDialog(
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text(if (practiceWord != null) "Kelime tekrarı" else "Kaydedilen kelimeler") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("saved-words-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (practiceWord != null) {
                    val position = practiceEntries.indexOfFirst { it.id == practiceWord.id }
                    item {
                        Text("Bu kitaba kaydedilmiş ${practiceEntries.size} kelime. Arama ve sıralama tekrarı değiştirmez.")
                    }
                    listOf(false, true).forEach { direction ->
                        item {
                            val label = if (direction) "Türkçe → İngilizce" else "İngilizce → Türkçe"
                            FilterChip(selected = reverse == direction, onClick = {
                                changePractice(practiceWord, renderedReverse) { current, _ ->
                                    if (reverse != direction) {
                                        reverse = direction
                                        current.copy(revealed = null)
                                    } else current
                                }
                            }, label = { Text(label) }, modifier = Modifier.fillMaxWidth()
                                .testTag(if (direction) "saved-word-practice-direction-turkish"
                                    else "saved-word-practice-direction-english")
                                .semantics { contentDescription = "Tekrar yönü: $label" })
                        }
                    }
                    item {
                        TextButton(onClick = {
                            changePractice(practiceWord, renderedReverse) { current, entries ->
                                current.shuffle(entries, practiceRandom)
                            }
                        }, enabled = practiceEntries.size >= 2,
                            modifier = Modifier.testTag("saved-word-practice-shuffle").semantics {
                                contentDescription = "Kelime tekrarını karıştır"
                            }) {
                            Text("Karıştır")
                        }
                    }
                    if (activePractice.orderIds.isNotEmpty()) item {
                        TextButton(onClick = {
                            changePractice(practiceWord, renderedReverse) { current, entries ->
                                current.inRepositoryOrder(entries)
                            }
                        }, modifier = Modifier.testTag("saved-word-practice-restore-order").semantics {
                            contentDescription = "Kelime tekrarında kayıt sırasına dön"
                        }) {
                            Text("Kayıt sırasına dön")
                        }
                    }
                    item {
                        Text("${position + 1} / ${practiceEntries.size} kelime",
                            modifier = Modifier.testTag("saved-word-practice-count").semantics {
                                contentDescription = "Kelime tekrarı, ${position + 1}. kelime, toplam ${practiceEntries.size}"
                            })
                    }
                    item { Text(if (reverse) "Türkçe" else "İngilizce", style = MaterialTheme.typography.labelMedium) }
                    item {
                        Text(if (reverse) practiceWord.turkish else practiceWord.english,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.testTag(if (reverse) "saved-word-practice-turkish"
                                else "saved-word-practice-english"))
                    }
                    if (activePractice.revealed == practiceWord) {
                        item { Text(if (reverse) "İngilizce kelime" else "Türkçe karşılık",
                            style = MaterialTheme.typography.labelMedium) }
                        item { Text(if (reverse) practiceWord.english else practiceWord.turkish,
                            modifier = Modifier.testTag(if (reverse) "saved-word-practice-english"
                                else "saved-word-practice-turkish")) }
                        item {
                            TextButton(onClick = {
                                changePractice(practiceWord, renderedReverse) { current, _ -> current.copy(revealed = null) }
                            }, modifier = Modifier.testTag("saved-word-practice-hide")) {
                                Text("Karşılığı gizle")
                            }
                        }
                    } else item {
                        TextButton(onClick = {
                            changePractice(practiceWord, renderedReverse) { current, entries -> current.reveal(entries) }
                        }, modifier = Modifier.testTag("saved-word-practice-reveal")) {
                            Text(if (reverse) "İngilizce kelimeyi göster" else "Türkçe karşılığı göster")
                        }
                    }
                    item {
                        TextButton(onClick = {
                            changePractice(practiceWord, renderedReverse) { current, entries -> current.move(entries, -1) }
                        }, enabled = position > 0, modifier = Modifier.testTag("saved-word-practice-previous")) {
                            Text("Önceki kelime")
                        }
                    }
                    item {
                        TextButton(onClick = {
                            changePractice(practiceWord, renderedReverse) { current, entries -> current.move(entries, 1) }
                        }, enabled = position < practiceEntries.lastIndex,
                            modifier = Modifier.testTag("saved-word-practice-next")) {
                            Text("Sonraki kelime")
                        }
                    }
                    item {
                        TextButton(onClick = { practice = null }, modifier = Modifier.testTag("saved-word-practice-return")) {
                            Text("Listeye dön")
                        }
                    }
                } else {
                    item {
                        Text(if (state.translationDraft) "Bu taslak makine çevirisinden alındı. Türkçe karşılığını kontrol et ve gerekirse düzelt. Yalnız Kelimeyi kaydet ile kaydedilir."
                            else "İngilizce kelimeyi ve Türkçe karşılığını kendin gir. Otomatik çeviri yapılmaz.")
                    }
                    state.pendingTranslationDraft?.let { incoming ->
                        item(key = "translation-draft-choice-" + incoming.id) {
                            TranslationDraftChoices(incoming,
                                current = { latestStateProvider.value?.invoke() ?: latestState.value },
                                onUse = onUseTranslationDraft, onKeep = onKeepExistingDraft)
                        }
                    }
                    item {
                        TextButton(onClick = {
                            val current = latestStateProvider.value?.invoke() ?: latestState.value
                            if (current.canPractice()) {
                                reverse = false
                                practice = SavedWordPractice.start(current.entries)
                                sortExpanded = false
                            }
                        }, enabled = practiceEnabled, modifier = Modifier.testTag("saved-word-practice-start")) {
                            Text("Kelime tekrarı")
                        }
                    }
                    if (onCopy != null) item {
                        Text("Kopyala İngilizce ve Türkçe kaydını birlikte kopyalar.")
                    }
                    item {
                        OutlinedTextField(
                            value = state.englishDraft,
                            onValueChange = onEditEnglish,
                            modifier = Modifier.fillMaxWidth().testTag("saved-word-english"),
                            enabled = !state.busy,
                            label = { Text("İngilizce kelime") },
                            singleLine = true,
                            isError = state.englishDraft.isNotEmpty() && !state.englishValid,
                            supportingText = { Text("1–100 karakter") },
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.turkishDraft,
                            onValueChange = onEditTurkish,
                            modifier = Modifier.fillMaxWidth().testTag("saved-word-turkish"),
                            enabled = !state.busy,
                            label = { Text("Türkçe karşılık") },
                            singleLine = true,
                            isError = state.turkishDraft.isNotEmpty() && !state.turkishValid,
                            supportingText = { Text("1–200 karakter") },
                        )
                    }
                    item { Button(onClick = onSave, enabled = state.canSave) { Text("Kelimeyi kaydet") } }
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onSearch,
                            modifier = Modifier.fillMaxWidth().testTag("saved-word-search"),
                            enabled = !state.busy,
                            singleLine = true,
                            label = { Text("Kelime veya karşılık ara") },
                            trailingIcon = {
                                if (state.query.isNotEmpty()) TextButton(onClick = { onSearch("") }) { Text("Temizle") }
                            },
                        )
                    }
                    item {
                        Box {
                            TextButton(
                                onClick = { sortExpanded = true },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("saved-word-sort"),
                            ) { Text("Sıralama: ${state.sort.label()}") }
                            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                SavedWordSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label()) },
                                        onClick = {
                                            onSort(option)
                                            sortExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (state.pendingEdit == null && state.pendingDelete == null && state.pendingTranslationDraft == null) {
                        state.message?.let { message -> item { Text(message) } }
                    }
                    if (state.busy) item { Text("İşlem sürüyor…", style = MaterialTheme.typography.bodySmall) }
                    when {
                        state.loading -> item { CircularProgressIndicator() }
                        state.loadFailed -> {
                            item { Text("Kelimeler yüklenemedi.", color = MaterialTheme.colorScheme.error) }
                            item { TextButton(onClick = onReload, enabled = !state.busy) { Text("Yeniden yükle") } }
                        }
                        state.entries.isEmpty() -> item { Text("Bu kitap için henüz kelime kaydedilmedi.") }
                        state.visibleEntries.isEmpty() -> item { Text("Aramanla eşleşen kelime yok.") }
                        else -> itemsIndexed(state.visibleEntries, key = { _, entry -> entry.id }) { index, entry ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.english, style = MaterialTheme.typography.titleMedium)
                                Text(entry.turkish)
                                onCopy?.let { copy ->
                                    TextButton(onClick = {
                                        if (copyEnabled) {
                                            val succeeded = try {
                                                copy(entry.id)
                                                true
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: RuntimeException) {
                                                false
                                            }
                                            copyFeedback = SavedWordCopyFeedback(entry.id, succeeded)
                                        }
                                    }, enabled = copyEnabled,
                                        modifier = Modifier.testTag("saved-word-copy-${entry.id}").semantics {
                                            contentDescription = "${index + 1}. kelime: ${entry.english}, kopyala"
                                        }) { Text("Kopyala") }
                                }
                                copyFeedback?.takeIf { it.entryId == entry.id }?.let { feedback ->
                                    Text(if (feedback.succeeded) "Kelime kaydı panoya kopyalandı."
                                        else "Kelime kaydı kopyalanamadı. Yeniden deneyebilirsin.",
                                        color = if (feedback.succeeded) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error)
                                }
                                TextButton(
                                    onClick = { onRequestEdit(entry.id) },
                                    enabled = !state.busy,
                                    modifier = Modifier.semantics {
                                        contentDescription = "${index + 1}. kelime: ${entry.english}, düzenle"
                                    },
                                ) { Text("Düzenle") }
                                TextButton(
                                    onClick = { onRequestDelete(entry.id) },
                                    enabled = !state.busy,
                                    modifier = Modifier.semantics {
                                        contentDescription = "${index + 1}. kelime: ${entry.english}, sil"
                                    },
                                ) { Text("Sil") }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Kapat") } },
    )
    state.pendingDelete?.let { entry ->
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = onCancelDelete,
            title = { Text("Kelime silinsin mi?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.english)
                    Text(entry.turkish)
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = onDelete, enabled = !state.busy) { Text("Kelimeyi sil") } },
            dismissButton = { TextButton(onClick = onCancelDelete, enabled = !state.busy) { Text("Vazgeç") } },
        )
    }
    state.pendingEdit?.let {
        AlertDialog(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = onCancelEdit,
            title = { Text("Kelimeyi düzenle") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).testTag("saved-word-edit-content"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        OutlinedTextField(
                            value = state.editEnglishDraft,
                            onValueChange = onEditSelectedEnglish,
                            modifier = Modifier.fillMaxWidth().testTag("saved-word-edit-english"),
                            enabled = !state.busy,
                            label = { Text("İngilizce kelime") },
                            singleLine = true,
                            isError = !state.editEnglishValid,
                            supportingText = { Text("1–100 karakter") },
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.editTurkishDraft,
                            onValueChange = onEditSelectedTurkish,
                            modifier = Modifier.fillMaxWidth().testTag("saved-word-edit-turkish"),
                            enabled = !state.busy,
                            label = { Text("Türkçe karşılık") },
                            singleLine = true,
                            isError = !state.editTurkishValid,
                            supportingText = { Text("1–200 karakter") },
                        )
                    }
                    state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    if (state.busy) item { Text("İşlem sürüyor…", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = onSaveEdit, enabled = state.canUpdate) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = onCancelEdit, enabled = !state.busy) { Text("Vazgeç") } },
        )
    }
}

private fun SavedWordsState.canPractice(): Boolean = !loading && !loadFailed && !busy &&
    pendingEdit == null && pendingDelete == null && pendingTranslationDraft == null

@NonSkippableComposable
@Composable private fun TranslationDraftChoices(draft: TranslatedWordDraft, current: () -> SavedWordsState,
    onUse: (TranslatedWordDraft) -> Unit, onKeep: (TranslatedWordDraft) -> Unit) {
    val liveCurrent = rememberUpdatedState(current)
    key(draft.id) {
        val lease = remember { DraftChoiceLease() }
        DisposableEffect(lease) { onDispose { lease.active = false } }
        fun fresh() = lease.active && liveCurrent.value().let {
            it.pendingTranslationDraft === draft && !it.busy && it.pendingEdit == null && it.pendingDelete == null
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Önceki kaydedilmemiş taslağın korunuyor. Çeviri taslağını kullanırsan iki alan onunla doldurulur.")
            Text("Yeni kelime: " + draft.english)
            Text("Makine çevirisi: " + draft.turkish)
            TextButton({ if (fresh()) onUse(draft) }, Modifier.fillMaxWidth().testTag("translation-draft-use")) {
                Text("Çeviri taslağını kullan", Modifier.fillMaxWidth())
            }
            TextButton({ if (fresh()) onKeep(draft) }, Modifier.fillMaxWidth().testTag("translation-draft-keep")) {
                Text("Önceki taslağımı koru", Modifier.fillMaxWidth())
            }
        }
    }
}
private class DraftChoiceLease { var active = true }

private fun SavedWordSort.label(): String = when (this) {
    SavedWordSort.RECENT -> "Kayıt sırası"
    SavedWordSort.ENGLISH -> "İngilizce A–Z"
    SavedWordSort.TURKISH -> "Türkçe A–Z"
}
