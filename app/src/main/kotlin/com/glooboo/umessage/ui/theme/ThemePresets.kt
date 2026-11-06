package com.glooboo.umessage.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fossify.commons.extensions.baseConfig
import com.glooboo.umessage.R
import java.util.concurrent.ConcurrentHashMap

/** SE-44: how bright the app is, chosen on its own. BLACK is Dark with pure black surfaces for OLED screens. */
enum class ThemeMode(@StringRes val label: Int, @StringRes val description: Int) {
    SYSTEM(R.string.preset_system, R.string.preset_system_desc),
    LIGHT(R.string.preset_light, R.string.preset_light_desc),
    DARK(R.string.preset_dark, R.string.preset_dark_desc),
    BLACK(R.string.preset_amoled, R.string.preset_amoled_desc),
}

/**
 * SE-44: the colours, chosen on their own; every one works in light and in dark. WALLPAPER is Material You, BRAND
 * the navy-and-gold uMessage look, the rest are [ThemePresets.palettes]. The order is the order in the picker.
 */
enum class ThemeColours(@StringRes val label: Int, @StringRes val description: Int) {
    WALLPAPER(R.string.preset_wallpaper, R.string.preset_wallpaper_desc),
    BRAND(R.string.preset_brand, R.string.preset_brand_desc),
    SLATE(R.string.preset_slate, R.string.preset_slate_desc),
    FIROUZEH(R.string.preset_firouzeh, R.string.preset_firouzeh_desc),
    POMEGRANATE(R.string.preset_pomegranate, R.string.preset_pomegranate_desc),
    HARBOR(R.string.preset_harbor, R.string.preset_harbor_desc),
    LAVENDER(R.string.preset_lavender, R.string.preset_lavender_desc),
    INK(R.string.preset_ink, R.string.preset_ink_desc),
    SKY(R.string.preset_sky, R.string.preset_sky_desc),
    GRAPHITE(R.string.preset_graphite, R.string.preset_graphite_desc),
    CONTRAST(R.string.preset_contrast, R.string.preset_contrast_desc),
}

/** The user's two choices together. */
data class ThemeChoice(val mode: ThemeMode, val colours: ThemeColours) {
    /** True when the app draws dark with this choice while the phone is [systemDark]. */
    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
}

/** The single look stored before Mode and Colours were split (schema 2 and older). Read only by the migration. */
enum class PresetId { SYSTEM, LIGHT, DARK, AMOLED, SKY, FOREST, SUNSET, GRAPHITE, WALLPAPER }

/**
 * A colour palette. Colours are ARGB ints for the base form, which is dark when [dark] is set. The other form is
 * generated from [primary] and [accent] with Material's tone mapping, so each palette works in light and in dark.
 */
data class ThemePreset(
    val id: ThemeColours,
    val dark: Boolean,
    val background: Int,
    val text: Int,
    val primary: Int,
    val accent: Int,
)

/** Users pick a mode and a palette; free-form colour editing is gone. The launcher icon is never affected. */
object ThemePresets {
    private const val PREFS = "spamless_theme"
    private const val KEY = "preset"
    private const val KEY_MODE = "mode"
    private const val KEY_COLOURS = "colours"

    val palettes: List<ThemePreset> = listOf(
        paletteOf(ThemeColours.SLATE, false, 0xFFF4F5F7, 0xFF1C2127, 0xFF3D4B5C, 0xFF0F766E),
        paletteOf(ThemeColours.FIROUZEH, false, 0xFFF1FBF9, 0xFF0B2B29, 0xFF0B7D75, 0xFFC9951F),
        paletteOf(ThemeColours.POMEGRANATE, false, 0xFFFFF6F4, 0xFF3A0F14, 0xFFA4162F, 0xFF1F6B5C),
        paletteOf(ThemeColours.HARBOR, true, 0xFF0C1C26, 0xFFE4F0F6, 0xFF6FB6D6, 0xFFF4A47C),
        paletteOf(ThemeColours.LAVENDER, false, 0xFFF7F4FE, 0xFF241A3D, 0xFF6B4CC7, 0xFF0F8A7E),
        paletteOf(ThemeColours.INK, true, 0xFF0D0F14, 0xFFECEEF2, 0xFFA8B4CC, 0xFFE8C37A),
        paletteOf(ThemeColours.SKY, false, 0xFFE0F2FE, 0xFF0C2A44, 0xFF0369A1, 0xFF16A34A),
        paletteOf(ThemeColours.GRAPHITE, true, 0xFF18181B, 0xFFFAFAFA, 0xFFF59E0B, 0xFF4ADE80),
        paletteOf(ThemeColours.CONTRAST, true, 0xFF000000, 0xFFFFFFFF, 0xFFFFD60A, 0xFF5CE1FF),
    )

