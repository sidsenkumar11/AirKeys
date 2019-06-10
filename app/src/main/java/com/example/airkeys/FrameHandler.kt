package com.example.airkeys

import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*
import kotlinx.coroutines.*
import org.opencv.core.Mat


object FrameHandler {

    // Matrices that change every frame
    private lateinit var mRgb: Mat
    private lateinit var mHsv: Mat
    private lateinit var mFiltered: Mat
    private lateinit var mThreshed: Mat
    private lateinit var mGrayHand: Mat
    private lateinit var mGrayThresh: Mat
    lateinit var mThreshed3D: Mat

    // One-time vars needed for histograms
    private lateinit var hand_rect_row_nw: List<Int>
    private lateinit var hand_rect_col_nw: List<Int>
    private lateinit var hand_rect_row_se: List<Int>
    private lateinit var hand_rect_col_se: List<Int>
    private lateinit var histogram: Mat
    private lateinit var disc: Mat

    // Finger-tracking
    private val drawn_points = LinkedList<Point>()
    private val prevGestures = LinkedList<Gesture>()
    private var freezeCount = 0
    private const val MAX_FREEZE_COUNT = 20

    // Flag variables and constants
    private var emulated = true
    private var freeze   = false
    var histCreated      = false
    var shouldCreateHist = false
    var sendDrawing      = false
    var clearScreen      = false
    private const val NUM_RECTS = 24
    private const val GESTURE_HISTORY_LENGTH = 5
    private const val TAG = "-------------- OUR LOG"
    // Define lambda for updating gesture history
    private val updateGestureHistory = { x: Gesture ->
        prevGestures.removeFirst()
        prevGestures.addLast(x)
    }

