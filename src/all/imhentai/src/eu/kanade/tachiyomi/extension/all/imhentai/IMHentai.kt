package eu.kanade.tachiyomi.extension.all.imhentai

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Source
abstract class IMHentai :
    KeiSource(),
    ConfigurableSource {

    private val baseHost get() = baseUrl.toHttpUrl().host

    private val prefs by getPreferencesLazy()

    /**
     * The language the site files this source's galleries under, with the flag that asks for it, or
     * null on the source that carries all of them.
     */
    private val pinnedLanguage: Pair<String, String>? by lazy {
        when (lang) {
            "en" -> "english" to "en"
            "ja" -> "japanese" to "jp"
            "es" -> "spanish" to "es"
            "fr" -> "french" to "fr"
            "ko" -> "korean" to "kr"
            "de" -> "german" to "de"
            "ru" -> "russian" to "ru"
            else -> null
        }
    }

    // The fallback is what an install that never opened the settings screen reads, so it has to
    // match the screen's default. The site's own page carries its own decorations - the event, the
    // circle and the artist, the language and the edition - and the description lists all of them,
    // so the title is shown without them.
    private val displayFullTitle: Boolean
        get() = prefs.getString(TITLE_PREF, "short") != "short"

    /** The site answers a plain OkHttp agent with a challenge page, so a browser agent is sent. */
    override fun Headers.Builder.configureHeaders(): Headers.Builder = setRandomUserAgent(filterInclude = listOf("chrome"))

    // Popular + Latest
    override suspend fun getPopularManga(page: Int): MangasPage = listing(page, POPULAR_PATH)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing(page, null)

    /** The site's own listing, either the newest uploads or the ones it has ranked the most popular. */
    private suspend fun listing(page: Int, sort: String?): MangasPage {
        val path = buildString {
            append('/')
            pinnedLanguage?.let { append("language/").append(it.first).append('/') }
            sort?.let { append(it).append('/') }
        }

        val url = "$baseUrl$path".toHttpUrl().newBuilder()
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()

        return client.get(url).asJsoup().parseResults(page)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        galleryIdFrom(query)?.let { id -> return MangasPage(listOf(getMangaById(id)), false) }

        if (filters.findWritten<RandomFilter>()?.state == true) return randomGallery()

        val offset = page + (filters.findWritten<OffsetPageFilter>()?.state?.toIntOrNull() ?: 0)
        val clauses = filters.clauses()
        val flags = searchFlags(filters)

        // A name kept out of the answer can only be asked of the site's advanced search, which reads
        // no search term of its own, so a reader who both writes a title and keeps a name out is
        // answered by the names alone.
        if (clauses.any { it.exclude }) {
            return client.get(advancedUrl(clauses, flags, offset)).asJsoup().parseResults(offset)
        }

        return search(query, clauses, flags, offset)
    }

    /**
     * The site matches the words of a title in the order they are written and reads no more of them
     * than it is given, so a title asked for as it is printed - both spellings the site files it
     * under, a pipe between them, and the decorations it was uploaded with - carries words no one
     * work has. The spellings are asked for one after the other, then the same spellings without
     * their decorations, and last the longest word on its own, for a query whose other words were
     * misspelled. The first answer that carries a gallery is the one the reader gets.
     *
     * A reader who ticks boxes without writing a title is asking for the galleries those boxes
     * allow, so the boxes are the whole question then, and with none of them ticked either the site
     * is asked for everything it carries with those flags.
     */
    private suspend fun search(query: String, clauses: List<Clause>, flags: List<Pair<String, String>>, page: Int): MangasPage {
        val written = query.words()

        if (written.isEmpty()) {
            return client.get(searchUrl("", clauses, flags, page)).asJsoup().parseResults(page)
        }

        for (attempt in query.attempts()) {
            val answer = client.get(searchUrl(attempt.term, clauses, flags, page)).asJsoup().parseResults(page)
            if (answer.mangas.isEmpty()) continue

            // A term that is not one of the reader's own spellings carries galleries that share no
            // more than a word, so the ones the reader's words fit are moved up front.
            return if (attempt.ranked) {
                MangasPage(answer.mangas.sortedByDescending { it.title.fit(written) }, answer.hasNextPage)
            } else {
                answer
            }
        }

        return MangasPage(emptyList(), false)
    }

    /**
     * The plain search: the words the reader wrote and the names they ticked, which the site reads
     * as one list of terms, and the flags that pick the kind, the language and the order.
     */
    private fun searchUrl(term: String, clauses: List<Clause>, flags: List<Pair<String, String>>, page: Int): HttpUrl {
        val terms = (listOf(term) + clauses.map { it.value }).filter(String::isNotBlank)

        return "$baseUrl/search/".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("key", terms.joinToString(",") { it.encodeTerm() })
            .apply { flags.forEach { (name, value) -> addQueryParameter(name, value) } }
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
    }

    /**
     * The site's advanced search, written in its own clause language: `+tag:"name"` asks for a name
     * and `-tag:"name"` keeps one out. It reads no search term, which is why it is only ever asked
     * with the names the reader asked for.
     */
    private fun advancedUrl(clauses: List<Clause>, flags: List<Pair<String, String>>, page: Int): HttpUrl = "$baseUrl/$ADVANCED_PATH/".toHttpUrl().newBuilder()
        .addEncodedQueryParameter("key", clauses.joinToString("+") { it.encoded() })
        .apply { flags.forEach { (name, value) -> addQueryParameter(name, value) } }
        .apply { if (page > 1) addQueryParameter("page", page.toString()) }
        .build()

    /**
     * The flags the site reads beside a search: the order to list the galleries in, the kinds of
     * work to ask for and the language. Every flag of a set is sent, since the site reads a flag it
     * is not given as one it should ask for.
     */
    private fun searchFlags(filters: FilterList): List<Pair<String, String>> {
        val sort = filters.findWritten<SortFilter>()?.selected ?: prefs.getString(SORT_PREF, SORT_DEFAULT)
        val sorts = SortFilter.SORTS.map { it.second to if (it.second == sort) "1" else "0" }

        val categories = filters.findWritten<CategoryFilter>()?.flags.orEmpty()
        val languages = pinnedLanguage
            ?.let { pinned -> LANGUAGE_FLAGS.map { it.second to if (it.second == pinned.second) "1" else "0" } }
            ?: filters.findWritten<LanguageFilter>()?.flags.orEmpty()

        return sorts + categories + languages
    }

    /** One gallery the site picks at random, which it answers with a redirect to that gallery. */
    private suspend fun randomGallery(): MangasPage {
        val response = client.get("$baseUrl/random/")
        val id = response.request.url.pathSegments.getOrNull(1)?.takeIf { it.all(Char::isDigit) }
            ?: throw IOException("IMHentai did not answer with a gallery")

        return MangasPage(listOf(getMangaById(id)), false)
    }

    private fun Document.parseResults(page: Int): MangasPage {
        // The site answers a search it finds too heavy to run with a page asking for a narrower one,
        // which reads as an empty listing unless it is caught.
        if (text().contains(OVERLOAD_MESSAGE)) {
            throw IOException("IMHentai is overloaded, try again in a moment or write a narrower search")
        }

        return MangasPage(select(THUMB_SELECTOR).mapNotNull { it.toManga() }, hasPageAfter(page))
    }

    /**
     * One gallery as a card prints it: where it sits, how its title is shown, and its cover. The
     * title is written out of whatever escapes the site left in it, and a title the site files with
     * its decorations - the event, the circle, the artist, the language, the edition - is shown
     * without them unless the reader asked for the whole thing.
     */
    private fun Element.toManga(): SManga? {
        val link = selectFirst(".inner_thumb a[href]")?.attr("href") ?: return null
        val id = link.trim('/').substringAfterLast('/')
        if (id.isEmpty()) return null

        return SManga.create().apply {
            val full = selectFirst(".caption")?.text()?.trim().orEmpty().unescaped()

            title = if (displayFullTitle) full else full.undecorated().ifEmpty { full }
            setUrlWithoutDomain("/$GALLERY_PATH/$id/")
            thumbnail_url = selectFirst(".inner_thumb img")?.imageUrl()
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    /**
     * Whether another page follows: the site's pager links to the pages it has left, and the page
     * the reader is on is printed as text rather than as a link.
     */
    private fun Document.hasPageAfter(page: Int): Boolean = select("ul.pagination a[href]").any { link ->
        link.attr("href").substringAfter("page=", "").substringBefore('&').toIntOrNull()?.let { it > page } == true
    }

    // Details + Chapters
    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    /**
     * A gallery asked for by its address. The reader's own address is answered as the gallery it
     * shows a page of, so a link to a page of a gallery opens the gallery itself rather than
     * nothing at all.
     */
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseHost) return null
        if (url.pathSegments.firstOrNull() !in GALLERY_PATHS) return null

        val id = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        return getMangaById(id)
    }

    private suspend fun getMangaById(id: String): SManga {
        val document = client.get("$baseUrl/$GALLERY_PATH/$id/").asJsoup()
        return parseMangaDetails(id, document, document.info())
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val id = manga.url.trim('/').substringAfterLast('/')
        val info = document.info()

        return SMangaUpdate(parseMangaDetails(id, document, info), parseChapterList(id, document, info))
    }

    private fun parseMangaDetails(id: String, document: Document, info: Map<String, String>): SManga = SManga.create().apply {
        initialized = true
        setUrlWithoutDomain("/$GALLERY_PATH/$id/")

        val fullTitle = document.selectFirst("h1")?.text()?.trim().orEmpty().unescaped()
        title = if (displayFullTitle) fullTitle else fullTitle.undecorated().ifEmpty { fullTitle }

        thumbnail_url = document.selectFirst(".left_cover img")?.imageUrl()

        // The site keeps the artist in the artist tags and the circle in the group tags, and a
        // gallery that names no circle is credited to its artist alone.
        artist = info[ARTISTS].orEmpty()
        author = info[GROUPS].orEmpty().ifEmpty { artist }

        genre = info[TAGS].orEmpty()

        description = document.description(fullTitle, info)

        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    /**
     * Every entry the site files a gallery under, as it lists them itself beside the title: the
     * tags it is marked with, the artist behind it, the circle, the work it continues and the
     * characters in it.
     */
    private fun Document.info(): Map<String, String> = select("ul.galleries_info > li").mapNotNull { line ->
        val label = line.selectFirst("span.tags_text")?.text()?.removeSuffix(":")?.trim() ?: return@mapNotNull null
        val names = line.select("a.tag").map { it.ownText().trim() }.filter(String::isNotBlank)

        names.takeIf(List<String>::isNotEmpty)?.let { label to it.joinToString().unescaped() }
    }.toMap()

    /**
     * What the site's own page says about a gallery that the title and the tags do not: the title as
     * it is filed, how many pages it holds, when it was posted and every entry it is filed under.
     */
    private fun Document.description(fullTitle: String, info: Map<String, String>): String = buildString {
        val alternative = selectFirst("p.subtitle")?.text()?.trim().orEmpty().unescaped()

        append("Full title:\n", fullTitle, "\n")
        if (alternative.isNotBlank()) append("Alternative title: ", alternative, "\n")

        pageCount().takeIf { it > 0 }?.let { append("\nPages: ", it, "\n") }
        postedAt().takeIf { it > 0 }?.let { append("Posted: ", it.asDate(), "\n") }

        DESCRIPTION_LINES.forEach { label -> info[label]?.let { append("\n", label, ": ", it, "\n") } }
    }

    private fun parseChapterList(id: String, document: Document, info: Map<String, String>): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Chapter"
            scanlator = info[GROUPS]
            date_upload = document.postedAt()
            setUrlWithoutDomain("/$GALLERY_PATH/$id/")
        },
    )

    /**
     * Whether the source offers the works the site calls nearest to a gallery, which is read by
     * Komikku alone.
     */
    override val supportsRelatedMangas = true

    /**
     * The row under a gallery, read from three places and ordered the way a reader reaches for them:
     *
     * 1. the other parts of the work, which the site files under titles of their own and numbers -
     *    the second, third and later galleries of one work - in the order they are numbered;
     * 2. the works the site files under the most of the gallery's own entries - the work it parodies,
     *    the tags it is marked with, the artist behind it - the most of them first;
     * 3. the galleries the site prints under it as "Related Galleries", kept for the works its own
     *    index matched on something that cannot be asked of it at all.
     *
     * A source pinned to a language answers with the works it carries that language in, since a row
     * holding another language would answer with something the source does not carry; the source that
     * carries every language answers with everything the site offers it.
     */
    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = runCatching {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty().unescaped()
        val row = document.relatedGalleries()
        val answers = document.entryAnswers(manga.url)

        // Every work the site offered, the ones its own row named first and then the ones its
        // searches answered, each with the entries it is filed under. A work both named carries
        // every entry either reading of it turned up.
        val offered = linkedMapOf<String, Offer>()
        (row + answers.works).forEach { offer ->
            val seen = offered[offer.manga.url]
            offered[offer.manga.url] = if (seen == null) {
                offer
            } else {
                Offer(seen.manga, seen.entries + offer.entries, seen.languages, seen.named)
            }
        }

        val rest = offered.values
            // A work a search answered that carries none of the gallery's entries is a work the
            // search matched on something else, and is left out; a work the site's own row named is
            // kept whether or not it carries any of them, since that row knows of what cannot be
            // asked of the site at all.
            .filter { offer -> offer.named || offer.entries.any(answers.shared::contains) }
            .filterNot { it.manga.url == manga.url }
            // The works sharing the most of the gallery's own entries first, and among the works
            // sharing as many as each other, the site's own order.
            .sortedByDescending { offer -> offer.entries.count(answers.shared::contains) }
            .map { it.manga }
        val parts = document.otherParts(title, row.map { it.manga } + rest)

        parts + rest.filterNot { work -> parts.any { it.url == work.url } }
    }.getOrDefault(emptyList())
        .take(MAX_RELATED)

    /** The galleries the site prints under a gallery, each read the way a listing reads one. */
    private fun Document.relatedGalleries(): List<Offer> {
        val pinned = pinnedLanguage?.first

        return select(RELATED_THUMB_SELECTOR)
            .filter { thumb -> pinned == null || thumb.carriesLanguage(pinned) }
            .mapNotNull { thumb ->
                thumb.toManga()?.let { Offer(it, thumb.entryNumbers(), thumb.languageFlags(), named = true) }
            }
            .distinctBy { it.manga.url }
    }

    /**
     * The other parts of one work: the galleries whose titles name the same work and a part of their
     * own, read in the order they are numbered. The site is asked for the work's name to find them,
     * since a part is filed under a title of its own and the row the site prints carries only the few
     * its index happened to match.
     */
    private suspend fun Document.otherParts(title: String, searched: List<SManga>): List<SManga> {
        val work = title.workTitle().takeIf(String::isNotEmpty) ?: return emptyList()
        val part = title.readPart()?.number

        return (searchPool(searchUrl(work, emptyList(), relatedFlags(), 1)) + searched)
            .filter { it.title.workTitle() == work && it.title.readPart()?.number != part }
            .sortedBy { it.title.readPart()?.number ?: Int.MAX_VALUE }
            .distinctBy { it.url }
    }

    /**
     * What the gallery's own entries answer: the works the site files under them, and the numbers the
     * site itself files the gallery under.
     */
    private class EntryAnswers(val works: List<Offer>, val shared: Set<String>)

    /**
     * The gallery's own entries, as the site lists them on its page, as many of each kind as are
     * asked for. What makes a gallery itself is its genre - the tags it is filed under - so most of
     * the searches are theirs, and the work it parodies, a character, the artist and the circle are
     * each asked for as well; every one of them is a request of the site, so each kind is bounded.
     */
    private fun Document.entryNames(): List<Pair<String, String>> {
        val listed = info()

        return ENTRY_KINDS.flatMap { (label, kind) ->
            listed[label].orEmpty().split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(ENTRY_LIMITS.getValue(kind))
                .map { kind to it }
        }
    }

    /**
     * Every one of the gallery's entries asked of the site's advanced search, which is what its own
     * row under a gallery cannot do: the site answers with the works filed under the entry, it files
     * the gallery being read under the entry too, and every card carries the numbers the site files
     * that work under, so what the gallery itself stands under is read off its own card and how many
     * of those entries another work carries is then counted rather than guessed at.
     *
     * They are asked a few at a time rather than one after the other, since a gallery is filed under
     * a dozen tags and the row would otherwise be as slow as the sum of every one of them.
     */
    private suspend fun Document.entryAnswers(mangaUrl: String): EntryAnswers {
        val entries = entryNames()
        val answers = coroutineScope {
            entries.chunked(ENTRY_PARALLEL).flatMap { batch ->
                batch.map { (kind, name) ->
                    async {
                        kind to searchCards(advancedUrl(listOf(Clause(kind, name, exclude = false)), relatedFlags(), 1))
                    }
                }.awaitAll()
            }
        }
        val works = mutableListOf<Offer>()
        val own = mutableSetOf<String>()
        val read = mutableSetOf<String>()

        for ((kind, cards) in answers) {
            cards.filter { it.manga.url == mangaUrl }.forEach { own += it.entries }
            read += cards.map(Offer::entries).sharedNumber(kind)
            works += cards.carrying(pinnedLanguage?.first)
        }

        // The gallery's own card carries every entry the site files it under, which is what the row
        // is ordered by, and a search for one of its entries is not always answered with it. Every
        // one of the entries at once is answered with the fewest works, so it is asked then, with
        // the language left open: what a gallery stands under is the same whichever language the
        // site printed beside it, and a source pinned to a language would not be answered with a
        // gallery it does not carry.
        if (own.isEmpty() && entries.isNotEmpty()) {
            val clauses = entries.map { (kind, name) -> Clause(kind, name, exclude = false) }
            val cards = searchCards(advancedUrl(clauses, openFlags(), 1))
            cards.filter { it.manga.url == mangaUrl }.forEach { own += it.entries }
            works += cards.carrying(pinnedLanguage?.first)
        }

        // With no card of its own anywhere, the entries it was asked for are read off the search for
        // each, as the number the whole answer carries, which is the gallery's own number for it.
        return EntryAnswers(works, own.ifEmpty { read })
    }

    /** The works out of a search that the source carries, when it is pinned to a language. */
    private fun List<Offer>.carrying(pinned: String?): List<Offer> = if (pinned == null) this else filter { pinned in it.languages }

    /**
     * The gallery's own number for one of its entries, read off the search asked for that entry. The
     * site files a gallery under the entry it is asked for, so the number the answer's cards carry in
     * common is that entry's own; a search that answers with works the entry does not stand for, or
     * with too few of them to tell, leaves the number the most of them carry.
     */
    private fun List<Set<String>>.sharedNumber(kind: String): Set<String> {
        val numbers = flatMap { entries -> entries.filter { it.startsWith("$kind:") } }
        val counts = numbers.groupingBy { it }.eachCount()
        val most = counts.values.maxOrNull() ?: return emptySet()

        return counts.filterValues { it == most }.keys
    }

    /** The works one search of the site answers with, or none when it cannot be asked at all. */
    private suspend fun searchPool(url: HttpUrl): List<SManga> = runCatching {
        client.get(url).asJsoup().select(THUMB_SELECTOR).mapNotNull { it.toManga() }
    }.getOrDefault(emptyList())

    /** The works one search of the site answers with, each with the entries it is filed under. */
    private suspend fun searchCards(url: HttpUrl): List<Offer> = runCatching {
        client.get(url).asJsoup().select(THUMB_SELECTOR)
            .mapNotNull { thumb ->
                thumb.toManga()?.let { Offer(it, thumb.entryNumbers(), thumb.languageFlags(), named = false) }
            }
    }.getOrDefault(emptyList())

    /**
     * The flags a related work is asked for with: every kind of work the site carries, listed in the
     * order the works under a gallery are wanted in, and the language the source carries.
     */
    private fun relatedFlags(): List<Pair<String, String>> {
        val pinned = pinnedLanguage?.second

        return SortFilter.SORTS.map { it.second to if (it.second == RELATED_SORT) "1" else "0" } +
            CategoryFilter.CATEGORY_FLAGS.map { it.second to "1" } +
            LANGUAGE_FLAGS.map { it.second to if (pinned == null || it.second == pinned) "1" else "0" }
    }

    /** The flags every language is asked for with, for a lookup that is not about the source's own. */
    private fun openFlags(): List<Pair<String, String>> = SortFilter.SORTS.map { it.second to if (it.second == RELATED_SORT) "1" else "0" } +
        CategoryFilter.CATEGORY_FLAGS.map { it.second to "1" } +
        LANGUAGE_FLAGS.map { it.second to "1" }

    /** The languages the site prints a card in, read off the flags it carries. */
    private fun Element.languageFlags(): List<String> = select(LANGUAGE_FLAG_SELECTOR)
        .map { it.attr("href").trim('/').substringAfterLast('/') }

    /**
     * Whether a card carries the language named, read off the flag the site prints on it. The site
     * prints one flag per card, so a gallery it files under two languages - a translation beside its
     * original, say - is read by the one of them the site chose to print.
     */
    private fun Element.carriesLanguage(language: String): Boolean = language in languageFlags()

    // Pages

    /**
     * The gallery's pages, read off the first page of the reader the site serves them with: it
     * writes down what every one of them is called, and the image it shows first names the folder
     * the whole gallery sits in, so one request answers for the whole chapter.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.trim('/').substringAfterLast('/')
        val reader = client.get("$baseUrl/$VIEW_PATH/$id/1/").asJsoup()

        val first = reader.selectFirst("img#gimg")?.imageUrl()
            ?: throw IOException("Could not find the gallery's first image")

        val folder = first.substringBeforeLast('/')
        val extension = first.substringAfterLast('.', "jpg")
        val pages = reader.pageSizes().ifEmpty {
            val count = client.get("$baseUrl/$GALLERY_PATH/$id/").asJsoup().pageCount()
            if (count == 0) throw IOException("Could not read the gallery's page list")

            List(count) { extension }
        }

        return pages.mapIndexed { index, written ->
            Page(
                index,
                url = "$baseUrl/$VIEW_PATH/$id/${index + 1}/",
                imageUrl = "$folder/${index + 1}.${written.imageExtension(extension)}",
            )
        }
    }

    /**
     * The file the reader says every page of a gallery is written in, one letter each, in the order
     * the pages are read.
     */
    private fun Document.pageSizes(): List<String> {
        val written = selectFirst("script:containsData(parseJSON)")?.data()
            ?.substringAfter("$.parseJSON('")
            ?.substringBefore("')")
            ?.trim()
            ?: return emptyList()

        return runCatching { written.parseAs<Map<String, String>>() }
            .getOrElse { return emptyList() }
            .entries
            .sortedBy { it.key.toIntOrNull() ?: 0 }
            .map { it.value.substringBefore(',') }
    }

    private fun Document.pageCount(): Int = selectFirst("li.pages")?.text()?.substringAfter("Pages:")?.trim()?.toIntOrNull() ?: 0

    /** The day a gallery was posted, which the site writes as `Posted: 3 years ago`. */
    private fun Document.postedAt(): Long {
        val written = selectFirst("li.posted")?.text()?.substringAfter("Posted:")?.trim() ?: return 0L
        val number = DIGITS.find(written)?.value?.toIntOrNull() ?: return 0L

        val field = when {
            written.contains("minute") -> Calendar.MINUTE
            written.contains("hour") -> Calendar.HOUR
            written.contains("day") -> Calendar.DAY_OF_MONTH
            written.contains("week") -> Calendar.WEEK_OF_YEAR
            written.contains("month") -> Calendar.MONTH
            written.contains("year") -> Calendar.YEAR
            else -> return 0L
        }

        return Calendar.getInstance().apply { add(field, -number) }.timeInMillis
    }

    // Preferences
    override fun getFilterList(data: JsonElement?): FilterList = getFilters(pinnedLanguage == null, SortFilter.indexOf(prefs.getString(SORT_PREF, SORT_DEFAULT)))

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = "Display title as"
            entries = arrayOf("Short title", "Full title")
            entryValues = arrayOf("short", "full")
            summary = "%s"
            setDefaultValue("short")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = SORT_PREF
            title = "Default sort when searching"
            entries = SortFilter.SORTS.map { it.first }.toTypedArray()
            entryValues = SortFilter.SORTS.map { it.second }.toTypedArray()
            summary = "%s"
            setDefaultValue(SORT_DEFAULT)
        }.also(screen::addPreference)

        screen.addRandomUAPreference()
    }

    companion object {
        private const val TITLE_PREF = "title_pref"
        private const val SORT_PREF = "sort_pref"
        private const val SORT_DEFAULT = "lt"

        private const val GALLERY_PATH = "gallery"
        private const val VIEW_PATH = "view"
        private const val ADVANCED_PATH = "advsearch"
        private const val POPULAR_PATH = "popular"
        private const val THUMB_SELECTOR = "div.thumb"

        /** The cards under a gallery, which sit in a row of their own rather than in a listing. */
        private const val RELATED_THUMB_SELECTOR = "div.row.related div.thumb"

        /** The flag a card carries, which names the language the site files the gallery under. */
        private const val LANGUAGE_FLAG_SELECTOR = "div.cat_flag a[href*=/language/]"

        /** The addresses a gallery is written at: its own, and the reader that shows its pages. */
        private val GALLERY_PATHS = listOf(GALLERY_PATH, VIEW_PATH)

        private const val PREFIX_ID_SEARCH = "id:"

        private const val TAGS = "Tags"
        private const val ARTISTS = "Artists"
        private const val GROUPS = "Groups"

        /** The entries the description prints, in the order the site lists them. */
        private val DESCRIPTION_LINES = listOf("Artists", "Groups", "Parodies", "Characters", "Languages", "Category")

        /**
         * The entries the row under a gallery is built from, in the order they are asked for: the
         * work it parodies is the series it belongs to, the tags are its genre, and a character, the
         * artist and the circle are what else the site files it under.
         */
        private val ENTRY_KINDS = listOf(
            "Parodies" to "parody",
            "Tags" to "tag",
            "Characters" to "character",
            "Artists" to "artist",
            "Groups" to "group",
        )

        /**
         * How many of each kind of a gallery's own entries are asked of the site for the row under
         * it. A gallery carries dozens of tags and only one or two of the rest, and the tags are
         * what its genre is, so they are what most of the searches are spent on.
         */
        private val ENTRY_LIMITS = mapOf(
            "parody" to 2,
            "tag" to 6,
            "character" to 1,
            "artist" to 1,
            "group" to 1,
        )

        /** How many of the row's searches are asked of the site at once. */
        private const val ENTRY_PARALLEL = 3

        /**
         * How many works the row under a gallery holds at most. A gallery is filed under a dozen
         * tags, so the works sharing them run well past what the row held when only three of the
         * gallery's entries were asked for.
         */
        private const val MAX_RELATED = 40

        /** The order the works under a gallery are wanted in, out of the orders the site offers. */
        private const val RELATED_SORT = "pp"

        /** The message the site answers a search it will not run with. */
        private const val OVERLOAD_MESSAGE = "Overload... Please use the advanced search"

        private val DIGITS = Regex("\\d+")

        /** A gallery can be asked for by its address, by `id:1234` or by its bare number. */
        private fun galleryIdFrom(query: String): String? {
            val trimmed = query.trim()
            val id = when {
                trimmed.startsWith(PREFIX_ID_SEARCH) -> trimmed.removePrefix(PREFIX_ID_SEARCH).trim()
                trimmed.isNotEmpty() && trimmed.all(Char::isDigit) -> trimmed
                trimmed.startsWith("http") -> trimmed.toHttpUrlOrNull()?.pathSegments?.getOrNull(1).orEmpty()
                else -> return null
            }

            return id.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        }
    }
}