    fun wallpaperAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Material You (wallpaper colours) where the phone has it, as in Google's own apps; the brand look elsewhere. */
    fun defaultChoice(wallpaper: Boolean = wallpaperAvailable()): ThemeChoice =
        ThemeChoice(ThemeMode.SYSTEM, if (wallpaper) ThemeColours.WALLPAPER else ThemeColours.BRAND)

    /** The palettes listed under Colours, in order. */
    fun colourOptions(wallpaper: Boolean = wallpaperAvailable()): List<ThemeColours> =
        ThemeColours.entries.filter { wallpaper || it != ThemeColours.WALLPAPER }

    private const val KEY_SCHEMA = "schema"

    /** 3: mode and colours are stored apart (SE-44). */
    private const val SCHEMA = 3
    private const val SCHEMA_SYSTEM_IS_CHOICE = 2

    @Volatile
    private var state: MutableStateFlow<ThemeChoice>? = null

    /** The choice as observable state: every screen's theme follows a change at once, without `recreate()`. */
    fun flow(context: Context): StateFlow<ThemeChoice> = stateFor(context).asStateFlow()

    fun current(context: Context): ThemeChoice = stateFor(context).value

    private fun stateFor(context: Context): MutableStateFlow<ThemeChoice> =
        state ?: synchronized(this) { state ?: MutableStateFlow(load(context)).also { state = it } }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): ThemeChoice {
        val prefs = prefs(context)
        val wallpaper = wallpaperAvailable()
        val mode = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) }
        val colours = coloursFromName(prefs.getString(KEY_COLOURS, null))
        val choice = if (mode != null && colours != null) {
            ThemeChoice(mode, colours)
        } else {
            legacyChoice(prefs.getString(KEY, null), prefs.getInt(KEY_SCHEMA, 0), wallpaper) ?: defaultChoice(wallpaper)
        }
        val usable = wallpaper || choice.colours != ThemeColours.WALLPAPER
        return if (usable) choice else choice.copy(colours = ThemeColours.BRAND)
    }

    /** Palettes dropped from the picker: a stored name keeps working and lands on the nearest remaining one. */
    private val removedColours = mapOf(
        "CORAL" to ThemeColours.POMEGRANATE,
        "SUNSET" to ThemeColours.POMEGRANATE,
        "FOREST" to ThemeColours.HARBOR,
    )

    /** The palette stored under [name], including the ones since removed; null for an unknown name. */
    internal fun coloursFromName(name: String?): ThemeColours? =
        ThemeColours.entries.firstOrNull { it.name == name } ?: removedColours[name]

    fun palette(id: ThemeColours): ThemePreset? = palettes.firstOrNull { it.id == id }

    /**
     * Turns the single look of older versions into a mode and a palette, keeping what the user saw. Schema 0 and 1
     * stored SYSTEM on first run without asking, so a stored SYSTEM there was not a choice: it maps to null (the
     * device default). Null too when nothing usable was stored.
     */
    internal fun legacyChoice(saved: String?, schema: Int, wallpaper: Boolean): ThemeChoice? {
        val id = PresetId.entries.firstOrNull { it.name == saved } ?: return null
        if (id == PresetId.SYSTEM && schema < SCHEMA_SYSTEM_IS_CHOICE) return null
        return when (id) {
            PresetId.SYSTEM -> ThemeChoice(ThemeMode.SYSTEM, ThemeColours.BRAND)
            PresetId.LIGHT -> ThemeChoice(ThemeMode.LIGHT, ThemeColours.BRAND)
            PresetId.DARK -> ThemeChoice(ThemeMode.DARK, ThemeColours.BRAND)
            PresetId.AMOLED -> ThemeChoice(ThemeMode.BLACK, ThemeColours.BRAND)
            // Sky and Sunset followed the phone, Forest and Graphite were dark-only. Sunset and Forest are gone:
            // they map to Pomegranate and Harbor.
            PresetId.SKY -> ThemeChoice(ThemeMode.SYSTEM, ThemeColours.SKY)
            PresetId.SUNSET -> ThemeChoice(ThemeMode.SYSTEM, ThemeColours.POMEGRANATE)
            PresetId.FOREST -> ThemeChoice(ThemeMode.DARK, ThemeColours.HARBOR)
            PresetId.GRAPHITE -> ThemeChoice(ThemeMode.DARK, ThemeColours.GRAPHITE)
            PresetId.WALLPAPER -> defaultChoice(wallpaper)
        }
    }

    /** Once per install: stores the old single look as a mode and a palette (or drops it for the default). */
    fun migrateIfNeeded(context: Context) {
        val prefs = prefs(context)
        val schema = prefs.getInt(KEY_SCHEMA, 0)
        if (schema >= SCHEMA) return
        val migrated = if (prefs.contains(KEY_MODE) && prefs.contains(KEY_COLOURS)) {
            null
        } else {
            legacyChoice(prefs.getString(KEY, null), schema, wallpaperAvailable())
        }
        prefs.edit {
            remove(KEY)
            if (migrated != null) {
                putString(KEY_MODE, migrated.mode.name)
                putString(KEY_COLOURS, migrated.colours.name)
            }
            putInt(KEY_SCHEMA, SCHEMA)
        }
        val choice = migrated ?: load(context)
        writeCommonsTheme(context, choice)
        state?.value = choice
    }

    fun setMode(context: Context, mode: ThemeMode) = apply(context, current(context).copy(mode = mode))

    fun setColours(context: Context, colours: ThemeColours) = apply(context, current(context).copy(colours = colours))

    /** Remembers the user's choice and publishes it to every open screen. */
    fun apply(context: Context, choice: ThemeChoice) {
        writeCommonsTheme(context, choice)
        prefs(context).edit {
            putString(KEY_MODE, choice.mode.name)
            putString(KEY_COLOURS, choice.colours.name)
            putInt(KEY_SCHEMA, SCHEMA)
        }
        stateFor(context).value = choice
    }

    /**
     * Writes the choice into the commons theme store that the remaining View screens read. They cannot follow the
     * phone's mode by themselves except for Material You and the default look, so a fixed palette is written in the
     * form the phone shows right now.
     */
    private fun writeCommonsTheme(context: Context, choice: ThemeChoice) {
        val config = context.applicationContext.baseConfig
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = choice.isDark(systemDark)
        when {
            choice.colours == ThemeColours.WALLPAPER -> config.isSystemThemeEnabled = wallpaperAvailable()
            choice.colours == ThemeColours.BRAND && choice.mode == ThemeMode.SYSTEM -> {
                config.resetThemeColors()
                config.isSystemThemeEnabled = false
            }
            else -> {
                val scheme = palette(choice.colours)?.colorScheme(dark)
                    ?: (if (dark) BrandCommons.dark else BrandCommons.light)
                val background = if (choice.mode == ThemeMode.BLACK) Color.Black else scheme.background
                config.isSystemThemeEnabled = false
                config.textColor = scheme.onBackground.toArgb()
                config.backgroundColor = background.toArgb()
                config.primaryColor = scheme.primary.toArgb()
                config.accentColor = scheme.tertiary.toArgb()
            }
        }
    }
}

