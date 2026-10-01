package eu.kanade.tachiyomi.extension.en.mangadotnet

import eu.kanade.tachiyomi.source.model.Filter

class BrowseFilter :
    Filter.Select<String>(
        name = "Browse",
        values = browseOptions.map { it.first }.toTypedArray(),
    ) {
    val selected get() = browseOptions[state].second
}

private val browseOptions = listOf(
    "None" to "",
    "Most Tracked" to "most-tracked",
    "Top Rated" to "top-rated",
    "Latest Updates" to "latest-updates",
    "Recently Added" to "recently-added",
    "Bookmarks" to "bookmarks",
)

class SortFilter :
    Filter.Sort(
        name = "Sort",
        values = sortOrders.map { it.first }.toTypedArray(),
        state = Selection(0, false),
    ) {
    val sort get() = sortOrders[state?.index ?: 0].second
    val ascending get() = state?.ascending ?: false
}

private val sortOrders = listOf(
    "Relevance" to "",
    "Latest Update" to "latest",
    "Alphabetical" to "alphabetical",
    "Total Chapters" to "chapters",
    "Most Viewed" to "views",
    "Most Tracked" to "tracked",
    "Top Rated" to "rating",
)

class StatusFilter :
    Filter.Select<String>(
        name = "Status",
        values = status.map { it.first }.toTypedArray(),
    ) {
    val selected get() = status[state].second
}

private val status = listOf(
    "Any Status" to null,
    "Ongoing" to "Ongoing",
    "Completed" to "Completed",
    "Hiatus" to "Hiatus",
)

class VolumesFilter :
    Filter.Select<String>(
        name = "Volumes",
        values = volumeOptions.map { it.first }.toTypedArray(),
    ) {
    val selected get() = volumeOptions[state].second
}

private val volumeOptions = listOf(
    "Any" to "",
    "Has Volumes" to "1",
    "No Volumes" to "0",
)

class ScanlatorFilter :
    Filter.Select<String>(
        name = "Scanlator Group",
        values = scanlatorOptions.map { it.first }.toTypedArray(),
    ) {
    val selected get() = scanlatorOptions[state].second
}

private val scanlatorOptions = listOf(
    "Any" to "",
    "Scanlator Group" to "1",
    "No Scanlator Group" to "0",
)

class LibraryFilter :
    Filter.Select<String>(
        name = "Library",
        values = libraryOptions.map { it.first }.toTypedArray(),
    ) {
    val selected get() = libraryOptions[state].second
}

private val libraryOptions = listOf(
    "Any" to "",
    "In Library" to "in",
    "Not In Library" to "out",
)

class MinRatingFilter :
    Filter.Select<String>(
        name = "Minimum Rating",
        values = ratingOptions.map { it.first }.toTypedArray(),
    ) {
    val selected get() = ratingOptions[state].second
}

private val ratingOptions = listOf(
    "Any" to "",
    "★ 1+" to "1",
    "★ 2+" to "2",
    "★ 3+" to "3",
    "★ 4+" to "4",
    "★ 5+" to "5",
    "★ 6+" to "6",
    "★ 7+" to "7",
    "★ 8+" to "8",
    "★ 9+" to "9",
)

class TypeCheckBox(name: String, val value: String) : Filter.CheckBox(name)

class TypeFilter :
    Filter.Group<TypeCheckBox>(
        "Types",
        types.map { TypeCheckBox(it.first, it.second) },
    ) {
    val checked get() = state.filter { it.state }.map { it.value }
}

private val types = listOf(
    "Manga" to "JP",
    "Manhwa" to "KR",
    "Manhua" to "CN",
    "OEL" to "EN",
    "One Shot" to "ONESHOT",
)

class ContentRatingFilter(excluded: Set<String> = emptySet()) :
    Filter.Group<TriStateFilter>(
        "Content Rating",
        contentRatings.map { (name, value) ->
            val state = if (value in excluded) TriState.STATE_EXCLUDE else TriState.STATE_IGNORE
            TriStateFilter(name, value, state)
        },
    ) {
    val included get() = state.filter { it.isIncluded() }.map { it.value }
    val excluded get() = state.filter { it.isExcluded() }.map { it.value }
}

val contentRatings = listOf(
    "Safe" to "safe",
    "Suggestive" to "suggestive",
    "Erotica" to "erotica",
    "Pornographic" to "pornographic",
)

class TriStateFilter(name: String, val value: String = name, state: Int = STATE_IGNORE) : Filter.TriState(name, state)

class DemographicFilter(excluded: Set<String> = emptySet()) :
    Filter.Group<TriStateFilter>(
        name = "Demographics",
        state = demographics.map { demo ->
            val state = if (demo in excluded) TriState.STATE_EXCLUDE else TriState.STATE_IGNORE
            TriStateFilter(demo, state = state)
        },
    ) {
    val included get() = state.filter { it.isIncluded() }.map { it.value }
    val excluded get() = state.filter { it.isExcluded() }.map { it.value }
}

private val demographics = listOf("Josei", "Seinen", "Shoujo", "Shounen")

class GenreFilter(genreValues: List<String>, excluded: Set<String>) :
    Filter.Group<TriStateFilter>(
        name = "Genres",
        state = genreValues.map { genre ->
            val state = if (genre in excluded) TriState.STATE_EXCLUDE else TriState.STATE_IGNORE
            TriStateFilter(genre, state = state)
        },
    ) {
    val included get() = state.filter { it.isIncluded() }.map { it.value }
    val excluded get() = state.filter { it.isExcluded() }.map { it.value }
}

