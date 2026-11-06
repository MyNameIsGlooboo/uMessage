package com.glooboo.umessage.ui.theme

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Material 3 style tones without the material-color-utilities library.
 *
 * A Material "tone" is CIE L* (0 black, 100 white), so a colour at a given tone has a known relative luminance and the
 * contrast between two tones is fixed whatever their hue: tone 80 on tone 20 is always about 7.7:1. Hue and chroma
 * come from OKLCH, which keeps a hue steadier than CIELAB when it is lightened (Lab blues drift toward purple). For
 * each tone the OKLab lightness is solved so the result has exactly that luminance, and chroma is reduced until the
 * colour fits in sRGB.
 */
internal object Tones {
    /** Hue (radians) and chroma of [argb] in OKLCH. */
    data class HueChroma(val hue: Double, val chroma: Double)

    fun hueChroma(argb: Int): HueChroma {
        val lab = linearToOklab(linear(argb shr 16 and 0xFF), linear(argb shr 8 and 0xFF), linear(argb and 0xFF))
        return HueChroma(atan2(lab[2], lab[1]), hypot(lab[1], lab[2]))
    }

    /** The Material tone (CIE L*) of [argb]. */
    fun tone(argb: Int): Double {
        val y = 0.2126 * linear(argb shr 16 and 0xFF) + 0.7152 * linear(argb shr 8 and 0xFF) + 0.0722 * linear(argb and 0xFF)
        return lstarFromY(y)
    }

    /** Relative luminance (0..1) of a colour at [tone]. */
    fun yFromTone(tone: Double): Double {
        val ft = (tone + 16.0) / 116.0
        val ft3 = ft * ft * ft
        return if (ft3 > EPSILON) ft3 else (116.0 * ft - 16.0) / KAPPA
    }

    /** WCAG contrast ratio of two colours. */
    fun contrast(a: Int, b: Int): Double {
        val ya = yFromTone(tone(a))
        val yb = yFromTone(tone(b))
        return (maxOf(ya, yb) + 0.05) / (minOf(ya, yb) + 0.05)
    }

    /** An opaque sRGB colour at [tone] with [hue] and as much of [chroma] as sRGB allows. */
    fun argb(hue: Double, chroma: Double, tone: Double): Int {
        if (tone <= 0.0) return 0xFF000000.toInt()
        if (tone >= 100.0) return 0xFFFFFFFF.toInt()
        val targetY = yFromTone(tone)
        val full = solve(hue, chroma, targetY)
        if (full.inGamut) return full.toArgb()
        // The largest chroma that still fits, by bisection; grey (chroma 0) always fits.
        var low = solve(hue, 0.0, targetY)
        var lowC = 0.0
        var highC = chroma
        repeat(24) {
            val mid = (lowC + highC) / 2
            val candidate = solve(hue, mid, targetY)
            if (candidate.inGamut) {
                low = candidate
                lowC = mid
            } else {
                highC = mid
            }
        }
        return low.toArgb()
    }

    private class Rgb(val r: Double, val g: Double, val b: Double) {
        val inGamut: Boolean get() = listOf(r, g, b).all { it in -GAMUT_SLACK..1.0 + GAMUT_SLACK }
        fun toArgb(): Int = (0xFF shl 24) or (encode(r) shl 16) or (encode(g) shl 8) or encode(b)
    }

    /** Linear sRGB at [hue] and [chroma] whose luminance is [targetY], found by bisection on OKLab lightness. */
    private fun solve(hue: Double, chroma: Double, targetY: Double): Rgb {
        val a = chroma * cos(hue)
        val b = chroma * sin(hue)
        var low = 0.0
        var high = 1.0
        var rgb = oklabToLinear(0.5, a, b)
        repeat(32) {
            val mid = (low + high) / 2
            rgb = oklabToLinear(mid, a, b)
            val y = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b
            if (y < targetY) low = mid else high = mid
        }
        return rgb
    }

    private fun linearToOklab(r: Double, g: Double, b: Double): DoubleArray {
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun oklabToLinear(lightness: Double, a: Double, b: Double): Rgb {
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return Rgb(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
    }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun encode(linear: Double): Int {
        val c = linear.coerceIn(0.0, 1.0)
        val srgb = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
        return (srgb * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun lstarFromY(y: Double): Double = if (y > EPSILON) 116.0 * cbrt(y) - 16.0 else KAPPA * y

    private const val EPSILON = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0
    private const val GAMUT_SLACK = 1e-4
}