/** The brand look's colours for the commons View screens (the Compose screens use UMessageTheme's own schemes). */
private object BrandCommons {
    val light: ColorScheme =
        paletteOf(ThemeColours.BRAND, false, 0xFFF8FAFC, 0xFF0F172A, 0xFF1E3A8A, 0xFF16A34A).toColorScheme()
    val dark: ColorScheme =
        paletteOf(ThemeColours.BRAND, true, 0xFF0B1220, 0xFFE2E8F0, 0xFF8FA8FF, 0xFF4ADE80).toColorScheme()
}

private fun paletteOf(id: ThemeColours, dark: Boolean, background: Long, text: Long, primary: Long, accent: Long) =
    ThemePreset(id, dark, background.toInt(), text.toInt(), primary.toInt(), accent.toInt())

private fun Int.c() = Color(this)

private val generatedSchemes = ConcurrentHashMap<Pair<ThemeColours, Boolean>, ColorScheme>()

/** The palette in light or dark: its own colours in its base form, the generated scheme in the other one. */
fun ThemePreset.colorScheme(dark: Boolean): ColorScheme = when {
    dark == this.dark -> toColorScheme()
    dark -> generatedSchemes.getOrPut(id to true) { toDarkColorScheme() }
    else -> generatedSchemes.getOrPut(id to false) { toLightColorScheme() }
}

/**
 * SE-44 Black: the dark scheme with pure black page and surfaces for OLED screens. Containers stay a little lighter,
 * so cards still stand out from the page.
 */
fun ColorScheme.blackened(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF323232),
    surfaceBright = Color(0xFF383838),
    inverseOnSurface = Color(0xFF141414),
)

/**
 * Dark Material scheme generated from the preset's seed colours with the M3 dark tone mapping: accents at tone 80 with
 * their "on" colours at 20, containers at 30 with text at 90, surfaces at tone 4 to 24 on a near-neutral of the
 * primary hue. Tones fix the contrast (80 on 20 is about 7.7:1, 90 on 30 about 7.2:1), see [Tones].
 */
