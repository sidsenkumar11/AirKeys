package com.example.airkeys

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*
import kotlinx.coroutines.*

object FrameHandler {

    // Matrices that change every frame
    private lateinit var mRgb: Mat
    private lateinit var mHsv: Mat
    private lateinit var mFiltered: Mat
    private lateinit var mThreshed: Mat
    private lateinit var mThreshed3D: Mat
    private lateinit var mGrayHand: Mat
    private lateinit var mGrayThresh: Mat
    private lateinit var hull: MatOfInt
    private lateinit var defects: MatOfInt4

    // One-time vars needed for histograms
    private lateinit var histogram: Mat
    private lateinit var oldHistogram: Mat
    private lateinit var disc: Mat
    private lateinit var hand_rect_row_nw: List<Int>
    private lateinit var hand_rect_col_nw: List<Int>
    private lateinit var hand_rect_row_se: List<Int>
    private lateinit var hand_rect_col_se: List<Int>
    private lateinit var oldContour: MatOfPoint2f

    // Flag variables and constants
    private var emulated = true
    var histCreated      = false
    var shouldCreateHist = false
    private const val numRects = 9
    private const val maxCaptures = 70
    private const val TAG = "----------------------- OUR LOG"

    // Finger-tracking
    private val drawn_points = LinkedList<Point>()
    private var freeze = false
    private var freezeCount = 0
    private const val maxFreezeCount = 50

    /**
     * Initialize the matrices just once to save memory.
     */
    fun init(emulated: Boolean) {
        // Histogram generation and filtering
        histogram   = Mat()
        mHsv        = Mat()
        mFiltered   = Mat()
        mThreshed   = Mat()
        mThreshed3D = Mat()
        disc = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(31.0, 31.0))
        this.emulated = emulated

