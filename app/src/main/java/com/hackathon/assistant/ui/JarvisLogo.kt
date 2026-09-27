package com.hackathon.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Jarvis mark: an assistant that lives in your phone and acts on what you ask.
 *  - the orb is Jarvis, always there (glossy, in the Jarvis spectrum);
 *  - the four-point spark at its heart is the intelligence acting for you; it breathes with
 *    [energy] (your voice while listening, Jarvis' words while speaking);
 *  - the orbit and its satellite circle the orb: always on, always around you. It turns faster
 *    while [busy] (thinking).
 * Everything is drawn inside the bounds, so it never clips.
 */
@Composable
fun JarvisLogo(modifier: Modifier = Modifier, energy: Float = 0f, busy: Boolean = false, colors: List<Color> = JarvisSpectrum, orbits: Boolean = true) {
    val t = rememberInfiniteTransition(label = "logo")
    val orbit by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (busy) 1_100 else 4_200, easing = LinearEasing)), label = "orbit")
    val shimmer by t.animateFloat(0f, 360f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "shimmer")
    // Slow breathing for the orbit-free (card) version's halo.
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse), label = "breath")

    Canvas(modifier.semantics { contentDescription = "Jarvis" }) {
        val s = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        val ringStroke = s * 0.055f
        val ringRadius = s / 2 - ringStroke / 2 - s * 0.03f   // inset: stroke and satellite stay inside
        val orbRadius = if (orbits) s * 0.33f else s * 0.30f

        if (!orbits) {
            // Halo glow: light that starts at the orb's edge and fades smoothly outward, in the Jarvis
            // spectrum. It never stops moving: the colours turn round the orb, the glow swells and
            // settles (breath), and it flares with the voice. Drawn before the orb, which covers the
            // inner half, so the glow begins exactly at the orb boundary.
            val e = energy.coerceIn(0f, 1f)
            val pulse = 0.5f + 0.5f * breath
            val reach = s * (0.17f + 0.06f * pulse + 0.05f * e)   // how far the glow extends
            val sweep = Brush.sweepGradient(colors + colors.first(), c)
            rotate(-shimmer * 2f, c) {
                // Outer soft corona: wide, faint, far-reaching.
                drawSoftRing(c, orbRadius, reach * 1.2f, sweep, alpha = 0.12f + 0.08f * pulse + 0.1f * e, blur = reach)
                // Inner bright glow hugging the edge.
                drawSoftRing(c, orbRadius, reach * 0.6f, sweep, alpha = 0.24f + 0.1f * pulse + 0.14f * e, blur = reach * 0.5f)
            }
        } else {

        // Orbit: a faint full ring, then comets whose tails taper and fade smoothly to nothing.
        drawCircle(colors[1].copy(alpha = 0.12f), ringRadius, c, style = Stroke(ringStroke * 0.35f))
        // Second, thinner comet going the other way, so the orbit never looks static.
        drawComet(c, ringRadius, -orbit + 180f, 120f, ringStroke * 0.6f, colors[3], colors[1], satellite = false, clockwise = false)
        drawComet(c, ringRadius, orbit, 150f, ringStroke, colors[0], colors[2], satellite = true)
        }

        // Orb: spectrum body turning slowly, a soft rim light, and a glossy highlight.
        rotate(shimmer, c) {
            drawCircle(Brush.sweepGradient(colors + colors.first(), c), orbRadius, c)
        }
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.0f), Color.Black.copy(alpha = 0.18f)), c, orbRadius), orbRadius, c)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), Offset(c.x - orbRadius * 0.35f, c.y - orbRadius * 0.45f), orbRadius * 0.7f),
            orbRadius, c,
        )

        // Spark: the four-point star, breathing with the voice.
        val sparkScale = 0.86f + 0.3f * energy.coerceIn(0f, 1f)
        scale(sparkScale, c) { drawSpark(c, orbRadius * 0.62f) }
    }
}

/**
 * A comet on the orbit, head at [headAngle] (degrees), tail trailing [length] degrees behind the
 * direction of travel ([clockwise] or not). The tail is one filled crescent whose width tapers
 * smoothly to a point and whose colour fades to transparent, so there are no bands, seams or a
 * blunt cap at its end. A wider, fainter copy under it is the glow.
 */