internal fun ThemePreset.toDarkColorScheme(): ColorScheme {
    val seed = Tones.hueChroma(primary)
    val accentSeed = Tones.hueChroma(accent)
    fun p(tone: Int) = Color(Tones.argb(seed.hue, seed.chroma, tone.toDouble()))
    fun s(tone: Int) = Color(Tones.argb(seed.hue, seed.chroma * SECONDARY_CHROMA, tone.toDouble()))
    fun t(tone: Int) = Color(Tones.argb(accentSeed.hue, accentSeed.chroma, tone.toDouble()))
    fun n(tone: Int) = Color(Tones.argb(seed.hue, NEUTRAL_CHROMA, tone.toDouble()))
    fun nv(tone: Int) = Color(Tones.argb(seed.hue, NEUTRAL_VARIANT_CHROMA, tone.toDouble()))
    return darkColorScheme(
        primary = p(80),
        onPrimary = p(20),
        primaryContainer = p(30),
        onPrimaryContainer = p(90),
        inversePrimary = p(40),
        secondary = s(80),
        onSecondary = s(20),
        secondaryContainer = s(30),
        onSecondaryContainer = s(90),
        tertiary = t(80),
        onTertiary = t(20),
        tertiaryContainer = t(30),
        onTertiaryContainer = t(90),
        background = n(6),
        onBackground = n(90),
        surface = n(6),
        onSurface = n(90),
        surfaceVariant = nv(30),
        onSurfaceVariant = nv(80),
        surfaceTint = p(80),
        inverseSurface = n(90),
        inverseOnSurface = n(20),
        // The Material baseline dark error roles.
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = nv(60),
        outlineVariant = nv(30),
        scrim = Color.Black,
        surfaceBright = n(24),
        surfaceDim = n(6),
        surfaceContainerLowest = n(4),
        surfaceContainerLow = n(10),
        surfaceContainer = n(12),
        surfaceContainerHigh = n(17),
        surfaceContainerHighest = n(22),
    ).withGeneratedFixedRoles(::p, ::s, ::t)
}

/**
 * Light Material scheme generated from the seeds with the M3 light tone mapping (accents at 40 with white text,
 * containers at 90 with text at 10, surfaces at tone 87 to 100). Used for the palettes whose own colours are dark.
 */
internal fun ThemePreset.toLightColorScheme(): ColorScheme {
    val seed = Tones.hueChroma(primary)
    val accentSeed = Tones.hueChroma(accent)
    fun p(tone: Int) = Color(Tones.argb(seed.hue, seed.chroma, tone.toDouble()))
    fun s(tone: Int) = Color(Tones.argb(seed.hue, seed.chroma * SECONDARY_CHROMA, tone.toDouble()))
    fun t(tone: Int) = Color(Tones.argb(accentSeed.hue, accentSeed.chroma, tone.toDouble()))
    fun n(tone: Int) = Color(Tones.argb(seed.hue, NEUTRAL_CHROMA, tone.toDouble()))
    fun nv(tone: Int) = Color(Tones.argb(seed.hue, NEUTRAL_VARIANT_CHROMA, tone.toDouble()))
    return lightColorScheme(
        primary = p(40),
        onPrimary = p(100),
        primaryContainer = p(90),
        onPrimaryContainer = p(10),
        inversePrimary = p(80),
        secondary = s(40),
        onSecondary = s(100),
        secondaryContainer = s(90),
        onSecondaryContainer = s(10),
        tertiary = t(40),
        onTertiary = t(100),
        tertiaryContainer = t(90),
        onTertiaryContainer = t(10),
        background = n(98),
        onBackground = n(10),
        surface = n(98),
        onSurface = n(10),
        surfaceVariant = nv(90),
        onSurfaceVariant = nv(30),
        surfaceTint = p(40),
        inverseSurface = n(20),
        inverseOnSurface = n(95),
        // The Material baseline light error roles.
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = nv(50),
        outlineVariant = nv(80),
        scrim = Color.Black,
        surfaceBright = n(98),
        surfaceDim = n(87),
        surfaceContainerLowest = n(100),
        surfaceContainerLow = n(96),
        surfaceContainer = n(94),
        surfaceContainerHigh = n(92),
        surfaceContainerHighest = n(90),
    ).withGeneratedFixedRoles(::p, ::s, ::t)
}

