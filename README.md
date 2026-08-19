# Manga Extensions

Repository of extension sources usable with read apps that support the Tachiyomi/Mihon extension format
(e.g. Mihon, Tachiyomi, Aniyomi, Kotatsu, Komikku...). Extensions are installed like regular Android apps, or directly
through the extension manager of your reader app.

This repo currently hosts the **Manga Ball** extension.

### Please give the repo a :star:

| Build | Extensions |
|-------|------------|
| [![CI](https://github.com/arasif10/manga-extensions/actions/workflows/build_and_publish.yml/badge.svg)](https://github.com/arasif10/manga-extensions/actions/workflows/build_and_publish.yml) | [Manga Ball](https://mangaball.net) |

## Usage

### Adding this repository to your reader app

1. Open your reader app (Mihon / Tachiyomi / Aniyomi / Komikku ...).
2. Go to **Settings → Extensions**.
3. Open the **three-dot menu** (top-right) → **Browse repositories** (or **+ Add repository**).
4. Paste the primary repository URL:

```
https://raw.githubusercontent.com/arasif10/manga-extensions/repo/index.min.json
```

5. Save, then browse / install **Manga Ball** from the Extensions list.

#### Supported repository URLs

All of the following URLs are live and can be pasted into the reader app.

| URL | What it is for |
|-----|----------------|
| `https://raw.githubusercontent.com/arasif10/manga-extensions/repo/index.json` | Tachiyomi/Mihon legacy repo index (formatted, easy to read) |
| `https://raw.githubusercontent.com/arasif10/manga-extensions/repo/index.min.json` | Primary repo URL - legacy `index.min.json` (array form), accepted by Tachiyomi/Mihon/Aniyomi/Komikku |
| `https://raw.githubusercontent.com/arasif10/manga-extensions/repo/repo.json` | New-format repo definition (contains `index_v2` -> `store.json`) |
| `https://raw.githubusercontent.com/arasif10/manga-extensions/repo/store.json` | New-format extension store (Mihon 0.17+ / Komikku) |

> **If the repository does not appear in the app update first, remove any previously-added version of this URL
> (the old entry can be cached), re-add it, and pull-to-refresh on the Extensions screen.**
>
> **If you previously installed Manga Ball from a different source** (for example a build signed with a
> temporary key), uninstall that APK first, then install from this repo, otherwise the update will be rejected
> because the signing key differs.

## Direct APK

The latest APK is always available in the
[`repo` branch](https://github.com/arasif10/manga-extensions/tree/repo/apk), for example
`https://raw.githubusercontent.com/arasif10/manga-extensions/repo/apk/tachiyomi-all.mangaball-v1.4.7.apk`
(stable signature: `CN=Ar Asif`, SHA-256 `bb3e15a4c4d43da5bde85a3d6d24bb519b7414b5d9039c0a5fbc60baf489186f`).

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

This project is not affiliated with Mihon/Keiyoushi/Tachiyomi/Komikku. Don't ask for help about these extensions at
the official support means of those apps. Credits for the extension API and build system go to
the original contributors.