private fun DrawScope.drawComet(
    c: Offset, radius: Float, headAngle: Float, length: Float, width: Float,
    tailColor: Color, headColor: Color, satellite: Boolean, clockwise: Boolean = true,
) {
    // Local frame: head at 0°, tail over negative angles. Mirroring flips the direction.
    rotate(headAngle, c) {
        scale(1f, if (clockwise) 1f else -1f, c) {
            val tailStop = 1f - length / 360f
            fun brush(alpha: Float) = Brush.sweepGradient(
                // Past the head the colour continues briefly, so the blur has no seam at 0°.
                0f to headColor.copy(alpha = alpha),
                0.04f to Color.Transparent,
                tailStop to tailColor.copy(alpha = 0f),
                (tailStop + 1f) / 2f to tailColor.copy(alpha = 0.55f * alpha),
                1f to headColor.copy(alpha = alpha),
                center = c,
            )
            // Blurred edges: the glow is heavily feathered, the core slightly, so no edge is hard.
            drawSoftPath(crescent(c, radius, length, width * 2.2f), brush(0.45f), blur = width * 1.6f)
            drawSoftPath(crescent(c, radius, length, width), brush(1f), blur = width * 0.35f)
        }
    }
    val head = Math.toRadians(headAngle.toDouble())
    val dot = Offset(c.x + radius * cos(head).toFloat(), c.y + radius * sin(head).toFloat())
    if (satellite) {
        drawCircle(Brush.radialGradient(listOf(headColor.copy(alpha = 0.55f), Color.Transparent), dot, width * 2.6f), width * 2.6f, dot)
        drawCircle(Color.White, width * 1.1f, dot)
        drawCircle(headColor, width * 0.7f, dot)
    } else {
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), headColor.copy(alpha = 0.4f), Color.Transparent), dot, width * 1.8f), width * 1.8f, dot)
    }
}

/** A ring stroke of [width] around [c] at [radius], its edges feathered by a blur of [blur] px. */
private fun DrawScope.drawSoftRing(c: Offset, radius: Float, width: Float, brush: Brush, alpha: Float, blur: Float) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = width
        shader = (brush as ShaderBrush).createShader(size)
        this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        maskFilter = android.graphics.BlurMaskFilter(blur.coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { it.nativeCanvas.drawCircle(c.x, c.y, radius, paint) }
}

/** Fills [path] with [brush], its edges feathered by a Gaussian blur of [blur] px. */
private fun DrawScope.drawSoftPath(path: Path, brush: Brush, blur: Float) {
    val shader = (brush as ShaderBrush).createShader(size)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.shader = shader
        maskFilter = android.graphics.BlurMaskFilter(blur.coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
}

/** A ring segment from -[length]° to 0° whose thickness grows smoothly from 0 to [width]. */
private fun crescent(c: Offset, r: Float, length: Float, width: Float): Path {
    val n = 64
    fun pt(deg: Float, rad: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(c.x + rad * cos(a).toFloat(), c.y + rad * sin(a).toFloat())
    }
    fun half(f: Float): Float { val e = f * f * (3 - 2 * f); return width / 2 * (0.02f + 0.98f * e) }
    return Path().apply {
        for (i in 0..n) {
            val f = i / n.toFloat()
            val p = pt(-length + f * length, r + half(f))
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        // Rounded head: a half-circle around the head end.
        for (j in 1 until 12) {
            val t = j / 12f * 180f
            val a = Math.toRadians(t.toDouble())
            val h = half(1f)
            val o = pt(0f, r)
            // Offset from the head point: outward normal rotates to inward through the travel direction.
            lineTo(o.x + h * cos(a).toFloat(), o.y + h * sin(a).toFloat())
        }
        for (i in n downTo 0) {
            val f = i / n.toFloat()
            val p = pt(-length + f * length, r - half(f))
            lineTo(p.x, p.y)
        }
        close()
    }
}

/** A four-point spark with softly concave sides. */
private fun DrawScope.drawSpark(c: Offset, r: Float) {
    val k = r * 0.18f // how far the sides pinch toward the centre
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
        quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
    drawPath(p, Color.White)
    drawCircle(Color.White.copy(alpha = 0.35f), r * 0.42f, c)
}

/** The Jarvis colours: waveform, aurora, outline and logo all use these. */
val JarvisSpectrum = listOf(Color(0xFF4C7DFF), Color(0xFF8E5CF7), Color(0xFFE9508F), Color(0xFF19B3A6))
