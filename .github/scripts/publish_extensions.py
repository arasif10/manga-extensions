#!/usr/bin/env python3
"""Publish freshly built manga extension APKs into the repo branch.

For every release APK it:
  - copies the APK into <repo>/apk/
  - updates or adds the entry in index.json / index.min.json  (legacy Tachiyomi/Mihon format)
  - generates repo.json + store.json                        (Komikku / new store format)
  - copies the extension icon to <repo>/icon/

Komikku flow (paste the index.min.json URL in the app):
  index.min.json (starts with "[") -> Komikku fetches repo.json next to it
  repo.json -> {"index_v2": "…/store.json", "meta": {...}}
  store.json -> new-format store with an absolute apkUrl/iconUrl per extension
"""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

APK_RE = re.compile(r"^tachiyomi-(\w+)\.([\w.-]+)-v(\d+\.\d+\.\d+)\.apk$")

# GitHub repository that hosts the published extensions.
REPO_OWNER = "arasif10"
REPO_NAME = "manga-extensions"
REPO_BRANCH = "repo"
RAW_BASE = f"https://raw.githubusercontent.com/{REPO_OWNER}/{REPO_NAME}/{REPO_BRANCH}"

# SHA-256 certificate digest of the signing keystore (arasif-release.jks, alias "arasif").
# Must match the key used by CI to sign the release APKs so apps auto-trust the extension.
SIGNING_KEY_FINGERPRINT = "bb3e15a4c4d43da5bde85a3d6d24bb519b7414b5d9039c0a5fbc60baf489186f"


def find_apks(apk_dir: Path):
    """Scan apk_dir for tachiyomi-*.apk files."""
    apks = {}
    for apk in sorted(apk_dir.rglob("*.apk")):
        m = APK_RE.match(apk.name)
        if not m:
            print(f"  skip: {apk.name}")
            continue
        lang, ext_name, version = m.group(1), m.group(2), m.group(3)
        code = sum(int(x) * (100 ** i) for i, x in enumerate(reversed(version.split("."))))
        # Read metadata if available
        meta_json = apk.parent.parent / "output-metadata.json"
        if not meta_json.exists():
            meta_json = apk.parent / "output-metadata.json"
        pkg = f"eu.kanade.tachiyomi.extension.{lang}.{ext_name}"
        if meta_json.exists():
            try:
                meta = json.loads(meta_json.read_text())
                pkg = meta.get("applicationId", pkg)
                code = meta.get("elements", [{}])[0].get("versionCode", code)
                version = meta.get("elements", [{}])[0].get("versionName", version)
            except Exception:
                pass
        apks[pkg] = {
            "lang": lang,
            "name": ext_name,
            "version": version,
            "code": code,
            "file": apk,
        }
    return apks


def load_json(path: Path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, data, minified: bool):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        if minified:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        else:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")