/** Fixed accent roles are the same in both modes: tone 90 and 80 with text at 10 and 30. */
private fun ColorScheme.withGeneratedFixedRoles(
    p: (Int) -> Color,
    s: (Int) -> Color,
    t: (Int) -> Color,
): ColorScheme = copy(
    primaryFixed = p(90),
    primaryFixedDim = p(80),
    onPrimaryFixed = p(10),
    onPrimaryFixedVariant = p(30),
    secondaryFixed = s(90),
    secondaryFixedDim = s(80),
    onSecondaryFixed = s(10),
    onSecondaryFixedVariant = s(30),
    tertiaryFixed = t(90),
    tertiaryFixedDim = t(80),
    onTertiaryFixed = t(10),
    onTertiaryFixedVariant = t(30),
)

/** Secondary keeps the primary hue at about a third of its chroma, as in Material's tonal-spot scheme. */
private const val SECONDARY_CHROMA = 0.35

/** OKLCH chroma of the surfaces: a hint of the preset's hue, as Material's neutral palettes have. */
private const val NEUTRAL_CHROMA = 0.012
private const val NEUTRAL_VARIANT_CHROMA = 0.024

private fun blend(base: Color, over: Color, amount: Float) = over.copy(alpha = amount).compositeOver(base)

private fun onColor(bg: Color) = if (bg.luminance() > 0.4f) Color(0xFF0F172A) else Color.White

/**
 * Material scheme for a palette's own colours. Every colour role is set from the preset's own colours (none is left at
 * the Material baseline purple): containers and surfaces are tints of the background toward the text or accent colour.
 */
fun ThemePreset.toColorScheme(): ColorScheme {
    val bg = background.c()
    val fg = text.c()
    val pri = primary.c()
    val tert = accent.c()
    val sec = blend(pri, fg, 0.25f)
    val err = if (dark) Color(0xFFFF8A80) else Color(0xFFDC2626)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val surface = blend(bg, fg, 0.04f)
    return base.copy(
        primary = pri,
        onPrimary = onColor(pri),
        primaryContainer = blend(bg, pri, if (dark) 0.35f else 0.22f),
        onPrimaryContainer = fg,
        inversePrimary = blend(pri, if (dark) Color.Black else Color.White, 0.45f),
        secondary = sec,
        onSecondary = onColor(sec),
        secondaryContainer = blend(bg, pri, if (dark) 0.24f else 0.14f),
        onSecondaryContainer = fg,
        tertiary = tert,
        onTertiary = onColor(tert),
        tertiaryContainer = blend(bg, tert, 0.3f),
        onTertiaryContainer = fg,
        background = bg,
        onBackground = fg,
        surface = surface,
        onSurface = fg,
        surfaceVariant = blend(bg, fg, 0.10f),
        onSurfaceVariant = blend(bg, fg, 0.72f),
        surfaceTint = pri,
        inverseSurface = blend(fg, bg, 0.08f),
        inverseOnSurface = blend(bg, fg, 0.06f),
        error = err,
        onError = onColor(err),
        errorContainer = blend(bg, err, if (dark) 0.30f else 0.16f),
        onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF7F1D1D),
        outline = blend(bg, fg, 0.50f),
        outlineVariant = blend(bg, fg, 0.18f),
        scrim = Color.Black,
        surfaceBright = if (dark) blend(bg, fg, 0.16f) else surface,
        surfaceDim = if (dark) bg else blend(bg, fg, 0.13f),
        surfaceContainerLowest = if (dark) blend(bg, Color.Black, 0.35f) else blend(bg, Color.White, 0.7f),
        surfaceContainerLow = blend(bg, fg, 0.03f),
        surfaceContainer = blend(bg, fg, 0.06f),
        surfaceContainerHigh = blend(bg, fg, 0.09f),
        surfaceContainerHighest = blend(bg, fg, 0.12f),
        primaryFixed = blend(Color.White, pri, 0.25f),
        primaryFixedDim = blend(Color.White, pri, 0.45f),
        onPrimaryFixed = blend(Color.Black, pri, 0.30f),
        onPrimaryFixedVariant = blend(Color.Black, pri, 0.60f),
        secondaryFixed = blend(Color.White, sec, 0.25f),
        secondaryFixedDim = blend(Color.White, sec, 0.45f),
        onSecondaryFixed = blend(Color.Black, sec, 0.30f),
        onSecondaryFixedVariant = blend(Color.Black, sec, 0.60f),
        tertiaryFixed = blend(Color.White, tert, 0.25f),
        tertiaryFixedDim = blend(Color.White, tert, 0.45f),
        onTertiaryFixed = blend(Color.Black, tert, 0.30f),
        onTertiaryFixedVariant = blend(Color.Black, tert, 0.60f),
    )
}
