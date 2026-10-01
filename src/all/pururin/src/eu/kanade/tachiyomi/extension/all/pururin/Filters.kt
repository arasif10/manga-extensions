package eu.kanade.tachiyomi.extension.all.pururin

import eu.kanade.tachiyomi.source.model.Filter

/*
 * How the filters work, because Pururin is not helpful about it.
 *
 * The site's own advanced search form offers Included/Excluded Tags and a Page Between range, but
 * only the page range is real: `/search?included_tags=...` ignores tags completely and always
 * answers with the whole library. What does work is browsing a single tag: `/browse/tags/<type>/<id>`
 * filters properly and pages through its own results, but only one tag fits in a url.
 *
 * So the groups below are tri-state: ticked entries are kept, ticked-off ones are dropped, and the
 * app decides where each rule is applied. A group holding exactly one ticked tag is sent to the
 * site as a tag browse url, which keeps results full and paging exact. Anything left over is
 * checked against the listing itself, so a filtered page can hold fewer entries than usual.
 */

/** Fields of a listing card that a tag group can be checked against. */
internal const val FIELD_PARODY = "parody"
internal const val FIELD_CATEGORY = "category"
internal const val FIELD_LANGUAGE = "language"

/** One tickable option of a tag group: ticked to include, ticked again to exclude. */
class TriStateFilter(
    name: String,
    val value: String,
    val tagType: String,
    val tagId: Int,
) : Filter.TriState(name) {
    /** Only the numeric id decides which tag a browse url means; the slug is there to be readable. */
    val slug: String get() = value.replace(' ', '-')
}

/**
 * A tri-state group of the site's tags.
 *
 * [listingField] is the field of a listing card that this group can be checked against, or null for
 * tags printed only on a gallery page (the Contents tags). Groups without a listing field can only
 * be honoured while they hold a single ticked tag, or by loading each gallery to read its tags.
 */
open class TagGroupFilter(
    name: String,
    val listingField: String?,
    tags: List<TriStateFilter>,
) : Filter.Group<TriStateFilter>(name, tags) {

    val included: List<String> get() = state.filter { it.isIncluded() }.map { it.value }
    val excluded: List<String> get() = state.filter { it.isExcluded() }.map { it.value }

    val isActive: Boolean get() = included.isNotEmpty() || excluded.isNotEmpty()

    /** The one ticked tag, when there is exactly one and nothing in this group is ticked off. */
    val singleInclude: TriStateFilter?
        get() = if (excluded.isEmpty()) state.singleOrNull { it.isIncluded() } else null
}

private fun tags(type: String, vararg entries: Pair<String, Int>) = entries.map {
    TriStateFilter(it.first, it.first.lowercase(), type, it.second)
}

/**
 * Contents are the tags a gallery page lists under that heading - the site's real subject tags.
 * They are not printed on a listing card, so this group is served by a tag browse url when it holds
 * a single ticked tag; with several it has to open each candidate gallery to read them.
 */
class ContentsFilter :
    TagGroupFilter(
        "Contents",
        null,
        tags(
            "contents",
            "Ahegao" to 1591,
            "Anal" to 1576,
            "Big Ass" to 1714,
            "Big Breasts" to 1539,
            "Big Penis" to 1693,
            "Blowjob" to 1571,
            "Bondage" to 1617,
            "Cheating" to 1698,
            "Chikan" to 1632,
            "Cunnilingus" to 1587,
            "Dark Skin" to 1564,
            "Deepthroat" to 1710,
            "Defloration" to 1639,
            "Exhibitionism" to 1612,
            "Femdom" to 1618,
            "FFM Threesome" to 1584,
            "Full Color" to 1540,
            "Futanari" to 1570,
            "Group Sex" to 1575,
            "Gyaru" to 1747,
            "Hairy" to 1700,
            "Handjob" to 1684,
            "Harem" to 1666,
            "Hidden Sex" to 21192,
            "Impregnation" to 1568,
            "Incest" to 1548,
            "Lactation" to 1641,
            "Lingerie" to 1734,
            "Maid" to 1705,
            "Masturbation" to 1595,
            "MILF" to 1640,
            "Mind Break" to 1594,
            "Mother" to 1642,
            "Nakadashi" to 1557,
            "Netorare" to 1604,
            "Netori" to 2755,
            "Non-H" to 1794,
            "Paizuri" to 1567,
            "Pregnant" to 1605,
            "Prostitution" to 1743,
            "Public Use" to 1952,
            "Rape" to 1590,
            "Schoolgirl Uniform" to 1550,
            "Sex Toys" to 1653,
            "Sole Female" to 1559,
            "Sole Male" to 1560,
            "Squirting" to 1772,
            "Stockings" to 1563,
            "Sweating" to 1637,
            "Swimsuit" to 1541,
            "Tanlines" to 1636,
            "Teacher" to 1647,
            "Tomboy" to 1644,
            "Uncensored" to 1692,
            "Virginity" to 1694,
            "Voyeurism" to 1793,
            "X-Ray" to 1572,
            "Yaoi" to 1629,
            "Yuri" to 1583,
        ),
    )

