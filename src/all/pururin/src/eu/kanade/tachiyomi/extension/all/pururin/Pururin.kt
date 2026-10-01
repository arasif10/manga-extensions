package eu.kanade.tachiyomi.extension.all.pururin

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pururin is a doujinshi gallery: every gallery is a single, complete work, so a title holds one
 * "chapter" with as many pages as the gallery has images.
 *
 * The site has no JSON API; listings and details are scraped from the rendered HTML. Its advanced
 * search offers Included/Excluded Tags, but those parameters are ignored - what really filters is
 * browsing one tag at a time under `/browse/tags/<id>`. The tri-state filters therefore send a
 * single ticked tag to that url and check everything else against the listing.
 */
@Source
abstract class Pururin :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    /** Cover urls end in `cover.jpg`, but a few galleries actually serve `.png` pages. */
    private val imageExtension: String
        get() = preferences.getString(IMAGE_EXT_PREF, "jpg").orEmpty().ifEmpty { "jpg" }

    // ============================== Popular & Latest ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = getBrowse("most-popular", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getBrowse("newest", page)

    private suspend fun getBrowse(sort: String, page: Int): MangasPage {
        val url = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .build()

        val document = client.get(url).parseHtml()
        return MangasPage(document.galleryCards().map { it.manga }, document.hasNextPage())
    }

    // ============================== Search ==============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstance<SortFilter>().selected
        val minRating = filters.firstInstance<RatingFilter>().threshold
        val minPages = filters.firstInstance<MinPagesFilter>().state.trim().toIntOrNull()
        val maxPages = filters.firstInstance<MaxPagesFilter>().state.trim().toIntOrNull()

        val groups = filters.filterIsInstance<TagGroupFilter>()
        val contents = filters.firstInstance<ContentsFilter>()

        // A title search and a tag browse are separate endpoints, so a query rules the tag anchor
        // out. Contents tags are the only ones a listing does not print, so they get the anchor
        // first - that way a single ticked Contents tag costs nothing extra.
        val anchor = if (query.isNotBlank()) {
            null
        } else {
            contents.singleInclude ?: groups.firstNotNullOfOrNull { it.singleInclude }
        }
        val anchoredGroup = anchor?.let { tag -> groups.first { group -> group.state.contains(tag) } }

        // Everything the site cannot be asked for itself has to be checked here.
        val residual = groups.filter { it.isActive && it !== anchoredGroup }
        val needsLocalCheck = residual.isNotEmpty() || minRating > 0.0 || minPages != null || maxPages != null
        val needsGalleryPages = residual.any { it.listingField == null }

        val base = if (anchor != null) tagBrowseUrl(anchor, sort) else searchUrl(query, sort)

        if (!needsLocalCheck) {
            val document = client.get(withPage(base, page)).parseHtml()
            return MangasPage(document.galleryCards().map { it.manga }, document.hasNextPage())
        }

        // Filtered listings hold fewer entries than the site sends, so pages are filled from as many
        // site pages as it takes, within reason.
        val matches = mutableListOf<GalleryCard>()
        var sitePage = 1
        var moreOnSite = false
        while (sitePage <= MAX_SCAN_PAGES && matches.size < PER_PAGE * page) {
            val document = client.get(withPage(base, sitePage)).parseHtml()
            moreOnSite = document.hasNextPage()

            var cards = document.galleryCards().filter { card ->
                residual.none { !it.matchesListing(card) } &&
                    card.rating >= minRating &&
                    card.matchesPages(minPages, maxPages)
            }
            if (needsGalleryPages) cards = cards.filterByGalleryPages(residual)

            matches += cards
            if (!moreOnSite) break
            sitePage++
        }

        val from = PER_PAGE * (page - 1)
        return MangasPage(
            matches.drop(from).take(PER_PAGE).map { it.manga },
            moreOnSite || matches.size > PER_PAGE * page,
        )
    }

    /**
     * A single ticked tag is browsed the way the site does it: `/browse/tags/<id>` really filters
     * and pages properly, which keeps results full instead of scrubbing a general listing.
     */
    private fun tagBrowseUrl(tag: TriStateFilter, sort: String): HttpUrl = "$baseUrl/browse/tags/${tag.tagType}/${tag.tagId}/${tag.slug}"
        .toHttpUrl()
        .newBuilder()
        .apply { if (sort.isNotEmpty()) addQueryParameter("sort", sort) }
        .build()

    private fun searchUrl(query: String, sort: String): HttpUrl = "$baseUrl/search".toHttpUrl()
        .newBuilder()
        .apply {
            if (sort.isNotEmpty()) addQueryParameter("sort", sort)
            // An empty `q` is a title search for nothing and answers with no results at all.
            if (query.isNotBlank()) addQueryParameter("q", query)
        }
        .build()

    private fun withPage(url: HttpUrl, page: Int): HttpUrl = url.newBuilder()
        .setQueryParameter("page", page.toString())
        .build()

    /**
     * Contents tags live on the gallery page only, so a listing has to be opened gallery by gallery
     * to check them. Only used when such a group holds more than one tag, or holds a tag that is
     * ticked off - a single ticked tag is served by [tagBrowseUrl] instead.
     */
    private suspend fun List<GalleryCard>.filterByGalleryPages(groups: List<TagGroupFilter>): List<GalleryCard> {
        val rules = groups.filter { it.listingField == null && it.isActive }
        if (rules.isEmpty()) return this

        return chunked(GALLERY_PAGE_CONCURRENCY).flatMap { chunk ->
            coroutineScope {
                chunk.map { card ->
                    async {
                        val tags = runCatching { galleryTags(card.manga.url) }.getOrDefault(emptyList())
                        if (rules.all { it.matchesTags(tags) }) card else null
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }

    private suspend fun galleryTags(id: String): List<String> = client.get(galleryUrl(id)).parseHtml().rowValues("Contents").map { it.lowercase() }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Sort By is the only filter the site itself honours. Everything else is applied by the app."),
        SortFilter(),
        RatingFilter(),
        MinPagesFilter(),
        MaxPagesFilter(),
        ContentsFilter(),
        CategoryFilter(),
        LanguageFilter(),
        ParodyFilter(),
        Filter.Separator(),
        Filter.Header(
            "Tick once to keep a tag, twice to drop it. Categories, languages and parodies " +
                "are read from the listing, so excluded ones only drop what they actually print.",
        ),
        Filter.Header(
            "Contents tags are not on a listing: one ticked tag browses the site's own tag " +
                "page, while several (or a ticked-off one) load each gallery to read its tags.",
        ),
    )

    // ============================== Details & Chapters ==============================

    /** Every gallery is one complete work, so the id alone identifies title and chapter. */
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val document = client.get(galleryUrl(manga.url)).parseHtml()
        val details = document.toSMangaDetails(manga.url)

        // The site does not expose an upload date, so the fetch time keeps the library sorted.
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Gallery"
            chapter_number = 0f
            scanlator = null
            date_upload = System.currentTimeMillis()
        }

        return SMangaUpdate(details, listOf(chapter))
    }

    override fun getMangaUrl(manga: SManga): String = galleryUrl(manga.url).toString()

    private fun galleryUrl(id: String): HttpUrl = "$baseUrl/gallery/$id".toHttpUrl()

    // ============================== Pages ==============================

    override fun getChapterUrl(chapter: SChapter): String = galleryUrl(chapter.url).toString()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(galleryUrl(chapter.url)).parseHtml()

        val total = document.selectFirst("span[itemprop=numberOfPages]")?.text()?.trim()?.toIntOrNull()
            ?: throw Exception("Could not read page count")

        // Pages live next to the cover under a number that counts up from one.
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: throw Exception("Could not read image base url")

        return (1..total).map { number ->
            val imageUrl = cover.substringBeforeLast('/') + "/$number.$imageExtension"
            Page(number - 1, imageUrl = imageUrl)
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = IMAGE_EXT_PREF
            title = "Image file extension"
            summary = "Galleries normally serve .jpg pages. If pages fail to load with a 404, " +
                "the gallery uses another format - set it here, e.g. png."
            setDefaultValue("jpg")
        }.also(screen::addPreference)
    }

    // ============================== Parsing ==============================

    private fun Response.parseHtml(): Document = use { Jsoup.parse(it.body!!.string()) }

    private fun Document.hasNextPage(): Boolean {
        val pager = selectFirst("ul.pagination") ?: return false
        return pager.select("a[rel=next]").isNotEmpty()
    }

    private fun Document.galleryCards(): List<GalleryCard> = select("a.card.card-gallery")
        .mapNotNull { it.toGalleryCard() }

    /**
     * A listing card carries the gallery id and title on the link itself, a five star rating next to
     * it, and an info line reading
     * "<parody> Hentai <category> by <artist>, <language>, <pages> Pages".
     */
    private fun Element.toGalleryCard(): GalleryCard? {
        val id = attr("data-gid").takeIf { it.isNotEmpty() }
            ?: attr("href").trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotEmpty() && it != "gallery" }
            ?: return null

        val parsed = INFO_REGEX.find(selectFirst(".info")?.text().orEmpty().trim())
        val head = parsed?.groups?.get("head")?.value.orEmpty()

        // The category is the last part of the head, and the parody is whatever precedes "Hentai".
        val category = CATEGORIES.firstOrNull { head.endsWith(it) }
        val parody = head
            .removeSuffix(category.orEmpty())
            .trim()
            .removeSuffix("Hentai")
            .trim()
            .ifEmpty { null }

        val rated = selectFirst(".rating")
        val rating = rated?.let { it.select("i.full").size + it.select("i.half").size * 0.5 } ?: 0.0

        val manga = SManga.create().apply {
            url = id
            title = cleanTitle(
                this@toGalleryCard.attr("title").ifEmpty { selectFirst(".title")?.text().orEmpty() },
            )
            thumbnail_url = selectFirst("img.card-img-top")?.absUrl("src")?.takeIf { it.isNotBlank() }
            genre = listOfNotNull(parody, category).joinToString(", ").takeIf { it.isNotEmpty() }
            status = SManga.COMPLETED
        }

        return GalleryCard(
            manga = manga,
            parody = parody?.lowercase(),
            category = category?.lowercase(),
            language = parsed?.groups?.get("language")?.value?.trim()?.lowercase()?.ifEmpty { null },
            pages = parsed?.groups?.get("pages")?.value?.trim()?.toIntOrNull(),
            rating = rating,
        )
    }

    /**
     * The metadata table lists one row per collection, e.g. Parody, Contents, Language, Category.
     * Values are links when the row is a tag and plain text otherwise, such as the page count.
     */
    private fun Document.metadataRows(): List<Pair<String, List<String>>> = select("table.table-info tr").mapNotNull { row ->
        val cells = row.select("td")
        val label = cells.getOrNull(0)?.text()?.trim().orEmpty()
        val cell = cells.getOrNull(1) ?: return@mapNotNull null
        if (label.isEmpty()) return@mapNotNull null

        val values = cell.select("ul li a").eachText().map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOfNotNull(cell.text().trim().takeIf { it.isNotEmpty() }) }
        if (values.isEmpty()) null else label to values
    }

    private fun Document.rowValues(label: String): List<String> = metadataRows()
        .filter { (rowLabel, _) -> rowLabel.equals(label, ignoreCase = true) }
        .flatMap { (_, values) -> values }

    /** The metadata table reads Artist, Circle, Parody, Contents, ..., so its tags make the genres. */
    private fun Document.toSMangaDetails(id: String): SManga = SManga.create().apply {
        url = id
        title = cleanTitle(
            selectFirst("h1 span[itemprop=name]")?.text()?.trim().orEmpty().ifEmpty {
                selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            },
        )

        val rows = metadataRows()
        genre = rows
            .filterNot { (label, _) -> label.lowercase() in NON_GENRE_ROWS }
            .flatMap { (_, values) -> values }
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }

        artist = rowValues("Artist").joinToString().takeIf { it.isNotEmpty() }
        author = rowValues("Circle").joinToString().ifEmpty { rowValues("Group").joinToString() }
            .takeIf { it.isNotEmpty() }
        status = SManga.COMPLETED
        description = selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
            .substringBefore("Pururin is")
            .trim()
            .takeIf { it.isNotEmpty() }
        initialized = true
    }

    /**
     * A good few titles are written with `&quot;` on the site, e.g. `Beauti "Gal" Life`. The quote
     * itself makes the app draw an empty cell, so it is swapped for the plain apostrophe.
     */
    private fun cleanTitle(title: String): String = title.trim().replace('"', '\'')

    private companion object {
        private const val IMAGE_EXT_PREF = "pref_image_extension"

        private const val PER_PAGE = 20
        private const val MAX_SCAN_PAGES = 4
        private const val GALLERY_PAGE_CONCURRENCY = 6

        private val INFO_REGEX = Regex(
            """^(?<head>.*?) by (?<artist>.*?), (?<language>[A-Za-z ]+), (?<pages>\d+) Pages$""",
        )

        /** Longest first, so "Western CG" is not read as a trailing "CG". */
        private val CATEGORIES = listOf("Western CG", "Artist CG", "Game CG", "Doujinshi", "Comic", "Manga")

        private val NON_GENRE_ROWS = setOf(
            "title",
            "artist",
            "circle",
            "group",
            "uploader",
            "pages",
            "ranking",
            "ratings",
        )
    }
}

