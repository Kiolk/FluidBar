package com.github.kiolk.fluidbar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pill-style bottom tab bar with a liquid-drop indicator that glides, stretches, charges on
 * press-and-hold, and bounces between tabs. Generic over the item type and themable via [colors]
 * so it can be shared across apps/screens without any knowledge of a specific tab set or palette.
 *
 * @param items the tabs to render, in order.
 * @param selectedItem the currently active tab; must be one of [items].
 * @param onItemSelected called when the user taps a different tab.
 * @param itemIcon reads the icon (glyph/emoji) to show for an item.
 * @param itemLabel reads the label text to show for an item.
 * @param colors visual palette; defaults to [FluidBarDefaults.colors].
 */
@Composable
fun <T : Any> FluidBar(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    itemIcon: (T) -> String,
    itemLabel: (T) -> String,
    modifier: Modifier = Modifier,
    colors: FluidBarColors = FluidBarDefaults.colors(),
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val itemBounds = remember { mutableStateMapOf<T, Rect>() }
    val indicatorX = remember { Animatable(0f) }
    val indicatorWidth = remember { Animatable(0f) }
    val indicatorLift = remember { Animatable(0f) }
    val chargeAnim = remember { Animatable(0f) }
    var flightPull by remember { mutableStateOf(0f) }
    var flightPullDirection by remember { mutableStateOf(1f) }
    var pressedItem by remember { mutableStateOf<T?>(null) }
    var pendingImpulse by remember { mutableStateOf(0f) }
    var chargeJob by remember { mutableStateOf<Job?>(null) }

    fun startCharging(item: T) {
        if (item == selectedItem || pressedItem == item) return
        pressedItem = item
        chargeJob?.cancel()
        chargeJob = scope.launch {
            chargeAnim.snapTo(0f)
            chargeAnim.animateTo(1f, tween(ChargeDurationMillis, easing = LinearEasing))
        }
    }

    fun endCharging(item: T, released: Boolean) {
        if (pressedItem != item) return
        chargeJob?.cancel()
        chargeJob = null
        if (released) {
            // Keep pressedItem set — don't snap the shape back yet. `selectedItem` only updates
            // once the caller's own state actually catches up (at least a frame later), so
            // nulling this immediately would show the neutral pill for a beat before the flight
            // animation below kicks in. Instead hold the charged pose until the transition
            // LaunchedEffect actually starts (it clears pressedItem itself), with a timeout
            // fallback in case this tap never causes selectedItem to change.
            pendingImpulse = chargeAnim.value
            scope.launch {
                delay(ChargeHoldTimeout)
                if (pressedItem == item) pressedItem = null
            }
        } else {
            // Keep pressedItem set here too — springing chargeAnim back to 0 from its current
            // value gives a smooth bouncy retreat; nulling pressedItem first would zero the shape
            // out instantly before the spring even starts.
            scope.launch {
                chargeAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = ChargeReleaseDampingRatio,
                        stiffness = ChargeReleaseStiffness,
                    ),
                )
                if (pressedItem == item) pressedItem = null
            }
        }
    }

    val targetBounds = itemBounds[selectedItem]
    LaunchedEffect(targetBounds) {
        val target = targetBounds ?: return@LaunchedEffect
        if (indicatorWidth.value == 0f) {
            // First layout pass — snap into place instead of sliding in from the corner.
            indicatorX.snapTo(target.left)
            indicatorWidth.snapTo(target.width)
            pendingImpulse = 0f
            return@LaunchedEffect
        }
        val impulse = pendingImpulse.coerceIn(0f, 1f)
        pendingImpulse = 0f
        // The transition is genuinely starting now — hand the shape over from the frozen charge
        // pose to the velocity-driven flight pose (seeded with matching velocity just below).
        pressedItem = null
        val motionSpec = spring<Float>(
            dampingRatio = (IndicatorDampingRatio - impulse * ChargeDampingBoost)
                .coerceAtLeast(MinDampingRatio),
            stiffness = IndicatorStiffness + impulse * ChargeStiffnessBoost,
        )
        // Carry the charge's reach straight into the launch as real starting velocity, so the
        // velocity-driven pull below picks up right where the charge left off instead of the
        // shape dropping to neutral for a frame while the spring is still at rest.
        val launchDirection = if (target.left >= indicatorX.value) 1f else -1f
        val launchVelocity = launchDirection * impulse * ChargeStretchAmount / StretchPerVelocity
        coroutineScope {
            launch {
                indicatorX.animateTo(
                    targetValue = target.left,
                    animationSpec = motionSpec,
                    initialVelocity = launchVelocity,
                ) {
                    flightPull = (abs(velocity) * StretchPerVelocity).coerceIn(0f, MaxStretch)
                    // Grow side is the leading edge (direction of travel) — the blob swells toward
                    // where it's headed and thins out behind it.
                    flightPullDirection = if (velocity >= 0f) 1f else -1f
                }
            }
            launch {
                indicatorWidth.animateTo(targetValue = target.width, animationSpec = motionSpec)
            }
            if (impulse > 0f) {
                launch {
                    indicatorLift.snapTo(-ChargeLiftPx * impulse)
                    indicatorLift.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = (0.35f - impulse * 0.15f).coerceAtLeast(MinDampingRatio),
                            stiffness = 300f,
                        ),
                    )
                }
            }
        }
        flightPull = 0f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(colors.barBackground)
            .border(1.dp, colors.border)
            .padding(PaddingValues(start = 21.dp, top = 12.dp, end = 21.dp, bottom = 21.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            // Decorative track — clipped to the pill shape, purely a background.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(36.dp))
                    .background(colors.pillBackground),
            )

            // Indicator + tab row live in an unclipped layer so the indicator can bounce/stretch
            // past the track's edge without being cut off.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            ) {
                if (indicatorWidth.value > 0f) {
                    val pressedBounds = pressedItem?.let { itemBounds[it] }
                    val chargePull = if (pressedBounds != null) {
                        chargeAnim.value * ChargeStretchAmount
                    } else {
                        0f
                    }
                    val pullMagnitude: Float
                    val pullDirection: Float
                    if (chargePull > 0f) {
                        pullMagnitude = chargePull
                        pullDirection = if (pressedBounds!!.left > indicatorX.value) 1f else -1f
                    } else {
                        pullMagnitude = flightPull
                        pullDirection = flightPullDirection
                    }

                    // Shadow — same path, blurred and offset down, drawn behind the main shape.
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                translationY = indicatorLift.value + ShadowOffsetPx
                                renderEffect = BlurEffect(ShadowBlurPx, ShadowBlurPx, TileMode.Decal)
                            },
                    ) {
                        val path = buildIndicatorPath(
                            h = size.height,
                            baseLeft = indicatorX.value,
                            baseWidth = indicatorWidth.value,
                            pullMagnitude = pullMagnitude,
                            pullDirection = pullDirection,
                        )
                        drawPath(path, color = colors.shadow)
                    }

                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { translationY = indicatorLift.value },
                    ) {
                        val path = buildIndicatorPath(
                            h = size.height,
                            baseLeft = indicatorX.value,
                            baseWidth = indicatorWidth.value,
                            pullMagnitude = pullMagnitude,
                            pullDirection = pullDirection,
                        )
                        drawPath(path, color = colors.activeIndicator)
                    }
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    items.forEach { item ->
                        FluidBarItem(
                            tab = item,
                            icon = itemIcon(item),
                            label = itemLabel(item),
                            isActive = item == selectedItem,
                            activeContentColor = colors.activeContent,
                            inactiveContentColor = colors.inactiveContent,
                            onClick = {
                                if (item != selectedItem) {
                                    // Instant confirmation the tap registered — independent of
                                    // however long the glide/bounce that follows takes.
                                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    // A second pulse timed to land roughly when the indicator
                                    // settles into place, instead of trying to detect the exact
                                    // moment the spring finishes (which was unreliable).
                                    scope.launch {
                                        delay(ArrivalHapticDelay)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                                onItemSelected(item)
                            },
                            onPressStart = { startCharging(item) },
                            onPressEnd = { released -> endCharging(item, released) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .onGloballyPositioned { coordinates ->
                                    itemBounds[item] = Rect(
                                        offset = coordinates.positionInParent(),
                                        size = coordinates.size.toSize(),
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Visual palette for [FluidBar]. Build via [FluidBarDefaults.colors]. */
data class FluidBarColors(
    val barBackground: Color,
    val pillBackground: Color,
    val activeIndicator: Color,
    val activeContent: Color,
    val inactiveContent: Color,
    val border: Color,
    val shadow: Color,
)

/** Default color values for [FluidBar] — override any subset to theme it. */
object FluidBarDefaults {
    fun colors(
        barBackground: Color = Color.White,
        pillBackground: Color = Color(0xFFF6F7F8),
        activeIndicator: Color = Color(0xFF4CAF50),
        activeContent: Color = Color.White,
        inactiveContent: Color = Color(0xFFD1D5DB),
        border: Color = Color(0xFFF3F4F6),
        shadow: Color = Color.Black.copy(alpha = 0.28f),
    ): FluidBarColors = FluidBarColors(
        barBackground = barBackground,
        pillBackground = pillBackground,
        activeIndicator = activeIndicator,
        activeContent = activeContent,
        inactiveContent = inactiveContent,
        border = border,
        shadow = shadow,
    )
}

// How strongly the indicator pulls/thins per unit of travel velocity (px/s) while flying between
// tabs — this feeds the same liquid-drop deformation used for the press-and-hold charge below.
private const val StretchPerVelocity = 1f / 6000f
private const val MaxStretch = 0.35f

// Low stiffness so the indicator visibly glides across any tabs in between the start and end
// position instead of snapping straight to the destination; the damping ratio still leaves a
// small overshoot/bounce once it arrives.
private const val IndicatorStiffness = 260f
private const val IndicatorDampingRatio = 0.6f
private const val MinDampingRatio = 0.18f

// Press-and-hold "charging" — the longer a different tab is held, the more the indicator reaches
// toward it (0..1 over this duration) before the tap is released.
private const val ChargeDurationMillis = 900
private const val ChargeStretchAmount = 0.55f

// Cancelled press (drag off / released without a tap) — the reach springs back to neutral with a
// bit of bounce instead of just easing back in a straight line.
private const val ChargeReleaseDampingRatio = 0.45f
private const val ChargeReleaseStiffness = 300f

// Release "launch" — a full charge makes the flight to the new tab snappier/bouncier and gives it
// a vertical pop, like the indicator was flung there.
private const val ChargeDampingBoost = 0.35f
private const val ChargeStiffnessBoost = 260f
private const val ChargeLiftPx = 46f

// Timed to roughly coincide with the indicator settling into place — simpler and more reliable
// than trying to detect the exact moment the spring finishes.
private val ArrivalHapticDelay = 300.milliseconds

// Safety net: releases the frozen charge pose if the tap never actually causes selectedItem to
// change (e.g. an item with no destination wired up yet), so it doesn't stay stretched forever.
private val ChargeHoldTimeout = 400.milliseconds

// Liquid-drop shape: the side reaching toward the destination (new tab / direction of travel)
// swells bigger as it's pulled, while the side left behind (origin tab / trailing edge) thins out
// and its top edge sags down toward the floor — like liquid flowing and pooling toward where it's
// being pulled, draining from where it used to be.
private const val GrowExtensionPx = 90f
private const val GrowRadiusBoost = 0.35f
private const val MaxSagPx = 26f
private const val ShrinkRecedePx = 46f
private const val ShrinkRadiusAmount = 0.85f
private const val MinShrinkRadiusFraction = 0.22f

// Soft drop shadow beneath the indicator, using the exact same deforming path, for a bit of
// volume/lift instead of looking flat against the track.
private const val ShadowOffsetPx = 6f
private const val ShadowBlurPx = 14f

/** Builds the liquid-drop blob path shared by both the indicator and its shadow. */
private fun buildIndicatorPath(
    h: Float,
    baseLeft: Float,
    baseWidth: Float,
    pullMagnitude: Float,
    pullDirection: Float,
): Path {
    val baseRight = baseLeft + baseWidth
    val baseRadius = h / 2f

    val growRadius = baseRadius * (1f + pullMagnitude * GrowRadiusBoost)
    val shrinkRadius = (baseRadius * (1f - pullMagnitude * ShrinkRadiusAmount))
        .coerceAtLeast(baseRadius * MinShrinkRadiusFraction)
    val growExtension = pullMagnitude * GrowExtensionPx
    val shrinkRecede = pullMagnitude * ShrinkRecedePx
    val sag = pullMagnitude * MaxSagPx

    // Direction-agnostic: always describe "the left circle" and "the right circle" of the blob —
    // which one is growing vs. shrinking depends on pullDirection, but the path tracing itself
    // never needs to change.
    val leftRadius: Float
    val leftCenterX: Float
    val rightRadius: Float
    val rightCenterX: Float
    if (pullDirection >= 0f) {
        // Growing toward the right; the left side drains and thins.
        leftRadius = shrinkRadius
        leftCenterX = baseLeft + shrinkRecede + shrinkRadius
        rightRadius = growRadius
        rightCenterX = baseRight + growExtension - growRadius
    } else {
        // Growing toward the left; the right side drains and thins.
        leftRadius = growRadius
        leftCenterX = baseLeft - growExtension + growRadius
        rightRadius = shrinkRadius
        rightCenterX = baseRight - shrinkRecede - shrinkRadius
    }

    val leftRect = Rect(leftCenterX - leftRadius, h - 2f * leftRadius, leftCenterX + leftRadius, h)
    val rightRect = Rect(rightCenterX - rightRadius, h - 2f * rightRadius, rightCenterX + rightRadius, h)
    val leftTopY = h - 2f * leftRadius
    val rightTopY = h - 2f * rightRadius

    // Two independent control points, each held at (near) its own endpoint's height, keep the
    // curve's tangent lined up with the arc's tangent at both joins (arcTo always leaves/enters
    // horizontally at the top of a circle) — that's what makes the connection smooth instead of
    // kinked. A small shared sag on both nudges the middle down for the droopy liquid look without
    // reintroducing a sharp angle at either join.
    val pull = (rightCenterX - leftCenterX) * 0.5f
    val control1X = leftCenterX + pull
    val control2X = rightCenterX - pull
    val control1Y = leftTopY + sag * 0.5f
    val control2Y = rightTopY + sag * 0.5f

    return Path().apply {
        moveTo(leftCenterX, h)
        arcTo(rect = leftRect, startAngleDegrees = 90f, sweepAngleDegrees = 180f, forceMoveTo = false)
        cubicTo(control1X, control1Y, control2X, control2Y, rightCenterX, rightTopY)
        arcTo(rect = rightRect, startAngleDegrees = 270f, sweepAngleDegrees = 180f, forceMoveTo = false)
        close()
    }
}

@Composable
private fun <T : Any> FluidBarItem(
    tab: T,
    icon: String,
    label: String,
    isActive: Boolean,
    activeContentColor: Color,
    inactiveContentColor: Color,
    onClick: () -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: (released: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (isActive) activeContentColor else inactiveContentColor,
        animationSpec = tween(durationMillis = 250),
    )
    // The gesture detector below is a single long-lived coroutine (only restarts if `tab`
    // changes, which it never does for a given item) — rememberUpdatedState makes sure it always
    // calls the latest onPressStart/onPressEnd instead of the ones captured on first composition,
    // which would otherwise close over a stale `selectedItem` from back when the tab bar first drew.
    val currentOnPressStart by rememberUpdatedState(onPressStart)
    val currentOnPressEnd by rememberUpdatedState(onPressEnd)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            // Shadow gesture detector — purely observes press/release timing to drive the
            // indicator's charge-and-launch animation; clickable below still owns the actual tap.
            .pointerInput(tab) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    currentOnPressStart()
                    val up = waitForUpOrCancellation()
                    currentOnPressEnd(up != null)
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Text(text = icon, fontSize = 18.sp)
        Text(
            text = label,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
    }
}