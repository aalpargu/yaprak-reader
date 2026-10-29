package com.yaprak.reader.data

import com.yaprak.reader.domain.library.BookOrganization
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BookOrganizationNormalizationTest {
    @Test fun `normalization trims blanks and preserves first Turkish insensitive spelling in any locale`() {
        val prior = Locale.getDefault()
        try {
            val input = BookOrganization(series = "  ", genre = " Teknik ",
                tags = listOf(" IŞIK ", "ışık", "İşİk", "", " Öğrenme ", "öğrenme"),
                collections = listOf(" Okul ", "OKUL", "  "))
            val expected = BookOrganization(genre = "Teknik", tags = listOf("IŞIK", "Öğrenme"),
                collections = listOf("Okul"))
            listOf(Locale.US, Locale.forLanguageTag("tr-TR")).forEach { locale ->
                Locale.setDefault(locale)
                assertEquals(expected, input.normalized())
                assertEquals(expected, expected.normalized())
            }
        } finally { Locale.setDefault(prior) }
    }

    @Test fun `exact bounds succeed and duplicates do not exhaust the label count`() {
        val input = BookOrganization(series = "s".repeat(200), genre = "g".repeat(200),
            tags = List(50) { "Etiket $it" }, collections = List(60) { "a".repeat(100) })
        val result = input.normalized()
        assertEquals(50, result.tags.size)
        assertEquals(listOf("a".repeat(100)), result.collections)
    }

    @Test fun `oversized metadata and control characters are rejected without truncation`() {
        listOf(BookOrganization(series = "a".repeat(201)), BookOrganization(genre = "one\ntwo"),
            BookOrganization(tags = listOf("a".repeat(101))),
            BookOrganization(tags = List(51) { "Tag $it" }),
            BookOrganization(collections = listOf("one\u0000two")))
            .forEach { assertTrue(runCatching { it.normalized() }.exceptionOrNull() is IllegalArgumentException) }
    }

    @Test fun `comma is a label separator but remains valid in series and genre`() {
        assertEquals("Bir, iki", BookOrganization(series = " Bir, iki ", genre = "Teknik, kuram")
            .normalized().series)
        listOf(BookOrganization(tags = listOf("one,two")),
            BookOrganization(collections = listOf("one, two")))
            .forEach { assertTrue(runCatching { it.normalized() }.exceptionOrNull() is IllegalArgumentException) }
    }
}