    /**
     * Initialize the matrices just once to save memory.
     */
    fun init(emulated: Boolean) {
        this.emulated = emulated

        // Histogram generation and filtering
        histogram   = Mat()
        mHsv        = Mat()
        mFiltered   = Mat()
        mThreshed   = Mat()
        mGrayHand   = Mat()
        mGrayThresh = Mat()
        mThreshed3D = Mat()
        disc = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))

        // Gestures
        for (i in 0 until GESTURE_HISTORY_LENGTH)
            prevGestures.add(Gesture.NONE)
        GestureClassifier.init()
    }

    /**
     * On receiving a frame:
     * - Draw rectangles to help user create a histogram of their hand, if not present.
     * - Create a histogram if user touches the screen while rectangles are visible.
     * - Return "space" if user makes SPACE gesture.
     * - Return "period" if user makes PERIOD gesture.
     * - Draw fingertip circles if user makes DRAW gesture.*
     * - If user presses volume down, they're done drawing.
     *   Send the points to a server for recognition and freeze the frame.
     */
    fun process(input: Mat): String? {
        this.mRgb = input
        if (shouldCreateHist) {
            createHist()
            shouldCreateHist = false
        }

        if (!histCreated) {
            drawRects()
            return null
        }

        if (clearScreen) {
            drawn_points.clear()
            clearScreen = false
        }

        // Mask away everything but the hand.
        val maxContour = applyHistMask()

        // Determine if we should draw anything on the screen or classify a letter.
        if (!freeze && sendDrawing) {
            freeze = true
            sendDrawing = false
            drawCircles()
            return LetterClassifier.classify(drawn_points, mRgb.rows(), mRgb.cols())
        } else if (freeze) {
            freezeCount += 1
            if (freezeCount > MAX_FREEZE_COUNT) {
                freeze = false
                freezeCount = 0
                drawn_points.clear()
            } else {
                // Indicate to user that screen is frozen.
                // They're free to move their finger to a new start position.
                Imgproc.rectangle(
                    mRgb, Point(0.0, 0.0), Point(30.0, 30.0), Scalar(0.0, 255.0, 0.0), -1)
            }
            return null
        } else {
            drawCircles()
        }
        return handleGesture(captureGesture(maxContour))
    }

    /**
     * Track fingers and identify gesture.
     */
    private fun captureGesture(maxContour: MatOfPoint?): Pair<Gesture, Point>? {
        if (freeze) {
            return null
        }

        // If no hand detected, return.
        if (maxContour == null) {
            return null
        }

        var gesture = Gesture.NONE
        var fingerPoint = Point()
        runBlocking {
            async {
                // Draw hand's centroid.
                val handCentroid = centroid(maxContour)
                if (handCentroid != null)  {
                    Imgproc.circle(mRgb, handCentroid, 5, Scalar(255.0, 0.0, 255.0), -1)
                }
            }

            // Determine hand gesture.
            async { gesture = GestureClassifier.classify(mRgb, maxContour) }

            // Find highest point in contour to use as fingertip.
            async { fingerPoint = highestPoint(maxContour) }
        }
        return Pair(gesture, fingerPoint)
    }

    /**
     * Handle actions for each type of gesture.
     */
    private fun handleGesture(vals: Pair<Gesture, Point>?): String? {
        // Check if params are null; if so we have no gesture to handle.
        if (vals == null) {
            updateGestureHistory(Gesture.NONE)
            return null
        }
        val gesture     = vals.first
        val fingerPoint = vals.second
        updateGestureHistory(gesture)

        // Prevent stray gesture readings.
        if (prevGestures.filterNot { it == gesture }.isNotEmpty()) {
            return null
        }

        when (gesture) {
            Gesture.SPACE  -> { freeze = true; drawn_points.clear(); return "<space>"  }
            Gesture.PERIOD -> { freeze = true; drawn_points.clear(); return "<period>" }
            Gesture.DRAW   -> {
                // Add new point to list of recently seen fingertip positions,
                // but only if the new point isn't too far from the last one.
                if (drawn_points.isNotEmpty()) {
                    val lastPoint = drawn_points.last
                    val epsilon = 90
                    if (Math.sqrt(Math.pow(lastPoint.y - fingerPoint.y, 2.0) + Math.pow(lastPoint.x - fingerPoint.x, 2.0))> epsilon) {
                        return null
                    }
                }
                drawn_points.add(fingerPoint)
                Imgproc.circle(mRgb, fingerPoint, 5, Scalar(0.0, 255.0, 255.0), -1)
                return null
            }
            else -> return null
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
        val histSrcList = arrayListOf(Mat(10 * NUM_RECTS, 10, hsv.type()))

        // Get several 10x10 squares of pixels from the rectangle regions
        for (i in 0 until NUM_RECTS) {
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

        // 2. Calculates the back projection of the hand-histogram.
        // Range of values for Hue: 0-179, Saturation: 0-255.
        Imgproc.calcBackProject(arrayListOf(mHsv), MatOfInt(0, 1), histogram, mFiltered,
            MatOfFloat(0.toFloat(), 180.toFloat(), 0.toFloat(), 256.toFloat()), 1.toDouble())

        // 3. Reduce some noise by blurring
        Imgproc.GaussianBlur(mFiltered, mFiltered, Size(7.0, 7.0), 0.0)

        // 4. Smooth the filtered image
        // a) Convolve the image using a disc to blur more.
        Imgproc.filter2D(mFiltered, mFiltered, -1, disc)

        // b) Applies fixed-level threshold to each array element to convert it to a binary image.
        Imgproc.threshold(mFiltered, mThreshed, 100.0, 255.0, Imgproc.THRESH_BINARY)

        // c) Merges thresh matrices to make 3 channels, since mRgb is a 3-channel matrix.
        Core.merge(arrayListOf(mThreshed, mThreshed, mThreshed), mThreshed3D)
        Core.bitwise_and(mRgb, mThreshed3D, mRgb)

        // 5. Filter out smaller contours
        val maxContour = maxContour() ?: return null
        val justContour = Mat(mRgb.rows(), mRgb.cols(), mRgb.type())
        Imgproc.drawContours(justContour, arrayListOf(maxContour), 0, Scalar(255.0, 255.0, 255.0), Imgproc.FILLED)

        // 6. Apply the final filter to the original image
        Core.bitwise_and(mRgb, justContour, mRgb)
//        Imgproc.medianBlur(mRgb, mRgb, 7)
        return maxContour
    }

    /**
     * Finds the highest point in the contour to find the drawing fingertip.
     */
    private fun highestPoint(contour: MatOfPoint): Point {
        // Min Row for actual fingertip
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
    }

    /**
     * From a list of contours, returns the contour with the greatest area.
     */
    private fun maxContour(): MatOfPoint? {
        // Convert RGB to grayscale
        Imgproc.cvtColor(mRgb, mGrayHand, Imgproc.COLOR_RGB2GRAY)

        // Applies fixed-level threshold to each array element so we can get contours from it.
        // This converts the grayscale image to a binary image.
        Imgproc.threshold(mGrayHand, mGrayThresh,0.0, 255.0, Imgproc.THRESH_BINARY)

        // Find contours in a binary image.
        val contours = arrayListOf<MatOfPoint>()
        Imgproc.findContours(mGrayThresh, contours, Mat(), Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
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
     * Draws several rectangles in a frame.
     */
    private fun drawRects(){
        // Get dimensions
        val rows = mRgb.rows()
        val cols = mRgb.cols()

        val x_margin = 45
        val y_margin = 0
        // Generate lists of points for rectangles
        hand_rect_row_nw = arrayListOf(
            x_margin + 2 * rows / 20, x_margin + 2 * rows / 20, x_margin + 2 * rows / 20,
            x_margin + 4 * rows / 20, x_margin + 4 * rows / 20, x_margin + 4 * rows / 20,
            x_margin + 6 * rows / 20, x_margin + 6 * rows / 20, x_margin + 6 * rows / 20,
            x_margin + 8 * rows / 20, x_margin + 8 * rows / 20, x_margin + 8 * rows / 20,
            x_margin + 10 * rows / 20, x_margin + 10 * rows / 20, x_margin + 10 * rows / 20,
            x_margin + 12 * rows / 20, x_margin + 12 * rows / 20, x_margin + 12 * rows / 20,
            x_margin + 14 * rows / 20, x_margin + 14 * rows / 20, x_margin + 14 * rows / 20,
            x_margin + 16 * rows / 20, x_margin + 16 * rows / 20, x_margin + 16 * rows / 20
        )
        hand_rect_col_nw = arrayListOf(
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20,
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20,
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20,
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20,
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20,
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20,
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20,
            y_margin + 9 * cols / 20, y_margin + 10 * cols / 20, y_margin + 11 * cols / 20
        )

        hand_rect_row_se = hand_rect_row_nw.map {it + 10}
        hand_rect_col_se= hand_rect_col_nw.map {it + 10}

        // Draw each rectangle
        for (i in 0 until NUM_RECTS) {
            Imgproc.rectangle(
                mRgb, Point(hand_rect_col_nw[i].toDouble(), hand_rect_row_nw[i].toDouble()),
                Point(hand_rect_col_se[i].toDouble(), hand_rect_row_se[i].toDouble()), Scalar(0.0, 255.0, 0.0), 1)
        }
    }

    /**
     * Draws circles in the last several spots that a finger was last seen.
     */
    private fun drawCircles() {
        for (i in 0 until drawn_points.size) {
            Imgproc.circle(mRgb, drawn_points[i], 5, Scalar(0.0, 255.0, 255.0), -1)
        }
    }
}
