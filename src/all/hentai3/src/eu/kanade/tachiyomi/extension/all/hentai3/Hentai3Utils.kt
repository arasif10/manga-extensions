package eu.kanade.tachiyomi.extension.all.hentai3

import java.util.Locale

internal object Hentai3Utils {

    /**
     * The circle a gallery came from, read out of its title.
     *
     * The site writes the artist as a plain search of the name and prints no circle anywhere, so a
     * title's "[Circle (Artist)]" is the only place a circle is named. A bracketed group that names
     * the artist beside another name credits that name as the circle, unless the page already
     * carries it: the series, the characters, the tags and the languages are written into the title
     * as well, and the description lists them in lines of their own.
     */
    fun circleNames(title: String, covered: Set<String>, artists: Set<String>): List<String> {
        val circles = mutableListOf<String>()

        for (pieces in title.titleGroups()) {
            if (!pieces.any { piece -> piece.keys().any { artists.covers(it) } }) continue

            for (piece in pieces) {
                if (piece.keys().any { covered.covers(it) }) continue
                if (circles.any { it.equals(piece, ignoreCase = true) }) continue
                circles += piece
            }
        }

        return circles
    }

    /** The keys a name the page carries is recognised by, the aliases the site lists beside it included. */
    fun keysOf(name: String): List<String> = name.keys()

    /**
     * The words a title is read by, which is how a gallery is told from another: the decorations
     * are dropped, everything is lower case, and the words too short to tell anything apart - the
     * "no", "in" and part numbers of a title - are left out.
     */
    fun titleWords(title: String): Set<String> = buildSet {
        val word = StringBuilder()

        for (char in stripDecorations(title).lowercase()) {
            if (char.isLetterOrDigit()) {
                word.append(char)
            } else {
                if (word.length >= SHORTEST_WORD) add(word.toString())
                word.clear()
            }
        }

        if (word.length >= SHORTEST_WORD) add(word.toString())
    }

    /** How many of the words of one title another writes down, the nearest title counting highest. */
    fun titleOverlap(words: Set<String>, title: String): Int = titleWords(title).count { it in words }

    /** A name written the way the site writes it in a heading, "kuroinu juu" becoming "Kuroinu Juu". */
    fun capitalizeEach(name: String): String = name.split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
    }

    /**
     * A title without its decorations, which the description carries in full instead. The scan is
     * written out rather than left to a pattern because the regex engine on the device rejects some
     * bracket patterns that a desktop build accepts. A title that would be left empty keeps its
     * decorations instead.
     */
    fun stripDecorations(title: String): String {
        val stripped = StringBuilder(title.length)
        var depth = 0

        for (char in title) {
            when {
                char in BRACKET_OPEN -> {
                    if (depth == 0) stripped.append(' ')
                    depth++
                }
                char in BRACKET_CLOSE -> if (depth > 0) depth--
                depth == 0 -> stripped.append(char)
            }
        }

        return stripped.toString().collapseWhitespace().ifEmpty { title.trim() }
    }

    /**
     * Each bracketed group of a title as the names written inside it: "[Circle (Artist)]" is read as
     * two names, so a circle is still known when only its artist is credited by the page.
     */
    private fun String.titleGroups(): List<List<String>> {
        val groups = mutableListOf<List<String>>()
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
                        val pieces = substring(start, index)
                            .split('(', ')', '（', '）')
                            .flatMap { it.split(',', '、', '&', '+') }
                            .map { it.clean() }
                            .filter(String::isNotEmpty)
                        if (pieces.isNotEmpty()) groups += pieces
                    }
                }
            }
        }

        return groups
    }

    /** The names a piece is recognised by, the names written beside it in the same bracket included. */
    private fun String.keys(): List<String> = buildList {
        add(key())
        split('|').forEach { add(it.key()) }
        split(',', '、', '&', '+', '(', ')', '（', '）').forEach { add(it.key()) }
    }.filter(String::isNotEmpty).distinct()

    /** The key a name is compared by: lower case, without the " | " the site separates aliases with. */
    private fun String.key(): String = collapseWhitespace().substringBefore(" | ").trim().lowercase()

    /** The text inside a bracket, with the whitespace and the " + " a title may leave in it tidied. */
    private fun String.clean(): String = collapseWhitespace().trim(' ', '+', '.', '。', '・', '-', '–', '—', '|')

    /**
     * Whether a name the page carries is the name a title writes, the shorter spellings of it - a
     * title's "(Dragon Ball)" for a series the page lists as "Dragon Ball Z" - included. Names
     * shorter than [SHORTEST_MATCH] are left to be matched whole, so "Mix" is not read as "Mix Fry".
     */
    private fun Set<String>.covers(key: String): Boolean = key in this ||
        any { name -> key.length >= SHORTEST_MATCH && name.length >= SHORTEST_MATCH && (name.startsWith(key) || key.startsWith(name)) }

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

    /** The length a name has to reach before a title may write it in a shorter form. */
    private const val SHORTEST_MATCH = 4

    /** The length a word has to reach before it is worth telling one title from another by. */
    private const val SHORTEST_WORD = 3
}
