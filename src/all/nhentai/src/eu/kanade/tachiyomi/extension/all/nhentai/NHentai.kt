package eu.kanade.tachiyomi.extension.all.nhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class NHentai : KeiSource() {

    private val apiUrl get() = "$baseUrl/api/v2"

    // The site's own popular endpoint answers one page and ignores `page`, so the browses go
    // through the listing that carries the whole ranking and can be paged, sorted the same way.
    override suspend fun getPopularManga(page: Int): MangasPage = browse(page, "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse(page, "")

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected

        val terms = (
            listOf(query.trim()).filter(String::isNotEmpty) +
                filters.filterIsInstance<TagGroupFilter>().flatMap { it.clauses } +
                filters.writtenFilters().flatMap { it.clauses }
            )
            .joinToString(" ")

        // Nothing was asked for, so the site's own listings are browsed instead of searched.
        if (terms.isBlank()) return browse(page, sort.orEmpty())

        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", terms)
            .addQueryParameter("page", page.toString())
            .apply { if (!sort.isNullOrEmpty()) addQueryParameter("sort", sort) }
            .build()

        return client.get(url).parseAs<GalleryPage>().toMangasPage(page)
    }

    private suspend fun browse(page: Int, sort: String): MangasPage {
        val url = "$apiUrl/galleries".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply { if (sort.isNotEmpty()) addQueryParameter("sort", sort) }
            .build()

        return client.get(url).parseAs<GalleryPage>().toMangasPage(page)
    }

    private fun GalleryPage.toMangasPage(currentPage: Int): MangasPage = MangasPage(
        result.filterNot { it.blacklisted }.map { it.toSManga() },
        currentPage < numPages,
    )

    private fun Gallery.toSManga(): SManga = SManga.create().apply {
        title = englishTitle?.takeIf(String::isNotBlank) ?: japaneseTitle.orEmpty()
        setUrlWithoutDomain("/g/$id")
        thumbnail_url = thumbnailUrl(id, thumbnail)
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters()

    // Details + Chapters
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
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
        val detail = fetchDetail(getMangaId(manga))
        return SMangaUpdate(parseMangaDetails(detail), parseChapterList(detail))
    }

    private suspend fun fetchDetail(id: Int): GalleryDetail = client.get("$apiUrl/galleries/$id").parseAs()

    private fun parseMangaDetails(detail: GalleryDetail): SManga = SManga.create().apply {
        initialized = true
        setUrlWithoutDomain("/g/${detail.id}")

        title = detail.title.english.takeIf(String::isNotBlank)
            ?: detail.title.japanese.takeIf(String::isNotBlank)
            ?: detail.title.pretty

        val artists = detail.tags.filter { it.type == "artist" }.map { it.name }
        val groups = detail.tags.filter { it.type == "group" }.map { it.name }
        author = artists.joinToString().ifEmpty { groups.joinToString() }
        artist = author

        genre = detail.tags.filter { it.type != "artist" && it.type != "group" }
            .map { it.name }
            .joinToString()

        description = buildString {
            detail.tags.filter { it.type == "parody" }.map { it.name }.takeIf(List<String>::isNotEmpty)?.let {
                append("Parodies: ", it.joinToString(), "\n\n")
            }
            detail.tags.filter { it.type == "character" }.map { it.name }.takeIf(List<String>::isNotEmpty)?.let {
                append("Characters: ", it.joinToString(), "\n\n")
            }
            groups.takeIf(List<String>::isNotEmpty)?.let { append("Groups: ", it.joinToString(), "\n\n") }
            append("Pages: ", detail.numPages, "\n")
            append("Favorites: ", detail.numFavorites, "\n")
            append("Uploaded: ", uploadDateFormat.format(detail.uploadDate * 1000), "\n")
            if (detail.scanlator.isNotBlank()) append("Scanlator: ", detail.scanlator, "\n")
        }

        thumbnail_url = thumbnailUrl(detail.id, detail.thumbnail.path.ifEmpty { detail.cover.path })
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun parseChapterList(detail: GalleryDetail): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Gallery"
            setUrlWithoutDomain("/g/${detail.id}")
            date_upload = detail.uploadDate * 1000
        },
    )

    private fun getMangaId(manga: SManga) = manga.url.trimEnd('/').substringAfterLast('/').toInt()

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.trimEnd('/').substringAfterLast('/').toInt()

        return fetchDetail(id).pages.mapIndexed { index, image ->
            Page(index, imageUrl = imageUrl(id, image.path))
        }
    }

    // Related manga
    override val supportsRelatedMangas = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val id = getMangaId(manga)
        return client.get("$apiUrl/galleries/$id/related").parseAs<List<Gallery>>()
            .filterNot { it.blacklisted }
            .map { it.toSManga() }
    }

    companion object {
        private val uploadDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

        /**
         * The site spreads its files over four servers, and asks for the one the gallery belongs to
         * so no single server has to carry everything.
         */
        private fun serverOf(id: Int) = id % 4 + 1

        private fun imageUrl(id: Int, path: String) = "https://i${serverOf(id)}.nhentai.net/$path"

        private fun thumbnailUrl(id: Int, path: String) = "https://t${serverOf(id)}.nhentai.net/$path"
    }
}

/**
 * The write-in boxes sit inside the Advanced group rather than in the filter list itself, so the
 * groups are searched as well.
 */
private fun FilterList.writtenFilters(): List<TextFilter> = filterIsInstance<TextFilter>() +
    filterIsInstance<Filter.Group<*>>().flatMap { it.state.filterIsInstance<TextFilter>() }
