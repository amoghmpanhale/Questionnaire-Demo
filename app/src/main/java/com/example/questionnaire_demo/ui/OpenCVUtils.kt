// OpenCVUtils.kt
package com.example.questionnaire_demo.ui.camera

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object OpenCVUtils {

    // =========================================================================
    // NEW — simple pipeline (grayscale → blur → Canny → contours → quad overlay)
    // Mirrors the Python document scanner script exactly.
    // Call this instead of detectPageQuad() if you want the leaner approach.
    // =========================================================================

    /**
     * Detects the document boundary using the classic pipeline:
     *   grayscale → Gaussian blur → Canny → contours → approxPolyDP → draw
     *
     * Returns a new Bitmap with a green quad + red corner dots drawn on it,
     * or the original bitmap unchanged if no quad is found.
     */
    fun detectAndDrawSimple(bitmap: Bitmap): Bitmap {
        val inputMat   = Mat()
        val grayMat    = Mat()
        val blurredMat = Mat()
        val edgesMat   = Mat()
        val outputMat  = Mat()
        val hierarchy  = Mat()

        try {
            Utils.bitmapToMat(bitmap, inputMat)
            inputMat.copyTo(outputMat)

            // Step 1 — Grayscale
            // Canny works on single-channel images; colour is irrelevant for edge detection.
            Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            // Step 2 — Gaussian blur (5×5 kernel, sigma derived from kernel size)
            // Merges high-frequency noise so Canny only fires on strong structural boundaries.
            Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)

            // Step 3 — Canny edge detection (thresholds 75 / 200, same as Python script)
            // Pixels above 200 are definite edges; below 75 are noise.
            // In-between pixels survive only if connected to a definite edge (hysteresis).
            Imgproc.Canny(blurredMat, edgesMat, 75.0, 200.0)

            // Step 4 — Find contours
            // RETR_LIST returns all contours with no hierarchy — we just want raw shapes.
            // CHAIN_APPROX_SIMPLE compresses runs of collinear points into two endpoints.
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(
                edgesMat, contours, hierarchy,
                Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
            )

            // Keep only the 5 largest contours — the document will almost always be one of them.
            val topContours = contours
                .sortedByDescending { Imgproc.contourArea(it) }
                .take(5)

            // Step 5 — Walk largest-first; first 4-vertex approximation is our document quad.
            val quad = findDocumentQuad(topContours)

            // Step 6 — Draw overlay
            if (quad != null) {
                drawQuadOverlay(quad, outputMat)
                quad.release()
            }

            val resultBitmap = Bitmap.createBitmap(
                outputMat.cols(), outputMat.rows(), Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(outputMat, resultBitmap)
            return resultBitmap

        } finally {
            // OpenCV Mats live on the C++ heap — the JVM GC won't collect them.
            // try/finally guarantees release even if an exception is thrown mid-pipeline.
            inputMat.release()
            grayMat.release()
            blurredMat.release()
            edgesMat.release()
            outputMat.release()
            hierarchy.release()
        }
    }

    /**
     * Iterates [contours] (already sorted largest-first) and returns the first
     * whose polygon approximation has exactly 4 vertices.
     *
     * approxPolyDP uses the Ramer–Douglas–Peucker algorithm.
     * epsilon = 2% of perimeter: large enough to collapse a rough page outline
     * into 4 corners, small enough not to over-simplify into a triangle.
     */
    private fun findDocumentQuad(contours: List<MatOfPoint>): MatOfPoint2f? {
        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)

            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)
            contour2f.release()

            if (approx.total() == 4L) {
                return approx   // caller must release
            } else {
                approx.release()
            }
        }
        return null
    }

    /**
     * Draws green lines between the 4 corners and red filled circles at each corner.
     * The modulo wrap on (i+1) closes the shape without a special case for the last edge.
     */
    private fun drawQuadOverlay(quad: MatOfPoint2f, mat: Mat) {
        val points      = quad.toArray()
        val lineColor   = Scalar(0.0, 255.0, 0.0, 255.0)   // green
        val cornerColor = Scalar(255.0, 0.0, 0.0, 255.0)   // red

        for (i in points.indices) {
            Imgproc.line(mat, points[i], points[(i + 1) % points.size], lineColor, 3)
        }
        for (point in points) {
            Imgproc.circle(mat, Point(point.x, point.y), 12, cornerColor, -1)
        }
    }

    // =========================================================================
    // ORIGINAL CODE — unchanged below this line
    // =========================================================================

    fun detectPageQuad(bitmap: Bitmap): Bitmap {
        val inputMat = Mat()
        val grayMat = Mat()
        val blurredMat = Mat()
        val edgesMat = Mat()
        val outputMat = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))

        try {
            Utils.bitmapToMat(bitmap, inputMat)
            Core.rotate(inputMat, inputMat, Core.ROTATE_90_CLOCKWISE)
            inputMat.copyTo(outputMat)

            Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurredMat, edgesMat, 50.0, 150.0)
            Imgproc.dilate(edgesMat, edgesMat, kernel)

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(
                edgesMat, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            // Primary path: contour-based quad detection
            val quad = findBestQuad(contours, inputMat.rows() * inputMat.cols())

            if (quad != null) {
                drawQuadOverlay(outputMat, quad)
                quad.release()
            } else {
                // Fallback: reconstruct quad from dominant Hough line clusters
                findQuadFromHoughLines(edgesMat, inputMat.rows(), inputMat.cols())
                    ?.let { corners ->
                        drawCornersOverlay(outputMat, corners)
                    }
            }

            val resultBitmap = Bitmap.createBitmap(outputMat.cols(), outputMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outputMat, resultBitmap)
            return resultBitmap

        } finally {
            inputMat.release()
            grayMat.release()
            blurredMat.release()
            edgesMat.release()
            outputMat.release()
            hierarchy.release()
            kernel.release()
        }
    }

    // -------------------------------------------------------------------------
    // Hough Lines fallback
    // -------------------------------------------------------------------------

    /**
     * Runs HoughLinesP on the edge image, clusters the resulting segments into
     * 4 directional groups (roughly horizontal-top, horizontal-bottom,
     * vertical-left, vertical-right), fits one representative line per group,
     * and intersects them pairwise to produce 4 corner points.
     *
     * Returns null if fewer than 4 clusters could be found.
     *
     * Why HoughLinesP instead of HoughLines?
     *   HoughLinesP (probabilistic) returns actual line *segments* with start/end
     *   points, which lets us filter by length and position. HoughLines returns
     *   infinite lines in (rho, theta) space — useful but harder to spatially filter.
     */
    private fun findQuadFromHoughLines(edgesMat: Mat, rows: Int, cols: Int): List<Point>? {
        val linesMat = Mat()
        try {
            Imgproc.HoughLinesP(
                edgesMat, linesMat,
                1.0, Math.PI / 180.0,
                80, 50.0, 10.0
            )
            if (linesMat.rows() == 0) return null

            val segments = (0 until linesMat.rows()).map { i ->
                val v = linesMat.get(i, 0)
                Segment(Point(v[0], v[1]), Point(v[2], v[3]))
            }

            // Filter out very short segments — likely noise, not page edges
            val minLength = sqrt((rows * rows + cols * cols).toDouble()) * 0.1
            val longSegments = segments.filter { it.length() > minLength }
            if (longSegments.size < 4) return null

            // Cluster by angle into 2 groups of parallel lines (the 2 edge directions of the page).
            // A page has 2 pairs of parallel edges, so we expect 2 dominant angle clusters.
            val clusters = clusterByAngle(longSegments, thresholdDegrees = 20.0)
            if (clusters.size < 2) return null

            // Take the 2 largest angle clusters
            val topTwo = clusters.sortedByDescending { it.size }.take(2)

            // Within each angle cluster, split into 2 parallel lines (opposite edges)
            // by clustering on perpendicular offset from the cluster's mean direction.
            val edgeGroups = topTwo.mapNotNull { cluster ->
                splitIntoTwoParallelEdges(cluster)
            }
            if (edgeGroups.size < 2) return null

            val (edgeGroupA, edgeGroupB) = edgeGroups[0] to edgeGroups[1]
            val (lineA1, lineA2) = edgeGroupA
            val (lineB1, lineB2) = edgeGroupB

            // Intersect each line from group A with each line from group B to get 4 corners
            val tl = intersect(lineA1, lineB1) ?: return null
            val tr = intersect(lineA1, lineB2) ?: return null
            val br = intersect(lineA2, lineB2) ?: return null
            val bl = intersect(lineA2, lineB1) ?: return null

            // Sanity check: corners should be inside (or near) the image bounds
            val margin = maxOf(rows, cols) * 0.3
            val allCorners = listOf(tl, tr, br, bl)
            if (allCorners.any { it.x < -margin || it.x > cols + margin ||
                        it.y < -margin || it.y > rows + margin }) return null

            return allCorners

        } finally {
            linesMat.release()
        }
    }

    /**
     * Clusters segments by their angle using a greedy approach.
     *
     * Segment angles are normalized to [0°, 180°) because a line at 10° and
     * a line at 190° are the same direction. We then greedily assign each
     * segment to an existing cluster if its angle is within [thresholdDegrees],
     * otherwise start a new cluster.
     *
     * Why not K-means? We don't know K in advance, and angle wrap-around
     * makes standard K-means unreliable without special handling.
     */
    private fun clusterByAngle(segments: List<Segment>, thresholdDegrees: Double): List<List<Segment>> {
        // Normalize angle to [0, 180)
        fun angleOf(seg: Segment): Double {
            var angle = Math.toDegrees(kotlin.math.atan2(seg.dy(), seg.dx()))
            if (angle < 0) angle += 180.0
            if (angle >= 180.0) angle -= 180.0
            return angle
        }

        // Angular distance accounting for wrap-around at 180°
        fun angleDist(a: Double, b: Double): Double {
            val diff = abs(a - b)
            return minOf(diff, 180.0 - diff)
        }

        val clusters = mutableListOf<Pair<Double, MutableList<Segment>>>() // mean angle, members

        for (seg in segments) {
            val angle = angleOf(seg)
            val match = clusters.minByOrNull { angleDist(it.first, angle) }

            if (match != null && angleDist(match.first, angle) < thresholdDegrees) {
                match.second.add(seg)
                // Update running mean angle of the cluster (wrap-aware average)
                val newMean = match.second.map(::angleOf).average()
                clusters[clusters.indexOf(match)] = newMean to match.second
            } else {
                clusters.add(angle to mutableListOf(seg))
            }
        }

        return clusters.map { it.second }
    }

    /**
     * Given a cluster of roughly parallel segments, splits them into exactly 2
     * groups representing opposite edges of the page.
     *
     * We project each segment's midpoint onto the direction perpendicular to the
     * cluster's mean angle — this gives a scalar "offset" for each segment along
     * the across-page axis. A page has two edges, so we expect a bimodal
     * distribution; splitting at the median gives us the two edge groups.
     *
     * Returns null if the two groups aren't far enough apart to be real edges.
     */
    private fun splitIntoTwoParallelEdges(segments: List<Segment>): Pair<Line, Line>? {
        if (segments.size < 2) return null

        // Mean direction vector of this cluster
        val angles = segments.map { Math.toDegrees(kotlin.math.atan2(it.dy(), it.dx())) }
        val meanAngleRad = Math.toRadians(angles.average())
        val perpX = -sin(meanAngleRad)  // perpendicular direction
        val perpY =  cos(meanAngleRad)

        // Project each segment's midpoint onto the perpendicular axis
        val offsets = segments.map { seg ->
            seg.midX() * perpX + seg.midY() * perpY
        }

        val medianOffset = offsets.sorted()[offsets.size / 2]

        val group1 = segments.zip(offsets).filter { it.second <  medianOffset }.map { it.first }
        val group2 = segments.zip(offsets).filter { it.second >= medianOffset }.map { it.first }

        if (group1.isEmpty() || group2.isEmpty()) return null

        // Check the two groups are actually separated (not just noise split)
        val spread = abs(offsets.max() - offsets.min())
        val minSpread = sqrt((group1.size + group2.size).toDouble()) * 20.0
        if (spread < minSpread) return null

        return fitLine(group1) to fitLine(group2)
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /** Lightweight wrapper around a line segment so we can attach helper methods. */
    private data class Segment(val p1: Point, val p2: Point) {
        fun dx() = p2.x - p1.x
        fun dy() = p2.y - p1.y
        fun midX() = (p1.x + p2.x) / 2.0
        fun midY() = (p1.y + p2.y) / 2.0
        fun length() = sqrt(dx() * dx() + dy() * dy())

        /**
         * "Horizontal" means the segment's angle is within 30° of the X-axis.
         * We use abs(dy/dx) < tan(30°) ≈ 0.577 as the fast check.
         * This threshold is intentionally generous to handle perspective-skewed pages.
         */
        fun isHorizontal() = length() > 0 && abs(dy()) < abs(dx()) * 0.7
        fun isVertical()   = length() > 0 && abs(dx()) < abs(dy()) * 0.7
    }

    /**
     * Splits a list into two halves: elements whose key is below the median go
     * into the first list, the rest into the second.
     *
     * This is better than a fixed pixel threshold because it adapts to
     * any frame size or camera distance automatically.
     */
    private fun <T> splitByMedian(items: List<T>, key: (T) -> Double): Pair<List<T>, List<T>> {
        val median = items.map(key).sorted()[items.size / 2]
        return items.filter { key(it) < median } to items.filter { key(it) >= median }
    }

    /**
     * Represents a line as two points (origin + direction).
     * Stored as doubles for intersection arithmetic.
     */
    private data class Line(val ox: Double, val oy: Double, val dx: Double, val dy: Double)

    /**
     * Fits a single representative line through all segments in a cluster.
     *
     * We collect all segment endpoints into a Mat and call Imgproc.fitLine,
     * which uses the M-estimator least-squares method (DIST_L2 here).
     * fitLine returns [vx, vy, x0, y0] — a unit direction vector and a point on the line.
     *
     * Why not just average the segment angles?
     *   Averaging angles wraps around at 180°, causing errors on near-vertical lines.
     *   fitLine handles this correctly and weights longer segments more heavily.
     */
    private fun fitLine(segments: List<Segment>): Line {
        // CV_32FC2 = 32-bit float, 2 channels (x, y per point)
        // The put() overload for float data requires a FloatArray explicitly
        val pointsMat = Mat(segments.size * 2, 1, org.opencv.core.CvType.CV_32FC2)
        segments.forEachIndexed { i, seg ->
            pointsMat.put(i * 2,     0, floatArrayOf(seg.p1.x.toFloat(), seg.p1.y.toFloat()))
            pointsMat.put(i * 2 + 1, 0, floatArrayOf(seg.p2.x.toFloat(), seg.p2.y.toFloat()))
        }
        val lineResult = Mat()
        Imgproc.fitLine(pointsMat, lineResult, Imgproc.DIST_L2, 0.0, 0.01, 0.01)
        val vx = lineResult.get(0, 0)[0].toDouble()
        val vy = lineResult.get(1, 0)[0].toDouble()
        val x0 = lineResult.get(2, 0)[0].toDouble()
        val y0 = lineResult.get(3, 0)[0].toDouble()
        pointsMat.release()
        lineResult.release()
        return Line(x0, y0, vx, vy)
    }

    /**
     * Finds the intersection point of two infinite lines using parametric form.
     *
     * Line A: P = (ax, ay) + t*(adx, ady)
     * Line B: P = (bx, by) + s*(bdx, bdy)
     *
     * Solving for t:
     *   adx*t - bdx*s = bx - ax
     *   ady*t - bdy*s = by - ay
     *
     * Using Cramer's rule. Returns null if lines are parallel (determinant ≈ 0).
     */
    private fun intersect(a: Line, b: Line): Point? {
        val denom = a.dx * b.dy - a.dy * b.dx
        if (abs(denom) < 1e-10) return null  // parallel lines

        val t = ((b.ox - a.ox) * b.dy - (b.oy - a.oy) * b.dx) / denom
        return Point(a.ox + t * a.dx, a.oy + t * a.dy)
    }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    private fun drawCornersOverlay(mat: Mat, corners: List<Point>) {
        val edgeColor   = Scalar(0.0, 255.0, 0.0, 255.0)
        val cornerColor = Scalar(255.0, 0.0, 0.0, 255.0)

        for (i in corners.indices) {
            val p1 = corners[i]
            val p2 = corners[(i + 1) % corners.size]
            Imgproc.line(mat, p1, p2, edgeColor, 3)
        }
        for (p in corners) {
            Imgproc.circle(mat, p, 10, cornerColor, -1)
        }
    }

    // -------------------------------------------------------------------------
    // Original contour path (unchanged)
    // -------------------------------------------------------------------------

    private fun findBestQuad(contours: List<MatOfPoint>, imageArea: Int): MatOfPoint2f? {
        var bestQuad: MatOfPoint2f? = null
        var bestArea = 0.0

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)

            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.04 * perimeter, true)
            contour2f.release()

            val area = Imgproc.contourArea(approx)
            val areaRatio = area / imageArea

            val isQuad = approx.total() == 4L
            val isConvex = Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))
            val isPageSized = areaRatio in 0.08..0.95

            if (isQuad && isConvex && isPageSized && area > bestArea) {
                bestQuad?.release()
                bestArea = area
                bestQuad = approx
            } else {
                approx.release()
            }
        }

        return bestQuad
    }

    private fun drawQuadOverlay(mat: Mat, quad: MatOfPoint2f) {
        val points = quad.toArray()
        val edgeColor   = Scalar(0.0, 255.0, 0.0, 255.0)
        val cornerColor = Scalar(255.0, 0.0, 0.0, 255.0)

        for (i in points.indices) {
            val p1 = Point(points[i].x, points[i].y)
            val p2 = Point(points[(i + 1) % points.size].x, points[(i + 1) % points.size].y)
            Imgproc.line(mat, p1, p2, edgeColor, 3)
        }
        for (point in points) {
            Imgproc.circle(mat, Point(point.x, point.y), 10, cornerColor, -1)
        }
    }
}