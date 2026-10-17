import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "IMHentai"
    // Raised from 1: the published index carries this number, and the app only offers an update
    // when it is higher than the one already installed.
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    // The site files every gallery under a language, so the same source is offered once per
    // language it carries and once for everything, the way its own search reads them.
    listOf("all", "en", "ja", "es", "fr", "ko", "de", "ru").forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://imhentai.xxx"
        }
    }

    deeplink {
        path("/gallery/..*")
    }
}

dependencies {
    implementation(project(":lib:randomua"))
}
