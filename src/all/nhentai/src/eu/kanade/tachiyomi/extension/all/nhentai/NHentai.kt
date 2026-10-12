package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import android.webkit.CookieManager
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.formatUploadDate
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getArtists
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getGroups
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTagDescription
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTags
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTitleNotes
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.shortenTitle
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NHentai :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl get() = "$baseUrl/api/v2"
    private val baseHost get() = baseUrl.toHttpUrl().host

    private val prefs by getPreferencesLazy()

    /** The language the site files this source's works under; the multi source asks for all. */
    private val nhLang by lazy {
        when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            else -> ""
        }
    }

    // The fallback is what an install that never opened the settings screen reads, so it has to
    // match the screen's default. Full titles keep the event, circle, language and scanlator
    // decorations, and the description lists all of them, so the title is shown clean.
    private val displayFullTitle: Boolean
        get() = prefs.getString(TITLE_PREF, "short") != "short"

    private val apiKey: String get() = prefs.getString(API_KEY, "").orEmpty()

    private val cookieToken: String
        get() = CookieManager.getInstance().getCookie(baseUrl)
            ?.split("; ")
            ?.firstOrNull { it.startsWith("access_token=") }
            ?.removePrefix("access_token=")
            .orEmpty()

    // Picture servers
    private var cachedConfig: NHConfig? = null

    private suspend fun config(): NHConfig = cachedConfig ?: runCatching {
        client.get("$apiUrl/config").parseAs<NHConfig>()
    }.getOrElse { NHConfig() }.also { cachedConfig = it }

    private suspend fun imageServer() = config().imageServers.ifEmpty { DEFAULT_IMAGE_SERVERS }.random()

    private suspend fun thumbServer() = config().thumbServers.ifEmpty { DEFAULT_THUMB_SERVERS }.random()

    /** The site answers a plain OkHttp agent with a challenge page, so a browser agent is sent. */
    override fun Headers.Builder.configureHeaders(): Headers.Builder = setRandomUserAgent(filterInclude = listOf("chrome"))

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        val (permits, period) = prefs.parseRateLimit()
        rateLimit(permits, period.seconds) { it.host == baseHost }

        // A gallery never changes once it is uploaded, so its answer is worth keeping around.
        addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!GALLERY_PATH_REGEX.matches(response.request.url.encodedPath)) return@addNetworkInterceptor response

            response.newBuilder()
                .removeHeader("Cache-Control")
                .removeHeader("Expires")
                .removeHeader("Pragma")
                .header("Cache-Control", "max-age=$GALLERY_CACHE_MAX_AGE_SECONDS")
                .build()
        }

        // The site hands out a short backoff when it is asked too quickly; one immediate retry is
        // enough to ride it out.
        addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != baseHost || !API_PATH_REGEX.matches(request.url.encodedPath)) {
                return@addInterceptor chain.proceed(request)
            }

            val response = chain.proceed(request)
            val retryAfter = response.header("Retry-After")?.trim()
            if (response.code != 429 || request.header(BACKOFF_RETRY_HEADER) != null) return@addInterceptor response
            if (!retryAfter.isNullOrEmpty() && retryAfter.toLongOrNull() != 0L) return@addInterceptor response

            response.close()
            chain.proceed(request.newBuilder().header(BACKOFF_RETRY_HEADER, "1").build())
        }

        // Favorites and other personal answers need an API key, or the token of a signed-in WebView.
        addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != baseHost || !API_PATH_REGEX.matches(request.url.encodedPath)) {
                return@addNetworkInterceptor chain.proceed(request)
            }

            val authorization = when {
                apiKey.isNotBlank() -> "Key $apiKey"
                request.url.encodedPath.contains("/favorites") && cookieToken.isNotBlank() -> "User $cookieToken"
                else -> null
            } ?: return@addNetworkInterceptor chain.proceed(request)

            val response = chain.proceed(request.newBuilder().addHeader("Authorization", authorization).build())
            if (response.code == 401) {
                response.close()
                throw IOException(
                    if (apiKey.isNotBlank()) "Invalid API key" else "Log in via WebView or add an API key in the settings to view favorites",
                )
            }
            response
        }
    }

    // Popular + Latest
    override suspend fun getPopularManga(page: Int): MangasPage = searchTerm(terms(nhLangSearch(), emptyList()), page, "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = if (nhLang.isBlank()) {
        listing(page)
    } else {
        // `date` is the order the site's own listing comes in, newest uploads first.
        searchTerm(terms("language:$nhLang", emptyList()), page, "date")
    }

    private suspend fun listing(page: Int): MangasPage {
        val url = "$apiUrl/galleries".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()

        return client.get(url).parseAs<PaginatedResponse<GalleryItem>>().toMangasPage(page)
    }

    /** Searching needs a term; a quoted empty string is how the site is asked for everything. */
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val galleryId = galleryIdFrom(query)
        if (galleryId != null) {
            return MangasPage(listOf(parseMangaDetails(fetchDetail(galleryId))), false)
        }

        val offset = page + (filters.findWritten<OffsetPageFilter>()?.state?.toIntOrNull() ?: 0)
        val clauses = buildList {
            nhLangSearch().takeIf(String::isNotEmpty)?.let(::add)
            addAll(filters.clauses())
        }

        if (filters.findWritten<FavoriteFilter>()?.state == true) {
            val url = "$apiUrl/favorites".toHttpUrl().newBuilder()
                .addQueryParameter("q", terms(query.firstSpelling(), clauses))
                .addQueryParameter("page", offset.toString())
                .build()

            return client.get(url).parseAs<PaginatedResponse<GalleryItem>>().toMangasPage(offset)
        }

        return search(query, clauses, offset, filters.findWritten<SortFilter>()?.selected)
    }

    /**
     * The reader's query read the way the site's search reads it: every word of it has to be found
     * in the title or the tags of a work and is matched from its beginning, so a title asked for as
     * it is printed - both spellings the site files it under, a pipe between them, and the
     * decorations it was uploaded with - carries words no one work has. The spellings are asked for
     * one after the other, then the words of the first with their ends cut off, which the site
     * still matches, and last the longest word on its own, which is what is left of a query whose
     * other words were misspelled. The first answer that carries a work is the one the reader gets.
     *
     * A reader who ticks boxes without writing a title is asking for the works those boxes allow, so
     * the boxes are the whole question then, and with none of them ticked either the site is asked
     * for everything it carries - which is what a search written as nothing means.
     */
    private suspend fun search(query: String, clauses: List<String>, page: Int, sort: String?): MangasPage {
        val written = query.words()

        if (written.isEmpty()) return searchTerm(terms(query, clauses), page, sort)

        for ((attempt, broad) in query.attempts()) {
            val answer = searchTerm(terms(attempt, clauses), page, sort)
            if (answer.mangas.isEmpty()) continue

            // A broad answer carries the works that share no more than a word, so the ones the
            // reader's own words fit are moved up front for the reader to pick from.
            return if (broad) {
                MangasPage(answer.mangas.sortedByDescending { it.title.fit(written) }, answer.hasNextPage)
            } else {
                answer
            }
        }

        return MangasPage(emptyList(), false)
    }

    private suspend fun searchTerm(term: String, page: Int, sort: String?): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", term.ifBlank { "\"\"" })
            .addQueryParameter("page", page.toString())
            .apply { if (!sort.isNullOrEmpty()) addQueryParameter("sort", sort) }
            .build()

        return client.get(url).parseAs<PaginatedResponse<GalleryItem>>().toMangasPage(page)
    }

    private fun PaginatedResponse<GalleryItem>.toMangasPage(currentPage: Int): MangasPage {
        val hasNextPage = when {
            numPages != null -> numPages > currentPage
            total != null -> total > currentPage * perPage
            else -> result.isNotEmpty()
        }

        val thumb = fallbackThumbServer()

        return MangasPage(result.map { it.toSManga(thumb) }, hasNextPage)
    }

    private fun GalleryItem.toSManga(thumb: String): SManga = SManga.create().apply {
        title = (englishTitle ?: japaneseTitle).orEmpty().let { if (displayFullTitle) it else it.shortenTitle() }
        setUrlWithoutDomain(galleryUrl(id))
        thumbnail_url = "$thumb/$thumbnail"
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(nhLang.isBlank(), SortFilter.indexOf(prefs.getString(SORT_PREF, "popular")))

    // Details + Chapters
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseHost) return null
        if (url.pathSegments.getOrNull(0) != "g") return null

        return url.pathSegments.getOrNull(1)?.toIntOrNull()?.let { id ->
            parseMangaDetails(fetchDetail(id))
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detail = fetchDetail(galleryIdOf(manga.url))
        return SMangaUpdate(parseMangaDetails(detail), parseChapterList(detail))
    }

    private suspend fun fetchDetail(id: Int) = client.get("$apiUrl/galleries/$id").parseAs<Hentai>()

    private suspend fun parseMangaDetails(data: Hentai): SManga = SManga.create().apply {
        initialized = true
        setUrlWithoutDomain(galleryUrl(data.id))

        title = if (displayFullTitle) {
            data.title.best
        } else {
            (data.title.pretty ?: data.title.best).shortenTitle()
        }

        thumbnail_url = "${thumbServer()}/${data.thumbnail.path}"

        // The site keeps the artist in the artist tags and the circle in the group tags, and a
        // gallery that names no circle is credited to its artist alone.
        artist = getArtists(data)
        author = getGroups(data) ?: artist

        genre = getTags(data)

        description = buildString {
            val primary = data.title.english?.takeIf(String::isNotBlank)
                ?: data.title.japanese?.takeIf(String::isNotBlank)
                ?: data.title.pretty.orEmpty()
            val japanese = data.title.japanese.orEmpty()

            append("Full English and Japanese titles:\n")
            append(primary, "\n")
            // One gallery often has only one of the two titles; printing it twice helps nobody.
            if (japanese.isNotBlank() && japanese != primary) append(japanese, "\n")
            append("\nPages: ", data.numPages, "\n")
            data.uploadedDate?.let { append("Uploaded: ", formatUploadDate(it), "\n") }
            append("Favorited by: ", data.numFavorites, "\n")
            append(getTagDescription(data))
            getTitleNotes(data)?.let { append("Title notes: ", it, "\n") }
        }

        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun parseChapterList(data: Hentai): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Chapter"
            scanlator = getGroups(data)
            date_upload = data.uploadedAt
            setUrlWithoutDomain(galleryUrl(data.id))
        },
    )

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val detail = fetchDetail(galleryIdOf(chapter.url))
        val server = imageServer()

        return detail.pages.mapIndexed { index, image -> Page(index, imageUrl = "$server/${image.path}") }
    }

    // Preferences
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
            setDefaultValue("popular")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = API_KEY
            title = "API key"
            summary = "Profile > Settings > API Keys. Used for favorites and a higher rate limit."
            setDefaultValue("")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = RATE_LIMIT_PREF
            title = "Network rate limit"
            summary = "%s (restart the app to apply)"
            entries = RATE_LIMIT_OPTIONS.map { it.first }.toTypedArray()
            entryValues = RATE_LIMIT_OPTIONS.map { it.second }.toTypedArray()
            setDefaultValue(RATE_LIMIT_DEFAULT)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Restart the app to apply", Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)

        screen.addRandomUAPreference()
    }

    private fun SharedPreferences.parseRateLimit(): Pair<Int, Long> = parseRateLimitString(getString(RATE_LIMIT_PREF, RATE_LIMIT_DEFAULT).orEmpty())
        ?: parseRateLimitString(RATE_LIMIT_DEFAULT)!!

    private fun parseRateLimitString(raw: String): Pair<Int, Long>? {
        val parts = raw.split("/", limit = 2)
        if (parts.size != 2) return null

        val permits = parts[0].toIntOrNull() ?: return null
        val period = parts[1].toLongOrNull() ?: return null

        return permits.coerceIn(RATE_LIMIT_MIN_PERMITS, RATE_LIMIT_MAX_PERMITS) to
            period.coerceIn(RATE_LIMIT_MIN_PERIOD_SECONDS, RATE_LIMIT_MAX_PERIOD_SECONDS)
    }

    /**
     * The thumbnails a listing carries are asked for before the server list has been read, so the
     * first answer falls back to the servers the site has always used.
     */
    private fun fallbackThumbServer() = DEFAULT_THUMB_SERVERS.first()

    /**
     * The term a search has to carry: the language this source is pinned to, or an empty phrase on
     * the multi source, which is how the site is asked for everything it has.
     */
    private fun nhLangSearch() = if (nhLang.isBlank()) "\"\"" else "language:$nhLang"

    companion object {
        private const val TITLE_PREF = "title_pref"
        private const val SORT_PREF = "sort_pref"
        private const val API_KEY = "api_key"
        private const val RATE_LIMIT_PREF = "rate_limit_pref"
        private const val RATE_LIMIT_DEFAULT = "1/1"
        private const val RATE_LIMIT_MIN_PERMITS = 1
        private const val RATE_LIMIT_MAX_PERMITS = 10
        private const val RATE_LIMIT_MIN_PERIOD_SECONDS = 1L
        private const val RATE_LIMIT_MAX_PERIOD_SECONDS = 60L
        private const val BACKOFF_RETRY_HEADER = "NHentai-Backoff-Retry"
        private const val GALLERY_CACHE_MAX_AGE_SECONDS = 7200

        private val RATE_LIMIT_OPTIONS = listOf(
            "0.25 rps" to "1/4",
            "0.5 rps" to "1/2",
            "1 rps (default)" to "1/1",
            "2 rps" to "2/1",
            "4 rps (recommended with an API key)" to "4/1",
        )

        private val DEFAULT_IMAGE_SERVERS = (1..4).map { "https://i$it.nhentai.net" }
        private val DEFAULT_THUMB_SERVERS = (1..4).map { "https://t$it.nhentai.net" }

        private val API_PATH_REGEX = Regex("^/api/v2/.*$")
        private val GALLERY_PATH_REGEX = Regex("^/api/v2/galleries/\\d+/?$")

        private const val PREFIX_ID_SEARCH = "id:"

        private fun galleryUrl(id: Int) = "/g/$id/"

        private fun galleryIdOf(url: String) = url.trim('/').substringAfterLast('/').toInt()

        /** A gallery can be asked for by its address, by `id:1234` or by its bare number. */
        private fun galleryIdFrom(query: String): Int? {
            val trimmed = query.trim()
            return when {
                trimmed.startsWith(PREFIX_ID_SEARCH) -> trimmed.removePrefix(PREFIX_ID_SEARCH).toIntOrNull()
                trimmed.toIntOrNull() != null -> trimmed.toInt()
                trimmed.startsWith("http") -> trimmed.toHttpUrl().pathSegments.getOrNull(1)?.toIntOrNull()
                else -> null
            }
        }
    }
}

