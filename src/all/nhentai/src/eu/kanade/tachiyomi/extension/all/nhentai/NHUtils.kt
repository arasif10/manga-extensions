package eu.kanade.tachiyomi.extension.all.nhentai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NHUtils {

    fun getArtists(data: Hentai): String = data.tags.filter { it.type == "artist" }
        .joinToString { it.name }

    fun getGroups(data: Hentai): String? = data.tags.filter { it.type == "group" }
        .joinToString { it.name }
        .takeIf { it.isNotBlank() }

    /**
     * Every entry the site's own page lists beside the tags: who made it, in what language it was
     * scanned, and what kind of work it is. The tags themselves are shown as genres instead.
     */
    fun getTagDescription(data: Hentai): String {
        val tags = data.tags.groupBy { it.type }
        return buildString {
            tags["artist"]?.joinToString { it.name }?.let { append("Artists: ", it, "\n") }
            tags["group"]?.joinToString { it.name }?.let { append("Groups: ", it, "\n") }
            tags["language"]?.joinToString { it.name }?.let { append("Languages: ", it, "\n") }
            tags["category"]?.joinToString { it.name }?.let { append("Categories: ", it, "\n") }
            tags["parody"]?.joinToString { it.name }?.let { append("Parodies: ", it, "\n") }
            tags["character"]?.joinToString { it.name }?.let { append("Characters: ", it, "\n") }
            data.scanlator.takeIf(String::isNotBlank)?.let { append("Scanlator: ", it.trim(), "\n") }
        }
    }

    /**
     * Whatever the title says that the lines above do not. A gallery's title carries its own
     * decorations - the event it was published for, the circle and artist, the language, the
     * scanlator and the edition - and the reader is shown a clean title, so a decoration the tag
     * lines do not already name is listed here rather than being dropped. The site leaves its own
     * scanlator field empty on nearly every gallery, which is why the title is usually the only
     * place a translation group's name survives.
     */
    fun getTitleNotes(data: Hentai): String? {
        val covered = data.tags.filter { it.type in COVERED_TYPES }.map { it.name.key() }.toSet()
        val scanlator = data.scanlator.key()

        // The Japanese title mirrors the English one, decorations included, so only one is read.
        val title = data.title.english?.takeIf(String::isNotBlank) ?: data.title.japanese.orEmpty()

        val notes = mutableListOf<String>()
        for (group in title.titleGroups()) {
            if (group.text.isEmpty() || group.keys.any { it in covered || it in LANGUAGE_NAMES }) continue

            val note = EDITION_ALIASES[group.text.key()] ?: group.text
            val key = note.key()
            if (key in covered || key in LANGUAGE_NAMES || key == scanlator) continue
            if (notes.none { it.equals(note, ignoreCase = true) }) notes += note
        }

        return notes.takeIf(List<String>::isNotEmpty)?.joinToString()
    }

    /** The day the gallery was put on the site. */
    fun formatUploadDate(uploadedAt: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(uploadedAt))

    fun getTags(data: Hentai): String = data.tags.filter { it.type == "tag" }
        .map { it.name }
        .sorted()
        .joinToString()

    /**
     * A title carries its own decorations - the event a doujinshi came from, the circle and artist,
     * the language and the scanlator - and the description lists every one of them, so the title is
     * shown without them. The scan is written out rather than left to a pattern because the regex
     * engine on the device rejects some bracket patterns that a desktop build accepts. A title that
     * would be left empty keeps its decorations instead.
     */
    fun String.shortenTitle(): String {
        val stripped = StringBuilder(length)
        var depth = 0
        for (char in this) {
            when {
                char in BRACKET_OPEN -> {
                    if (depth == 0) stripped.append(' ')
                    depth++
                }
                char in BRACKET_CLOSE -> if (depth > 0) depth--
                depth == 0 -> stripped.append(char)
            }
        }
        return stripped.toString().collapseWhitespace().ifEmpty { this }
    }

    /**
     * Each bracketed group of a title, with every name it can be recognised by: the text of the
     * group, the credits written inside it and the groups nested in it, so a circle that names its
     * artist - "[Circle (Artist)]" - is known as soon as any one of those names is tagged.
     */
    private fun String.titleGroups(): List<TitleGroup> {
        val groups = mutableListOf<TitleGroup>()
        var depth = 0
        var start = 0
        forEachIndexed { index, char ->
            when {
                char in BRACKET_OPEN -> {
                    if (depth == 0) start = index + 1
                    depth++
                }
                char in BRACKET_CLOSE && depth > 0 -> {
                    depth--
                    if (depth == 0) {
                        val inner = substring(start, index)
                        groups += TitleGroup(inner.cleanSegment(), inner.creditKeys() + inner.titleGroups().flatMap { it.keys })
                    }
                }
            }
        }
        return groups
    }

    private class TitleGroup(val text: String, val keys: List<String>)

    /**
     * The key a name is compared by: lower case, and without the note the site adds to some tags
     * ("japanese | definition revised please read the wiki").
     */
    private fun String.key(): String = substringBefore(" | ").trim().lowercase()

    /** The text inside a bracket, with the whitespace and the "+" a title may leave in it tidied. */
    private fun String.cleanSegment(): String = collapseWhitespace().trim(' ', '+', '.', '。', '・', '-', '–', '—', '|')

    /**
     * The names a credit can be recognised by, the ones it lists side by side included: the circle
     * and its artist are usually written together as "[Bad Mushrooms (Chicke III, 4why)]".
     */
    private fun String.creditKeys(): List<String> = listOf(this)
        .plus(split(',', '、', '&', '+', '(', ')', '（', '）'))
        .map { it.cleanSegment().key() }
        .filter(String::isNotEmpty)

    /** Runs of spaces, tabs and newlines, including the ones a dropped bracket left behind. */
    private fun String.collapseWhitespace(): String {
        val out = StringBuilder(length)
        var pendingSpace = false
        for (char in this) {
            if (char.isWhitespace()) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) {
                    out.append(' ')
                    pendingSpace = false
                }
                out.append(char)
            }
        }
        return out.toString()
    }

    /** The brackets a title decorates itself with, the ones Japanese titles use included. */
    private const val BRACKET_OPEN = "[({（【"
    private const val BRACKET_CLOSE = "])}）】"

    /** The kinds of tag the description prints, so a decoration repeating one is not repeated. */
    private val COVERED_TYPES = setOf("artist", "group", "language", "category", "parody", "character", "tag")

    /** Brackets that only name a language, which the Languages line already carries. */
    private val LANGUAGE_NAMES = setOf(
        "english", "translated", "japanese", "chinese", "korean", "spanish", "french", "german",
        "portuguese", "italian", "russian", "thai", "vietnamese", "indonesian", "polish", "turkish",
        "arabic", "dutch", "hungarian", "czech", "swedish", "romanian", "catalan", "greek", "hebrew",
        "hindi", "persian", "bulgarian", "croatian", "danish", "finnish", "norwegian", "serbian",
        "slovak", "slovenian", "ukrainian", "filipino", "tagalog", "esperanto", "mongolian",
        "英訳", "英語", "日本語", "日语", "英语", "中国語", "中文", "漢化", "汉化", "翻譯", "翻译",
        "한국어", "국역", "번역",
    )

    /** Title markers that read better spelled out, with the Japanese forms that mean the same. */
    private val EDITION_ALIASES = mapOf(
        "dl版" to "Digital",
        "デジタル版" to "Digital",
        "digital" to "Digital",
        "full color" to "Full Color",
        "full color-ban" to "Full Color",
        "フルカラー" to "Full Color",
        "フルカラー版" to "Full Color",
        "無修正" to "Decensored",
        "decensored" to "Decensored",
        "uncensored" to "Uncensored",
        "前編" to "Part 1",
        "後編" to "Part 2",
        "上巻" to "Volume 1",
        "下巻" to "Volume 2",
    )
}