/** The address an image really sits at: the site hands nearly all of them out as lazy-loaded data. */
private fun Element.imageUrl(): String? = when {
    hasAttr("data-src") -> absUrl("data-src")
    hasAttr("data-lazy-src") -> absUrl("data-lazy-src")
    hasAttr("data-cfsrc") -> absUrl("data-cfsrc")
    else -> absUrl("src")
}.takeIf(String::isNotBlank)

/**
 * The site files some of its titles with their escapes already written, so a title holding an
 * apostrophe arrives as the escape itself, which the page hands over as text and which then reads
 * on screen as the escape rather than as the mark. Anything still written as one is written out.
 */
private fun String.unescaped(): String = if (REMAINING_ENTITY.containsMatchIn(this)) Parser.unescapeEntities(this, false) else this

/** An escape in text that has already been read: the site writes some of them twice. */
private val REMAINING_ENTITY = Regex("&(?:#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")

/** A day, written the way the description shows it. */
private fun Long.asDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(this))

/** The file a page is written in, which the reader names with a single letter. */
private fun String.imageExtension(fallback: String): String = when (this) {
    "p" -> "png"
    "b" -> "bmp"
    "g" -> "gif"
    "w" -> "webp"
    "j" -> "jpg"
    else -> fallback
}

/** The part a title names itself with, and how many of its words that part is written with. */
private class TitlePart(val number: Int, val tokens: Int)

