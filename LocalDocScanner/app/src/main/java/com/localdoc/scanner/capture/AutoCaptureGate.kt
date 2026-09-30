package com.localdoc.scanner.capture

import com.localdoc.scanner.cv.Quad
import kotlin.math.abs

/**
 * Stable duration + page replacement gate. The timed capture approach is informed by
 * ZynkSoftware ScanSurfaceView.scheduleAutoCapture (MIT); replacement logic is local.
 * Frame count alone changes behavior with CPU speed. A stationary page fires once.
 */
class AutoCaptureGate(private val holdMs: Long = 900L, private val cooldownMs: Long = 1600L) {
    enum class State(val hint: String) {
        SEARCHING("请把纸张四角完整放入画面"),
        QUALITY("请对焦并避开强光"),
        HOLDING("请保持不动"),
        WAITING_FOR_NEXT("已拍摄，请翻页或移走纸张"),
        CAPTURE("正在拍摄")
    }
    private var previous: Quad? = null
    private var stableSince = -1L
    private var absentSince = -1L
    private var replacementSince = -1L
    private var lastShotAt = -cooldownMs
    private var armed = true
    private var shotQuad: Quad? = null
    private var shotHash: Long? = null
    var state: State = State.SEARCHING
        private set

    fun reset() {
        previous = null; stableSince = -1L; absentSince = -1L; replacementSince = -1L
        lastShotAt = -cooldownMs; armed = true; shotQuad = null; shotHash = null
        state = State.SEARCHING
    }

    fun markCaptured(nowMs: Long, quad: Quad?, contentHash: Long?) {
        armed = false; lastShotAt = nowMs; shotQuad = quad; shotHash = contentHash
        stableSince = -1L; replacementSince = -1L; state = State.WAITING_FOR_NEXT
    }

    fun observe(quad: Quad?, contentHash: Long?, nowMs: Long, qualityOk: Boolean, busy: Boolean = false): Boolean {
        if (quad == null) {
            if (absentSince < 0) absentSince = nowMs
            if (nowMs - absentSince >= 400L) armed = true
            previous = null; stableSince = -1L; replacementSince = -1L
            state = State.SEARCHING
            return false
        }
        absentSince = -1L
        if (!armed) {
            val moved = shotQuad?.let { distance(it, quad) > 0.12f } ?: false
            val changed = shotHash != null && contentHash != null && java.lang.Long.bitCount(shotHash!! xor contentHash) >= 10
            if ((moved || changed) && nowMs - lastShotAt >= cooldownMs) {
                if (replacementSince < 0) replacementSince = nowMs
                if (nowMs - replacementSince >= 500L) { armed = true; stableSince = -1L; previous = null }
            } else replacementSince = -1L
            if (!armed) { state = State.WAITING_FOR_NEXT; return false }
        }
        if (busy || !qualityOk) {
            previous = quad; stableSince = -1L; state = State.QUALITY
            return false
        }
        val stable = previous?.let { distance(it, quad) < 0.018f } ?: false
        previous = quad
        if (!stable || stableSince < 0) stableSince = nowMs
        state = State.HOLDING
        if (nowMs - stableSince < holdMs || nowMs - lastShotAt < cooldownMs) return false
        markCaptured(nowMs, quad, contentHash)
        state = State.CAPTURE
        return true
    }

    private fun distance(a: Quad, b: Quad): Float = a.toList().zip(b.toList()).maxOf { (x, y) -> abs(x.x - y.x) + abs(x.y - y.y) }
}
