package eu.kanade.tachiyomi.extension.all.mangaball

import eu.kanade.tachiyomi.source.model.Filter

/**
 * The write-in boxes, kept in a collapsible group at the end of the filter list: a tag or theme
 * that is already known can be written down instead of hunted for in the long lists above.
 */
class AdvancedFilterGroup :
    Filter.Group<Filter<*>>(
        "Advanced",
        listOf(
            Filter.Header(
                "Write tags or themes by name instead of hunting for them in the lists above. " +
                    "Names are matched ignoring case, spacing and punctuation, several can be " +
                    "separated by commas, and a leading dash excludes a tag.",
            ),
            TagChoiceFilter(),
            ThemeChoiceFilter(),
        ),
    )

/** Tags to add to the ones ticked in the groups above; a leading dash excludes a tag. */
class TagChoiceFilter : Filter.Text("Tags, e.g. isekai, romance, -ecchi")

/** Themes to add to the ones ticked under Theme; a leading dash excludes a theme. */
class ThemeChoiceFilter : Filter.Text("Themes, e.g. reincarnation, harem, -vampires")

/** A tag as the checkbox groups declare it: the label to match a written name against, and the id. */
internal data class TagOption(val name: String, val id: String)

/** The ids a written tag query asks for, split by whether a leading dash asked to exclude them. */
internal class TagQuery(val included: List<String>, val excluded: List<String>)

/**
 * Turns a written tag query into tag ids. The API only accepts ids, so names are looked up in the
 * vocabulary the checkbox groups already carry:
 *
 * - names are compared ignoring case, spacing and punctuation, so `sci fi` finds `Sci-Fi`;
 * - a name that is only the start of a tag stands for every tag it starts, so `ise` finds `Isekai`;
 * - a leading dash excludes, which is how a tag is kept out without ticking it anywhere else.
 *
 * Anything unrecognised is dropped: a typo must not silently turn into a tag no title carries.
 */
internal fun resolveTagQuery(query: String, vocabulary: List<TagOption>): TagQuery {
    val known = vocabulary.map { normalizeName(it.name) to it.id }
    val included = mutableSetOf<String>()
    val excluded = mutableSetOf<String>()

    for (token in query.split(',', ';', '\n')) {
        val written = token.trim()
        if (written.isEmpty()) continue

        val name = written.removePrefix("-")
        val wanted = normalizeName(TAG_QUERY_ALIASES[normalizeName(name)] ?: name)
        if (wanted.isEmpty()) continue

        // An exact name wins outright, so "manga" means the tag Manga and not Manhua and Manhwa.
        val exact = known.filter { it.first == wanted }
        val matches = exact.ifEmpty { known.filter { it.first.startsWith(wanted) } }

        (if (written.startsWith('-')) excluded else included) += matches.map { it.second }
    }

    return TagQuery(included.toList(), excluded.toList())
}

private val TAG_QUERY_ALIASES = aliasesOf(
    "bl" to "boys love",
    "gl" to "girls love",
    "sol" to "slice of life",
)

/** Aliases are looked up by normalised name, so the keys have to be normalised as well. */
private fun aliasesOf(vararg pairs: Pair<String, String>): Map<String, String> = pairs.associate { normalizeName(it.first) to it.second }

/** Case, spacing and punctuation are ignored so a name can be written the way it is spoken. */
private fun normalizeName(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
