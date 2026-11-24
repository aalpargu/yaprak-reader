package com.yaprak.reader.feature.assistant

import com.yaprak.reader.domain.search.limitLibraryTextQuery
import java.util.Locale

private val stopWords = setOf(
    // Turkish question and filler words
    "acaba", "ama", "ancak", "bana", "ben", "bir", "biri", "birisi", "biz", "bu", "bunu", "bunun", "buna", "burada",
    "çok", "daha", "de", "da", "değil", "diye", "en", "gibi", "göre", "hangi", "hangisi", "hem", "hep", "her", "hiç",
    "ile", "için", "ise", "kadar", "kim", "kimdir", "kimin", "mi", "mı", "mu", "mü", "midir", "mıdır", "nasıl",
    "ne", "neden", "nedir", "nerede", "nereden", "neler", "nelerdir", "niçin", "niye", "o", "olan", "olarak", "olur",
    "onu", "onun", "ona", "orada", "sen", "siz", "şey", "şu", "şunu", "üzerine", "var", "veya", "ve", "ya", "yani",
    "yok", "anlat", "anlatır", "açıkla", "kitap", "kitapta", "kitabın", "bölüm", "bölümde", "geçiyor", "geçer",
    "geçmişti", "oldu", "olmuştu", "yaptı", "yapıyor", "neydi", "nedenle",
    // English
    "the", "and", "for", "with", "what", "who", "whom", "why", "how", "when", "where", "which", "does", "did",
    "was", "were", "are", "this", "that", "these", "those", "about", "from", "into", "book", "chapter", "explain",
)

/**
 * Search terms for a natural-language question, most specific first. The book search matches every
 * term, so later candidates drop the shortest terms until something is found.
 */
internal fun questionKeywordCandidates(question: String): List<String> {
    val locale = Locale.forLanguageTag("tr")
    val terms = Regex("[\\p{L}\\p{N}]+").findAll(question.lowercase(locale)).map { it.value }
        .filter { it.length >= 3 && it !in stopWords }
        .distinct().toList()
    if (terms.isEmpty()) return emptyList()
    val ranked = terms.sortedByDescending { it.length }.take(5)
    val candidates = LinkedHashSet<String>()
    for (size in minOf(4, ranked.size) downTo 2) candidates += ranked.take(size).joinToString(" ")
    ranked.forEach { candidates += it }
    return candidates.map(::limitLibraryTextQuery).filter { it.isNotBlank() }.distinct()
}
