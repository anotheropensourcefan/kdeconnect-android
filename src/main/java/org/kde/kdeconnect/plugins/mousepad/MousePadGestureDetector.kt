/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

class MousePadGestureDetector {
    private val tapTimeout = ViewConfiguration.getTapTimeout() + 100
    private val touchSlop: Int
    private val gestureListener: OnGestureListener
    private var firstDownTime = 0L
    private var isGestureHandled = false
    private var maxPointerTravel = 0f
    private val pointerStartX = FloatArray(MaxTrackedPointers)
    private val pointerStartY = FloatArray(MaxTrackedPointers)

    interface OnGestureListener {
        fun onTripleFingerTap(ev: MotionEvent): Boolean

        fun onDoubleFingerTap(ev: MotionEvent): Boolean
    }

    constructor(gestureListener: OnGestureListener, touchSlop: Int = ViewConfiguration.getTouchSlop()) {
        this.gestureListener = gestureListener
        this.touchSlop = touchSlop
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        when (action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                isGestureHandled = false
                firstDownTime = event.eventTime
                maxPointerTravel = 0f
                storePointerStarts(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (index in 0 until MaxTrackedPointers) {
                    pointerStartX[index] = event.getX(index)
                    pointerStartY[index] = event.getY(index)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                updateMaxPointerTravel(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                updateMaxPointerTravel(event)
                val count = event.pointerCount
                if (event.eventTime - firstDownTime <= tapTimeout && maxPointerTravel <= touchSlop) {
                    if (count == 3) {
                        if (!isGestureHandled) {
                            isGestureHandled = gestureListener.onTripleFingerTap(event)
                        }
                    }
                    else if (count == 2) {
                        if (!isGestureHandled) {
                            isGestureHandled = gestureListener.onDoubleFingerTap(event)
                        }
                    }
                }
                firstDownTime = 0
            }
        }
        return isGestureHandled
    }

    private fun storePointerStarts(event: MotionEvent) {
        val count = minOf(event.pointerCount, MaxTrackedPointers)
        for (i in 0 until count) {
            pointerStartX[i] = event.getX(i)
            pointerStartY[i] = event.getY(i)
        }
    }

    private fun updateMaxPointerTravel(event: MotionEvent) {
        val count = minOf(event.pointerCount, MaxTrackedPointers)
        for (i in 0 until count) {
            val travel = hypot(
                abs(event.getX(i) - pointerStartX[i]).toDouble(),
                abs(event.getY(i) - pointerStartY[i]).toDouble(),
            ).toFloat()
            if (travel > maxPointerTravel) {
                maxPointerTravel = travel
            }
        }
    }

    companion object {
        private const val MaxTrackedPointers = 5
    }
}
