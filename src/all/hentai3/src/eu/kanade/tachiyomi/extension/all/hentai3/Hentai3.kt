package eu.kanade.tachiyomi.extension.all.hentai3

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Hentai3 :
    KeiSource(),
    ConfigurableSource {

    /**
     * The language the source is pinned to, in the two spellings the site reads: the name it files a
     * language under, which both its search box and its `/language/` pages answer to, and the flag
     * it prints on every card of a gallery in it. The source of the whole site carries neither.
     */
    private val siteLanguage: SiteLanguage?
        get() = SITE_LANGUAGES[lang]

    private val searchLang: String get() = siteLanguage?.name.orEmpty()

    private val searchFlag: String get() = siteLanguage?.flag.orEmpty()

    private val prefs by getPreferencesLazy()

    // Popular + Latest
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (searchLang.isNotEmpty()) {
            "$baseUrl/language/$searchLang/${if (page > 1) page else ""}?sort=popular"
        } else {
            "$baseUrl/search?q=pages%3A>0&page=$page&sort=popular"
        }
        val doc = client.get(url).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (searchLang.isNotEmpty()) {
            "$baseUrl/language/$searchLang/$page"
        } else {
            "$baseUrl/search?q=pages%3A>0&page=$page"
        }
        val doc = client.get(url).asJsoup()
        return parseMangasPage(doc)
    }

    private fun parseMangasPage(doc: Document): MangasPage {
        val hasNextPage = doc.selectFirst("a[rel=next]") != null
        return MangasPage(parseCards(doc), hasNextPage)
    }

    /** The cards a listing page prints, the galleries of a search or of a language among them. */
    private fun parseCards(document: Document, selector: String = CARD_SELECTOR): List<SManga> = document.select(selector).mapNotNull(::cardOf)

    /**
     * One card of a listing: the link to the gallery, its title and its thumbnail. The thumbnail is
     * read from the plain `img` the site writes for readers without scripts, because the one above
     * it carries its address in a lazy `data-src` instead.
     */
    private fun cardOf(card: Element): SManga? {
        val link = card.selectFirst("a[href*=/d/]") ?: return null
        val title = link.selectFirst("div.title")?.ownText() ?: return null

        return SManga.create().apply {
            this.title = title.shortenTitle()
            setUrlWithoutDomain(link.absUrl("href"))
            thumbnail_url = link.selectFirst("img:not([class])")!!.absUrl("src")
        }
    }

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.filterIsInstance<SelectFilter>().firstOrNull()?.getValue().orEmpty()

        // Ticked options of the tag groups and every name written into the Advanced boxes turn
        // into site clauses, which the site reads as one big query.
        val tags = (
            filters.filterIsInstance<TagGroupFilter>().flatMap { it.clauses } +
                filters.writtenTextFilters().flatMap { it.clauses }
            )
            .distinct()
            .joinToString(" ")

        val language = if (searchLang.isNotEmpty()) "language:$searchLang" else ""

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addQueryParameter("q", "$query $language $tags")
            if (page > 1) addQueryParameter("page", page.toString())
            addQueryParameter("sort", sort)
        }.build()

        return parseMangasPage(client.get(url).asJsoup())
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(lang)

    // Details + Chapters
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.getOrNull(0) != "d") return null

        return url.pathSegments.getOrNull(1)?.let { id ->
            parseMangaDetails(client.get("$baseUrl/d/$id").asJsoup())
        }
    }

    private fun parseMangaDetails(document: Document): SManga {
        // Every name the site lists beside the picture: the category, the series, the characters,
        // the tags, the languages, and the artist, which is written as a plain search of its name
        // because the site files no artist, group or character pages for a gallery.
        val listed = document.select("div.tag-container a.name").eachText()
        val artists = document.select("span.tag-tags a[href*=/search?q=]").eachText()
        val categories = document.select("a[href*=/category/]").eachText()
        val characters = document.select("a[href*=/characters/]").eachText()
        val series = document.select("a[href*=/series/]").eachText()
        // The site writes a note beside a language it qualifies - "japanese | definition revised
        // please read the wiki" - which says nothing about the gallery, so only the name is read.
        val languages = document.select("a[href*=/language/]").eachText().map { it.substringBefore(" | ").trim() }.distinct()
        val pages = document.select("div.tag-container:contains(pages:)").text().trim()

        // The title keeps its decorations in the description for the reader to pick a gallery apart
        // by, and the site writes no circle anywhere, so the circle is read out of them.
        val fullTitle = document.select("h1").text()
        val circles = Hentai3Utils.circleNames(
            fullTitle,
            listed.flatMap(Hentai3Utils::keysOf).toSet(),
            artists.flatMap(Hentai3Utils::keysOf).toSet(),
        )

        return SManga.create().apply {
            setUrlWithoutDomain(document.location())
            initialized = true

            title = fullTitle.shortenTitle()
            author = Hentai3Utils.capitalizeEach((circles.ifEmpty { artists }).joinToString())
            artist = Hentai3Utils.capitalizeEach(artists.joinToString())
            genre = document.select("a[href*=/tags/]").eachText().joinToString {
                val capitalized = Hentai3Utils.capitalizeEach(it)
                if (capitalized.contains("male")) {
                    capitalized.replace("(female)", "♀").replace("(male)", "♂")
                } else {
                    "$capitalized ◊"
                }
            }

            description = buildString {
                if (fullTitle.isNotBlank() && fullTitle != title) append("Full title:\n", fullTitle, "\n\n")
                if (pages.isNotEmpty()) append(pages, "\n\n")
                appendNames("Categories", categories)
                appendNames("Series", series)
                appendNames("Characters", characters)
                appendNames("Groups", circles)
                appendNames("Artists", artists)
                appendNames("Languages", languages)
            }.trimEnd()
            thumbnail_url = document.selectFirst("img")!!.let { img ->
                img.attr("data-src").ifEmpty { img.absUrl("src") }
            }
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    private fun parseChapterList(doc: Document): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Chapter"
            setUrlWithoutDomain(doc.location())
            date_upload = dateFormat.tryParse(doc.select("time").attr("datetime"))
        },
    )

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(parseMangaDetails(doc), parseChapterList(doc))
    }

    // Related manga
    override val supportsRelatedMangas = true

    /**
     * A gallery the site prints nothing beside is still worth a search of its own, so the app is
     * left to search the source by its title as well.
     */
    protected override val supportRelatedMangasBySearch = true

    /**
     * The suggestions row under a gallery, read in the order a reader wants it: what continues the
     * work first - the site files the parts of a work together under the series a gallery carries,
     * and one it files under no series is continued by the artist behind it - then the galleries
     * whose titles read like the title being read, and last the ones the site matched by tag in its
     * own "More like this" row.
     *
     * A source pinned to a language answers with galleries in that language alone: the search is
     * written with the site's own language clause and the row is read by the flag the site prints on
     * a card. A language the site prints no flag for is one it cannot be searched by either, so a
     * source pinned to it reads neither and answers with nothing rather than with galleries in
     * other languages. The reader who wants the whole set opens the Multi source, which carries it.
     */
    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = runCatching {
        if (searchLang.isNotEmpty() && searchFlag.isEmpty()) return@runCatching emptyList()

        val document = client.get(getMangaUrl(manga)).asJsoup()
        val words = Hentai3Utils.titleWords(document.select("h1").text())

        // What continues the work, wanted first: the series the gallery is filed under, or the
        // artist behind a work the site files under none. The site files every work of its own
        // under the series "original", which names no work at all and is read as none.
        val series = document.select("div.tag-container a[href*=/series/]").eachText()
            .filterNot { it.equals("original", ignoreCase = true) }
        val artists = document.select("span.tag-tags a[href*=/search?q=]").eachText()
        val continuing = when {
            series.isNotEmpty() -> relatedSearch("serie", series.first())
            artists.isNotEmpty() -> relatedSearch("artist", artists.first())
            else -> emptyList()
        }

        // Every pool is read by how much its titles read like the gallery's before it is joined to
        // the next, so a part of the same work sorts above a gallery that shares no more than its
        // tags. The sort is stable, which keeps the site's own order between titles that read alike.
        listOf(continuing, similarCards(document))
            .flatMap { cards ->
                cards.filterNot { it.galleryId == manga.galleryId }
                    .distinctBy { it.url }
                    .sortedByDescending { Hentai3Utils.titleOverlap(words, it.title) }
            }
            .distinctBy { it.url }
            .take(MAX_RELATED)
    }.getOrDefault(emptyList())

    /**
     * A search of the source for the galleries that continue a work, written the way the site's own
     * search box reads it: the series or the artist the gallery is filed under, and, on a source
     * pinned to a language, only the galleries in it.
     */
    private suspend fun relatedSearch(category: String, name: String): List<SManga> {
        val term = name.withoutQuotes()
        if (term.isEmpty()) return emptyList()

        val language = if (searchLang.isNotEmpty()) " language:'$searchLang'" else ""
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", "$category:'$term'$language")
            .build()

        return parseCards(client.get(url).asJsoup())
    }

    /**
     * The galleries the site prints under "More like this", chosen by tag, which a source pinned to
     * a language reads through the flag of that language alone.
     */
    private fun similarCards(document: Document): List<SManga> = document
        .select("#similar-content div.doujin")
        .filter { searchFlag.isEmpty() || it.hasClass("flag-$searchFlag") }
        .mapNotNull(::cardOf)

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(getChapterUrl(chapter)).asJsoup()
        return doc.select("img:not([class], [src*=thumb], [src*=cover])")
            .mapIndexed { index, image ->
                val imageUrl = image.absUrl("src")
                Page(index, imageUrl = imageUrl.replace(REMOVE_THUMB_REGEX, ""))
            }
    }

    // Preferences
    private fun String.shortenTitle() = if (displayFullTitle) {
        this
    } else {
        Hentai3Utils.stripDecorations(this)
    }

    private val displayFullTitle
        get() = prefs.getBoolean("full_title", false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = "full_title"
            title = "Display full title"
        }.also(screen::addPreference)
    }

    companion object {
        /** The cards of a listing page, which is how the site writes every gallery it prints. */
        private const val CARD_SELECTOR = "div.doujin"

        /** How many suggestions a gallery is worth: the row the app prints stays readable. */
        private const val MAX_RELATED = 20

        private val REMOVE_THUMB_REGEX = Regex("t(?=\\.)")
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ", Locale.ROOT)
    }
}

