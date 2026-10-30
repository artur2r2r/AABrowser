/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.data.prefs

import android.content.Context
import androidx.core.content.edit

/**
 * Persistent state for the in-app split screen (browser on one side, a pinned
 * web app such as Slack on the other).
 */
object SplitScreenPreferences {
    const val DEFAULT_SPLIT_URL = "https://app.slack.com/client"
    const val DEFAULT_RATIO = 0.5f
    const val MIN_RATIO = 0.25f
    const val MAX_RATIO = 0.75f

    private const val PREFS_NAME = UIPreferences.PREFS_NAME
    private const val KEY_ENABLED = "split_screen_enabled"
    private const val KEY_HOME_URL = "split_screen_home_url"
    private const val KEY_LAST_URL = "split_screen_last_url"
    private const val KEY_RATIO = "split_screen_ratio"
    private const val KEY_PANE_ON_LEFT = "split_screen_pane_on_left"
    private const val KEY_DESKTOP_MODE = "split_screen_desktop_mode"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    fun getHomeUrl(context: Context): String =
        prefs(context).getString(KEY_HOME_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SPLIT_URL

    fun setHomeUrl(context: Context, url: String) = prefs(context).edit {
        putString(KEY_HOME_URL, url)
        remove(KEY_LAST_URL)
    }

    fun getLastUrl(context: Context): String? =
        prefs(context).getString(KEY_LAST_URL, null)?.takeIf { it.isNotBlank() }

    fun setLastUrl(context: Context, url: String) = prefs(context).edit { putString(KEY_LAST_URL, url) }

    /** Fraction of the width occupied by the main browser (0.25 – 0.75). */
    fun getRatio(context: Context): Float =
        prefs(context).getFloat(KEY_RATIO, DEFAULT_RATIO).coerceIn(MIN_RATIO, MAX_RATIO)

    fun setRatio(context: Context, ratio: Float) =
        prefs(context).edit { putFloat(KEY_RATIO, ratio.coerceIn(MIN_RATIO, MAX_RATIO)) }

    fun isPaneOnLeft(context: Context): Boolean = prefs(context).getBoolean(KEY_PANE_ON_LEFT, false)
    fun setPaneOnLeft(context: Context, onLeft: Boolean) = prefs(context).edit { putBoolean(KEY_PANE_ON_LEFT, onLeft) }

    /** Slack and most chat web apps only serve their full client to desktop browsers. */
    fun isDesktopMode(context: Context): Boolean = prefs(context).getBoolean(KEY_DESKTOP_MODE, true)
    fun setDesktopMode(context: Context, desktop: Boolean) = prefs(context).edit { putBoolean(KEY_DESKTOP_MODE, desktop) }
}
