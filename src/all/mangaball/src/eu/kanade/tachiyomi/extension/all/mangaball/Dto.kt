package eu.kanade.tachiyomi.extension.all.mangaball

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/** Covers are only served with a `https://mangaball.com/` referer, which the source sends. */
private const val COVER_CDN = "https://bulbasaur.poke-black-and-white.net/covers"

// Created at values are sent as either "2026-08-25T23:24:01" or with a microsecond fraction
// ("2026-08-25T23:24:01.665000"), so the fraction has to be optional and of any length.
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
    .toFormatter()

@Serializable
class SearchResponse(
    private val data: List<Title> = emptyList(),
    private val pagination: Pagination? = null,
) {
    /** Only used to sample which sites currently publish chapters. */
    val titleIds: List<String> get() = data.map { it.id }

    @Serializable
    class Pagination(
        private val page: Int = 1,
        @SerialName("total_pages") private val totalPages: Int = 1,
    ) {
        val hasNextPage get() = page < totalPages
    }

    fun toMangasPage() = MangasPage(data.map(Title::toSManga), pagination?.hasNextPage ?: false)

    /**
     * The API cannot exclude by origin or demographic, so titles carrying one of the excluded
     * values are dropped here; a title with none of these fields set survives every exclusion.
     */
    fun dropping(excludedLanguages: List<String>, excludedDemographics: List<String>): SearchResponse = SearchResponse(
        data.filter { title ->
            title.originalLanguageCode !in excludedLanguages && title.demographic !in excludedDemographics
        },
        pagination,
    )
}

@Serializable
class Title(
    val id: String,
    val slug: String,
    private val name: String,
    private val image: Image? = null,
    /** A language code (`ja`, `ko`, `zh`, `en`), so an excluded origin can be matched. */
    @SerialName("originalLanguage") private val originalLanguage: String? = null,
    @SerialName("publicationDemographic") private val publicationDemographic: String? = null,
) {
    internal val originalLanguageCode: String? get() = originalLanguage

    internal val demographic: String? get() = publicationDemographic
    fun toSManga() = SManga.create().apply {
        url = slug
        title = name
        thumbnail_url = image?.best
    }
}

/** Also embedded in [TitleDetail], which is why it is not nested inside [Title]. */
@Serializable
class Image(
    @SerialName("cdn_mangadex") private val cdnMangadex: String? = null,
    @SerialName("cdn_mangaupdate") private val cdnMangaupdate: String? = null,
    private val file: String? = null,
    private val cover: Cover? = null,
) {
    // The MangaDex mirror is the only field that is consistently a full, live image URL.
    val best: String? get() = listOfNotNull(cdnMangadex, cover?.url, file, cdnMangaupdate)
        .firstOrNull { it.isNotBlank() }

    @Serializable
    class Cover(
        private val path: String? = null,
    ) {
        val url: String? get() = path?.takeIf { it.isNotBlank() }?.let { "$COVER_CDN/$it" }
    }
}

@Serializable
class TitleDetailResponse(
    val data: TitleDetail,
)

@Serializable
class TitleDetail(
    val id: String,
    val slug: String,
    private val name: String,
    private val image: Image? = null,
    private val status: String? = null,
    private val originalLanguage: String? = null,
    private val description: List<String>? = null,
    private val authors: List<Author>? = null,
    private val tags: List<Tag>? = null,
    @SerialName("alternateName") private val alternateNames: List<String>? = null,
) {
    @Serializable
    class Author(val name: String? = null)

    @Serializable
    class Tag(val name: String? = null)

    fun toSManga(): SManga {
        // Read into locals first: inside `apply` these names would resolve to SManga's own
        // members (title/status/description), and SChapter/SManga name fields are `lateinit`,
        // so touching them before assignment throws.
        val detailSlug = slug
        val detailTitle = name
        val thumbnail = image?.best
        // The API repeats the same author across list entries.
        val authorText = authors.orEmpty().mapNotNull { it.name }.distinct().joinToString()
        val state = when (status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus", "on_hold" -> SManga.ON_HIATUS
            "cancelled", "canceled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        val paragraphs = description.orEmpty()
        val alternatives = alternateNames.orEmpty()
        val genreText = buildList {
            when (originalLanguage) {
                "ja" -> add("Manga")
                "ko" -> add("Manhwa")
                "zh" -> add("Manhua")
                "en" -> add("Comic")
            }
            tags.orEmpty().mapNotNull { it.name }.forEach(::add)
        }.joinToString()
        val synopsis = buildString {
            paragraphs.filter { it.isNotBlank() }.forEach { append(it, "\n\n") }
            if (alternatives.any { it.isNotBlank() }) {
                append("Alternative Names:\n")
                alternatives.filter { it.isNotBlank() }.forEach { append("- ", it.trim(), "\n") }
            }
        }.trim()

        return SManga.create().apply {
            url = detailSlug
            title = detailTitle
            thumbnail_url = thumbnail
            author = authorText
            genre = genreText
            status = state
            description = synopsis
        }
    }
}

@Serializable
class ChapterListRequest(
    @SerialName("title_id") private val titleId: String,
)

@Serializable
class ChapterListResponse(
    val data: List<Chapter> = emptyList(),
)

@Serializable
class Chapter(
    val id: String,
    private val name: String? = null,
    val lang: String? = null,
    private val number: Float? = null,
    private val volume: Float? = null,
    @SerialName("created_at") private val createdAt: String? = null,
    @SerialName("group_name") private val groupName: String? = null,
    private val site: String? = null,
) {
    /** Scanlator groups are named inconsistently across fields, so both are considered. */
    val sourceNames: List<String> get() = listOfNotNull(site, groupName).map { it.lowercase() }

    /** The publishing site. Some chapters carry the owning group's id here instead of a slug. */
    val siteSlug: String? get() = site?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

    /** The uploader shown on the chapter row, and what the scanlator blacklist matches on. */
    val scanlator: String? get() = groupName ?: site

    val chapterNumber: Float? get() = number

    val volumeNumber: Float get() = volume ?: 0f

    val uploadedAt: Long get() = DATE_FORMAT.tryParseDateTime(createdAt)

    fun toSChapter(): SChapter {
        // `name` and `chapter_number` must be read before `apply`, which would otherwise resolve
        // them to SChapter's own (lateinit) members rather than this DTO's.
        val chapterId = id
        val label = name.orEmpty()
        val number = number
        val uploaded = uploadedAt
        val group = scanlator.orEmpty()
        val volume = volumeNumber
        val numberText = number?.toDisplay()

        val displayName = buildString {
            if (volume > 0f) append("Vol. ${volume.toDisplay()} ")
            when {
                numberText == null -> append(label.ifEmpty { "Chapter" })
                label.isEmpty() -> append("Ch. $numberText")
                label.contains(numberText) -> append(label)
                else -> append("Ch. $numberText $label")
            }
        }

        return SChapter.create().apply {
            url = chapterId
            name = displayName
            chapter_number = number ?: -1f
            date_upload = uploaded
            scanlator = group
        }
    }
}

@Serializable
class ChapterDetailResponse(
    val data: Data,
) {
    @Serializable
    class Data(val chapter: Chapter)

    @Serializable
    class Chapter(
        @SerialName("title_id") val titleId: String? = null,
        val pages: List<String>? = null,
    )
}

private fun Float.toDisplay() = toString().removeSuffix(".0")
