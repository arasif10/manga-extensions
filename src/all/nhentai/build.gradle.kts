import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    // The site carries one library but tags every work with a language, so the same source is
    // offered once per language and once for everything.
    listOf("all", "en", "ja", "zh").forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://nhentai.net"
        }
    }

    deeplink {
        host("nhentai.net")
        path("/g/..*")
    }
}

dependencies {
    implementation(project(":lib:randomua"))
}