/**
 * The words of a title, with the marks the site leaves at its end taken off: a title is written with
 * its decorations in brackets, and what stands behind the last of them is the site's own mark rather
 * than a word of the work - the dash a parody used to be written behind, for one, which would
 * otherwise hide the number a part is written with.
 */
private fun String.titleTokens(): List<String> = undecorated()
    .split(WHITESPACE)
    .filter(String::isNotBlank)
    .dropLastWhile { token -> token.none(Char::isLetterOrDigit) }

/**
 * The part a title names at its end, read the way the site's own titles write one: a bare number, or
 * a number written after the word a part is called - `Part 2`, `Vol. 4`, `Chapter 3`. A title ending
 * in a year, a date or a word names no part, and neither does a number written after another number,
 * which is a range rather than a part of a work.
 */
private fun String.readPart(): TitlePart? {
    val tokens = titleTokens()
    if (tokens.size < 2) return null

    val written = tokens.last()
    written.toIntOrNull()?.takeIf { written.length <= PART_DIGITS } ?: return null

    val before = tokens[tokens.size - 2]
    if (before.toIntOrNull() != null) return null

    return if (before.trim(*PART_MARKS).lowercase() in PART_WORDS) TitlePart(written.toInt(), 2) else TitlePart(written.toInt(), 1)
}

