package com.hackathon.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.key
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hackathon.assistant.core.StepStatus
import com.hackathon.assistant.core.TaskStep
import com.hackathon.assistant.core.VoiceState
import com.hackathon.assistant.voice.Captions
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Jarvis card: a compact floating sheet at the bottom of any app.
 *
 *  ┌──────────────────────────────────────────┐
 *  │ ◉ Listening                          ✕  │   status + close
 *  │ Send a message to Sarah saying I'll be   │   user's words (grey while recognising)
 *  │ ∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿     (●)  │   voice waveform + mic
 *  └──────────────────────────────────────────┘
 *
 * Tap the card to talk again, or to stop Jarvis mid-reply. Springs in from the bottom, settles
 * when Jarvis is done, and slides away (✕, swipe down, or shortly after the last word).
 */
@Composable
fun AssistantCardHost(
    visible: Boolean,
    state: VoiceState,
    captions: Captions,
    level: StateFlow<Float>,
    onMic: () -> Unit,
    onClose: () -> Unit,
    steps: List<TaskStep> = emptyList(),
) {
    val palette = if (isSystemInDarkTheme()) Palette.Dark else Palette.Light
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(visible) { if (visible) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { it / 2 } +
            scaleIn(spring(dampingRatio = 0.86f, stiffness = 420f), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)) +
            fadeIn(tween(140)),
        exit = slideOutVertically(tween(200, easing = FastOutLinearInEasing)) { it / 2 } +
            scaleOut(tween(200), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f)) +
            fadeOut(tween(160)),
    ) {
        SwipeDown(onDismiss = onClose) { Card(state, captions, steps, level, palette, onTap = onMic, onClose = onClose) }
    }
}

@Composable
private fun Card(state: VoiceState, c: Captions, steps: List<TaskStep>, level: StateFlow<Float>, p: Palette, onTap: () -> Unit, onClose: () -> Unit) {
    val mic by level.collectAsState()
    // Each spoken word (TTS range callback) kicks the waveform, so it pulses with Jarvis' voice.
    val wordPulse = remember { Animatable(0f) }
    LaunchedEffect(c.spokenUpTo) {
        if (c.spokenUpTo > 0) { wordPulse.snapTo(1f); wordPulse.animateTo(0f, tween(320)) }
    }
    // One smoothed "energy" drives the waveform, the aurora and the outline, so they move together.
    val energy by animateFloatAsState(
        targetValue = when (state) {
            VoiceState.LISTENING -> 0.18f + 0.82f * mic
            VoiceState.SPEAKING -> 0.35f + 0.45f * wordPulse.value
            VoiceState.THINKING -> 0.2f
            VoiceState.IDLE -> 0f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "energy",
    )
    // Corners concentric with the phone's screen corners: screen radius minus the card's side inset.
    val cornerRadius = cardCornerRadius()
    val shape = RoundedCornerShape(cornerRadius)
    val description = if (state == VoiceState.SPEAKING || state == VoiceState.THINKING) "Stop Jarvis" else "Talk to Jarvis"

    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = CARD_INSET, end = CARD_INSET, bottom = CARD_INSET, top = GLOW_ROOM)) {
        Column(
            Modifier
                .fillMaxWidth()
                .spectrumGlow(energy, cornerRadius)
                .shadow(elevation = 16.dp, shape = shape, ambientColor = p.shadow, spotColor = p.shadow)
                .clip(shape)
                .background(p.surface)
                .aurora(energy, p)
                .clickable(remember { MutableInteractionSource() }, ripple(color = Palette.Spectrum[1]), onClick = onTap)
                .semantics { contentDescription = description }
                // No animated height: the card lives in a wrap-content overlay window, and the
                // window resizes a frame behind animated content, which makes the card clip and
                // jitter. Size changes land in one frame; transitions below only fade and slide.
                .padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 18.dp),
        ) {
            // Working on a task: a compact card (the app underneath stays visible) with the stepper.
            // Once the answer starts, the card stays on the answer: it never flips back to the
            // stepper when the voice briefly goes quiet between the answer and the task's end.
            val working = steps.isNotEmpty() && c.reply.isBlank() && state != VoiceState.SPEAKING && state != VoiceState.LISTENING
            StatusRow(state, c.notice, energy, p, onClose, working = if (working) workingLabel(steps) else null, answered = c.reply.isNotBlank())
            Spacer(Modifier.height(if (working) 10.dp else 14.dp))
            AnimatedContent(
                targetState = working,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(120))) using
                        SizeTransform(clip = false) { _, _ -> snap() }
                },
                label = "cardMode",
            ) { isWorking ->
                Column(Modifier.fillMaxWidth()) {
                    if (isWorking) {
                        if (c.heard.isNotBlank()) {
                            Text(c.heard, style = p.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
                            Spacer(Modifier.height(10.dp))
                        }
                        Stepper(steps, p, Modifier.padding(start = 4.dp, end = 8.dp))
                    } else {
                        if (steps.isNotEmpty() && c.reply.isNotBlank()) {
                            StepsSummary(steps, p, Modifier.padding(start = 4.dp, bottom = 8.dp))
                        }
                        Box(Modifier.padding(start = 4.dp, end = 8.dp)) { Transcript(state, c, voice = c.userSpeaking, p = p) }
                        Spacer(Modifier.height(16.dp))
                        Waveform(state, energy, Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp).height(52.dp))
                    }
                }
            }
        }
    }
}

