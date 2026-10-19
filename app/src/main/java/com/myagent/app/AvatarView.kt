package com.myagent.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class AvatarView(ctx: Context) : View(ctx) {
    var state = 0
    var surprised = false
    var walking = false
    private var hopAt = 0L
    private val t0 = System.nanoTime()
    private var attached = false
    private val PI = Math.PI.toFloat()

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ear = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ball = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        head.color = 0xFFECEFF1.toInt()
        dark.color = 0xFF263238.toInt()
        ear.color = 0xFFB0BEC5.toInt()
        ball.color = 0xFFFFC107.toInt()
        ring.style = Paint.Style.STROKE
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
    }

    fun hop() {
        hopAt = System.nanoTime()
    }

    private fun hopping(): Boolean = hopAt != 0L && (System.nanoTime() - hopAt) < 450000000L

    private fun sn(x: Float): Float = Math.sin(x.toDouble()).toFloat()

    private fun cs(x: Float): Float = Math.cos(x.toDouble()).toFloat()

    private fun stateColor(): Int = when (state) {
        1 -> 0xFF2E7D32.toInt()
        2 -> 0xFFEF6C00.toInt()
        3 -> 0xFF1565C0.toInt()
        else -> 0xFF455A64.toInt()
    }

    private val frame = object : Runnable {
        override fun run() {
            invalidate()
            if (attached) {
                val busy = state != 0 || walking || surprised || hopping()
                postDelayed(this, if (busy) 16L else 66L)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        post(frame)
    }

    override fun onDetachedFromWindow() {
        attached = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = Math.min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val now = System.nanoTime()
        val t = (now - t0) / 1e9f
        val r = s * 0.38f
        val col = stateColor()

        if (state == 1) {
            for (k in 0..1) {
                val ph = (t * 0.9f + k * 0.5f) % 1f
                val rad = r + (s * 0.5f - r) * ph
                ring.color = col
                ring.alpha = ((1f - ph) * 150f).toInt()
                ring.strokeWidth = s * 0.025f
                canvas.drawCircle(cx, cy, rad, ring)
            }
        }
        if (state == 2) {
            for (i in 0..2) {
                val a = t * 3.5f + i * 2.0944f
                body.color = Color.WHITE
                canvas.drawCircle(cx + r * 1.22f * cs(a), cy + r * 1.22f * sn(a), s * 0.035f, body)
            }
        }

        var yoff = 0f
        val hd = (now - hopAt) / 1e9f
        if (hopAt != 0L && hd < 0.45f) yoff = -Math.abs(sn(PI * hd / 0.45f)) * r * 0.5f
        var scale = 1f + 0.03f * sn(t * 2f)
        if (state == 1) scale = 1f + 0.06f * sn(t * 6f)
        if (surprised) scale = 1.1f

        canvas.save()
        canvas.translate(0f, yoff)
        if (walking) canvas.rotate(sn(t * 10f) * 6f, cx, cy)
        canvas.scale(scale, scale, cx, cy)

        body.color = col
        canvas.drawCircle(cx, cy, r, body)
        ring.color = Color.WHITE
        ring.alpha = 255
        ring.strokeWidth = s * 0.035f
        canvas.drawCircle(cx, cy, r, ring)

        canvas.drawCircle(cx - r * 0.68f, cy + r * 0.1f, r * 0.11f, ear)
        canvas.drawCircle(cx + r * 0.68f, cy + r * 0.1f, r * 0.11f, ear)

        val wob = sn(t * 3f) * r * (if (state == 1) 0.2f else 0.1f)
        line.color = 0xFFB0BEC5.toInt()
        line.strokeWidth = s * 0.025f
        canvas.drawLine(cx, cy - r * 0.4f, cx + wob, cy - r * 0.7f, line)
        canvas.drawCircle(cx + wob, cy - r * 0.7f, r * 0.09f, ball)

        rect.set(cx - r * 0.62f, cy - r * 0.4f, cx + r * 0.62f, cy + r * 0.56f)
        canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, head)

        var bl = 1f
        val ph2 = t % 3.7f
        if (ph2 < 0.16f) bl = 1f - sn(PI * ph2 / 0.16f) * 0.92f
        var er = r * 0.12f
        if (state == 1) er *= 1.3f
        if (surprised) er *= 1.5f
        val dx = if (state == 2) sn(t * 2f) * r * 0.06f else 0f
        val ex = r * 0.26f
        val ey = cy - r * 0.06f
        rect.set(cx - ex + dx - er, ey - er * bl, cx - ex + dx + er, ey + er * bl)
        canvas.drawOval(rect, dark)
        rect.set(cx + ex + dx - er, ey - er * bl, cx + ex + dx + er, ey + er * bl)
        canvas.drawOval(rect, dark)

        val my = cy + r * 0.3f
        line.color = 0xFF263238.toInt()
        line.strokeWidth = s * 0.03f
        if (surprised) {
            rect.set(cx - r * 0.08f, my - r * 0.1f, cx + r * 0.08f, my + r * 0.1f)
            canvas.drawOval(rect, dark)
        } else if (state == 3) {
            val hh = r * 0.04f + Math.abs(sn(t * 11f)) * r * 0.14f
            rect.set(cx - r * 0.14f, my - hh, cx + r * 0.14f, my + hh)
            canvas.drawOval(rect, dark)
        } else if (state == 1) {
            canvas.drawCircle(cx, my, r * 0.07f, line)
        } else if (state == 2) {
            canvas.drawLine(cx - r * 0.12f, my, cx + r * 0.12f, my, line)
        } else {
            rect.set(cx - r * 0.18f, my - r * 0.14f, cx + r * 0.18f, my + r * 0.12f)
            canvas.drawArc(rect, 20f, 140f, false, line)
        }
        canvas.restore()
    }
}
