package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The envelope every listing answers with: the galleries, and how many pages there are. */
@Serializable
class GalleryPage(
    val result: List<Gallery> = emptyList(),
    @SerialName("num_pages") val numPages: Int = 0,
    val total: Int = 0,
)

/** A gallery as a listing carries it: enough to draw a row, no pages and no tags. */
@Serializable
class Gallery(
    val id: Int,
    @SerialName("media_id") val mediaId: String = "",
    @SerialName("english_title") val englishTitle: String? = null,
    @SerialName("japanese_title") val japaneseTitle: String? = null,
    @SerialName("num_pages") val numPages: Int = 0,
    @SerialName("num_favorites") val numFavorites: Int = 0,
    val thumbnail: String = "",
    val blacklisted: Boolean = false,
)

/** A gallery as its own page carries it: titles, tags, covers and every page. */
@Serializable
class GalleryDetail(
    val id: Int,
    @SerialName("media_id") val mediaId: String = "",
    val title: Title = Title(),
    val cover: Image = Image(),
    val thumbnail: Image = Image(),
    val scanlator: String = "",
    @SerialName("upload_date") val uploadDate: Long = 0,
    val tags: List<Tag> = emptyList(),
    @SerialName("num_pages") val numPages: Int = 0,
    @SerialName("num_favorites") val numFavorites: Int = 0,
    val pages: List<PageImage> = emptyList(),
)

@Serializable
class Title(
    val english: String = "",
    val japanese: String = "",
    val pretty: String = "",
)

@Serializable
class Image(
    val path: String = "",
)

@Serializable
class PageImage(
    val number: Int = 0,
    val path: String = "",
    val thumbnail: String = "",
)

@Serializable
class Tag(
    val id: Int = 0,
    val type: String = "",
    val name: String = "",
    val slug: String = "",
    val count: Int = 0,
)
