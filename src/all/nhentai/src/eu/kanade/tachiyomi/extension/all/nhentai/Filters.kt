package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * The sort comes first and the write-in boxes come last, so the long tag list stays out of the way
 * of the boxes that are ticked most often.
 */
fun getFilters(): FilterList = FilterList(
    SortFilter(),
    Filter.Separator(),
    TagGroupFilter("Tags", "tag", TAG_VOCABULARY),
    TagGroupFilter("Categories", "category", CATEGORY_VOCABULARY),
    TagGroupFilter("Languages", "language", LANGUAGE_VOCABULARY),
    Filter.Separator(),
    AdvancedFilterGroup(),
)

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

internal class SortFilter :
    Filter.Select<String>(
        "Sort by",
        SORTS.map { it.first }.toTypedArray(),
    ) {
    val selected: String get() = SORTS[state].second

    private companion object {
        private val SORTS = listOf(
            "Latest" to "date",
            "Popular: All Time" to "popular",
            "Popular: This Month" to "popular-month",
            "Popular: This Week" to "popular-week",
            "Popular: Today" to "popular-today",
        )
    }
}

/**
 * The write-in boxes, kept in a collapsible group at the end of the filter list: a tag, parody,
 * character, artist, group or language that the lists above do not carry can be written down
 * instead of hunted for, and a leading dash keeps one out.
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
        ),
    )

/**
 * A write-in box. Every name written into it becomes a search clause of its own category, so
 * `sole female, ahegao` in the tag box searches for both tags.
 */
internal open class TextFilter(name: String, private val category: String) : Filter.Text(name) {

    val clauses: List<String> get() = state.split(',', ';', '\n').mapNotNull { token ->
        val written = token.trim()
        if (written.isEmpty()) return@mapNotNull null

        val exclude = written.startsWith('-')
        val name = written.removePrefix("-").trim()
        if (name.isEmpty()) return@mapNotNull null

        // A name written with its own category in front, like `artist:kubo lion`, is kept as it
        // is; every other name is asked for as the category this box stands for.
        val value = if (name.contains(':')) name else "$category:\"$name\""
        if (exclude) "-$value" else value
    }
}
