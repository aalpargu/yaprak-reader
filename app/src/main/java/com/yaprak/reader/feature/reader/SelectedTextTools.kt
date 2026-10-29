package com.yaprak.reader.feature.reader

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val MAX_OUTBOUND_SELECTED_TEXT = 5000
internal const val MAX_TEXT_PROCESSORS = 32
private const val MAX_DISCOVERED_TEXT_ACTIVITIES = 128

internal data class SelectedTextTool(val packageName: String, val activityName: String, val label: String)
internal enum class SelectedTextToolFailure { INVALID_TEXT, NO_HANDLER, DENIED, SOURCE, LIMIT }
internal class SelectedTextToolException(val reason: SelectedTextToolFailure) : Exception(reason.name)
internal data class SelectedTextToolsState(val requestId: String, val loading: Boolean = true,
    val tools: List<SelectedTextTool> = emptyList(), val discoveryFailure: SelectedTextToolFailure? = null,
    val launchFailure: SelectedTextToolFailure? = null)

/** Android boundary; discovery sends no selected text and launch is always an explicit action. */
internal class AndroidSelectedTextTools(private val context: Context) {
    private val packages get() = context.packageManager

    suspend fun discover(): List<SelectedTextTool> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val matches = try { packages.queryIntentActivities(processIntent(), 0) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw SelectedTextToolException(SelectedTextToolFailure.SOURCE) }
        if (matches.size > MAX_DISCOVERED_TEXT_ACTIVITIES) throw SelectedTextToolException(SelectedTextToolFailure.LIMIT)
        val found = LinkedHashMap<ComponentName, SelectedTextTool>()
        for (match in matches) {
            currentCoroutineContext().ensureActive()
            val info = match.activityInfo ?: continue
            if (!accessible(info)) continue
            val label = try { match.loadLabel(packages)?.toString() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null } ?: continue
            if (label.isBlank() || label.length > 200 || !wellFormedSelectedText(label)) continue
            found[ComponentName(info.packageName, info.name)] = SelectedTextTool(info.packageName, info.name, label)
            if (found.size > MAX_TEXT_PROCESSORS) throw SelectedTextToolException(SelectedTextToolFailure.LIMIT)
        }
        currentCoroutineContext().ensureActive()
        Collections.unmodifiableList(found.values.sortedWith(compareBy<SelectedTextTool> { it.label }
            .thenBy { it.packageName }.thenBy { it.activityName }))
    }

    fun searchWeb(text: String) = launch(selectedTextWebIntent(text))

    fun process(tool: SelectedTextTool, text: String) {
        validateOutboundSelectedText(text)
        val component = ComponentName(tool.packageName, tool.activityName)
        val current = try { packages.getActivityInfo(component, 0) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PackageManager.NameNotFoundException) { throw SelectedTextToolException(SelectedTextToolFailure.NO_HANDLER) }
            catch (_: Exception) { throw SelectedTextToolException(SelectedTextToolFailure.SOURCE) }
        if (!accessible(current)) throw SelectedTextToolException(SelectedTextToolFailure.DENIED)
        val intent = selectedTextProcessIntent(tool, text)
        val stillHandles = try { packages.queryIntentActivities(processIntent().setComponent(component), 0)
            .any { it.activityInfo?.packageName == tool.packageName && it.activityInfo?.name == tool.activityName } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw SelectedTextToolException(SelectedTextToolFailure.SOURCE) }
        if (!stillHandles) throw SelectedTextToolException(SelectedTextToolFailure.NO_HANDLER)
        launch(intent)
    }

    private fun accessible(info: ActivityInfo): Boolean = info.packageName != context.packageName &&
        !info.packageName.isNullOrBlank() && !info.name.isNullOrBlank() && info.enabled && info.exported &&
        info.applicationInfo?.enabled == true && (info.permission.isNullOrEmpty() ||
            context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)

    private fun launch(intent: Intent) {
        try { context.startActivity(intent) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ActivityNotFoundException) { throw SelectedTextToolException(SelectedTextToolFailure.NO_HANDLER) }
        catch (_: SecurityException) { throw SelectedTextToolException(SelectedTextToolFailure.DENIED) }
        catch (_: Exception) { throw SelectedTextToolException(SelectedTextToolFailure.SOURCE) }
    }
}

private fun processIntent() = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")

internal fun selectedTextWebIntent(text: String): Intent {
    validateOutboundSelectedText(text)
    return Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text)
}

internal fun selectedTextProcessIntent(tool: SelectedTextTool, text: String): Intent {
    validateOutboundSelectedText(text)
    return processIntent().setComponent(ComponentName(tool.packageName, tool.activityName))
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
}

private fun validateOutboundSelectedText(text: String) {
    if (text.isBlank() || text.length > MAX_OUTBOUND_SELECTED_TEXT || !wellFormedSelectedText(text))
        throw SelectedTextToolException(SelectedTextToolFailure.INVALID_TEXT)
}

private fun wellFormedSelectedText(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\u0000' || character.isLowSurrogate()) return false
        if (character.isHighSurrogate()) {
            if (++index >= text.length || !text[index].isLowSurrogate()) return false
        }
        index++
    }
    return true
}
