package com.yaprak.reader.formats.docx

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val DOCX_WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
internal const val DOCX_STRICT_WORD_NS = "http://purl.oclc.org/ooxml/wordprocessingml/main"
internal const val DOCX_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val DOCX_STRICT_REL_NS = "http://purl.oclc.org/ooxml/officeDocument/relationships"
internal const val DOCX_PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
internal const val DOCX_TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
internal const val DOCX_MAIN_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
internal const val DOCX_CORE_NS = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
internal const val DOCX_DC_NS = "http://purl.org/dc/elements/1.1/"

internal data class DocxRelationship(val id: String, val type: String, val target: String, val external: Boolean)
internal data class DocxMetadata(val title: String?, val authors: String?, val language: String)

/** Verified package identities and relationships. Resolves package URIs without fetching or extracting. */
internal class DocxOpc private constructor(val source: DocxPackage, val mainPart: String,
    val main: DocxXmlElement, val metadata: DocxMetadata, private val types: Map<String, String>,
    private val links: Map<String, Map<String, DocxRelationship>>) {
    val wordNamespace get() = main.name.namespace
    val relationshipNamespace get() = if (wordNamespace == DOCX_STRICT_WORD_NS) DOCX_STRICT_REL_NS else DOCX_REL_NS
    fun type(part: String): String = types[docxPartKey(part)] ?: docxFail()
    fun relationships(part: String = ""): Map<String, DocxRelationship> = links[docxPartKey(part)].orEmpty()
    fun relationship(part: String, id: String): DocxRelationship = relationships(part)[id] ?: docxFail()
    fun officePart(role: String, owner: String = mainPart): String? {
        val matches = relationships(owner).values.filter { it.type == "$relationshipNamespace/$role" }
        if (matches.size > 1) docxFail()
        return matches.singleOrNull()?.let { if (it.external) docxFail(DocxFailure.UNSUPPORTED); resolve(owner, it.target) }
    }
    fun resolve(owner: String, target: String): String =
        source.partName(resolveDocxTarget(owner, target)) ?: docxFail()
    suspend fun xml(part: String): DocxXmlElement = DocxXml.read(source.required(part))

    companion object {
        suspend fun read(source: DocxPackage): DocxOpc {
            val caller = currentCoroutineContext(); caller.ensureActive()
            val typesRoot = DocxXml.read(source.required("[Content_Types].xml"))
            if (typesRoot.name != DocxName(DOCX_TYPES_NS, "Types")) docxFail()
            typesRoot.requireElementContent(); typesRoot.onlyAttributes(emptySet())
            val defaults = LinkedHashMap<String, String>(); val overrides = LinkedHashMap<String, String>()
            for (node in typesRoot.elements) {
                caller.ensureActive(); node.requireElementContent()
                if (node.elements.isNotEmpty() || node.name.namespace != DOCX_TYPES_NS) docxFail()
                val mime = node.requiredAttribute("ContentType").also { value ->
                    if (!value.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))) docxFail()
                }.lowercase(Locale.ROOT)
                if (mime.contains("macroenabled") || mime.contains("vbaproject") || mime.contains("activex") ||
                    mime == "application/vnd.openxmlformats-officedocument.oleobject") docxFail(DocxFailure.UNSUPPORTED)
                when (node.name.local) {
                    "Default" -> {
                        node.onlyAttributes(setOf("Extension", "ContentType"))
                        val extension = node.requiredAttribute("Extension").also {
                            if (!it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) docxFail()
                        }.lowercase(Locale.ROOT)
                        if (defaults.put(extension, mime) != null) docxFail()
                    }
                    "Override" -> {
                        node.onlyAttributes(setOf("PartName", "ContentType"))
                        val raw = node.requiredAttribute("PartName")
                        if (!raw.startsWith('/') || raw.endsWith('/')) docxFail()
                        val part = canonicalDocxPart(raw.removePrefix("/"))
                        if (source.partName(part) == null || overrides.put(docxPartKey(part), mime) != null) docxFail()
                    }
                    else -> docxFail()
                }
            }
            val types = LinkedHashMap<String, String>()
            val links = LinkedHashMap<String, Map<String, DocxRelationship>>()
            for (part in source.parts.keys.filter { it != "[Content_Types].xml" }) {
                caller.ensureActive()
                val type = overrides[docxPartKey(part)] ?: defaults[part.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: docxFail()
                types[docxPartKey(part)] = type
                val owner = relationshipOwner(part) ?: continue
                if (type != "application/vnd.openxmlformats-package.relationships+xml") docxFail()
                if (owner.isNotEmpty() && source.partName(owner) == null) docxFail()
                if (links.put(docxPartKey(owner), readRelationships(source.required(part))) != null) docxFail()
            }
            // Every internal relation must name an existing ordinary part. Relationship parts cannot be targets.
            for ((owner, relationships) in links) for (link in relationships.values.filterNot { it.external }) {
                caller.ensureActive()
                val target = source.partName(resolveDocxTarget(owner, link.target)) ?: docxFail()
                if (target == "[Content_Types].xml" || relationshipOwner(target) != null) docxFail()
            }
            val rootLinks = links[""] ?: docxFail()
            val office = rootLinks.values.filter { it.type in setOf("$DOCX_REL_NS/officeDocument", "$DOCX_STRICT_REL_NS/officeDocument") }
                .singleOrNull() ?: docxFail()
            if (office.external) docxFail(DocxFailure.UNSUPPORTED)
            val part = source.partName(resolveDocxTarget("", office.target)) ?: docxFail()
            if (types[docxPartKey(part)] != DOCX_MAIN_TYPE) docxFail(DocxFailure.UNSUPPORTED)
            val main = DocxXml.read(source.required(part))
            val expectedNamespace = if (office.type.startsWith(DOCX_STRICT_REL_NS)) DOCX_STRICT_WORD_NS else DOCX_WORD_NS
            if (main.name != DocxName(expectedNamespace, "document")) docxFail()
            main.requireElementContent()
            if (main.single("body") == null) docxFail()
            val metadataLinks = rootLinks.values.filter { it.type == "$DOCX_PACKAGE_REL_NS/metadata/core-properties" }
            if (metadataLinks.size > 1) docxFail()
            val metadata = metadataLinks.singleOrNull()?.let { link ->
                if (link.external) docxFail(DocxFailure.UNSUPPORTED)
                val target = source.partName(resolveDocxTarget("", link.target)) ?: docxFail()
                if (types[docxPartKey(target)] != "application/vnd.openxmlformats-package.core-properties+xml") docxFail()
                readMetadata(DocxXml.read(source.required(target)))
            } ?: DocxMetadata(null, null, "und")
            caller.ensureActive()
            return DocxOpc(source, part, main, metadata, types.toMap(), links.toMap())
        }

        private suspend fun readRelationships(bytes: ByteArray): Map<String, DocxRelationship> {
            val caller = currentCoroutineContext(); caller.ensureActive()
            val root = DocxXml.read(bytes)
            if (root.name != DocxName(DOCX_PACKAGE_REL_NS, "Relationships")) docxFail()
            root.requireElementContent(); root.onlyAttributes(emptySet())
            val result = LinkedHashMap<String, DocxRelationship>()
            for (node in root.elements) {
                caller.ensureActive()
                if (node.name != DocxName(DOCX_PACKAGE_REL_NS, "Relationship") || node.elements.isNotEmpty()) docxFail()
                node.requireElementContent(); node.onlyAttributes(setOf("Id", "Type", "Target", "TargetMode"))
                val id = node.requiredAttribute("Id").also { if (!it.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,255}"))) docxFail() }
                val type = node.requiredAttribute("Type")
                if (!docxUri(type).isAbsolute || docxUri(type).rawFragment != null) docxFail()
                val target = node.requiredAttribute("Target").also { if (it.length > 8192) docxFail(DocxFailure.LIMIT) }
                val external = when (node.attribute("TargetMode")) {
                    null, "Internal" -> false; "External" -> true; else -> docxFail()
                }
                docxUri(target) // Syntax only for external targets; no network or execution occurs.
                if (result.put(id, DocxRelationship(id, type, target, external)) != null) docxFail()
                if (result.size > 512) docxFail(DocxFailure.LIMIT)
            }
            return result.toMap()
        }

        private fun readMetadata(root: DocxXmlElement): DocxMetadata {
            if (root.name != DocxName(DOCX_CORE_NS, "coreProperties")) docxFail()
            root.requireElementContent()
            fun property(local: String): String? = root.single(local, DOCX_DC_NS)?.let { node ->
                if (node.elements.isNotEmpty()) docxFail()
                node.text().replace(Regex("[\\s\\p{Z}]+"), " ").trim().also {
                    if (it.length > 8192) docxFail(DocxFailure.LIMIT)
                }.takeIf { it.isNotEmpty() }
            }
            return DocxMetadata(property("title"), property("creator"), property("language")?.takeIf {
                it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
            } ?: "und")
        }
    }
}

