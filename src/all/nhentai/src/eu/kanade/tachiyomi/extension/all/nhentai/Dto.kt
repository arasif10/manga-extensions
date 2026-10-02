package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The servers the site hands out for pictures and for covers. */
@Serializable
class NHConfig(
    @SerialName("image_servers") val imageServers: List<String> = emptyList(),
    @SerialName("thumb_servers") val thumbServers: List<String> = emptyList(),
)

/** The envelope every listing answers with. */
@Serializable
class PaginatedResponse<T>(
    val result: List<T> = emptyList(),
    @SerialName("per_page") val perPage: Int = 0,
    @SerialName("num_pages") val numPages: Int? = null,
    val total: Int? = null,
)

/** A gallery as a listing carries it: enough to draw a row. */
@Serializable
class GalleryItem(
    val id: Int,
    val thumbnail: String = "",
    @SerialName("english_title") val englishTitle: String? = null,
    @SerialName("japanese_title") val japaneseTitle: String? = null,
)

/** A gallery as its own page carries it: titles, tags, covers and every page. */
@Serializable
class Hentai(
    val id: Int,
    val pages: List<Image> = emptyList(),
    val thumbnail: Image = Image(),
    val tags: List<Tag> = emptyList(),
    val title: Title = Title(),
    @SerialName("scanlator") val scanlator: String = "",
    @SerialName("upload_date") private val uploadDate: Long = 0,
    @SerialName("num_favorites") val numFavorites: Long = 0,
    @SerialName("num_pages") val numPages: Int = 0,
) {
    /** When the gallery was put on the site, in milliseconds, or null if the site did not say. */
    val uploadedDate: Long? get() = uploadDate.takeIf { it > 0 }?.times(1000)

    /** A chapter date is a plain number, so an answer the site left out counts as unknown. */
    val uploadedAt: Long get() = uploadedDate ?: 0
}

@Serializable
class Title(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
) {
    val best: String get() = english ?: japanese ?: pretty.orEmpty()
}

@Serializable
class Image(
    val path: String = "",
)

@Serializable
class Tag(
    val name: String,
    val type: String,
)