/** One listing card: the gallery itself plus everything a filter can be checked against. */
private class GalleryCard(
    val manga: SManga,
    val parody: String?,
    val category: String?,
    val language: String?,
    val pages: Int?,
    val rating: Double,
) {
    fun field(name: String): String? = when (name) {
        FIELD_PARODY -> parody
        FIELD_CATEGORY -> category
        FIELD_LANGUAGE -> language
        else -> null
    }

    fun matchesPages(min: Int?, max: Int?): Boolean {
        if (min == null && max == null) return true
        val pages = pages ?: return false
        return pages >= (min ?: 0) && pages <= (max ?: Int.MAX_VALUE)
    }
}

/**
 * Applies a group to a card, using only what the listing printed. Groups whose tags a listing does
 * not carry are left to [Pururin.filterByGalleryPages], so they pass here untouched.
 */
private fun TagGroupFilter.matchesListing(card: GalleryCard): Boolean {
    val field = listingField ?: return true
    val actual = card.field(field)
    if (excluded.any { it == actual }) return false
    return included.isEmpty() || (actual != null && included.any { it == actual })
}

/** Applies a group to the tag names a gallery page lists. */
private fun TagGroupFilter.matchesTags(tags: Collection<String>): Boolean {
    if (excluded.any { it in tags }) return false
    return included.isEmpty() || included.any { it in tags }
}