        // Fingertip Detection
        oldContour  = MatOfPoint2f()
        hull        = MatOfInt()
        defects     = MatOfInt4()
        mGrayHand   = Mat()
        mGrayThresh = Mat()
    }

    /**
     * On receiving a frame, draw rectangles to help a user create a histogram of their hand.
     * If it's already been created, draw circles tracking their finger tips instead.
     * After the user indicates that they've drawn a character (by not moving their finger),
     * send the points to a server for recognition and freeze the frame.
     */
    fun process(input: Mat): String? {
        this.mRgb = input

        // Create the histogram if the screen was tapped
        if (shouldCreateHist) {
            createHist()
            shouldCreateHist = false
        }

        // If we haven't created a histogram of skin samples yet,
        // draw rectangles to help users align their hands where we'll sample.
        // Otherwise, draw finger-tracing circles.
        if (!histCreated) {
            drawRects()
        } else {
            captureFingerTip()
            drawCircles()
        }

        // If the finger doesn't appear to be moving, try classifying the current points as a character.
        if (!freeze && stationary()) {
            freeze = true
            return LetterClassifier.classify(drawn_points, mRgb.rows(), mRgb.cols())
        } else if (freeze) {
            freezeCount += 1
            if (freezeCount > maxFreezeCount) {
                freezeCount = 0
                freeze = false
                drawn_points.clear()
            } else {
                // Indicate to user that they're free to move their finger to a new start position
                Imgproc.rectangle(
                    mRgb, Point(0.0, 0.0), Point(30.0, 30.0), Scalar(0.0, 255.0, 0.0), -1)
            }
        }
        return null
    }

    /**
     * Determines if the last 1/8 of points appear to be stationary,
     * indicating that the user is finished drawing a character.
     */
    private fun stationary(): Boolean {
        if (drawn_points.size < maxCaptures) return false

        val lastFactor: Double = 7.0 / 8
        val startIndex = (maxCaptures * lastFactor).toInt()
        var maxX = drawn_points[startIndex].x
        var minX = drawn_points[startIndex].x
        var maxY = drawn_points[startIndex].y
        var minY = drawn_points[startIndex].y

        for (i in startIndex until maxCaptures) {
            val x = drawn_points[i].x
            val y = drawn_points[i].y
            if (x > maxX) maxX = x
            if (x < minX) minX = x
            if (y > maxY) maxY = y
            if (y < minY) minY = y
        }

        val epsilon = 14
        if (maxX - minX > epsilon) return false
        if (maxY - minY > epsilon) return false
        return true
    }

    /**
     * Create the hand histogram from the current frame's content.
     */
    private fun createHist() {
        // Convert RGB to HSV
        val hsv = Mat()
        Imgproc.cvtColor(mRgb, hsv, Imgproc.COLOR_RGB2HSV)

        // Create one large matrix containing all the hand's pixels for the histogram.
        val histSrcList = arrayListOf(Mat(10 * numRects, 10, hsv.type()))

        // Get several 10x10 squares of pixels from the rectangle regions
        for (i in 0 until numRects) {
            val roi = Rect(hand_rect_col_nw[i], hand_rect_row_nw[i], 10, 10)
            val tmp = histSrcList[0].submat(Rect(0, i*10, 10, 10))
            hsv.submat(roi).copyTo(tmp)
        }

        // Compute the histogram (using only Hue and Saturation) and normalize values to be between 0 and 255.
        Imgproc.calcHist(histSrcList, MatOfInt(0, 1), Mat(), histogram, MatOfInt(30, 32),
            MatOfFloat(0.toFloat(), 180.toFloat(), 0.toFloat(), 256.toFloat()))
        Core.normalize(histogram, histogram, 0.0, 255.0, Core.NORM_MINMAX)

        // Reclaim memory
        hsv.release()
        histSrcList[0].release()
    }

    /**
     * Mask the current frame with our histogram to filter everything but the hand.
     * Returns the max contour.
     */
    private fun applyHistMask(): MatOfPoint? {
        // 1. Convert RGB to HSV.
        Imgproc.cvtColor(mRgb, mHsv, Imgproc.COLOR_RGB2HSV)

        // 2. Reduce some noise
        Imgproc.GaussianBlur(mHsv, mHsv, Size(5.0, 5.0),0.0)

        // 3. Calculates the back projection of the hand-histogram.
        // Range of values for Hue: 0-179, Saturation: 0-255.
        Imgproc.calcBackProject(arrayListOf(mHsv), MatOfInt(0, 1), histogram, mFiltered,
            MatOfFloat(0.toFloat(), 180.toFloat(), 0.toFloat(), 256.toFloat()), 1.toDouble())

        // 4. Smooth the filtered image
        // a) Convolve the image using a disc to blur more.
        Imgproc.filter2D(mFiltered, mFiltered, -1, disc)

        // b) Applies fixed-level threshold to each array element to convert it to a binary image.
        Imgproc.threshold(mFiltered, mThreshed, 90.0, 255.0, Imgproc.THRESH_BINARY)

        // c) Merges thresh matrices to make 3 channels, since mRgb is a 3-channel matrix.
        Core.merge(arrayListOf(mThreshed, mThreshed, mThreshed), mThreshed3D)

        // 5. In the original image, filter out all pixels that aren't above our threshold.
        Core.bitwise_and(mRgb, mThreshed3D, mRgb)

        // 6. Filter out smaller contours
        val maxContour = maxContour(contours()) ?: return null
        val justContour = Mat(mRgb.rows(), mRgb.cols(), mRgb.type())
        Imgproc.drawContours(justContour, arrayListOf(maxContour), 0, Scalar(255.0, 255.0, 255.0), Imgproc.FILLED)
        Core.bitwise_and(mRgb, justContour, mRgb)
        return maxContour
    }

    /**
     * Get location of finger tip and draw a circle there.
     * Assumes hand histogram has already been created.
     */
    private fun captureFingerTip() {
        // 1. Mask away everything but the hand.
        val maxContour = applyHistMask()

        if (freeze) return

        // If no hand detected, return.
        if (maxContour == null) {
            if (drawn_points.isNotEmpty()) drawn_points.removeFirst()
            return
        }

        // 2. Calculate hand's centroid.
        val handCentroid = centroid(maxContour)
        if (handCentroid == null)  {
            if (drawn_points.isNotEmpty()) drawn_points.removeFirst()
            return
        }

        // 3. Verify the centroid makes sense. If it doesn't, don't assume we have a fingertip.
//        if (Imgproc.pointPolygonTest(oldContour, handCentroid, false) <= 0)
//            return
//        maxContour.convertTo(oldContour, CvType.CV_32F)

        // 3. Draw a circle at the centroid.
        Imgproc.circle(mRgb, handCentroid, 5, Scalar(255.0, 0.0, 255.0), -1)

        // 4. Compute convex hull of hand and get convexity defects (space between hull and actual contour).
//        Imgproc.convexHull(maxContour, hull)
//        Imgproc.convexityDefects(maxContour, hull, defects)

        // 5. Compute furthest point from a defect to the centroid. This point is assumed to be the finger tip.
        val fingerPoint = furthestPoint(defects, maxContour, handCentroid)
        if (fingerPoint != null) {
            // Add location to list of recently seen fingertip positions.
            if (drawn_points.size < maxCaptures) {
                drawn_points.add(fingerPoint)
            } else {
                drawn_points.removeFirst()
                drawn_points.add(fingerPoint)
            }
        }
    }

    /**
     * Finds contours in an input image. Contours are useful for object detection/recognition.
     */
    private fun contours(): List<MatOfPoint> {
        // Convert RGB to grayscale
        Imgproc.cvtColor(mRgb, mGrayHand, Imgproc.COLOR_RGB2GRAY)

        // Applies fixed-level threshold to each array element so we can get contours from it.
        // This converts the grayscale image to a binary image.
        Imgproc.threshold(mGrayHand, mGrayThresh,0.0, 255.0, Imgproc.THRESH_BINARY)

        // Find contours in a binary image.
        val contours = arrayListOf<MatOfPoint>()
        Imgproc.findContours(mGrayThresh, contours, Mat(), Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
        return contours
    }

    /**
     * Given a list of contours, returns the contour with the greatest area.
     */
    private fun maxContour(contours: List<MatOfPoint>): MatOfPoint? {
        return contours.maxBy { Imgproc.contourArea(it) }
    }

    /**
     * Computes the centroid of the hand-contour.
     */
    private fun centroid(max_contour: Mat): Point? {
        val moment = Imgproc.moments(max_contour)
        if (moment.m00 != 0.0) {
            val cx = moment.m10 / moment.m00
            val cy = moment.m01 / moment.m00
            return Point(cx, cy)
        }
        return null
    }

    /**
     * Computes the furthest defect point from the centroid of the hand contour.
     * This point is assumed to be the location of a finger-tip.
     */
    private fun furthestPoint(defects: MatOfInt4, contour: MatOfPoint, centroid: Point): Point? {
        // Use max distance between defects and hull

        // Min Row
        var maxRow = 0
        var maxPointValue = contour.get(0, 0)[1]
        for (i in 0 until contour.rows()) {
            val rowPoint = contour.get(i, 0)[1]
            if (rowPoint < maxPointValue) {
                maxPointValue = rowPoint
                maxRow = i
            }
        }
        val maxPoint = contour.get(maxRow, 0)
        return Point(maxPoint[0], maxPoint[1])

//        // Max Distance
//        val cenRow = centroid.y
//        val cenCol = centroid.x
//        var maxPoint = contour.get(0, 0)
//        var maxDist = 0.0
//        for (i in 0 until contour.rows()) {
//            val conRow = contour.get(i, 0)[1]
//            val conCol = contour.get(i, 0)[0]
//            val dist = Math.sqrt(Math.pow(cenRow - conRow, 2.0) + Math.pow(cenCol - conCol, 2.0))
//            if (dist > maxDist) {
//                maxDist = dist
//                maxPoint = contour.get(i, 0)
//            }
//        }
//        return Point(maxPoint[0], maxPoint[1])
    }

    /**
     * Draws several rectangles in a frame.
     */
    private fun drawRects() {
        // Get dimensions
        val rows = mRgb.rows()
        val cols = mRgb.cols()

        val x_margin = 45
        val y_margin = 0
        // Generate lists of points for rectangles
        hand_rect_row_nw = arrayListOf(
            x_margin + 6 * rows / 20, x_margin + 6 * rows / 20, x_margin + 6 * rows / 20,
            x_margin + 8 * rows / 20, x_margin + 8 * rows / 20, x_margin + 8 * rows / 20,
            x_margin + 10 * rows / 20, x_margin + 10 * rows / 20, x_margin + 10 * rows / 20
        )
        hand_rect_col_nw = arrayListOf(
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20,
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20,
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20
        )

        hand_rect_row_se = hand_rect_row_nw.map {it + 10}
        hand_rect_col_se= hand_rect_col_nw.map {it + 10}

        // Draw each rectangle
        for (i in 0 until numRects) {
            Imgproc.rectangle(
                mRgb, Point(hand_rect_col_nw[i].toDouble(), hand_rect_row_nw[i].toDouble()),
                Point(hand_rect_col_se[i].toDouble(), hand_rect_row_se[i].toDouble()), Scalar(0.0, 255.0, 0.0), 1)
        }
    }

    /**
     * Draws circles in the last several spots that a finger was last seen.
     * The circles decrease in size to indicate they were seen longer ago.
     */
    private fun drawCircles() {
        for (i in 0 until drawn_points.size) {
            Imgproc.circle(mRgb, drawn_points[i], 5, Scalar(0.0, 255.0, 255.0), -1)
        }
    }
}