/**
 * Soft glow of the waveform colours behind the text: four blurred colour fields that drift slowly
 * and brighten with [energy]. Stays subtle so the transcript keeps full contrast.
 */
@Composable
private fun Modifier.aurora(energy: Float, p: Palette): Modifier {
    val t = rememberInfiniteTransition(label = "aurora")
    val drift by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "drift")
    return drawBehind {
        val strength = p.auroraBase + p.auroraGain * energy
        Palette.Spectrum.forEachIndexed { i, color ->
            val a = drift + i * (PI / 2).toFloat()
            val center = Offset(
                size.width * (0.2f + 0.6f * (i / 3f)) + size.width * 0.08f * sin(a),
                size.height * (0.85f + 0.12f * sin(a * 1.3f + i)),
            )
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = strength), Color.Transparent), center, size.width * 0.42f),
                radius = size.width * 0.42f,
                center = center,
            )
        }
    }
}

// ---- status ----------------------------------------------------------------------------------

@Composable
private fun StatusRow(state: VoiceState, notice: String, energy: Float, p: Palette, onClose: () -> Unit, working: String? = null, answered: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        JarvisLogo(Modifier.size(40.dp), energy = energy, busy = state == VoiceState.THINKING, orbits = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("J.A.R.V.I.S", style = p.title.copy(fontFamily = Orbitron, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)))
            val status = notice.ifEmpty {
                working ?: when (state) {
                    VoiceState.LISTENING -> "Listening"
                    VoiceState.THINKING -> "Thinking"
                    VoiceState.SPEAKING -> "Speaking"
                    VoiceState.IDLE -> if (answered) "Done" else "Say “Jarvis”"
                }
            }
            AnimatedContent(
                status,
                transitionSpec = { (fadeIn(tween(180, 60)) + slideInVertically(tween(180)) { it / 3 }) togetherWith fadeOut(tween(100)) },
                label = "status",
            ) { text ->
                Text(text, style = p.label.copy(color = if (notice.isNotEmpty()) p.warning else p.secondary))
            }
        }
        CloseButton(p, onClose)
    }
}

// ---- task steps ------------------------------------------------------------------------------

private fun workingLabel(steps: List<TaskStep>): String {
    val waiting = steps.lastOrNull()?.status == StepStatus.WAITING_FOR_USER
    return if (waiting) "Waiting for you" else "Working · step ${steps.size}"
}

/**
 * Fixed-size stepper: the previous step small and grey, the current step full size. However many
 * steps a task has, the card keeps the same height (rows have fixed heights, the previous-step
 * slot is reserved even when empty), so nothing resizes while Jarvis works. When a step starts,
 * the current one moves up into the grey slot and the new one slides in.
 */
@Composable
private fun Stepper(steps: List<TaskStep>, p: Palette, modifier: Modifier = Modifier) {
    val current = steps.lastOrNull() ?: return
    val previous = steps.getOrNull(steps.size - 2)
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(PREVIOUS_ROW)) {
            if (previous != null) key(previous.id) { PreviousStep(previous, p) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(CURRENT_ROW)) {
            key(current.id) { CurrentStep(current, p) }
        }
    }
}

