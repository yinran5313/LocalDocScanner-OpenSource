/*
 * Adapted from ZynkSoftware Document-Scanning-Android-SDK (MIT, Copyright 2020
 * ZynkSoftware SRL), PerspectiveTransformation.transform and the contour pipeline;
 * and flatpage (MIT, Copyright 2026 chaxus), flatten_illumination/_estimate_background.
 * Full permission notices and pinned source locations are in THIRD_PARTY_NOTICES.md
 * and app/src/main/assets/third_party/. Changes: OpenCV 4.12, scoped Mat ownership,
 * multiple edge thresholds, no invented missing corner, bounded output, LAB color.
 */
package com.localdoc.scanner.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point as CvPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** Reuses the same native library as PaddleOCR; no second OpenCV binary. */
object OpenCvDocument {
    @Volatile private var loaded = false
    @Synchronized fun available(): Boolean {
        if (loaded) return true
        loaded = runCatching { System.loadLibrary("opencv_java4"); true }.getOrDefault(false)
        return loaded
    }

    fun detect(bitmap: Bitmap, maxSide: Int = 720): Quad? {
        if (!available()) return null
        val small = scaleBitmap(bitmap, maxSide)
        return try {
            Mats().use { mats ->
                val rgba = mats.mat(); val gray = mats.mat(); val mask = mats.mat()
                Utils.bitmapToMat(small, rgba)
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
                val kernel = mats.keep(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
                var best: Quad? = null
                var bestArea = 0f
                // Canny retains page outlines; Otsu also handles a light page on a dark desk.
                for (pass in 0..2) {
                    when (pass) {
                        0 -> Imgproc.Canny(gray, mask, 45.0, 135.0)
                        1 -> Imgproc.Canny(gray, mask, 15.0, 60.0)
                        else -> Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                    }
                    Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
                    val contours = mutableListOf<MatOfPoint>()
                    val hierarchy = mats.mat()
                    try {
                        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                        contours.sortedByDescending { Imgproc.contourArea(it) }.take(16).forEach { contour ->
                            val area = Imgproc.contourArea(contour)
                            if (area < small.width * small.height * 0.08) return@forEach
                            val curve = MatOfPoint2f(*contour.toArray())
                            val approx = MatOfPoint2f()
                            try {
                                val perimeter = Imgproc.arcLength(curve, true)
                                for (epsilon in doubleArrayOf(0.015, 0.025, 0.035)) {
                                    Imgproc.approxPolyDP(curve, approx, perimeter * epsilon, true)
                                    if (approx.rows() != 4) continue
                                    val points = approx.toArray().map { Point(it.x.toFloat(), it.y.toFloat()) }
                                    val quad = orderedQuad(points) ?: continue
                                    if (!validDocumentQuad(quad, small.width, small.height)) continue
                                    val quadArea = polygonArea(quad.toList())
                                    // A contour must support its quad; avoid enclosing scattered text/background.
                                    if (area / quadArea < 0.75 || quadArea <= bestArea) continue
                                    bestArea = quadArea
                                    best = quad
                                }
                            } finally { curve.release(); approx.release() }
                        }
                    } finally { contours.forEach(Mat::release) }
                }
                best?.let { q ->
                    val sx = bitmap.width.toFloat() / small.width
                    val sy = bitmap.height.toFloat() / small.height
                    Quad(q.p0.scaled(sx, sy), q.p1.scaled(sx, sy), q.p2.scaled(sx, sy), q.p3.scaled(sx, sy))
                }
            }
        } catch (_: Exception) { null }
        finally { if (small !== bitmap) small.recycle() }
    }

    fun warp(source: Bitmap, quad: Quad, width: Int, height: Int): Bitmap? {
        if (!available() || width < 2 || height < 2 || width.toLong() * height > 20_000_000L) return null
        if (!isConvex(quad.toList()) || polygonArea(quad.toList()) < 4f ||
            quad.toList().any { !it.x.isFinite() || !it.y.isFinite() }) return null
        return runCatching {
            Mats().use { mats ->
                val input = mats.mat(); val output = mats.mat()
                Utils.bitmapToMat(source, input)
                val src = mats.keep(MatOfPoint2f(*quad.toList().map { CvPoint(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()))
                val dst = mats.keep(MatOfPoint2f(CvPoint(0.0, 0.0), CvPoint(width - 1.0, 0.0),
                    CvPoint(width - 1.0, height - 1.0), CvPoint(0.0, height - 1.0)))
                val transform = mats.keep(Imgproc.getPerspectiveTransform(src, dst))
                Imgproc.warpPerspective(input, output, transform, Size(width.toDouble(), height.toDouble()),
                    Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar.all(255.0))
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
            }
        }.getOrNull()
    }

    /** Port of flatpage's LAB background closing/division. Red stamps remain colored. */
    fun flattenIllumination(source: Bitmap): Bitmap? {
        if (!available()) return null
        return runCatching {
            Mats().use { mats ->
                val rgba = mats.mat(); val rgb = mats.mat(); val lab = mats.mat()
                Utils.bitmapToMat(source, rgba)
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
                val channels = mutableListOf<Mat>()
                Core.split(lab, channels); channels.forEach { mats.keep(it) }
                val luminance = channels[0]
                val small = mats.mat(); val background = mats.mat()
                Imgproc.resize(luminance, small, Size(max(source.width / 8, 16).toDouble(), max(source.height / 8, 16).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                val k = max(3, min(small.rows(), small.cols()) / 6 or 1)
                val kernel = mats.keep(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
                Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
                Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), max(k / 3.0, 1.0))
                Imgproc.resize(small, background, luminance.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                // CV_32F avoids saturated integer intermediate division.
                luminance.convertTo(luminance, CvType.CV_32F)
                background.convertTo(background, CvType.CV_32F)
                Core.max(background, Scalar.all(1.0), background)
                Core.divide(luminance, background, luminance, 235.0)
                luminance.convertTo(luminance, CvType.CV_8U)
                Core.merge(channels, lab)
                Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
                Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgb, it) }
            }
        }.getOrNull()
    }

    private fun Point.scaled(xScale: Float, yScale: Float) = Point(x * xScale, y * yScale)
    private class Mats : AutoCloseable {
        private val items = mutableListOf<Mat>()
        fun mat() = keep(Mat())
        fun <T : Mat> keep(value: T): T = value.also { items.add(it) }
        override fun close() = items.asReversed().forEach(Mat::release)
    }
}

/** Angular order avoids the duplicate corners produced by sum/difference at 45 degrees. */
internal fun orderedQuad(points: List<Point>): Quad? {
    if (points.size != 4 || points.distinct().size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val cx = points.map { it.x }.average(); val cy = points.map { it.y }.average()
    val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
    val first = sorted.indices.minBy { sorted[it].x + sorted[it].y }
    val p = List(4) { sorted[(first + it) % 4] }
    return Quad(p[0], p[1], p[2], p[3]).takeIf { isConvex(p) }
}

internal fun validDocumentQuad(quad: Quad, width: Int, height: Int): Boolean {
    val p = quad.toList()
    val fraction = polygonArea(p) / (width.toFloat() * height)
    if (fraction !in 0.08f..0.97f || !isConvex(p)) return false
    // Do not fabricate a complete page when the contour touches the camera frame.
    if (p.any { it.x < 1f || it.y < 1f || it.x > width - 2f || it.y > height - 2f }) return false
    return p.indices.all { i ->
        val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
        val ux = a.x - b.x; val uy = a.y - b.y; val vx = c.x - b.x; val vy = c.y - b.y
        val length = kotlin.math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        length > 1f && abs((ux * vx + uy * vy) / length) < 0.85f
    }
}