/**
 * A title as the work it belongs to: the decorations taken off, and the part the title names taken
 * off with them, so that the parts of one work read as one title and can be told from the works
 * beside them. The site files a part of a work under a title of its own and nothing else, so the
 * words left are the only way to find the other parts of the work being read.
 */
private fun String.workTitle(): String = titleTokens()
    .dropLast(readPart()?.tokens ?: 0)
    .joinToString(" ")
    .lowercase()

/**
 * A work the site printed, with the entries the site's own numbers file it under, and whether the
 * site's own row under the gallery named it.
 */
private class Offer(
    val manga: SManga,
    val entries: Set<String>,
    val languages: List<String>,
    val named: Boolean,
)

/**
 * The entries a card is filed under, read off the card itself, by the attribute the site prints each
 * kind of entry in. A card of a listing or of a row under a gallery carries the work's own numbers
 * for the entries it stands under, and a number stands for one entry of one kind wherever it is
 * printed, so two works can be told from each other by what the site files them under.
 */
private fun Element.entryNumbers(): Set<String> = ENTRY_NUMBERS
    .flatMap { (attribute, kind) -> attr(attribute).split(' ').filter(String::isNotBlank).map { "$kind:$it" } }
    .toSet()

/** The attributes the site prints a work's own entry numbers in, by the kind of entry. */
private val ENTRY_NUMBERS = listOf(
    "data-parodies" to "parody",
    "data-tags" to "tag",
    "data-artists" to "artist",
    "data-characters" to "character",
    "data-groups" to "group",
)