class CategoryFilter :
    TagGroupFilter(
        "Categories",
        FIELD_CATEGORY,
        tags(
            "category",
            "Doujinshi" to 13003,
            "Manga" to 13004,
            "Artist CG" to 13006,
            "Game CG" to 13008,
            "Comic" to 39227,
            "Western CG" to 39234,
        ),
    )

/** Pururin itself only files galleries under these two languages. */
class LanguageFilter :
    TagGroupFilter(
        "Languages",
        FIELD_LANGUAGE,
        tags(
            "language",
            "English" to 13010,
            "Japanese" to 13011,
        ),
    )

/**
 * The parody a card prints is the tag's own english name, and a gallery with several parodies only
 * prints the first of them, so an excluded parody can still be hiding behind a printed one.
 */
class ParodyFilter :
    TagGroupFilter(
        "Parodies",
        FIELD_PARODY,
        tags(
            "parody",
            "Original" to 1,
            "Pokemon" to 271,
            "Naruto" to 101,
            "One Piece" to 136,
            "Bleach" to 58,
            "Dragon Ball" to 555,
            "My Hero Academia" to 213,
            "Genshin Impact" to 30669,
            "Blue Archive" to 32556,
            "Hololive" to 26983,
            "Nijisanji" to 26877,
            "Kantai Collection" to 10,
            "Fate Grand Order" to 13,
            "Overlord" to 238,
            "Sword Art Online" to 29,
            "To Love-Ru" to 66,
            "Undertale" to 1493,
            "League of Legends" to 53,
            "Overwatch" to 719,
            "Nier Automata" to 1532,
            "Final Fantasy" to 949,
            "Metroid" to 415,
            "Resident Evil" to 204,
            "Street Fighter" to 44,
            "Fire Emblem" to 167,
            "Persona" to 1077,
            "Danganronpa" to 37,
            "Splatoon" to 73,
            "Vocaloid" to 124,
            "Gundam" to 935,
            "Sailor Moon" to 331,
            "Inuyasha" to 147,
            "Fairy Tail" to 206,
            "Chainsaw Man" to 30780,
            "Spy x Family" to 37782,
            "Jujutsu Kaisen" to 32740,
            "Tekken" to 498,
            "Dead or Alive" to 242,
            "Granblue Fantasy" to 60,
            "Azur Lane" to 18587,
        ),
    )

/** Cards carry a five star rating, so the average can be filtered without opening a gallery. */
class RatingFilter :
    Filter.Select<String>(
        "Minimum Rating",
        arrayOf("Any", "3 stars", "3.5 stars", "4 stars", "4.5 stars"),
    ) {
    val threshold: Double get() = when (state) {
        1 -> 3.0
        2 -> 3.5
        3 -> 4.0
        4 -> 4.5
        else -> 0.0
    }
}

/** The site's own advanced search calls this Page Between; cards print the page count directly. */
class MinPagesFilter : Filter.Text("Minimum Pages")

class MaxPagesFilter : Filter.Text("Maximum Pages")

class SortFilter :
    Filter.Select<String>(
        "Sort By",
        arrayOf(
            "Relevance",
            "Newest",
            "Most Viewed",
            "Most Popular",
            "Highest Rated",
            "Title A-Z",
        ),
    ) {
    val selected: String get() = when (state) {
        1 -> "newest"
        2 -> "most-viewed"
        3 -> "most-popular"
        4 -> "highest-rated"
        5 -> "title"
        else -> ""
    }
}
