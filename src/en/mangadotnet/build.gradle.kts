import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaDot"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        // The stock keiyoushi extension's English source id, so existing MangaDot library
        // entries keep working when this extension replaces it (same id, same baseUrl).
        id = 5900936305360403385L
        baseUrl = "https://mangadot.net"
    }
}
