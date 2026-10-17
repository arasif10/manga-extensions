package eu.kanade.tachiyomi.extension.all.imhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * The sort comes first and the write-in boxes come last, so the long lists stay out of the way of
 * the boxes that are ticked most often. Each kind of entry the site files its galleries under has a
 * list of its own, filled with the names its own index puts first and kept A to Z.
 *
 * The kind and the language are read by the site as flags rather than as names, so they are sent as
 * flags on every search, which is also what keeps a search that only ticks boxes working at all.
 *
 * The language list is left out of a source that is already pinned to one language, since the two
 * would ask for different languages at once and answer with nothing.
 */
fun getFilters(isMulti: Boolean, defaultSort: Int): FilterList {
    val filters = mutableListOf<Filter<*>>(
        SortFilter(defaultSort),
        Filter.Separator(),
        TagGroupFilter("Tags", "tag", TAG_VOCABULARY),
        TagGroupFilter("Parodies", "parody", PARODY_VOCABULARY),
        TagGroupFilter("Characters", "character", CHARACTER_VOCABULARY),
        TagGroupFilter("Artists", "artist", ARTIST_VOCABULARY),
        TagGroupFilter("Groups", "group", GROUP_VOCABULARY),
        CategoryFilter(),
    )

    if (isMulti) filters += LanguageFilter()

    filters += Filter.Separator()
    filters += AdvancedFilterGroup()

    return FilterList(*filters.toTypedArray())
}

/** A name the reader asked for: what kind of entry it is, how the site spells it, and whether it is
 * to be kept out of the answer. */
internal class Clause(val kind: String, val value: String, val exclude: Boolean)

/** One tickable option: ticked to search for it, ticked twice to keep it out. */
internal class TriStateName(name: String, val value: String) : Filter.TriState(name)

/**
 * A group of names as tick boxes. The site matches a name itself, so the values are the names as it
 * spells them.
 *
 * A ticked option becomes a search clause and a ticked-off one becomes the same clause with a
 * leading dash, which is how the site keeps a name out without any other box being ticked.
 */
internal open class TagGroupFilter(
    name: String,
    private val kind: String,
    options: List<Pair<String, String>>,
) : Filter.Group<TriStateName>(name, options.map { TriStateName(it.first, it.second) }) {

    val clauses: List<Clause> get() = state.mapNotNull { option ->
        when {
            option.isIncluded() -> Clause(kind, option.value, exclude = false)
            option.isExcluded() -> Clause(kind, option.value, exclude = true)
            else -> null
        }
    }
}

/**
 * Flags for a group of tick boxes: with none of them ticked the site is left to ask for everything
 * it carries, and otherwise the ticked ones are asked for and the rest are kept out.
 */
internal fun List<TriStateName>.flagToggles(): List<Pair<String, String>> {
    val ticked = filter { it.isIncluded() }.map { it.value }

    return map { it.value to if (ticked.isEmpty() || it.value in ticked) "1" else "0" }
}

/** The kinds of work the site carries, each with the flag it is asked with. */
internal class CategoryFilter :
    Filter.Group<TriStateName>(
        "Categories",
        CATEGORY_FLAGS.map { TriStateName(it.first, it.second) },
    ) {
    val flags: List<Pair<String, String>> get() = state.flagToggles()

    companion object {
        val CATEGORY_FLAGS = listOf(
            "Manga" to "m",
            "Doujinshi" to "d",
            "Western" to "w",
            "Image Set" to "i",
            "Artist CG" to "a",
            "Game CG" to "g",
        )
    }
}

/** The languages the site tags its galleries with, each with the flag it is asked with. */
internal class LanguageFilter :
    Filter.Group<TriStateName>(
        "Languages",
        LANGUAGE_FLAGS.map { TriStateName(it.first, it.second) },
    ) {
    val flags: List<Pair<String, String>> get() = state.flagToggles()
}

/**
 * The write-in boxes, kept in a collapsible group at the end of the filter list: a name the long
 * lists above do not carry can be written down instead of hunted for, and a leading dash keeps one
 * out. The first three headers are what the boxes read and what they ask for; the last one is the
 * one thing they cannot do, so the reader is told rather than left guessing.
 */
internal class AdvancedFilterGroup :
    Filter.Group<Filter<*>>(
        "Advanced",
        listOf(
            Filter.Header("Write names that are not listed above, separated by commas (,)."),
            Filter.Header("Prepend a name with a dash (-) to keep it out."),
            Filter.Header("A name kept out is searched on its own, without the term above."),
            TextFilter("Tags", "tag"),
            TextFilter("Parodies", "parody"),
            TextFilter("Characters", "character"),
            TextFilter("Artists", "artist"),
            TextFilter("Groups", "group"),
            Filter.Header("Or pick a gallery the site chooses at random."),
            RandomFilter(),
            Filter.Header("Skips that many result pages, which is how a search carries on."),
            OffsetPageFilter(),
        ),
    )

/**
 * A write-in box. Every name written into it becomes a search clause of its own kind, so
 * `sole female, ahegao` in the Tags box asks for both tags.
 */
internal open class TextFilter(name: String, private val kind: String) : Filter.Text(name) {

    val clauses: List<Clause> get() = state.split(',', ';', '\n').mapNotNull { token ->
        val written = token.trim()
        if (written.isEmpty()) return@mapNotNull null

        val exclude = written.startsWith('-')
        val value = written.removePrefix("-").trim()
        if (value.isEmpty()) return@mapNotNull null

        Clause(kind, value, exclude)
    }
}

/** One gallery the site picks at random, which it answers with. */
internal class RandomFilter : Filter.CheckBox("Random gallery", false)

/** Skips that many result pages, which is how a reader carries on from where a search stopped. */
internal class OffsetPageFilter : Filter.Text("Offset results by # pages")

/** The orders the site lists its galleries in, each with the flag it is asked with. */
internal class SortFilter(default: Int) :
    Filter.Select<String>(
        "Sort by",
        SORTS.map { it.first }.toTypedArray(),
        default,
    ) {
    val selected: String get() = SORTS[state].second

    companion object {
        val SORTS = listOf(
            "Recent" to "lt",
            "Popular" to "pp",
            "Downloads" to "dl",
            "Top Rated" to "tr",
        )

        /** The index a saved preference stands for, falling back to the site's own order. */
        fun indexOf(value: String?): Int = SORTS.indexOfFirst { it.second == value }.coerceAtLeast(0)
    }
}

/** Every language the site's own search can name, with the flag it is asked with. */
internal val LANGUAGE_FLAGS = listOf(
    "English" to "en",
    "Japanese" to "jp",
    "Spanish" to "es",
    "French" to "fr",
    "Korean" to "kr",
    "German" to "de",
    "Russian" to "ru",
)