def compute_source_id(pkg: str) -> int:
    """Deterministic, stable source id (no Python hash randomization)."""
    digest = hashlib.md5(pkg.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") & ((1 << 63) - 1)


def write_komikku_store(repo: Path, apks: dict):
    """Generate repo.json + store.json for Komikku / new-format readers."""
    # Legacy bridge: points the app from index.min.json to the new store.
    repo_json = {
        "index_v2": f"{RAW_BASE}/store.json",
        "meta": {
            "name": "Manga Extensions",
            "shortName": "manga-extensions",
            "website": f"https://github.com/{REPO_OWNER}/{REPO_NAME}",
            "signingKeyFingerprint": SIGNING_KEY_FINGERPRINT,
        },
    }

    extensions = []
    for pkg, info in apks.items():
        apk_name = f"tachiyomi-{info['lang']}.{info['name']}-v{info['version']}.apk"
        ext_display_name = info["name"].replace("-", " ").title()
        base_url = "https://mangaball.net" if "mangaball" in pkg else ""
        lib_version = info["version"].rsplit(".", 1)[0]
        extensions.append(
            {
                "name": ext_display_name,
                "packageName": pkg,
                "resources": {
                    "apkUrl": f"{RAW_BASE}/apk/{apk_name}",
                    "iconUrl": f"{RAW_BASE}/icon/{pkg}.png",
                },
                "extensionLib": lib_version,
                "versionCode": info["code"],
                "versionName": info["version"],
                "contentWarning": "CONTENT_WARNING_MIXED",
                "sources": [
                    {
                        "id": compute_source_id(pkg),
                        "name": "Mangaball",
                        "language": "all",
                        "homeUrl": base_url,
                    },
                ],
            },
        )

    store_json = {
        "name": "Manga Extensions",
        "badgeLabel": "manga-extensions",
        "signingKey": SIGNING_KEY_FINGERPRINT,
        "contact": {
            "website": f"https://github.com/{REPO_OWNER}/{REPO_NAME}",
            "discord": None,
        },
        "extensionList": {"extensions": extensions},
    }

    write_json(repo / "repo.json", repo_json, minified=False)
    write_json(repo / "store.json", store_json, minified=False)
    print("repo.json and store.json updated.")


def main():
    parser = argparse.ArgumentParser(description="Publish manga extensions to repo branch")
    parser.add_argument("--apk-dir", required=True, help="Directory with built APKs")
    parser.add_argument("--repo-dir", required=True, help="Checked-out repo branch")
    parser.add_argument("--icons-dir", default=None, help="Directory with extension icons")
    args = parser.parse_args()

    apk_dir = Path(args.apk_dir)
    repo = Path(args.repo_dir)
    if not (repo / "index.json").exists():
        sys.exit(f"error: {repo} does not have index.json")

    apks = find_apks(apk_dir)
    if not apks:
        sys.exit("error: no extension APKs found in " + str(apk_dir))

    print(f"Found {len(apks)} extension(s):")
    for pkg, info in apks.items():
        print(f"  {pkg} v{info['version']} (code {info['code']})")

    index_pretty = load_json(repo / "index.json")
    index_min = load_json(repo / "index.min.json")
    by_pkg_pretty = {e.get("pkg"): e for e in index_pretty}
    by_pkg_min = {e.get("pkg"): e for e in index_min}

    changed = False
    for pkg, info in apks.items():
        apk_name = f"tachiyomi-{info['lang']}.{info['name']}-v{info['version']}.apk"
        entry_pretty = by_pkg_pretty.get(pkg)
        entry_min = by_pkg_min.get(pkg)

        ext_display_name = info["name"].replace("-", " ").title()
        source_id = compute_source_id(pkg)
        sources = [
            {
                "name": ext_display_name,
                "lang": info["lang"],
                "id": str(source_id),
                "baseUrl": "https://mangaball.net" if "mangaball" in pkg else "",
            },
        ]

        if entry_pretty is None or entry_min is None:
            # New extension — create entry
            entry_pretty = {
                "name": ext_display_name,
                "pkg": pkg,
                "apk": apk_name,
                "lang": info["lang"],
                "code": info["code"],
                "version": info["version"],
                "nsfw": 0,
                "sources": sources,
            }
            entry_min = dict(entry_pretty)
            index_pretty.append(entry_pretty)
            index_min.append(entry_min)
            by_pkg_pretty[pkg] = entry_pretty
            by_pkg_min[pkg] = entry_min
            changed = True
        else:
            # Update existing
            if entry_pretty.get("sources") != sources:
                entry_pretty["sources"] = sources
                entry_min["sources"] = sources
                changed = True
            for key in ("code", "version", "apk"):
                new_val = {"code": info["code"], "version": info["version"], "apk": apk_name}[key]
                if entry_pretty.get(key) != new_val:
                    entry_pretty[key] = new_val
                    entry_min[key] = new_val
                    changed = True

        # Copy APK
        dest = repo / "apk" / apk_name
        dest.parent.mkdir(parents=True, exist_ok=True)
        if not dest.exists() or dest.read_bytes() != info["file"].read_bytes():
            # Remove old versions of same extension
            for stale in dest.parent.glob(f"tachiyomi-{info['lang']}.{info['name']}-v*.apk"):
                if stale.name != apk_name:
                    stale.unlink()
            shutil.copyfile(info["file"], dest)
            changed = True
        print(f"  -> {apk_name}")

    # Copy icons
    if args.icons_dir:
        icons_dir = Path(args.icons_dir)
        for pkg, info in apks.items():
            icon_name = f"{info['lang']}.{info['name']}.png"
            icon_src = icons_dir / icon_name
            if not icon_src.exists():
                # Try all.<name>.png as fallback
                icon_src = icons_dir / f"all.{info['name']}.png"
            if not icon_src.exists():
                print(f"  !! no icon for {pkg} - skipping")
                continue
            icon_dest = repo / "icon" / f"{pkg}.png"
            icon_dest.parent.mkdir(parents=True, exist_ok=True)
            if not icon_dest.exists() or icon_dest.read_bytes() != icon_src.read_bytes():
                shutil.copyfile(icon_src, icon_dest)
                changed = True
            print(f"  -> icon/{pkg}.png")

    # Always refresh the Komikku files so they stay in sync with the current build.
    write_komikku_store(repo, apks)
    changed = True

    if not changed:
        print("No changes needed.")
        return

    write_json(repo / "index.json", index_pretty, minified=False)
    write_json(repo / "index.min.json", index_min, minified=True)

    print("Index updated.")


if __name__ == "__main__":
    main()