/** The term a search is asked with: what the reader wrote and the clauses they asked for. */
private fun terms(query: String, clauses: List<String>): String = (listOf(query) + clauses)
    .filter(String::isNotBlank)
    .joinToString(" ")

/**
 * The terms a query is tried with, in the order they are asked for: each spelling of the title as
 * the site files it, then the first spelling with every word cut to its beginning - which the site
 * still matches, and which forgives a misspelled ending, an apostrophe and the decorations a title
 * was uploaded with - and last the longest words on their own, for a query whose other words were
 * misspelled. The broad terms are the ones whose answers are worth sorting, and how many of them
 * are asked for at all is kept low, since every one of them is a request of the site. A term
 * already asked for is not asked twice, so a single-word query is not read over and over.
 */
private fun String.attempts(): List<Pair<String, Boolean>> = buildList {
    val spellings = spellings()
    val first = spellings.firstOrNull() ?: trim()
    val cut = first.cutWords()
    val longest = first.words().sortedByDescending(String::length).take(BROAD_WORDS).map { it.take(PREFIX_LENGTH) }

    spellings.forEach { add(it to false) }
    if (cut.isNotEmpty()) add(cut to false)
    longest.filter { it.length >= SHORTEST_WORD }.forEach { add(it to true) }
}.distinctBy { it.first }.take(MAX_ATTEMPTS)

