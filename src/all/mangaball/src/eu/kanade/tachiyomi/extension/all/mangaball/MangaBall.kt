package eu.kanade.tachiyomi.extension.all.mangaball

import android.util.Log
import androidx.preference.EditTextPreference
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
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The public site is a Next.js frontend; everything here actually talks to the JSON API on
 * api.mangaball.com, which is addressed by title slug for browsing and by Mongo title id for
 * chapter listings.
 *
 * A chapter is usually published by several external sites at once, so one of them is kept and the
 * others are hidden; the priority order setting decides which (see [sourceRanking]). That merging
 * can be turned off, and the sites the site serves are topped up in the background (see
 * [syncChapterSources]).
 */
@Source
abstract class MangaBall :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val adultMode: String
        get() = if (preferences.getBoolean(NSFW_PREF, false)) "no_18" else "all"

    private val titleIds = ConcurrentHashMap<String, String>()

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", filtersSortedBy(SortFilter.MOST_VIEWED))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", filtersSortedBy(SortFilter.LATEST_UPDATE))

    /** Browse is the same search endpoint, just with a fixed sort and no query. */
    private fun filtersSortedBy(sortIndex: Int) = getFilterList(null).apply {
        firstInstance<SortFilter>().state = sortIndex
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        SortOrderFilter(),
        TagModeFilter(),
        DemographicFilter(),
        OriginFilter(),
        StatusFilter(),
        ContentFilter(),
        FormatFilter(),
        GenreFilter(),
        ThemeFilter(),
        AdvancedFilterGroup(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$API_URL/title/search-advanced".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("keyword", query)
            .addQueryParameter("adult_mode", adultMode)
            .addQueryParameter("use_user_settings", "false")
            .addQueryParameter("sort_by", filters.firstInstance<SortFilter>().selected)
            .addQueryParameter("sort_order", filters.firstInstance<SortOrderFilter>().selected)
            .addQueryParameter("tag_mode", filters.firstInstance<TagModeFilter>().selected)

        filters.firstInstance<StatusFilter>().selected.takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("status", it) }

        // Ticked origins and demographics are sent to the API as one comma separated list. The
        // API cannot exclude them, so ticked-off ones are dropped from the answer afterwards.
        val origin = filters.firstInstance<OriginFilter>()
        val demographic = filters.firstInstance<DemographicFilter>()
        origin.included.takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("original_language", it.joinToString(",")) }
        demographic.included.takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("demographic", it.joinToString(",")) }

        val tagGroups = filters.filterIsInstance<TagGroupFilter>()
        val writtenTags = filters.findWritten<TagChoiceFilter>()
            ?.let { resolveTagQuery(it.state, tagGroups.flatMap(TagGroupFilter::tagOptions)) }
        // Themes get their own box, looked up only in the Theme group, so a name that exists both
        // as a genre and a theme (Comics) resolves to the theme tag alone.
        val writtenThemes = filters.findWritten<ThemeChoiceFilter>()
            ?.let { resolveTagQuery(it.state, filters.firstInstance<ThemeFilter>().tagOptions) }

        (tagGroups.flatMap { it.included } + writtenTags?.included.orEmpty() + writtenThemes?.included.orEmpty())
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("included_tags", it.joinToString(",")) }
        (tagGroups.flatMap { it.excluded } + writtenTags?.excluded.orEmpty() + writtenThemes?.excluded.orEmpty())
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("excluded_tags", it.joinToString(",")) }

        val answered = client.get(url.build()).parseAs<SearchResponse>()

        return if (origin.excluded.isEmpty() && demographic.excluded.isEmpty()) {
            answered.toMangasPage()
        } else {
            answered.dropping(origin.excludedLanguageCodes, demographic.excluded).toMangasPage()
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slugOrId = when (url.pathSegments.firstOrNull()) {
            "title-detail" -> url.pathSegments.getOrNull(1)
            // Chapter links only carry the chapter id, so the owning title is looked up first.
            "chapter-detail" -> url.pathSegments.getOrNull(1)?.let { chapterId ->
                client.get("$API_URL/chapter-detail?chapter_id=$chapterId")
                    .parseAs<ChapterDetailResponse>().data.chapter.titleId
            }
            else -> null
        } ?: return null

        return loadMangaDetails(SManga.create().apply { this.url = slugOrId })
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // The chapter endpoint is keyed by the Mongo id, which only the details response returns,
        // so a chapter-only update is the one case that skips the details request.
        val updated = if (fetchDetails) loadMangaDetails(manga) else manga

        return SMangaUpdate(
            manga = updated,
            chapters = if (fetchChapters) loadChapterList(updated.url) else chapters,
        )
    }

    private suspend fun loadMangaDetails(manga: SManga): SManga {
        val detail = client.get("$API_URL/title/detail/${manga.url}").parseAs<TitleDetailResponse>().data

        // Older library entries stored "<slug>-<id>", so both keys are cached to keep the
        // chapter listing free of an extra lookup.
        titleIds[manga.url] = detail.id
        titleIds[detail.slug] = detail.id

        return detail.toSManga()
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/title-detail/${manga.url}"

    private suspend fun loadChapterList(url: String): List<SChapter> {
        val body = JsonBody(ChapterListRequest(titleIdFor(url)).toJsonString())
        val listed = client.post("$API_URL/chapter/chapter-listing-by-title-id", body)
            .parseAs<ChapterListResponse>().data
            .filter { it.lang == null || it.lang in siteLang }

        // Remembered before anything is hidden, so a blacklisted group can be restored later.
        preferences.rememberScanlators(listed.mapNotNull { it.scanlator })
        val chapters = listed.filterBlacklistedScanlators(preferences.scanlatorBlacklist())

        // With merging turned off every site's version of a chapter is listed as it arrives.
        return if (mergeEnabled()) bestTranslationPerNumber(chapters) else chapters.map(Chapter::toSChapter)
    }

    /**
     * One row per chapter number: where several sites published the same number, only the highest
     * ranked of them survives. A number that a single site has is kept as it is, so a site that is
     * ahead of the ranked ones still shows the newest chapters nobody else has released yet.
     */
    private fun bestTranslationPerNumber(chapters: List<Chapter>): List<SChapter> {
        val ranking = sourceRanking()

        return chapters.groupBy { it.chapterNumber }
            .mapNotNull { (_, translations) -> pickBestTranslation(translations, ranking)?.toSChapter() }
    }

    /** The API exposes no page counts, so a known-good group wins over the newest upload. */
    private fun pickBestTranslation(translations: List<Chapter>, ranking: List<String>): Chapter? = translations.maxWithOrNull(
        compareBy(
            { chapter ->
                val rank = ranking.indexOfFirst { it in chapter.sourceNames }
                if (rank == -1) -1 else ranking.size - rank
            },
            { it.uploadedAt },
            { it.volumeNumber },
        ),
    )

    /** The sources the user ranked first, followed by the built-in order of reliable groups. */
    private fun sourceRanking(): List<String> = (currentSourceOrder() + RELIABLE_GROUPS).distinct()

    private suspend fun titleIdFor(url: String): String {
        titleIds[url]?.let { return it }
        directTitleId(url)?.let {
            titleIds[url] = it
            return it
        }

        val id = client.get("$API_URL/title/detail/$url").parseAs<TitleDetailResponse>().data.id
        titleIds[url] = id
        return id
    }

    /** Slugs are only resolved over the network when they do not already carry the id. */
    private fun directTitleId(url: String): String? = when {
        MONGO_ID.matches(url) -> url
        else -> url.substringAfterLast('-').takeIf { MONGO_ID.matches(it) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = client.get("$API_URL/chapter-detail?chapter_id=${chapter.url}")
            .parseAs<ChapterDetailResponse>().data.chapter.pages.orEmpty()

        return pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/chapter-detail/${chapter.url}"

    /** Chapters are published in many languages, so only the source's own ones are listed. */
    private val siteLang: List<String>
        get() = when (lang) {
            "es" -> listOf("es", "es-es", "es-la")
            "pt-BR" -> listOf("pt-br", "pt")
            "ko" -> listOf("ko", "kr")
            "zh" -> listOf("zh", "cn", "zh-cn", "zh-hk", "zh-tw")
            else -> listOf(lang)
        }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = NSFW_PREF
            title = "Hide NSFW titles"
            summary = "Leaves adult titles out of browse and search results."
            setDefaultValue(false)
        }.also(screen::addPreference)

        screen.addScanlatorBlacklistPreference(preferences)

        // A source screen only accepts plain preference rows, so there is no way to drag sources
        // into order; the ranking is typed instead, first name winning. It is added to the screen
        // after the switch that decides whether it is used at all.
        val orderPreference = EditTextPreference(screen.context).apply {
            key = SOURCE_ORDER_PREF
            title = "Priority order"
            dialogTitle = "Priority order"
            dialogMessage = "Comma separated, first is read first, for example mangadex, comick, " +
                "bato. When several sites published the same chapter the first of them is kept and " +
                "the rest are hidden, but a chapter only one site has is always listed."
            setDefaultValue("")
        }

        orderPreference.setOnPreferenceChangeListener { preference, value ->
            // The typed text is rewritten as a cleaned up list, so the framework must not also
            // store the raw string.
            val order = parseSourceOrder(value as? String)
            preferences.edit().putString(SOURCE_ORDER_PREF, order.joinToString(", ")).apply()
            (preference as EditTextPreference).summary = sourceOrderSummary(order)
            false
        }

        SwitchPreferenceCompat(screen.context).apply {
            key = MERGE_PREF
            title = "Merge duplicate chapters"
            summary = "Keeps one version of each chapter number, the priority order deciding " +
                "which; turned off, every site's versions are listed."
            setDefaultValue(true)
            isChecked = mergeEnabled()
            setOnPreferenceChangeListener { _, value ->
                applyMergeSetting(orderPreference, value as? Boolean ?: true)
                true
            }
        }.also(screen::addPreference)

        screen.addPreference(orderPreference)
        applyMergeSetting(orderPreference, mergeEnabled())

        // Everything the sync discovers shows up the next time this screen is opened.
        syncChapterSources()
    }

    private fun mergeEnabled(): Boolean = preferences.getBoolean(MERGE_PREF, true)

    /** The typed ranking only decides anything while duplicates are being merged. */
    private fun applyMergeSetting(orderPreference: EditTextPreference, merge: Boolean) {
        orderPreference.setEnabled(merge)
        orderPreference.summary = if (merge) {
            sourceOrderSummary(currentSourceOrder())
        } else {
            "Turn on merging duplicate chapters to rank sources."
        }
    }

    /** The typed order, e.g. "mangadex, comick bato" as the user wrote it. */
    private fun currentSourceOrder(): List<String> = parseSourceOrder(preferences.getString(SOURCE_ORDER_PREF, null))

    private fun parseSourceOrder(raw: String?): List<String> = raw.orEmpty()
        .split(',', ';', ' ', '\n')
        .map { it.trim().lowercase() }
        .filter(::isSiteSlug)
        .distinct()

    private fun sourceOrderSummary(order: List<String>): String {
        if (order.isEmpty()) return "Not set, so the built-in order decides. Tap to rank sources."

        // A name the site has never served is usually a typo, and silently ranking it would look
        // like the setting did nothing. Chapters are also ranked by uploader name, so the names
        // seen while browsing count as known as well.
        val known = chapterSources() + preferences.knownScanlatorNames().map { it.trim().lowercase() }
        val unknown = order.filterNot { it in known }
        val text = order.joinToString(" > ")
        return if (unknown.isEmpty()) text else "$text (unknown: ${unknown.joinToString(", ")})"
    }

    /** Bundled sources plus everything the last sync discovered. */
    private fun chapterSources(): List<String> {
        val synced = preferences.getStringSet(SYNCED_SOURCES_PREF, emptySet()).orEmpty()

        return (BUNDLED_SOURCES + synced)
            .map { it.trim().lowercase() }
            .filter(::isSiteSlug)
            .distinct()
            .sorted()
    }

    /**
     * The site picks up and drops chapter sources over time, so the known list is topped up instead
     * of staying frozen at the sources that existed when the extension was built. Throttled, and
     * errors are not fatal: the bundled list always works on its own.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun syncChapterSources() {
        val syncedAt = preferences.getLong(SOURCES_SYNCED_AT_PREF, 0L)
        if (System.currentTimeMillis() - syncedAt < SOURCE_SYNC_INTERVAL.inWholeMilliseconds) return

        GlobalScope.launch(Dispatchers.IO) {
            val discovered = runCatching { discoverChapterSources() }.getOrElse {
                Log.e(name, "Chapter source sync failed", it)
                return@launch
            }
            if (discovered.isEmpty()) return@launch

            // Kept alongside what is already known rather than replacing it: a source that none of
            // the sampled titles happens to use this time must not disappear from the list. The
            // stored set is re-filtered so entries an older build wrongly added are dropped too.
            val known = preferences.getStringSet(SYNCED_SOURCES_PREF, emptySet()).orEmpty()
                .map { it.trim().lowercase() }
                .filter(::isSiteSlug)
                .toSet()
            preferences.edit()
                .putStringSet(SYNCED_SOURCES_PREF, known + discovered)
                .putLong(SOURCES_SYNCED_AT_PREF, System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * Site slugs are not listed anywhere - only the chapters themselves carry them - so a few
     * popular and a few freshly updated titles are sampled and every site they mention is
     * collected. Bounded by a time budget because one long-running title can answer with
     * thousands of chapters; a failed or cut-off sample just yields a shorter list.
     */
    private suspend fun discoverChapterSources(): Set<String> {
        val found = mutableSetOf<String>()
        val titleIds = SOURCE_SAMPLE_SORTS.flatMap { sort ->
            runCatching {
                client.get(
                    "$API_URL/title/search-advanced?page=1&limit=$SOURCE_SAMPLE_TITLES&keyword=&adult_mode=all" +
                        "&use_user_settings=false&sort_by=$sort&sort_order=desc",
                ).parseAs<SearchResponse>().titleIds
            }.getOrDefault(emptyList())
        }.distinct()

        withTimeoutOrNull(SOURCE_SYNC_BUDGET) {
            for (titleId in titleIds) {
                runCatching {
                    client.post(
                        "$API_URL/chapter/chapter-listing-by-title-id",
                        JsonBody(ChapterListRequest(titleId).toJsonString()),
                    ).parseAs<ChapterListResponse>().data.mapNotNullTo(found) { it.siteSlug }
                }
            }
        }

        return found.map { it.trim().lowercase() }.filter(::isSiteSlug).toSet()
    }
}

