/*
 * SPDX-FileCopyrightText: 2026
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.simple

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.plugins.mousepad.MousePadGestureDetector
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.plugins.mousepad.PointerAccelerationProfile
import org.kde.kdeconnect.plugins.mousepad.PointerAccelerationProfileFactory
import org.kde.kdeconnect_tp.R
import kotlin.math.abs
import kotlin.math.pow

/**
 * Touchpad surface without left/middle/right buttons. Gestures match [org.kde.kdeconnect.plugins.mousepad.MousePadActivity].
 */
class RemotePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs),
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    MousePadGestureDetector.OnGestureListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private enum class ClickType {
        LEFT, RIGHT, MIDDLE, NONE;

        companion object {
            fun fromString(s: String?): ClickType = when (s) {
                "left" -> LEFT
                "right" -> RIGHT
                "middle" -> MIDDLE
                else -> NONE
            }
        }
    }

    var plugin: MousePadPlugin? = null

    private var mPrevX = 0f
    private var mPrevY = 0f
    private var dragging = false
    private var maybeDragging = false
    private var accumulatedDragDistance2 = 0.0f
    private var mCurrentSensitivity = 1.0f
    private var displayDpiMultiplier = 1.0f
    private var scrollDirection = 1
    private var scrollCoefficient = 1.0
    private var doubleTapDragEnabled = false
    private var isScrolling = false
    private var accumulatedDistanceX = 0.0
    private var accumulatedDistanceY = 0.0
    private var singleTapAction = ClickType.LEFT
    private var doubleTapAction = ClickType.RIGHT
    private var tripleTapAction = ClickType.MIDDLE
    private var prefsApplied = false
    private var threeFingerSwipeFired = false
    private var threeFingerAccumX = 0f
    private var threeFingerAccumY = 0f
    private val threeFingerSwipeThresholdPx: Float =
        96f * resources.displayMetrics.density

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val detector = GestureDetector(context, this).also { it.setOnDoubleTapListener(this) }
    private val mousePadGestureDetector = MousePadGestureDetector(this)
    private var pointerAccelerationProfile: PointerAccelerationProfile =
        PointerAccelerationProfileFactory.getProfileWithName("noacceleration")
    private var mouseDelta = PointerAccelerationProfile.MouseDelta()

    init {
        isClickable = true
        isFocusable = false
        isHapticFeedbackEnabled = true
        displayDpiMultiplier = StandardDpi / resources.displayMetrics.xdpi
        prefs.registerOnSharedPreferenceChangeListener(this)
        applyPrefs()
        contentDescription = context.getString(R.string.mousepad_info_no_gestures)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyPrefs()
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        prefsApplied = false
        applyPrefs()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            threeFingerSwipeFired = false
            threeFingerAccumX = 0f
            threeFingerAccumY = 0f
        }

        if (mousePadGestureDetector.onTouchEvent(event)) {
            return true
        }
        if (detector.onTouchEvent(event)) {
            return true
        }

        val actionType = event.action
        if (isScrolling) {
            if (actionType == MotionEvent.ACTION_UP) {
                isScrolling = false
            } else {
                return false
            }
        }

        when (actionType) {
            MotionEvent.ACTION_DOWN -> {
                mPrevX = event.x
                mPrevY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val mCurrentX = event.x
                val mCurrentY = event.y
                val deltaX = (mCurrentX - mPrevX) * displayDpiMultiplier * mCurrentSensitivity
                val deltaY = (mCurrentY - mPrevY) * displayDpiMultiplier * mCurrentSensitivity

                if (maybeDragging) {
                    accumulatedDragDistance2 += deltaX * deltaX + deltaY * deltaY
                    if (accumulatedDragDistance2 >= MinDraggingDistance2) {
                        maybeDragging = false
                        dragging = true
                        accumulatedDragDistance2 = 0.0f
                        plugin?.sendSingleHold()
                    }
                } else {
                    pointerAccelerationProfile.touchMoved(deltaX, deltaY, event.eventTime)
                    mouseDelta = pointerAccelerationProfile.commitAcceleratedMouseDelta(mouseDelta)
                    plugin?.sendMouseDelta(mouseDelta.x, mouseDelta.y)
                }

                mPrevX = mCurrentX
                mPrevY = mCurrentY
            }
            MotionEvent.ACTION_UP -> {
                if (doubleTapDragEnabled && maybeDragging) {
                    maybeDragging = false
                    accumulatedDragDistance2 = 0.0f
                    plugin?.sendDoubleClick()
                }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val distanceY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val distanceX = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            accumulatedDistanceX += distanceX
            accumulatedDistanceY += distanceY
            if (abs(accumulatedDistanceX) > MinDistanceToSendScroll || abs(accumulatedDistanceY) > MinDistanceToSendScroll) {
                plugin?.sendScroll(accumulatedDistanceX, accumulatedDistanceY)
                accumulatedDistanceX = 0.0
                accumulatedDistanceY = 0.0
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDown(e: MotionEvent): Boolean = false

    override fun onShowPress(e: MotionEvent) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        if (e2.pointerCount <= 1) return false

        // Three-finger Windows-like gestures take priority over two-finger scroll.
        if (e2.pointerCount >= 3 || threeFingerSwipeFired) {
            isScrolling = true
            if (e2.pointerCount >= 3 && !threeFingerSwipeFired) {
                handleThreeFingerSwipe(distanceX, distanceY)
            }
            return true
        }

        isScrolling = true
        var filteredDistanceX = distanceX
        var filteredDistanceY = distanceY
        val absX = abs(filteredDistanceX)
        val absY = abs(filteredDistanceY)
        if (absX < absY * DirectionLockRatio) {
            filteredDistanceX = 0f
        } else if (absY < absX * DirectionLockRatio) {
            filteredDistanceY = 0f
        }
        accumulatedDistanceX += filteredDistanceX * scrollCoefficient
        accumulatedDistanceY += filteredDistanceY * scrollCoefficient
        if (abs(accumulatedDistanceX) > MinDistanceToSendScroll || abs(accumulatedDistanceY) > MinDistanceToSendScroll) {
            plugin?.sendScroll(-scrollDirection * accumulatedDistanceX, scrollDirection * accumulatedDistanceY)
            accumulatedDistanceX = 0.0
            accumulatedDistanceY = 0.0
        }
        return true
    }

    /**
     * [distanceX]/[distanceY] follow GestureDetector: positive when the finger moves left/up.
     * Finger swipe up → Task View; left/right → previous/next window.
     */
    private fun handleThreeFingerSwipe(distanceX: Float, distanceY: Float) {
        // Convert scroll distances into finger-movement deltas (right/down positive).
        threeFingerAccumX += -distanceX
        threeFingerAccumY += -distanceY

        val absX = abs(threeFingerAccumX)
        val absY = abs(threeFingerAccumY)
        if (absX < threeFingerSwipeThresholdPx && absY < threeFingerSwipeThresholdPx) {
            return
        }

        threeFingerSwipeFired = true
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        if (absY >= absX) {
            if (threeFingerAccumY < 0f) {
                plugin?.sendTaskView()
            }
            // Swipe down is intentionally unused (Windows "show desktop" not requested).
        } else if (threeFingerAccumX > 0f) {
            plugin?.sendNextWindow()
        } else {
            plugin?.sendPreviousWindow()
        }
    }

    override fun onLongPress(e: MotionEvent) {
        if (!doubleTapDragEnabled && !dragging) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            plugin?.sendSingleHold()
            dragging = true
        }
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        performClickType(singleTapAction)
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (!dragging) {
            if (doubleTapDragEnabled) {
                maybeDragging = true
            } else {
                plugin?.sendDoubleClick()
            }
        }
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && maybeDragging) {
            return false
        }
        return true
    }

    override fun onTripleFingerTap(ev: MotionEvent): Boolean {
        if (threeFingerSwipeFired) {
            return true
        }
        performClickType(tripleTapAction)
        return true
    }

    override fun onDoubleFingerTap(ev: MotionEvent): Boolean {
        performClickType(doubleTapAction)
        return true
    }

    private fun performClickType(type: ClickType) {
        when (type) {
            ClickType.LEFT -> sendLeftClick()
            ClickType.RIGHT -> plugin?.sendRightClick()
            ClickType.MIDDLE -> plugin?.sendMiddleClick()
            ClickType.NONE -> {}
        }
    }

    private fun sendLeftClick() {
        if (dragging) {
            plugin?.sendSingleRelease()
            dragging = false
        } else {
            plugin?.sendLeftClick()
        }
    }

    private fun applyPrefs() {
        if (prefsApplied) return
        val p = prefs
        scrollDirection = if (p.getBoolean(context.getString(R.string.mousepad_scroll_direction), false)) -1 else 1
        var scrollSensitivity = p.getInt(context.getString(R.string.mousepad_scroll_sensitivity), 100)
        if (scrollSensitivity == 0) scrollSensitivity = 1
        scrollCoefficient = (scrollSensitivity / 100f).toDouble().pow(1.5)

        singleTapAction = ClickType.fromString(
            p.getString(context.getString(R.string.mousepad_single_tap_key), context.getString(R.string.mousepad_default_single))
        )
        doubleTapAction = ClickType.fromString(
            p.getString(context.getString(R.string.mousepad_double_tap_key), context.getString(R.string.mousepad_default_double))
        )
        tripleTapAction = ClickType.fromString(
            p.getString(context.getString(R.string.mousepad_triple_tap_key), context.getString(R.string.mousepad_default_triple))
        )

        mCurrentSensitivity = when (
            p.getString(context.getString(R.string.mousepad_sensitivity_key), context.getString(R.string.mousepad_default_sensitivity))
        ) {
            "slowest" -> 0.2f
            "aboveSlowest" -> 0.5f
            "default" -> 1.0f
            "aboveDefault" -> 1.5f
            "fastest" -> 2.0f
            else -> 1.0f
        }

        val accelerationProfileName = p.getString(
            context.getString(R.string.mousepad_acceleration_profile_key),
            context.getString(R.string.mousepad_default_acceleration_profile),
        ) ?: context.getString(R.string.mousepad_default_acceleration_profile)
        pointerAccelerationProfile = PointerAccelerationProfileFactory.getProfileWithName(accelerationProfileName)
        doubleTapDragEnabled = p.getBoolean(context.getString(R.string.mousepad_doubletap_drag_enabled_pref), true)
        prefsApplied = true
    }

    companion object {
        private const val MinDistanceToSendScroll = 2.5f
        private const val DirectionLockRatio = 0.5f
        private const val StandardDpi = 240.0f
        private const val MinDraggingDistance2 = 25.0f
    }
}