/**
 * The spellings a title is filed under, written by the site with a pipe between them, and by this
 * extension the same way, so a search made from a title the app shows carries both.
 */
private fun String.spellings(): List<String> = split('|').map(String::trim).filter(String::isNotBlank)

/** The title as the reader is shown it, which is the spelling the site lists first. */
private fun String.firstSpelling(): String = spellings().firstOrNull() ?: trim()

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

/** The words of a query with their ends cut off, which the site's search still matches. */
private fun String.cutWords(): String = words().joinToString(" ") { it.take(PREFIX_LENGTH) }

/** How many of the reader's words a title carries, so the nearest title can be read first. */
private fun String.fit(words: List<String>): Int {
    val title = lowercase().words()

    return words.count { word -> title.any { it.startsWith(word.lowercase()) } }
}

/** The length a word is asked with: the site matches a word from its beginning. */
private const val PREFIX_LENGTH = 5

/** The length a name has to reach before it is worth a search on its own. */
private const val SHORTEST_WORD = 4

/** How many of the reader's longest words are asked for one by one when nothing else answers. */
private const val BROAD_WORDS = 2

/** How many terms one search is asked with at most, since each of them is a request of the site. */
private const val MAX_ATTEMPTS = 4

/**
 * Every clause the reader asked for from the tick boxes and the write-in boxes, which sit inside
 * the Advanced group rather than in the filter list itself.
 */
private fun FilterList.clauses(): List<String> {
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
