# Manga Extensions

Repository of extension sources usable with read apps that support the Tachiyomi/Mihon extension format
(e.g. Mihon, Tachiyomi, Aniyomi, Kotatsu...). Extensions are installed like regular Android apps, or directly
through the extension manager of your reader app.

This repo currently hosts the **Manga Ball** extension.

### Please give the repo a :star:

| Build | Extensions |
|-------|------------|
| [![CI](https://github.com/arasif10/manga-extensions/actions/workflows/build_and_publish.yml/badge.svg)](https://github.com/arasif10/manga-extensions/actions/workflows/build_and_publish.yml) | [Manga Ball](https://mangaball.net) |

## Usage

### Adding this repository to your reader app

1. Open your reader app (Mihon / Tachiyomi / Aniyomi ...).
2. Go to **Settings → Extensions**.
3. Open the **three-dot menu** (top-right) → **Browse repositories** (or **+ Add repository**).
4. Paste the following repository URL:

```
https://raw.githubusercontent.com/arasif10/manga-extensions/repo/index.min.json
```

5. Save, then browse / install **Manga Ball** from the Extensions list.

> You can also download the extension APK directly from the
> [`repo` branch](https://github.com/arasif10/manga-extensions/tree/repo/apk).

## Contents

| Name | Status |
|------|--------|
| [Manga Ball](https://mangaball.net) | Working |

## Building

Extensions are built and published automatically by GitHub Actions on every push to `main`
(`.github/workflows/build_and_publish.yml`), then pushed to the `repo` branch.

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## Disclaimer

This project does not have any affiliation with the content providers available (e.g. Manga Ball).

This project is not affiliated with Mihon/Tachiyomi/Keiyoushi. Don't ask for help about these extensions at
the official support means of Mihon/Tachiyomi/Keiyoushi. Credits for the extension API and build system go to
the original contributors.