private fun DocxXmlElement.requiredAttribute(local: String): String = attribute(local)?.takeIf { it.isNotEmpty() } ?: docxFail()
private fun DocxXmlElement.onlyAttributes(names: Set<String>) {
    if (attributes.keys.any { it.namespace.isNotEmpty() || it.local !in names }) docxFail()
}
private fun docxUri(value: String): URI = try { URI(value) } catch (_: Exception) { docxFail() }

/** Part URI (not the .rels URI) is the base. Encoded separators/traversal are never decoded twice. */
internal fun resolveDocxTarget(owner: String, target: String): String {
    if (target.isBlank() || '\\' in target || target.any { it.isISOControl() }) docxFail()
    val reference = docxUri(target)
    if (reference.isAbsolute || reference.rawAuthority != null || reference.rawQuery != null || reference.rawFragment != null)
        docxFail()
    val base = URI("docx", null, "/$owner", null)
    val resolved = base.resolve(reference).normalize()
    val path = resolved.rawPath ?: docxFail()
    if (resolved.scheme != "docx" || resolved.rawAuthority != null || !path.startsWith('/') || path.endsWith('/')) docxFail()
    val result = canonicalDocxPart(path.removePrefix("/"))
    if (result == "[Content_Types].xml") docxFail()
    return result
}

private fun relationshipOwner(part: String): String? {
    if (docxPartKey(part) == "_rels/.rels") return ""
    val segments = part.split('/')
    if (segments.size < 2 || docxPartKey(segments[segments.lastIndex - 1]) != "_rels" ||
        !docxPartKey(segments.last()).endsWith(".rels")) return null
    val name = segments.last().dropLast(5)
    if (name.isEmpty()) docxFail()
    return (segments.dropLast(2) + name).joinToString("/")
}