@Composable
private fun PreviousStep(step: TaskStep, p: Palette) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(220)) }
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = 0.3f + 0.7f * enter.value; translationY = (1f - enter.value) * 10.dp.toPx() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { StepIcon(step.status, p, iconSize = 14.dp) }
        Spacer(Modifier.width(12.dp))
        Text(step.title, style = p.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CurrentStep(step: TaskStep, p: Palette) {
    // Slide + fade in when this step starts.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) }
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 22.dp.toPx() },
    ) {
        Box(Modifier.width(22.dp).padding(top = 1.dp), contentAlignment = Alignment.TopCenter) { StepIcon(step.status, p) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AnimatedContent(step.title, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "stepTitle") { title ->
                Text(title, style = p.stepTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Always one line (blank if there is no detail) so the row height never changes.
            Text(step.detail.ifBlank { " " }, style = p.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Running: a turning spectrum arc. Done: a filled check. Failed: a cross. Waiting: a pulsing dot. */
@Composable
private fun StepIcon(status: StepStatus, p: Palette, iconSize: androidx.compose.ui.unit.Dp = 20.dp) {
    val t = rememberInfiniteTransition(label = "stepIcon")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Crossfade(status, animationSpec = tween(180), label = "stepStatus") { s ->
        Canvas(Modifier.size(iconSize)) {
            val w = size.width
            val h = size.height
            val r = size.minDimension / 2
            val stroke = (if (iconSize < 18.dp) 1.8 else 2.2).dp.toPx()
            when (s) {
                StepStatus.RUNNING -> {
                    drawCircle(p.hairline, r - stroke / 2, style = Stroke(stroke))
                    drawArc(
                        Brush.sweepGradient(listOf(Palette.Spectrum[0], Palette.Spectrum[1], Palette.Spectrum[2])),
                        startAngle = spin, sweepAngle = 110f, useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2), size = Size(w - stroke, h - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                StepStatus.DONE -> {
                    drawCircle(Palette.Spectrum[0], r)
                    val check = Path().apply {
                        moveTo(w * 0.28f, h * 0.52f)
                        lineTo(w * 0.44f, h * 0.67f)
                        lineTo(w * 0.73f, h * 0.36f)
                    }
                    drawPath(check, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
                StepStatus.FAILED -> {
                    drawCircle(p.warning, r)
                    val a = w * 0.34f; val b = w * 0.66f
                    drawLine(Color.White, Offset(a, a), Offset(b, b), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White, Offset(b, a), Offset(a, b), 2.dp.toPx(), StrokeCap.Round)
                }
                StepStatus.WAITING_FOR_USER -> {
                    drawCircle(Palette.Spectrum[1].copy(alpha = 0.25f), r * pulse)
                    drawCircle(Palette.Spectrum[1], r * 0.45f)
                }
            }
        }
    }
}

/** After the task: one line, "✓ 4 steps" (or how many failed), above the answer. */
@Composable
private fun StepsSummary(steps: List<TaskStep>, p: Palette, modifier: Modifier = Modifier) {
    val failed = steps.count { it.status == StepStatus.FAILED }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepIcon(if (failed == 0) StepStatus.DONE else StepStatus.FAILED, p)
        Spacer(Modifier.width(8.dp))
        Text(
            "${steps.size} step${if (steps.size == 1) "" else "s"}" + if (failed > 0) " · $failed failed" else "",
            style = p.caption,
        )
    }
}

private val PREVIOUS_ROW = 20.dp
private val CURRENT_ROW = 40.dp

// ---- transcript ------------------------------------------------------------------------------

private enum class Block { HINT, HEARD, REPLY }

@Composable
private fun Transcript(state: VoiceState, c: Captions, voice: Boolean, p: Palette) {
    val block = when {
        c.reply.isNotBlank() && state != VoiceState.LISTENING -> Block.REPLY
        c.heard.isNotBlank() || c.pending.isNotBlank() -> Block.HEARD
        else -> Block.HINT
    }
    AnimatedContent(
        targetState = block,
        transitionSpec = {
            (fadeIn(tween(220, 80)) + slideInVertically(tween(260)) { it / 6 }) togetherWith fadeOut(tween(120)) using
                SizeTransform(clip = false) { _, _ -> snap() }
        },
        label = "transcript",
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) { b ->
        Column(Modifier.fillMaxWidth()) {
            when (b) {
                // While Jarvis answers, only the answer is shown, in the same type as the user's words.
                Block.REPLY -> Text(spoken(c.reply, c.spokenUpTo, p), style = p.headline, maxLines = 6, overflow = TextOverflow.Ellipsis)
                Block.HEARD -> LiveText(c.heard, c.pending, final = c.heardFinal, typing = false, p = p)
                // The moment the mic hears speech, before the first words are recognised, show the
                // typing dots so the card reacts instantly.
                Block.HINT -> if (state == VoiceState.LISTENING && voice) LiveText("", "", final = false, typing = true, p = p)
                else Text(
                    when (state) {
                        VoiceState.LISTENING -> "How can I help?"
                        VoiceState.THINKING -> "One moment…"
                        else -> "Say “Jarvis” anytime"
                    },
                    style = p.headline.copy(color = p.secondary),
                )
            }
        }
    }
}

/**
 * The user's words as they are recognised, like Gboard: confident words, then the recogniser's
 * tentative words slightly lighter, updated word by word. Only genuinely new words fade in
 * (a revision of earlier words just replaces them, no flash). Dots show only before the first word.
 */
@Composable
private fun LiveText(text: String, pending: String, final: Boolean, typing: Boolean, p: Palette) {
    if (typing && text.isEmpty() && pending.isEmpty()) return TypingDots(p)
    val base = if (final) p.primary else p.secondary
    val tentative = base.copy(alpha = base.alpha * 0.6f)
    val full = listOf(text, pending).filter { it.isNotBlank() }.joinToString(" ")
    // Remember what was on screen; fade in only a strict extension of it (revisions just replace).
    val previous = remember { mutableStateOf("") }
    val fade = remember { Animatable(1f) }
    val grownFrom = remember(full) { previous.value.takeIf { it.isNotEmpty() && full.startsWith(it) && full.length > it.length }?.length }
    LaunchedEffect(full) {
        previous.value = full
        if (grownFrom != null) { fade.snapTo(0.25f); fade.animateTo(1f, tween(110)) } else fade.snapTo(1f)
    }
    val stableEnd = text.length.coerceAtMost(full.length)
    val newFrom = grownFrom ?: full.length
    Text(
        buildAnnotatedString {
            // A few runs, not one per character: cheap to rebuild on every partial result.
            fun run(from: Int, to: Int, color: Color) { if (to > from) withStyle(SpanStyle(color = color)) { append(full, from, to) } }
            val cut1 = minOf(stableEnd, newFrom)
            run(0, cut1, base)
            run(cut1, stableEnd, base.copy(alpha = base.alpha * fade.value))
            val cut2 = maxOf(stableEnd, newFrom)
            run(stableEnd, cut2, tentative)
            run(cut2, full.length, tentative.copy(alpha = tentative.alpha * fade.value))
        },
        style = p.headline, maxLines = 4, overflow = TextOverflow.Ellipsis,
    )
}

/** Three dots that pulse in turn; composed (and animated) only while shown. */
@Composable
private fun TypingDots(p: Palette) {
    val t = rememberInfiniteTransition(label = "typing")
    val beat by t.animateFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "beat")
    Text(
        buildAnnotatedString {
            for (i in 0 until 3) withStyle(SpanStyle(color = p.secondary.copy(alpha = if (beat.toInt() == i) 0.9f else 0.3f))) { append("• ") }
        },
        style = p.headline,
    )
}

/** Words already spoken in full colour, the rest dimmed, so the reply reads along with the voice. */
private fun spoken(text: String, upTo: Int, p: Palette) = buildAnnotatedString {
    if (upTo !in 1 until text.length) { withStyle(SpanStyle(color = p.primary)) { append(text) }; return@buildAnnotatedString }
    withStyle(SpanStyle(color = p.primary)) { append(text, 0, upTo) }
    withStyle(SpanStyle(color = p.primary.copy(alpha = 0.34f))) { append(text, upTo, text.length) }
}

// ---- waveform --------------------------------------------------------------------------------

/**
 * Four tapered sine strands in the spectrum colours. Amplitude follows the voice frame by frame,
 * and louder speech makes them oscillate faster and tighter; they settle to a calm line at the end.
 */
@Composable
private fun Waveform(state: VoiceState, energy: Float, modifier: Modifier) {
    val phase = remember { mutableFloatStateOf(0f) }
    val currentEnergy by rememberUpdatedState(energy)
    val speed = if (state == VoiceState.THINKING) 1.6f else 3.2f
    LaunchedEffect(state) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1e9f
                last = now
                // Not wrapped: strands run at different multiples of the phase, so wrapping would make them jump.
                phase.floatValue += dt * (speed + 9f * currentEnergy)
            }
        }
    }
    Canvas(modifier.semantics { contentDescription = "Voice activity" }) {
        val mid = size.height / 2
        val cycles = 2.2f + 1.6f * energy
        val strands = listOf(1.0f to 0f, 0.8f to 1.6f, 0.62f to 3.1f, 0.45f to 4.5f)
        strands.forEachIndexed { i, (weight, offset) ->
            val amp = mid * 0.95f * energy * weight
            val color = Palette.Spectrum[i]
            if (amp < 0.6f) {
                drawLine(color.copy(alpha = 0.35f), Offset(0f, mid), Offset(size.width, mid), 2.dp.toPx(), StrokeCap.Round)
                return@forEachIndexed
            }
            val path = Path()
            val steps = 80
            for (k in 0..steps) {
                val u = k.toFloat() / steps
                val envelope = sin(PI * u).toFloat().let { it * it }
                val y = mid + amp * envelope * sin((u * cycles * (1f + 0.12f * i) * 2 * PI).toFloat() + phase.floatValue * (1f + 0.15f * i) + offset)
                if (k == 0) path.moveTo(size.width * u, y) else path.lineTo(size.width * u, y)
            }
            drawPath(path, color.copy(alpha = 0.92f), style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

// ---- controls --------------------------------------------------------------------------------

@Composable
private fun CloseButton(p: Palette, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(p.tonal)
            .clickable(remember { MutableInteractionSource() }, ripple(), onClick = onClick)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Close, null, tint = p.secondary, modifier = Modifier.size(16.dp)) }
}

// ---- outline, swipe --------------------------------------------------------------------------

/**
 * A thin spectrum outline drawn over the hairline border. It turns while Jarvis is active and
 * fades out entirely when idle, so a finished card looks calm.
 */
@Composable
private fun Modifier.spectrumOutline(state: VoiceState, energy: Float): Modifier {
    val t = rememberInfiniteTransition(label = "outline")
    val turn by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == VoiceState.LISTENING) 2_800 else 5_500, easing = LinearEasing)), label = "turn")
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    return drawWithContent {
        drawContent()
        if (energy < 0.02f) return@drawWithContent
        val n = Palette.Spectrum.size
        val shift = (turn * n).toInt() % n
        val stops = List(n) { Palette.Spectrum[(it + shift) % n] }
        val w = (1f + 1.5f * energy).dp.toPx()
        drawRoundRect(
            brush = Brush.sweepGradient(stops + stops.first(), center = Offset(size.width / 2, size.height / 2)),
            topLeft = Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(radius - w / 2),
            style = Stroke(width = w),
            alpha = (0.35f + 0.65f * energy).coerceAtMost(1f),
        )
    }
}

/** Side inset of the card from the screen edge; the corner radius is derived from it. */
private val CARD_INSET = 6.dp

/** Transparent room above the card so its glow fades out fully instead of being cut by the window's top edge. */
private val GLOW_ROOM = 28.dp

/**
 * The card's corner radius, concentric with the display's rounded corners (read from the window
 * insets, API 31+): screen corner radius minus the card's inset. Read from the display. Falls back to 28 dp.
 */
@Composable
private fun cardCornerRadius(): androidx.compose.ui.unit.Dp {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    // From the display, not the window insets: insets are null on the card's first frame, which
    // made the card open with the fallback radius and then snap to the real one.
    val screenPx = remember {
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(android.view.WindowManager::class.java).defaultDisplay
                .getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        }.getOrDefault(0) else 0
    }
    if (screenPx <= 0) return 28.dp
    val screen = with(density) { screenPx.toDp() }
    return (screen - CARD_INSET).coerceAtLeast(16.dp)
}

/**
 * The card's edge: instead of a border, a soft spectrum glow that starts at the card's edge and
 * fades outward, the same light as the logo's halo. The colours turn round the card and the glow
 * breathes continuously; it swells a little with the voice. Drawn behind the card, which covers
 * its inner half, so the light begins exactly at the card boundary.
 */
@Composable
private fun Modifier.spectrumGlow(energy: Float, corner: androidx.compose.ui.unit.Dp): Modifier {
    val t = rememberInfiniteTransition(label = "glow")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "turn")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse), label = "breath")
    val density = LocalDensity.current
    return drawBehind {
        val e = energy.coerceIn(0f, 1f)
        val radius = with(density) { corner.toPx() }
        val reach = with(density) { (9f + 3f * breath + 3f * e).dp.toPx() }
        val c = Offset(size.width / 2, size.height / 2)
        val stops = Palette.Spectrum + Palette.Spectrum.first()
        val shader = android.graphics.SweepGradient(c.x, c.y, stops.map { it.toArgb() }.toIntArray(), null).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(turn, c.x, c.y) })
        }
        fun layer(width: Float, alpha: Float, blur: Float) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = width
                this.shader = shader
                this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
                maskFilter = android.graphics.BlurMaskFilter(blur.coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint) }
        }
        layer(reach * 1.2f, 0.16f + 0.08f * breath + 0.1f * e, reach)          // wide soft corona
        layer(reach * 0.6f, 0.28f + 0.1f * breath + 0.14f * e, reach * 0.5f)   // brighter glow at the edge
    }
}

