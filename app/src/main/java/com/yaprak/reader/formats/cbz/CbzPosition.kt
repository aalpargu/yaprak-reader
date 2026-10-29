package com.yaprak.reader.formats.cbz

import com.yaprak.reader.domain.reader.ReadingLocation
import org.json.JSONObject
import com.yaprak.reader.domain.library.BookFormat

/** Actual original image visits, never a PDF page or original EPUB text observation. */
internal object CbzPosition {
    fun encode(index: Int, archive: CbzArchive, format: BookFormat = BookFormat.CBZ): ReadingLocation {
        require(format.comic && index in archive.pages.indices)
        val tag = format.extension
        val name = archive.pages[index].entry.name
        return ReadingLocation(JSONObject().put("format", tag).put("version", 1)
            .put("pageIndex", index).put("entry", name).toString(), "$tag/$name")
    }
    fun decode(location: ReadingLocation?, archive: CbzArchive, format: BookFormat = BookFormat.CBZ): Int? {
        if (!format.comic) return null
        val tag = format.extension
        if (location == null || location.locatorVersion != 1 || location.locatorJson.length > 4096) return null
        return runCatching {
            val json = JSONObject(location.locatorJson)
            if (json.keys().asSequence().toSet() != setOf("format", "version", "pageIndex", "entry") ||
                json.opt("format") != tag || json.opt("version") != 1) return null
            val index = json.opt("pageIndex") as? Int ?: return null
            val name = json.opt("entry") as? String ?: return null
            index.takeIf { it in archive.pages.indices && name == archive.pages[it].entry.name && location.resourceHref == "$tag/$name" }
        }.getOrNull()
    }
}
