package com.yaprak.reader.feature.librarylabels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaprak.reader.domain.library.*

private fun LibraryLabelKind.title() = if (this == LibraryLabelKind.TAG) "Etiket" else "Koleksiyon"
@Composable internal fun LibraryLabelsRoute(model: LibraryLabelsViewModel, onBack: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val token = remember(model, lifecycle) { Any() }
    DisposableEffect(model, lifecycle) {
        model.attach(token)
        val observer = LifecycleEventObserver { _, _ -> model.active(token, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer); model.active(token, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); model.detach(token) }
    }
    val state by model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    fun allowed() = model.isActive(token) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    BackHandler(enabled = state.editor != null || state.saving) {
        if (allowed()) state.editor?.let { model.dismiss(token, it.id, state.revision) }
    }
    val entries = state.snapshot?.entries(state.kind).orEmpty()
    val terms = libraryLabelKey(state.query).split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
    val matches = remember(entries, terms) { entries.filter { entry -> terms.all(libraryLabelKey(entry.name)::contains) } }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).testTag("library-labels"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Etiket ve koleksiyonlar", style = MaterialTheme.typography.headlineMedium) }
            item { Text("Bir adı tüm ilgili kitaplarda değiştir veya kitaplardan kaldır. Aynı kabul edilen adlar birlikte yönetilir.") }
            item { Row {
                for (kind in LibraryLabelKind.entries) TextButton(
                    onClick = { if (allowed()) model.kind(token, state.revision, kind) },
                    enabled = state.active && !state.saving && state.editor == null) { Text(kind.title()) }
            } }
            item { OutlinedTextField(state.query, { if (allowed()) model.query(token, state.revision, it) },
                label = { Text("Ad ara") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("label-search"),
                enabled = state.active && !state.saving && state.editor == null) }
            when {
                state.loading -> item { Column { CircularProgressIndicator(); Text("Liste yükleniyor…") } }
                state.failure != null -> item { Column {
                    Text(if (state.failure == LibraryLabelFailure.LIMIT) "Liste bu ekranda yönetilemeyecek kadar büyük. Kitap düzenleyicisini kullanabilirsin."
                        else "Etiket ve koleksiyonlar okunamadı.")
                    TextButton({ if (allowed()) model.retry(token, state.revision) }) { Text("Yeniden yükle") }
                } }
                else -> {
                    item { Column {
                        Text(state.kind.title() + " eşleşmesi: " + matches.size)
                        if (matches.size > 200) Text("İlk 200 sonuç gösteriliyor. Ad arayarak daraltabilirsin.")
                        if (entries.isEmpty()) Text("Henüz kayıt yok. Kitap düzenleyicisinden ekleyebilirsin.")
                    } }
                    items(matches.take(200), key = { libraryLabelKey(it.name) }) { entry ->
                        OutlinedButton({ if (allowed()) model.open(token, state.revision, entry.name) },
                            enabled = state.active && !state.saving && state.editor == null,
                            modifier = Modifier.fillMaxWidth().testTag("label-" + libraryLabelKey(entry.name))) {
                            Column { Text(entry.name); Text(entry.bookCount.toString() + " kitap") }
                        }
                    }
                }
            }
            state.changedBooks?.let { count -> item { Text(count.toString() + " kitabın kaydı güncellendi.") } }
            item { TextButton({ if (allowed() && !state.saving && state.editor == null) onBack() }) { Text("Kütüphaneye dön") } }
        }
    }
    state.editor?.let { draft ->
        val target = state.target(draft)
        AlertDialog(onDismissRequest = { if (allowed()) model.dismiss(token, draft.id, state.revision) },
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false), title = { Text(draft.kind.title() + " düzenle") },
            text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("label-editor"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Column { Text(draft.source); Text("İlgili kitap: " + draft.expected.size) } }
                item { TextButton({ if (allowed()) model.edit(token, draft.id, state.revision, remove = false) },
                    enabled = !state.saving) { Text(if (!draft.remove) "✓ Yeniden adlandır" else "Yeniden adlandır") } }
                item { TextButton({ if (allowed()) model.edit(token, draft.id, state.revision, remove = true) },
                    enabled = !state.saving) { Text(if (draft.remove) "✓ Kitaplardan kaldır" else "Kitaplardan kaldır") } }
                if (!draft.remove) item { OutlinedTextField(draft.target,
                    { if (allowed()) model.edit(token, draft.id, state.revision, target = it) }, label = { Text("Yeni ad") },
                    singleLine = true, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag("label-new-name")) }
                item { Column {
                    if (draft.remove) Text("Bu işlem " + draft.expected.size + " kitaptaki bu adı kaldırır.")
                    else if (target != null) {
                        Text("Yeni ad: " + target)
                        Text("Aynı kabul edilen mevcut adlarla birleşir.")
                    } else Text("1–100 karakterlik, virgül veya satır sonu içermeyen bir ad gir.")
                    if (!state.fresh(draft)) Text("Kayıtlar değişti veya henüz yüklenmedi. Liste güncellendikten sonra işlemi yeniden aç.")
                    if (state.writeFailure != null) Text(if (state.writeFailure == LibraryLabelFailure.CONFLICT)
                        "Kayıtlar değişti. İşlemi yeniden açarak güncel kitapları kontrol et." else "Değişiklik kaydedilemedi. Yeniden deneyebilirsin.")
                } }
                if (state.failure != null) item { TextButton({ if (allowed()) model.retry(token, state.revision) }) { Text("Verileri yeniden yükle") } }
            } },
            confirmButton = { TextButton({ if (allowed()) model.save(token, draft.id, state.revision) },
                enabled = state.canSave(draft), modifier = Modifier.testTag("label-apply")) { Text(if (state.saving) "Kaydediliyor…" else "Uygula") } },
            dismissButton = { TextButton({ if (allowed()) model.dismiss(token, draft.id, state.revision) },
                enabled = !state.saving) { Text("Vazgeç") } })
    }
}
