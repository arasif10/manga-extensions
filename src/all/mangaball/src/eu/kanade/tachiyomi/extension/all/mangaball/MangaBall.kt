package eu.kanade.tachiyomi.extension.all.mangaball

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.ConcurrentHashMap

/**
 * The public site is a Next.js frontend; everything here actually talks to the JSON API on
 * api.mangaball.com, which is addressed by title slug for browsing and by Mongo title id for
 * chapter listings.
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
        StatusFilter(),
        OriginFilter(),
        ContentFilter(),
        FormatFilter(),
        GenreFilter(),
        ThemeFilter(),
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

        filters.firstInstance<StatusFilter>().selected
            .takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("status", it) }
        filters.firstInstance<OriginFilter>().selected
            .takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("original_language", it) }
        filters.firstInstance<DemographicFilter>().selected
            .takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("demographic", it) }

        val tagGroups = filters.filterIsInstance<TagGroupFilter>()
        tagGroups.flatMap { it.included }
            .takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("included_tags", it.joinToString(",")) }
        tagGroups.flatMap { it.excluded }
            .takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("excluded_tags", it.joinToString(",")) }

        return client.get(url.build()).parseAs<SearchResponse>().toMangasPage()
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
        val chapters = client.post("$API_URL/chapter/chapter-listing-by-title-id", body)
            .parseAs<ChapterListResponse>().data
            .filter { it.lang == null || it.lang in siteLang }

        return when (val mode = preferences.getString(CHAPTER_SOURCE_PREF, PREF_AUTO).orEmpty()) {
            PREF_ALL -> chapters.map(Chapter::toSChapter)
            PREF_AUTO, "" -> bestTranslationPerNumber(chapters)
            else -> chapters.filter { mode in it.sourceNames }
                .map(Chapter::toSChapter)
                // Never open with an empty chapter list when the chosen group has not picked
                // this title up: fall back to the best available translation.
                .ifEmpty { bestTranslationPerNumber(chapters) }
        }
    }

    private fun bestTranslationPerNumber(chapters: List<Chapter>): List<SChapter> = chapters
        .groupBy { it.chapterNumber }
        .mapNotNull { (_, translations) -> pickBestTranslation(translations)?.toSChapter() }

    /** The API exposes no page counts, so a known-good group wins over the newest upload. */
    private fun pickBestTranslation(translations: List<Chapter>): Chapter? = translations.maxWithOrNull(
        compareBy(
            { chapter ->
                val rank = RELIABLE_GROUPS.indexOfFirst { it in chapter.sourceNames }
                if (rank == -1) -1 else RELIABLE_GROUPS.size - rank
            },
            { it.uploadedAt },
            { it.volumeNumber },
        ),
    )

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

        ListPreference(screen.context).apply {
            key = CHAPTER_SOURCE_PREF
            title = "Chapter source"
            entries = CHAPTER_SOURCE_ENTRIES.map { it.first }.toTypedArray()
            entryValues = CHAPTER_SOURCE_ENTRIES.map { it.second }.toTypedArray()
            setDefaultValue(PREF_AUTO)
            summary = "Most titles have several translations. Current: %s"
        }.also(screen::addPreference)
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
private const val CHAPTER_SOURCE_PREF = "pref_chapter_source"
private const val PREF_AUTO = "auto"
private const val PREF_ALL = "all"

private val MONGO_ID = Regex("[0-9a-f]{24}")

/** Groups that keep their pages online long term, best first. */
private val RELIABLE_GROUPS = listOf(
    "mangadistrict",
    "mangadot",
    "mangadex",
    "comick",
    "atsu",
    "mangahub",
)

/** Groups seen in the chapter listings, used for the "Chapter source" preference. */
private val GROUPS = listOf(
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
    "lelscanfr",
    "likemanga",
    "manga18",
    "mangabuddy",
    "mangadistrict",
    "mangadex",
    "mangadot",
    "mangahub",
    "mangakatana",
    "mangalivre",
    "mangaonline",
    "manhwaread",
    "manhwaclub",
    "manhwaden",
    "oremanga",
    "rawdex",
    "scanita",
    "tiamanhwa",
    "zinmanga",
)

private val CHAPTER_SOURCE_ENTRIES = listOf(
    "Auto (best translation)" to PREF_AUTO,
    "All translations" to PREF_ALL,
) + GROUPS.sorted().map { it to it }