/** The words a part of a work is numbered with, beside the number itself. */
private val PART_WORDS = setOf("part", "parts", "vol", "volume", "ch", "chapter", "ep", "episode")

/** The marks a number is written with, which are not words of their own. */
private val PART_MARKS = charArrayOf('.', ',', '-', '~', ':', '#', '+')

/** How many digits a number can have before it is read as something other than a part. */
private const val PART_DIGITS = 3

/** A clause as the site's own clause language writes it: `%2Btag:"big breasts"`, or `-tag:"..."`. */
private fun Clause.encoded(): String = buildString {
    append(if (exclude) "-" else "%2B")
    append(kind).append(':')
    append("%22").append(value.encodeTerm()).append("%22")
}

/**
 * A term or a name as the site's query string wants it: a space is written as a `+`, the few
 * characters that are safe everywhere are left alone, and everything else is escaped a byte at a
 * time, since a name carries whatever its readers wrote and some of them are not Latin.
 */
private fun String.encodeTerm(): String {
    val out = StringBuilder(length)

    for (byte in toByteArray(Charsets.UTF_8)) {
        val value = byte.toInt() and 0xFF
        val char = value.toChar()

        when {
            char == ' ' -> out.append('+')
            char.isLetterOrDigit() && value < 128 -> out.append(char)
            char in SAFE_CHARS -> out.append(char)
            else -> out.append('%').append(HEX[value shr 4]).append(HEX[value and 0xF])
        }
    }

    return out.toString()
}