/**
 * The write-in boxes sit inside the Advanced group rather than in the filter list itself, so the
 * groups are searched as well.
 */
private fun FilterList.writtenTextFilters(): List<TextFilter> = filterIsInstance<TextFilter>() +
    filterIsInstance<Filter.Group<*>>().flatMap { it.state.filterIsInstance<TextFilter>() }

/** One `Label: names` line of the description, left out when the page carries no such name. */
private fun StringBuilder.appendNames(label: String, names: List<String>) {
    names.joinToString().takeIf(String::isNotBlank)?.let {
        append(label, ": ", Hentai3Utils.capitalizeEach(it), "\n\n")
    }
}

/** The number a gallery is named by in its URL, `/d/258511` being gallery 258511. */
private val SManga.galleryId: String?
    get() = GALLERY_ID_REGEX.find(url)?.groupValues?.get(1)

private val GALLERY_ID_REGEX = Regex("/d/(\\d+)")

/** A name the site's search reads inside quotes, with none of its own left to end the clause. */
private fun String.withoutQuotes(): String = replace("'", "").replace("\"", "").trim()

/**
 * One language as the site reads it: the name its search box and its `/language/` pages are written
 * with - both spellings are the same to it, and the one written here is the one the site prints -
 * and the flag it prints on a card of a gallery in that language.
 *
 * The site carries pages for a few languages its search cannot name - Tagalog, Burmese, Bulgarian,
 * Mongolian and Finnish among them - and none of those carries a flag this extension can pin, so
 * they are left without one: a source pinned to one of them answers with no suggestions of its own,
 * the Multi source being the one that carries them.
 */
