package com.hazzus.karooclimber.ui

import android.graphics.Typeface

/**
 * Karoo's native data-field font (IBM Plex Sans Condensed, shipped in
 * /system/fonts), used for all overlay and field text at its single Medium
 * weight. Null on other devices -> Paint falls back to the default typeface.
 */
object KarooFonts {
    val plexCondensed: Typeface? by lazy {
        runCatching { Typeface.createFromFile("/system/fonts/IBMPlexSansCondensed-Medium.otf") }.getOrNull()
    }
}