private const val SAFE_CHARS = "-._~:"
private const val HEX = "0123456789ABCDEF"

/**
 * The terms a query is tried with, in the order they are asked for: each spelling of the title as
 * the site files it - it writes both of them, a pipe between them - then those spellings without
 * the decorations a title is uploaded with, which are not part of the words the site matches, and
 * last the longest words on their own, for a query whose other words were misspelled. A term that
 * is not one of the reader's own spellings carries galleries that share no more than a word with
 * what was asked for, so its answer is worth sorting; and how many terms are asked for at all is
 * kept low, since every one of them is a request of the site.
 */
private fun String.attempts(): List<Attempt> = buildList {
    val spellings = spellings()

    spellings.forEach { add(Attempt(it, ranked = false)) }
    spellings.map(String::undecorated).forEach { add(Attempt(it, ranked = true)) }
    spellings.firstOrNull()?.words()
        ?.filter { it.length >= SHORTEST_WORD }
        ?.sortedByDescending(String::length)
        ?.take(BROAD_WORDS)
        ?.forEach { add(Attempt(it, ranked = true)) }
}
    .filter { it.term.isNotBlank() }
    .distinctBy { it.term }
    .take(MAX_ATTEMPTS)

private class Attempt(val term: String, val ranked: Boolean)

