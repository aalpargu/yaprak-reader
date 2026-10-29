package com.yaprak.reader.formats.epub

import com.yaprak.reader.reader.SelectedText
import com.yaprak.reader.reader.SelectionRangeCandidate
import com.yaprak.reader.reader.SourceRangeAnchor
import com.yaprak.reader.reader.SourceTextAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** IO may outlive a selected range. Only the same current native/document proof can be returned. */
internal object ExactSelectionCapture {
    suspend fun capture(
        selected: SelectedText,
        href: String,
        current: () -> Boolean,
        evaluate: suspend (String) -> String?,
        singleNodeScript: String,
        rangeScript: String,
        identityScript: String,
        verifySingle: suspend (SelectionRangeCandidate, String) -> SourceTextAnchor?,
        verifyRange: suspend (SelectionRangeV2Candidate) -> SourceRangeAnchor?,
        sameNativeSelection: suspend () -> Boolean,
    ): SelectedText {
        suspend fun identity(): SelectionRangeIdentity? = safely {
            if (!current() || identityScript.isBlank()) null else
                SelectionRangeV2CandidateDecoder.decodeIdentity(evaluate(identityScript), href, selected.text).takeIf { current() }
        }
        suspend fun range(): SelectionRangeV2Candidate? = safely {
            if (!current() || rangeScript.isBlank()) null else
                SelectionRangeV2CandidateDecoder.decode(evaluate(rangeScript), href, selected.text).takeIf { current() }
        }
        suspend fun single(): SelectionRangeCandidate? = safely {
            if (!current() || singleNodeScript.isBlank()) null else
                SelectionRangeCandidateDecoder.decode(evaluate(singleNodeScript), href, selected.text).takeIf { current() }
        }
        val initialIdentity = identity() ?: return selected
        val candidateRange = range().takeIf { it?.selectionToken == initialIdentity.selectionToken }
        // Preserve the old identity of independently proven unique one-node selections.
        // Reopening an existing v1 annotation must not create the same source range in v2.
        if (candidateRange == null || candidateRange.segments.size == 1) {
            val candidate = single()
            val anchor = if (candidate == null) null else safely { verifySingle(candidate, selected.text) }
            if (!current()) return selected
            if (anchor != null) {
                if (safely { sameNativeSelection() } != true || !current()) return selected
                val candidateAgain = single()
                val rangeAgain = if (candidateRange == null) null else range()
                val identityAgain = identity()
                currentCoroutineContext().ensureActive()
                return if (current() && candidateAgain == candidate &&
                    (candidateRange == null || rangeAgain == candidateRange) && identityAgain == initialIdentity)
                    selected.copy(rangeCandidate = candidate, sourceAnchor = anchor) else selected
            }
        }
        // Repeated full source node values cannot use v1's uniqueness proof. A matching
        // complete typed body can still prove their exact v2 source path.
        val rangeAnchor = if (candidateRange == null) null else safely { verifyRange(candidateRange) }
        if (rangeAnchor == null || !current()) return selected
        if (safely { sameNativeSelection() } != true || !current()) return selected
        val rangeAgain = range()
        val identityAgain = identity()
        currentCoroutineContext().ensureActive()
        return if (current() && rangeAgain == candidateRange && identityAgain == initialIdentity)
            selected.copy(sourceAnchor = rangeAnchor) else selected
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        currentCoroutineContext().ensureActive()
        block().also { currentCoroutineContext().ensureActive() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
