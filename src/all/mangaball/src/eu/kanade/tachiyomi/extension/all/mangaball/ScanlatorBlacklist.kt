package eu.kanade.tachiyomi.extension.all.mangaball

import android.content.SharedPreferences
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen

// The site exposes no list of the groups uploading its chapters, so the names the blacklist can
// offer are collected from the chapter listings the user opens.

internal fun SharedPreferences.scanlatorBlacklist(): Set<String> = getStringSet(SCANLATOR_BLACKLIST_PREF, emptySet())
    .orEmpty()
    .mapTo(mutableSetOf()) { it.trim().lowercase() }

internal fun SharedPreferences.knownScanlatorNames(): List<String> = getStringSet(KNOWN_SCANLATORS_PREF, emptySet())
    .orEmpty()
    .sortedBy { it.lowercase() }

internal fun SharedPreferences.rememberScanlators(names: List<String>) {
    val known = getStringSet(KNOWN_SCANLATORS_PREF, emptySet()).orEmpty()
    val added = names.filter { it.isNotBlank() }.toSet() - known
    if (added.isEmpty()) return
    edit().putStringSet(KNOWN_SCANLATORS_PREF, known + added).apply()
}

/**
 * Applied to the raw listing rather than to the finished chapters, so a chapter whose only
 * translation came from a blacklisted group is not simply dropped: the chapter source mode then
 * picks the next best group that is still allowed.
 */
internal fun List<Chapter>.filterBlacklistedScanlators(blacklist: Set<String>): List<Chapter> = filterNot { it.scanlator?.trim()?.lowercase()?.let(blacklist::contains) == true }

internal fun PreferenceScreen.addScanlatorBlacklistPreference(preferences: SharedPreferences) {
    val scanlators = preferences.knownScanlatorNames().toTypedArray()
    MultiSelectListPreference(context).apply {
        key = SCANLATOR_BLACKLIST_PREF
        title = "Scanlator blacklist"
        summary = "Hides chapters from the chosen scanlators. Fills up as you browse titles."
        entries = scanlators
        entryValues = scanlators
        setDefaultValue(emptySet<String>())
    }.also(::addPreference)
}

private const val SCANLATOR_BLACKLIST_PREF = "pref_scanlator_blacklist"
private const val KNOWN_SCANLATORS_PREF = "pref_known_scanlators"