@Composable
private fun SwipeDown(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    // A deliberate fling, not a small flick (px/s depend on the screen density).
    val flingVelocity = with(LocalDensity.current) { 1_200.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset(0, offset.value.roundToInt()) }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceAtLeast(0f)) } },
                onDragStopped = { v ->
                    if (offset.value > threshold || (v > flingVelocity && offset.value > threshold / 3)) { onDismiss(); offset.snapTo(0f) }
                    else offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium))
                },
            ),
    ) { content() }
}

// ---- theme, icons ----------------------------------------------------------------------------

@Immutable
private data class Palette(
    val surface: Color,
    val primary: Color,
    val secondary: Color,
    val tonal: Color,
    val hairline: Color,
    val accent: Color,
    val warning: Color,
    val shadow: Color,
    val auroraBase: Float,
    val auroraGain: Float,
) {
    val headline get() = TextStyle(color = primary, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.15).sp)
    val body get() = TextStyle(color = primary, fontSize = 18.sp, lineHeight = 25.sp, letterSpacing = (-0.05).sp)
    val caption get() = TextStyle(color = secondary, fontSize = 13.sp, lineHeight = 18.sp)
    val stepTitle get() = TextStyle(color = primary, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val title get() = TextStyle(color = primary, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
    val label get() = TextStyle(color = secondary, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)

    companion object {
        val Spectrum = JarvisSpectrum
        val Light = Palette(
            surface = Color(0xFFFFFFFF), primary = Color(0xFF15161B), secondary = Color(0xFF6E7180),
            tonal = Color(0xFFF1F3F8), hairline = Color(0x14000000), accent = Color(0xFF4C7DFF),
            warning = Color(0xFFC2410C), shadow = Color(0x26000000), auroraBase = 0.10f, auroraGain = 0.16f,
        )
        val Dark = Palette(
            surface = Color(0xFF1A1B20), primary = Color(0xFFF3F4F8), secondary = Color(0xFF9A9DAB),
            tonal = Color(0xFF26282F), hairline = Color(0x1FFFFFFF), accent = Color(0xFF8FAAFF),
            warning = Color(0xFFFF9466), shadow = Color(0x80000000), auroraBase = 0.16f, auroraGain = 0.22f,
        )
    }
}

/** Material Symbols paths (Apache 2.0), so the overlay needs no icon library. */
private object Icons {
    val Mic = icon("M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val Stop = icon("M8,6h8c1.1,0 2,0.9 2,2v8c0,1.1 -0.9,2 -2,2H8c-1.1,0 -2,-0.9 -2,-2V8c0,-1.1 0.9,-2 2,-2z")
    val Close = icon("M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")

    private fun icon(path: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}
