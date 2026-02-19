// OpenCVUtils.kt
package com.example.questionnaire_demo.ui.camera

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object OpenCVUtils {

    /**
     * Detects the largest quadrilateral in the frame that matches page-on-table
     * heuristics, draws a polygon overlay on top of the original image, and
     * returns the result as a Bitmap.
     */
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
                edgesMat,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            findBestQuad(contours, inputMat.rows() * inputMat.cols())
                ?.let { bestQuad ->
                    drawQuadOverlay(outputMat, bestQuad)
                    bestQuad.release()
                }

            val resultBitmap = Bitmap.createBitmap(outputMat.cols(), outputMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outputMat, resultBitmap)
            return resultBitmap

        } finally {
            // finally block guarantees release even if an exception is thrown mid-pipeline
            inputMat.release()
            grayMat.release()
            blurredMat.release()
            edgesMat.release()
            outputMat.release()
            hierarchy.release()
            kernel.release()
        }
    }

    /**
     * Iterates contours and returns the largest convex quadrilateral that passes
     * the page-on-table area ratio filter, or null if none qualifies.
     */
    private fun findBestQuad(contours: List<MatOfPoint>, imageArea: Int): MatOfPoint2f? {
        var bestQuad: MatOfPoint2f? = null
        var bestArea = 0.0

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)

            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)
            contour2f.release()

            val area = Imgproc.contourArea(approx)
            val areaRatio = area / imageArea

            val isQuad = approx.total() == 4L
            val isConvex = Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))
            val isPageSized = areaRatio in 0.15..0.95

            if (isQuad && isConvex && isPageSized && area > bestArea) {
                bestQuad?.release()  // release the previous best before replacing it
                bestArea = area
                bestQuad = approx
            } else {
                approx.release()
            }
        }

        return bestQuad
    }

    /**
     * Draws the quad edges in green and corner circles in red onto the given Mat.
     */
    private fun drawQuadOverlay(mat: Mat, quad: MatOfPoint2f) {
        val points = quad.toArray()
        val edgeColor = org.opencv.core.Scalar(0.0, 255.0, 0.0, 255.0)
        val cornerColor = org.opencv.core.Scalar(255.0, 0.0, 0.0, 255.0)

        for (i in points.indices) {
            val p1 = org.opencv.core.Point(points[i].x, points[i].y)
            val p2 = org.opencv.core.Point(points[(i + 1) % points.size].x, points[(i + 1) % points.size].y)
            Imgproc.line(mat, p1, p2, edgeColor, 3)
        }

        for (point in points) {
            Imgproc.circle(mat, org.opencv.core.Point(point.x, point.y), 10, cornerColor, -1)
        }
    }
}