// ----------------------------------------------------------------
// Experimental Code Notes
// ----------------------------------------------------------------

//    private fun furthestPoint(defects: MatOfInt4, contour: MatOfPoint, centroid: Point): Point? {
//        val s = defects.get(0, 0)[0].toInt()
//        val cx = centroid.x
//        val cy = centroid.y
//
//        val x: Mat = contour.get(s, 0)
//        val y = contour.get(s, 0)
//
//        val diffx = Mat()
//        subtract(x, cx, diffx)
//        val xp = Mat()
//        pow(diffx, 2.0, xp)
//
//        val sumMat = Mat()
//        sum(xp, yp, sumMat)
//        val distMat = Mat()
//        sqrt(sumMat, distMat)
//        val dist_max_i = argMax(distMat)
//
//        if (dist_max_i < s.length) {
//            val farthest_defect = s[dist_max_i]
//            farthest_point = contour[farthest_defect][0]
//            return farthest_point
//        } else {
//            return null
//        }
//    }

//    private fun argMax(vals: Mat): Int {
//
//        var max_i = 0
//        var max_val = vals.get(0, 0)
//        for (i in 0 until vals.rows()) {
//            for (j in 0 until vals.cols()) {
//                if (vals.get(i, j) > max_val) {
//                    max_val = vals.get(i, j)
//                    max_i = vals.cols() * i + j)
//                }
//            }
//        }
//        return max_i
//    }
