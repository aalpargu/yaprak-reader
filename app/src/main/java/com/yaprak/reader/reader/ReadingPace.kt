package com.yaprak.reader.reader

import kotlinx.coroutines.flow.StateFlow

/** Approximate book position mapped from a proven live engine point. Never coverage or completion proof. */
data class ReadingPaceObservation(
    val revision: Long,
    val totalProgression: Double?,
    val observedAtMillis: Long,
    /** Proof of the visible native resource progression and its actual SDK catalogue extent. */
    val resourceHref: String? = null,
    val resourceProgression: Double? = null,
    val rangeStart: Double? = null,
    val rangeEnd: Double? = null,
    /** Actual unique unfragmented leaf TOC entry with proven next-resource/EOF end; never a file-as-chapter guess. */
    val chapterEntryId: String? = null,
)

/** Optional: only an adapter which can prove the currently visible position supplies this. */
interface ReadingPaceSource {
    val observations: StateFlow<ReadingPaceObservation>
}