/** The spellings a title is filed under, written by the site with a pipe between them. */
private fun String.spellings(): List<String> = split('|').map(String::trim).filter(String::isNotBlank)

/**
 * A title without the decorations it is uploaded with. The strip is written out rather than left to
 * a pattern because the regex engine on the device rejects some bracket patterns that a desktop
 * build accepts.
 */
private fun String.undecorated(): String {
    val stripped = StringBuilder(length)
    var depth = 0

    for (char in this) {
        when {
            char in BRACKET_OPEN -> {
                if (depth == 0) stripped.append(' ')
                depth++
            }
            char in BRACKET_CLOSE -> if (depth > 0) depth--
            depth == 0 -> stripped.append(char)
        }
    }

    return stripped.toString().replace(WHITESPACE, " ").trim()
}

/** Everything a word is written with that is not a letter or a digit, dropped. */
private fun String.words(): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()

    for (char in this) {
        if (char.isLetterOrDigit()) {
            word.append(char)
        } else {
            if (word.isNotEmpty()) words += word.toString()
            word.clear()
        }
    }

    if (word.isNotEmpty()) words += word.toString()

    return words
}

/** How many of the reader's words a title carries, so the nearest title can be read first. */
private fun String.fit(words: List<String>): Int {
    val title = lowercase().words()

    return words.count { word -> title.any { it.startsWith(word.lowercase()) } }
}

