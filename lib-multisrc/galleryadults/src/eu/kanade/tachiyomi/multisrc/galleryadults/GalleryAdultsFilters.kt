package eu.kanade.tachiyomi.multisrc.galleryadults

import eu.kanade.tachiyomi.source.model.Filter

/**
 * One tag: ticked to search for it, ticked twice to keep it out. The site can only keep a tag out
 * of the advanced search, so ticking one off takes the search there.
 */
class Genre(name: String, val uri: String) : Filter.TriState(name)

class GenresFilter(genres: Map<String, String>) :
    Filter.Group<GenreGroup>(
        "Tags",
        genres.toList().sortedBy { it.first }.groupBy {
            val c = it.second.firstOrNull()?.uppercase()
            if (c != null && c in "A".."Z") c else "#"
        }
            .map { (letter, chunk) -> GenreGroup(letter, chunk) },
    ) {
    /** Tags ticked to search for. */
    val included get() = state.flatMap { it.state.filter { it.isIncluded() } }

    /** Tags ticked off, which only the advanced search can keep out. */
    val excluded get() = state.flatMap { it.state.filter { it.isExcluded() } }
}

class GenreGroup(letter: String, val genres: List<Pair<String, String>>) :
    Filter.Group<Genre>(
        letter,
        genres.map {
            Genre(it.first, it.second)
        },
    )

class SortOrderFilter(sortOrderURIs: List<Pair<String, String>>) : Filter.Select<String>("Sort By", sortOrderURIs.map { it.first }.toTypedArray())

class FavoriteFilter : Filter.CheckBox("Show favorites only (login via WebView)", false)

class RandomEntryFilter : Filter.CheckBox("Random manga", false)

// Speechless
class SpeechlessFilter : Filter.CheckBox("Show speechless items only", false)

// Intermediate search
class SearchFlagFilter(name: String, val uri: String, state: Boolean = true) : Filter.CheckBox(name, state)
class CategoryFilters(flags: List<SearchFlagFilter>) : Filter.Group<SearchFlagFilter>("Categories", flags)

// Advance search
abstract class AdvancedTextFilter(name: String) : Filter.Text(name)
class TagsFilter : AdvancedTextFilter("Tags")
class ParodiesFilter : AdvancedTextFilter("Parodies")
class ArtistsFilter : AdvancedTextFilter("Artists")
class CharactersFilter : AdvancedTextFilter("Characters")
class GroupsFilter : AdvancedTextFilter("Groups")

/**
 * The write-in boxes, kept in a collapsible group at the end of the filter list: a tag, parody,
 * artist, character or group that the long lists above do not carry can be written down instead of
 * hunted for.
 */
class AdvancedFilterGroup(advancedFilters: List<Filter<*>>) :
    Filter.Group<Filter<*>>(
        "Advanced",
        listOf<Filter<*>>(
            Filter.Header("Write names that are not listed above, separated by commas (,)."),
            Filter.Header("Prepend a name with a dash (-) to keep it out."),
            Filter.Header("Written names search the advanced search, which ignores the search term above."),
        ) + advancedFilters,
    )