class TagsGroupFilter(
    state: List<TagFilter>,
) : Filter.Group<TagFilter>("Tags", state)

class TagFilter(name: String, tagValues: List<String>, excluded: Set<String> = emptySet()) :
    Filter.Group<TriStateFilter>(
        name = name,
        state = tagValues.map { tag ->
            val state = if (tag in excluded) TriState.STATE_EXCLUDE else TriState.STATE_IGNORE
            TriStateFilter(tag, state = state)
        },
    ) {
    val included get() = state.filter { it.isIncluded() }.map { it.value }
    val excluded get() = state.filter { it.isExcluded() }.map { it.value }
}

/**
 * The write-in boxes, gathered in a collapsible group at the end of the filter list: a genre or tag
 * that is already known can be written down instead of hunted for in the long lists above.
 */
class AdvancedFilterGroup(genreValues: List<String>, tagValues: List<String>) :
    Filter.Group<Filter<*>>(
        "Advanced",
        listOf(
            Filter.Header(
                "Write genres or tags by name instead of hunting for them in the lists above. " +
                    "Names are matched ignoring case, spacing and punctuation, several can be " +
                    "separated by commas, and a leading dash excludes a name.",
            ),
            GenreChoiceFilter(genreValues),
            TagChoiceFilter(tagValues),
            Filter.Header("The boxes below are passed to the site as they are written."),
            MinChaptersFilter(),
            MinYearFilter(),
            MaxYearFilter(),
            AuthorFilter(),
            ArtistFilter(),
        ),
    ) {
    /** Genres written into the box, resolved against the genre list the site reported. */
    internal val writtenGenres: TagQuery get() = state.filterIsInstance<GenreChoiceFilter>().first().resolved

    /** Tags written into the box, resolved against the tag list the site reported. */
    internal val writtenTags: TagQuery get() = state.filterIsInstance<TagChoiceFilter>().first().resolved
}

/** Genres to add besides the ones ticked in the lists above; a leading dash excludes a genre. */
class GenreChoiceFilter(private val vocabulary: List<String>) : Filter.Text("Genres, e.g. action, romance, -ecchi") {
    internal val resolved: TagQuery get() = resolveNameQuery(state, vocabulary)
}

/** Tags to add besides the ones ticked in the lists above; a leading dash excludes a tag. */
class TagChoiceFilter(private val vocabulary: List<String>) : Filter.Text("Tags, e.g. isekai, time travel, -harem") {
    internal val resolved: TagQuery get() = resolveNameQuery(state, vocabulary)
}

/** The names a written query asks for, split by whether a leading dash asked to exclude them. */
internal class TagQuery(val included: List<String>, val excluded: List<String>)

/**
 * Turns a written name query into the names the site uses:
 *
 * - names are compared ignoring case, spacing and punctuation, so `sci fi` finds `Sci-Fi`;
 * - a name that is only the start of one in the vocabulary stands for every name it starts, so
 *   `ise` finds `Isekai`;
 * - a leading dash excludes, which is how a name is kept out without ticking it anywhere else;
 * - an exact match wins outright, so `manga` means Manga and not Manhua and Manhwa.
 *
 * A name that matches nothing is dropped once the site has reported its vocabulary, because a typo
 * must not silently turn into a search for something no title carries. Before that vocabulary has
 * arrived the written name is passed on as written, so it still stands a chance.
 */
internal fun resolveNameQuery(query: String, vocabulary: List<String>): TagQuery {
    val known = vocabulary.map { normalizeName(it) to it }
    val included = mutableSetOf<String>()
    val excluded = mutableSetOf<String>()

    for (token in query.split(',', ';', '\n')) {
        val written = token.trim()
        if (written.isEmpty()) continue

        val name = written.removePrefix("-")
        val wanted = normalizeName(NAME_QUERY_ALIASES[normalizeName(name)] ?: name)
        if (wanted.isEmpty()) continue

        val exact = known.filter { it.first == wanted }
        val matches = when {
            exact.isNotEmpty() -> exact
            known.isNotEmpty() -> known.filter { it.first.startsWith(wanted) }
            else -> emptyList()
        }
        val resolved = matches.map { it.second }.ifEmpty { if (known.isEmpty()) listOf(name) else emptyList() }

        (if (written.startsWith('-')) excluded else included) += resolved
    }

    return TagQuery(included.toList(), excluded.toList())
}

private val NAME_QUERY_ALIASES = aliasesOf(
    "bl" to "boys love",
    "gl" to "girls love",
    "sol" to "slice of life",
)

/** Aliases are looked up by normalised name, so the keys have to be normalised as well. */
private fun aliasesOf(vararg pairs: Pair<String, String>): Map<String, String> = pairs.associate { normalizeName(it.first) to it.second }

/** Case, spacing and punctuation are ignored so a name can be written the way it is spoken. */
private fun normalizeName(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

class MinChaptersFilter : Filter.Text("Minimum Chapters")

class MinYearFilter : Filter.Text("Minimum Year")

class MaxYearFilter : Filter.Text("Maximum Year")

class AuthorFilter : Filter.Text("Author")

class ArtistFilter : Filter.Text("Artist")
