import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga Ball"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    // The site serves 42 interface languages, so the extension is registered once per language
    // to show up in the app's per-language source lists.
    listOf(
        "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "en", "es", "fa", "fi", "fr", "he", "hi", "hu",
        "id", "it", "is", "ja", "ko", "kn", "ml", "ms", "ne", "nl", "no", "pl", "pt-BR", "ro", "ru", "sk",
        "sl", "sq", "sr", "sv", "ta", "th", "tr", "uk", "vi", "zh",
    ).forEach {
        source {
            lang = it
            baseUrl = "https://mangaball.com"
        }
    }

    deeplink {
        host("mangaball.com")
        host("www.mangaball.com")
        host("mangaball.net")
        host("www.mangaball.net")
        path("/title-detail/..*")
        path("/chapter-detail/..*")
    }
}
