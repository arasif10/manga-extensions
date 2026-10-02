package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * The sort comes first and the write-in boxes come last, so the long lists stay out of the way of
 * the boxes that are ticked most often. Each kind of entry the site sorts its works by has a list
 * of its own, filled with the entries its own index puts first and kept A to Z.
 *
 * The language list is left out of a source that is already pinned to one language, since the two
 * would ask for different languages at once and answer with nothing.
 */
fun getFilters(isMulti: Boolean, defaultSort: Int): FilterList {
    val filters = mutableListOf<Filter<*>>(
        SortFilter(defaultSort),
        Filter.Separator(),
        TagGroupFilter("Tags", "tag", TAG_VOCABULARY),
        TagGroupFilter("Artists", "artist", ARTIST_VOCABULARY),
        TagGroupFilter("Groups", "group", GROUP_VOCABULARY),
        TagGroupFilter("Parodies", "parody", PARODY_VOCABULARY),
        TagGroupFilter("Characters", "character", CHARACTER_VOCABULARY),
        TagGroupFilter("Categories", "category", CATEGORY_VOCABULARY),
    )

    if (isMulti) filters += TagGroupFilter("Languages", "language", LANGUAGE_VOCABULARY)

    filters += Filter.Separator()
    filters += AdvancedFilterGroup()

    return FilterList(*filters.toTypedArray())
}

/** One tickable option: ticked to search for it, ticked twice to keep it out. */
internal class TriStateTag(name: String, val value: String) : Filter.TriState(name)

/**
 * A group of tags as tick boxes. The site matches tag names itself, so the values are the names as
 * it spells them.
 *
 * A ticked option becomes a search clause and a ticked-off one becomes the same clause with a
 * leading dash, which is how the site keeps a tag out without any other box being ticked.
 */
internal open class TagGroupFilter(
    name: String,
    private val category: String,
    options: List<Pair<String, String>>,
) : Filter.Group<TriStateTag>(name, options.map { TriStateTag(it.first, it.second) }) {

    val clauses: List<String> get() = state.mapNotNull { option ->
        when {
            option.isIncluded() -> clause(option.value, exclude = false)
            option.isExcluded() -> clause(option.value, exclude = true)
            else -> null
        }
    }

    /** `tag:"sole female"`, or `-tag:"sole male"` when the option is ticked off. */
    private fun clause(value: String, exclude: Boolean) = "${if (exclude) "-" else ""}$category:\"$value\""
}

/**
 * The write-in boxes, kept in a collapsible group at the end of the filter list: a tag, parody,
 * character, artist or group that the long lists above do not carry can be written down instead of
 * hunted for, and a leading dash keeps one out.
 */
internal class AdvancedFilterGroup :
    Filter.Group<Filter<*>>(
        "Advanced",
        listOf(
            Filter.Header("Write names that are not listed above, separated by commas (,)."),
            Filter.Header("Prepend a name with a dash (-) to keep it out."),
            TextFilter("Tags", "tag"),
            TextFilter("Parodies", "parody"),
            TextFilter("Characters", "character"),
            TextFilter("Artists", "artist"),
            TextFilter("Groups", "group"),
            TextFilter("Languages", "language"),
            Filter.Header("Filter by pages, for example: >20"),
            TextFilter("Pages", "pages", quoted = false),
            Filter.Header("Filter by upload date, units are h, d, w, m, y. Example: >20d"),
            TextFilter("Uploaded", "uploaded", quoted = false),
            OffsetPageFilter(),
            FavoriteFilter(),
        ),
    )

/**
 * A write-in box. Every name written into it becomes a search clause of its own category, so
 * `sole female, ahegao` in the tag box searches for both tags.
 */
internal open class TextFilter(
    name: String,
    private val category: String,
    private val quoted: Boolean = true,
) : Filter.Text(name) {

    val clauses: List<String> get() = state.split(',', ';', '\n').mapNotNull { token ->
        val written = token.trim()
        if (written.isEmpty()) return@mapNotNull null

        val exclude = written.startsWith('-')
        val name = written.removePrefix("-").trim()
        if (name.isEmpty()) return@mapNotNull null

        // A name written with its own category in front, like `artist:kubo lion`, is kept as it
        // is; every other name is asked for as the category this box stands for. Page counts and
        // upload dates are written as the site's own expressions, so they are never quoted.
        val value = when {
            name.contains(':') -> name
            quoted -> "$category:\"$name\""
            else -> "$category:$name"
        }
        if (exclude) "-$value" else value
    }
}

/** Skips that many result pages, which is how a reader carries on from where a search stopped. */
internal class OffsetPageFilter : Filter.Text("Offset results by # pages")

internal class FavoriteFilter : Filter.CheckBox("Show favorites only (needs an API key)", false)

internal class SortFilter(default: Int) :
    Filter.Select<String>(
        "Sort by",
        SORTS.map { it.first }.toTypedArray(),
        default,
    ) {
    val selected: String get() = SORTS[state].second

    companion object {
        val SORTS = listOf(
            "Popular: All Time" to "popular",
            "Popular: This Month" to "popular-month",
            "Popular: This Week" to "popular-week",
            "Popular: Today" to "popular-today",
            "Recent" to "date",
        )

        /** The index a saved preference stands for, falling back to the site's own default. */
        fun indexOf(value: String?): Int = SORTS.indexOfFirst { it.second == value }.coerceAtLeast(0)
    }
}
