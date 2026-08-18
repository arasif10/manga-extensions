#!/usr/bin/env python3
"""Publish freshly built manga extension APKs into the repo branch.

For every release APK it:
  - copies the APK into <repo>/apk/
  - updates or adds the entry in index.json / index.min.json
  - copies the extension icon to <repo>/icon/
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

APK_RE = re.compile(r"^tachiyomi-(\w+)\.([\w.-]+)-v(\d+\.\d+\.\d+)\.apk$")


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

        if entry_pretty is None or entry_min is None:
            # New extension — create entry
            entry_pretty = {
                "pkg": pkg,
                "name": info["name"].replace("-", " ").title(),
                "lang": info["lang"],
                "code": info["code"],
                "version": info["version"],
                "apk": apk_name,
            }
            entry_min = dict(entry_pretty)
            index_pretty.append(entry_pretty)
            index_min.append(entry_min)
            by_pkg_pretty[pkg] = entry_pretty
            by_pkg_min[pkg] = entry_min
            changed = True
        else:
            # Update existing
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

    if not changed:
        print("No changes needed.")
        return

    write_json(repo / "index.json", index_pretty, minified=False)
    write_json(repo / "index.min.json", index_min, minified=True)

    print("Index updated.")


if __name__ == "__main__":
    main()