/**
 * The API answers 400 "Missing title_id" for any content type other than a bare
 * `application/json`, but OkHttp's string body helpers append a `charset=utf-8` parameter to
 * the media type on Android, so the body is written by hand here.
 */
private class JsonBody(private val json: String) : RequestBody() {
    override fun contentType(): MediaType = JSON_MEDIA_TYPE

    override fun contentLength(): Long = json.toByteArray().size.toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(json)
    }
}

private val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

private const val API_URL = "https://api.mangaball.com/api/v1"
private const val PAGE_SIZE = 24
private const val NSFW_PREF = "pref_hide_nsfw"
private const val MERGE_PREF = "pref_merge_duplicate_chapters"
private const val SYNCED_SOURCES_PREF = "pref_synced_chapter_sources"
private const val SOURCES_SYNCED_AT_PREF = "pref_chapter_sources_synced_at"
private const val SOURCE_ORDER_PREF = "pref_source_priority_order"
private val SOURCE_SAMPLE_SORTS = listOf("views", "lastupdate")
private const val SOURCE_SAMPLE_TITLES = 3
private val SOURCE_SYNC_BUDGET = 30.seconds
private val SOURCE_SYNC_INTERVAL = 7.days

/**
 * The write-in boxes sit inside the Advanced group rather than in the filter list itself, so the
 * groups are searched as well.
 */