/** The brackets a title decorates itself with, the ones Japanese titles use included. */
private const val BRACKET_OPEN = "[({（【"
private const val BRACKET_CLOSE = "])}）】"

private val WHITESPACE = Regex("\\s+")

/** The length a word has to reach before it is worth a search on its own. */
private const val SHORTEST_WORD = 4

/** How many of the reader's longest words are asked for one by one when nothing else answers. */
private const val BROAD_WORDS = 2

/** How many terms one search is asked with at most, since each of them is a request of the site. */
private const val MAX_ATTEMPTS = 4

/**
 * Every clause the reader asked for from the tick boxes and the write-in boxes, which sit inside
 * the Advanced group rather than in the filter list itself.
 */
private fun FilterList.clauses(): List<Clause> {
    val groups = filterIsInstance<TagGroupFilter>()
    val written = filterIsInstance<TextFilter>() +
        filterIsInstance<Filter.Group<*>>().flatMap { it.state.filterIsInstance<TextFilter>() }

    return groups.flatMap { it.clauses } + written.flatMap { it.clauses }
}

/**
 * The boxes that only one answer is wanted from sit inside the Advanced group, so the groups are
 * searched as well.
 */
private inline fun <reified T : Filter<*>> FilterList.findWritten(): T? = filterIsInstance<T>().firstOrNull()
    ?: filterIsInstance<Filter.Group<*>>().flatMap { it.state.filterIsInstance<T>() }.firstOrNull()
