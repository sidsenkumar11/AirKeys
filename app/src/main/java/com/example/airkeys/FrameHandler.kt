package com.example.airkeys

import android.util.Log
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
    private lateinit var disc: Mat
    private lateinit var hand_rect_row_nw: List<Int>
    private lateinit var hand_rect_col_nw: List<Int>
    private lateinit var hand_rect_row_se: List<Int>
    private lateinit var hand_rect_col_se: List<Int>

    // Variables and constants
    private var emulated: Boolean = true
    var histCreated: Boolean = false
    var shouldCreateHist: Boolean = false
    private const val numRects = 9
    private const val TAG = "----------------------- OUR LOG"

    // Finger-tracking
    val traverse_point = LinkedList<Point>()

    /**
     * Initialize the matrices just once to save memory.
     */
    fun init(emulated: Boolean) {
        histogram = Mat()
        mHsv = Mat()
        mFiltered = Mat()
        mThreshed = Mat()
        mThreshed3D = Mat()

        // Create a structuring element for morphological operations. We want a disk-like image.
        disc = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(31.0, 31.0))
        this.emulated = emulated

        // Fingertip Detection
        hull = MatOfInt()
        defects = MatOfInt4()
        mGrayHand = Mat()
        mGrayThresh = Mat()
    }

    /**
     * On receiving a frame, draw rectangles to help a user create a histogram of their hand.
     * After it's created, draw circles tracking their finger tips.
     */
    fun process(input: Mat) {
        this.mRgb = input

        // Rotate 90 degrees if on phone
        if (!emulated) {
            Core.transpose(mRgb, mRgb)
            Imgproc.resize(mRgb, mRgb, mRgb.size(), 0.0, 0.0, 0)
            Core.flip(mRgb, mRgb, Core.ROTATE_90_COUNTERCLOCKWISE)
        }

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
            getFingerTip()
            drawCircles()
        }
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
        Imgproc.calcHist(histSrcList, MatOfInt(0, 1), Mat(), histogram, MatOfInt(180, 256),
                         MatOfFloat(0.toFloat(), 180.toFloat(), 0.toFloat(), 256.toFloat()))
        Core.normalize(histogram, histogram, 0.0, 255.0, Core.NORM_MINMAX)

        // Reclaim memory
        hsv.release()
        histSrcList[0].release()
    }

    /**
     * Mask the current frame with our histogram to filter everything but the hand.
     */
    private fun applyHistMask() {
        // 1. Convert RGB to HSV (hue, saturation, value).
        // Done because RGB contains information about brightness, and we don't want that.
        Imgproc.cvtColor(mRgb, mHsv, Imgproc.COLOR_RGB2HSV)

        // 2. Calculates the back projection of the hand-histogram.
        // Range of values for Hue: 0-179, Value: 0-255.
        Imgproc.calcBackProject(arrayListOf(mHsv), MatOfInt(0, 1), histogram, mFiltered,
                                MatOfFloat(0.toFloat(), 180.toFloat(), 0.toFloat(), 256.toFloat()), 1.toDouble())

        // 3. Smooth the filtered image
        // a) Convolve the image with the structuring element
        Imgproc.filter2D(mFiltered, mFiltered, -1, disc)

        // b) Applies fixed-level threshold to each array element. We'll be converting it to a binary image.
        Imgproc.threshold(mFiltered, mThreshed, 100.0, 255.0, Imgproc.THRESH_BINARY)

        // c) Merges thresh matrices to make 3 channels, since mRgba is a 3-channel matrix.
        Core.merge(arrayListOf(mThreshed, mThreshed, mThreshed), mThreshed3D)

        // 4. In the original image, filter out all pixels that aren't above our threshold.
        Core.bitwise_and(mRgb, mThreshed3D, mRgb)
    }

    /**
     * Get location of finger tip and draw a circle there.
     * Assumes hand histogram has already been created.
     */
    private fun getFingerTip() {

        // 1. Mask away everything but the hand.
        applyHistMask()

        // 2. Get the max area contour and calculate its centroid.
        val contours = contours()
        if (contours.isEmpty()) {
            // No hand detected on screen!
            if (traverse_point.isNotEmpty()) traverse_point.removeFirst()
            return
        }
        val maxContour = maxContour(contours)
        val maxCentroid = centroid(maxContour)
        if (maxCentroid == null)  {
            // No hand detected on screen!
            if (traverse_point.isNotEmpty()) traverse_point.removeFirst()
            return
        }

        // 3. Draw a circle at the centroid.
        Imgproc.circle(mRgb, maxCentroid, 5, Scalar(255.0, 0.0, 255.0), -1)

        // 4. Compute convex hull of hand and get convexity defects (space between hull and actual contour).
        Imgproc.convexHull(maxContour, hull)
        Imgproc.convexityDefects(maxContour, hull, defects)

        // 5. Compute furthest point from a defect to the centroid. This point is assumed to be the finger tip.
        val fingerPoint = furthestPoint(defects, maxContour, maxCentroid)
        if (fingerPoint != null) {
            // Create a circle at the finger tip.
            Imgproc.circle(mRgb, fingerPoint, 5, Scalar(0.0, 0.0, 255.0), -1)

            // Add location to list of recently seen fingertip positions.
            if (traverse_point.size < 20) {
                traverse_point.add(fingerPoint)
            } else {
                traverse_point.removeFirst()
                traverse_point.add(fingerPoint)
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
    private fun maxContour(contour_list: List<MatOfPoint>): MatOfPoint {
        var maxIndex = 0
        var maxArea = 0.0
        for (i in 0 until contour_list.size-1) {
            val cnt = contour_list[i]
            val contourArea = Imgproc.contourArea(cnt)
            if (contourArea > maxArea) {
                maxArea = contourArea
                maxIndex = i
            }
        }
        return contour_list[maxIndex]
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

    /**
     * Computes the furthest defect point from the centroid of the hand contour.
     * This point is assumed to be the location of a finger-tip.
     */
    private fun furthestPoint(defects: MatOfInt4, contour: MatOfPoint, centroid: Point?): Point? {
        if (centroid == null) return null

        // MatOfInt4: 1 element matrix at 0,0 -> n rows, 1 column matrix -> each row has 4 ints
//
//        val s = defects.get(0, 0)[0].toInt()
//        val cx = com.example.airkeys.centroid.x
//        val cy = com.example.airkeys.centroid.y
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
//            com.example.airkeys.farthest_point = contour[farthest_defect][0]
//            return com.example.airkeys.farthest_point
//        } else {
//            return null
//        }
        val nums = contour.get(0, 0)
        return Point(nums[0], nums[1])
    }

    /**
     * Draws several rectangles in a frame.
     */
    private fun drawRects() {
        // Get dimensions
        val rows = mRgb.rows()
        val cols = mRgb.cols()

        // Generate lists of points for rectangles
        hand_rect_row_nw = arrayListOf(6 * rows / 20, 6 * rows / 20, 6 * rows / 20, 10 * rows / 20, 10 * rows / 20, 10 * rows / 20, 14 * rows / 20,
            14 * rows / 20, 14 * rows / 20)
        hand_rect_col_nw = arrayListOf(9 * cols / 20, 10 * cols / 20, 11 * cols / 20, 9 * cols / 20, 10 * cols / 20, 11 * cols / 20, 9 * cols / 20,
            10 * cols / 20, 11 * cols / 20)

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
        for (i in 0 until traverse_point.size) {
            Imgproc.circle(mRgb, traverse_point[i], 5 - (5 * i * 3) / 100, Scalar(0.0, 255.0, 255.0), -1)
        }
    }

    /**
     * Resizes the frame dimensions by some percentage.
     */
    fun rescaleFrame(wpercent: Int = 130, hpercent: Int = 130) {
        val width = mRgb.width() * wpercent / 100.0
        val height = mRgb.height() * hpercent / 100.0
        Imgproc.resize(mRgb, mRgb, Size(width, height), 0.0, 0.0, Imgproc.INTER_AREA)
    }
}