private inline fun <reified T : Filter<*>> FilterList.findWritten(): T? = filterIsInstance<T>().firstOrNull()
    ?: filterIsInstance<Filter.Group<*>>().flatMap { it.state.filterIsInstance<T>() }.firstOrNull()

private val MONGO_ID = Regex("^[0-9a-f]{24}$")
private val SITE_SLUG = Regex("^[a-z0-9][a-z0-9._-]{1,31}$")

/**
 * Site slugs are what the chapter listings carry, but group entries expose 24-hex Mongo ids that
 * fit [SITE_SLUG] just as well, so those ids are rejected here.
 */
private fun isSiteSlug(value: String): Boolean = value.isNotEmpty() && SITE_SLUG.matches(value) && !MONGO_ID.matches(value)

/** Groups that keep their pages online long term, best first. */
private val RELIABLE_GROUPS = listOf(
    "mangadistrict",
    "mangadot",
    "mangadex",
    "comick",
    "atsu",
    "mangahub",
)

/**
 * Chapter sources known to publish on the site when this build was made; the sync tops these up
 * with whatever it finds later.
 */
private val BUNDLED_SOURCES = listOf(
    "atsu",
    "bato",
    "comick",
    "comix",
    "daomeoden",
    "flowermanga",
    "godamh",
    "harimanga",
    "hiperdex",
    "kingofshojo",
    "kiryuu",
    "komikcast",
    "komikindo",
    "komiku",
    "leercapitulo",
    "lelmanga",
    "lelscanfr",
    "likemanga",
    "manga18",
    "mangabr",
    "mangabuddy",
    "mangadistrict",
    "mangadex",
    "mangadot",
    "mangahub",
    "mangakatana",
    "mangalivre",
    "mangaonline",
    "manhwaclub",
    "manhwaden",
    "manhwaread",
    "mugiwaras",
    "oremanga",
    "rawdex",
    "scanita",
    "tiamanhwa",
    "xbat",
    "zinmanga",
)