private data class SiteLanguage(val name: String, val flag: String = "")

private val SITE_LANGUAGES = mapOf(
    "ar" to SiteLanguage("arabic", "ara"),
    "cs" to SiteLanguage("czech", "ces"),
    "de" to SiteLanguage("german", "deu"),
    "en" to SiteLanguage("english", "eng"),
    "es" to SiteLanguage("spanish", "spa"),
    "fr" to SiteLanguage("french", "fra"),
    "hu" to SiteLanguage("hungarian", "hun"),
    "id" to SiteLanguage("indonesian", "ind"),
    "is" to SiteLanguage("icelandic", "isl"),
    "it" to SiteLanguage("italian", "ita"),
    "ja" to SiteLanguage("japanese", "jpn"),
    "jv" to SiteLanguage("javanese", "jav"),
    "ko" to SiteLanguage("korean", "kor"),
    "la" to SiteLanguage("latin", "lat"),
    "nl" to SiteLanguage("dutch", "nld"),
    "pl" to SiteLanguage("polish", "pol"),
    "pt" to SiteLanguage("portuguese", "por"),
    "ru" to SiteLanguage("russian", "rus"),
    "th" to SiteLanguage("thai", "tha"),
    "tr" to SiteLanguage("turkish", "tur"),
    "uk" to SiteLanguage("ukrainian", "ukr"),
    "vi" to SiteLanguage("vietnamese", "vie"),
    "zh" to SiteLanguage("chinese", "zho"),
    "bg" to SiteLanguage("bulgarian"),
    "fi" to SiteLanguage("finnish"),
    "mo" to SiteLanguage("mongolian"),
    "my" to SiteLanguage("burmese"),
    "tl" to SiteLanguage("tagalog"),
)
