package com.olerast.suflyor.overlay

import android.content.Context
import android.view.ViewGroup

/**
 * Holds one child and turns it by 0°, +90° or −90°, measuring it with swapped sides so the rotated child exactly
 * covers the frame. Touches follow the rotation (ViewGroup maps them through the child's transform).
 *
 * Needed for landscape recording: camera apps like Samsung Camera and Instagram keep the screen in portrait even
 * when the phone is held sideways, so the prompter has to turn its own content to stay readable.
 */
class RotatedFrame(context: Context) : ViewGroup(context) {
    /** 0, 90 (content turned clockwise) or −90. */
    var angle: Int = 0
        set(v) {
            if (field == v) return
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child == null) {
            setMeasuredDimension(0, 0)
            return
        }
        if (angle == 0) {
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(
                resolveSize(child.measuredWidth, widthMeasureSpec),
                resolveSize(child.measuredHeight, heightMeasureSpec),
            )
        } else {
            measureChild(child, heightMeasureSpec, widthMeasureSpec)
            setMeasuredDimension(
                resolveSize(child.measuredHeight, widthMeasureSpec),
                resolveSize(child.measuredWidth, heightMeasureSpec),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        val w = r - l
        val h = b - t
        child.pivotX = 0f
        child.pivotY = 0f
        when (angle) {
            90 -> {
                child.layout(0, 0, h, w)
                child.rotation = 90f
                child.translationX = w.toFloat()
                child.translationY = 0f
            }
            -90 -> {
                child.layout(0, 0, h, w)
                child.rotation = -90f
                child.translationX = 0f
                child.translationY = h.toFloat()
            }
            else -> {
                child.layout(0, 0, w, h)
                child.rotation = 0f
                child.translationX = 0f
                child.translationY = 0f
            }
        }
    }
}
