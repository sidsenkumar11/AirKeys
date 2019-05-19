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
    private lateinit var hull: MatOfInt
    private lateinit var defects: MatOfInt4
    private lateinit var mGrayHand: Mat
    private lateinit var mGrayThresh: Mat
    private lateinit var mThreshed3D: Mat

    // One-time vars needed for histograms
    private lateinit var hand_rect_row_nw: List<Int>
    private lateinit var hand_rect_col_nw: List<Int>
    private lateinit var hand_rect_row_se: List<Int>
    private lateinit var hand_rect_col_se: List<Int>
    private lateinit var histogram: Mat
    private lateinit var disc: Mat

    // Finger-tracking
    private val drawn_points = LinkedList<Point>()
    private var freeze = false
    private var freezeCount = 0
    private const val maxFreezeCount = 50

    // Flag variables and constants
    private var emulated = true
    private var spaceEmitted = false
    private var freezeSpace = false
    var histCreated      = false
    var shouldCreateHist = false
    private const val numRects = 9
    private const val maxCaptures = 70
    private const val TAG = "-------------- OUR LOG"

    /**
     * Initialize the matrices just once to save memory.
     */
    fun init(emulated: Boolean) {
        // Histogram generation and filtering
        histogram   = Mat()
        mHsv        = Mat()
        mFiltered   = Mat()
        mThreshed   = Mat()
        mGrayHand   = Mat()
        mGrayThresh = Mat()
        mThreshed3D = Mat()
        disc = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        this.emulated = emulated

        // Fingertip Detection
        hull        = MatOfInt()
        defects     = MatOfInt4()
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
            spaceEmitted = captureGesture()
            if (!freezeSpace) drawCircles()
        }

        // If we detected a space, return that.
        if (!freezeSpace && spaceEmitted) {
            drawn_points.clear()
            freezeCount = 0
            freezeSpace = true
            return "<space>"
        }

        // If the finger doesn't appear to be moving, try classifying the current points as a character.
        if (!freeze && !freezeSpace && stationary()) {
            freeze = true
            return LetterClassifier.classify(drawn_points, mRgb.rows(), mRgb.cols())
        } else if (freeze || freezeSpace) {
            freezeCount += 1
            if (freezeCount > maxFreezeCount) {
                freezeCount = 0
                drawn_points.clear()
                freeze = false
                spaceEmitted = false
                freezeSpace = false
            } else {
                // Indicate to user that they're free to move their finger to a new start position
                Imgproc.rectangle(
                    mRgb, Point(0.0, 0.0), Point(30.0, 30.0), Scalar(0.0, 255.0, 0.0), -1)
            }
        }
        return null
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

        // 2. Calculates the back projection of the hand-histogram.
        // Range of values for Hue: 0-179, Saturation: 0-255.
        Imgproc.calcBackProject(arrayListOf(mHsv), MatOfInt(0, 1), histogram, mFiltered,
            MatOfFloat(0.toFloat(), 180.toFloat(), 0.toFloat(), 256.toFloat()), 1.toDouble())

        // 3. Reduce some noise by blurring
        Imgproc.GaussianBlur(mFiltered, mFiltered, Size(7.0, 7.0),0.0)

        // 4. Smooth the filtered image
        // a) Convolve the image using a disc to blur more.
        Imgproc.filter2D(mFiltered, mFiltered, -1, disc)

        // b) Applies fixed-level threshold to each array element to convert it to a binary image.
        Imgproc.threshold(mFiltered, mThreshed, 90.0, 255.0, Imgproc.THRESH_BINARY)

        // c) Merges thresh matrices to make 3 channels, since mRgb is a 3-channel matrix.
        Core.merge(arrayListOf(mThreshed, mThreshed, mThreshed), mThreshed3D)
        Core.bitwise_and(mRgb, mThreshed3D, mRgb)

        // 5. Filter out smaller contours
        val maxContour = maxContour() ?: return null
        val justContour = Mat(mRgb.rows(), mRgb.cols(), mRgb.type())
        Imgproc.drawContours(justContour, arrayListOf(maxContour), 0, Scalar(255.0, 255.0, 255.0), Imgproc.FILLED)

        // 6. Apply the final filter to the original image
        Core.bitwise_and(mRgb, justContour, mRgb)
        return maxContour
    }

    /**
     * Get location of finger tip and draw a circle there.
     * Assumes hand histogram has already been created.
     */
    private fun captureGesture(): Boolean {
        // 1. Mask away everything but the hand.
        val maxContour = applyHistMask()
        if (freeze) return false

        // If no hand detected, return.
        if (maxContour == null) {
            if (drawn_points.isNotEmpty()) drawn_points.removeFirst()
            return false
        }

        // 2. Calculate hand's centroid.
        val handCentroid = centroid(maxContour)
        if (handCentroid == null)  {
            if (drawn_points.isNotEmpty()) drawn_points.removeFirst()
            return false
        }

        // 3. Draw a circle at the centroid.
        Imgproc.circle(mRgb, handCentroid, 5, Scalar(255.0, 0.0, 255.0), -1)
        var handOpen = false
        var fingerPoint = Point()
        runBlocking {
            // 4. Determine if hand is open (gesture). If so, yield a "space" character.
            val handTask = async { handOpen = handOpen(maxContour) }
            // 5. Find highest point in contour to use as finger.
            val fingerTask = async { fingerPoint = highestPoint(maxContour) }
            handTask.await()
            fingerTask.await()
        }

        if (handOpen) {
            return true
        }

        // Add location to list of recently seen fingertip positions, but only if it makes sense.
        if (drawn_points.isNotEmpty()) {
            val lastPoint = drawn_points.last
            val epsilon = 90
            if (Math.sqrt(Math.pow(lastPoint.y - fingerPoint.y, 2.0) + Math.pow(lastPoint.x - fingerPoint.x, 2.0))> epsilon) {
                return false
            }
        }

        if (drawn_points.size < maxCaptures) {
            drawn_points.add(fingerPoint)
        } else {
            drawn_points.removeFirst()
            drawn_points.add(fingerPoint)
        }
        return false
    }

    /**
     * Uses convexity defects to determine if hand is open or closed.
     */
    private fun handOpen(contour: MatOfPoint): Boolean {
        // Use simplified convex hull (merging neighbors) to find defects
        val mergedHull = roughHull(contour)
        Imgproc.convexityDefects(contour, mergedHull, defects)
         drawDefects(contour)

        // Apply law of cosines to find out how many fingers open
        var count = 0
        for (i in 0 until defects.rows()) {
            val defect = defects.get(i, 0)
            val s = defect[0].toInt()
            val e = defect[1].toInt()
            val f = defect[2].toInt()

            val start = Point(contour.get(s, 0)[0], contour.get(s, 0)[1])
            val end = Point(contour.get(e, 0)[0], contour.get(e, 0)[1])
            val far = Point(contour.get(f, 0)[0], contour.get(f, 0)[1])

            val a = Math.sqrt(Math.pow(start.x-far.x, 2.0) + Math.pow(start.y-far.y, 2.0))
            val b = Math.sqrt(Math.pow(end.x-far.x, 2.0) + Math.pow(end.y-far.y, 2.0))
            val c = Math.sqrt(Math.pow(start.x-end.x, 2.0) + Math.pow(start.y-end.y, 2.0))

            // Law of cosines wasn't working too well because we get too many weird defects...
            // Just gonna use distances of sides to see if they're fingers for now...
            if (a > c && b > c && Math.abs(a-b) < 50) count += 1
//            val angle = Math.acos((Math.pow(b, 2.0) + Math.pow(c, 2.0) - Math.pow(a, 2.0)) /  (2 * b * c)) * (180.0 / Math.PI)
//            if (angle <= 50) count += 1 // <50 degrees, treat as finger
        }
        // Determine if hand is open
//        Log.e(TAG, "${count}, ${defects.rows()}")
        return count > 0
    }

    /**
     * Merges points on hull that are close to each other.
     */
    private fun roughHull(contour: MatOfPoint): MatOfInt {

        // Put contour points into map; mapping point to index
        val contourMap = HashMap<Point, Int>()
        for (i in 0 until contour.rows()) {
            val conPt = Point(contour.get(i, 0)[0], contour.get(i, 0)[1])
            contourMap[conPt] = i
        }

        // Compute convex hull of hand
        Imgproc.convexHull(contour, hull)

        val hullPts = mutableListOf<Point>()
        for (i in 0 until hull.size().height.toInt()) {
            val idx = hull.get(i, 0)[0].toInt()
            val hullPoint = Point(contour.get(idx, 0)[0], contour.get(idx, 0)[1])
            hullPts.add(hullPoint)
        }

        // Place points into partitions based on distances
        val epsilon = 90.0
        val distPartitions = mutableListOf<List<Point>>()
        val inserted = HashSet<Point>()
        for (i in 0 until hullPts.size) {
            // If this point was already inserted in a bucket, it doesn't go in any other buckets
            val pt1 = hullPts[i]
            if (inserted.contains(pt1)) continue

            // Otherwise, create a new bucket for this point
            val bucket = mutableListOf<Point>()
            inserted.add(pt1)
            bucket.add(pt1)

            // Go through all the other points
            for (j in 0 until hullPts.size) {
                // Skip same point and points that have already been bucketized
                val pt2 = hullPts[j]
                if (inserted.contains(pt2)) continue

                // Calculate distance and insert into bucket if it's small
                val dist = Math.sqrt(Math.pow(pt1.x-pt2.x, 2.0) + Math.pow(pt1.y-pt2.y, 2.0))
                if (dist < epsilon) {
                    bucket.add(pt2)
                    inserted.add(pt2)
                }
            }
            // Add this bucket to the list of all buckets
            distPartitions.add(bucket)
        }

        // From each partition, calculate the central point
        val betterHull = mutableListOf<Int>()
        for (i in 0 until distPartitions.size) {
            val ptList = distPartitions[i]
            val sumPt = ptList.fold(Point(0.0, 0.0)) {
                    oldPt, newPt -> Point(oldPt.x + newPt.x, oldPt.y + newPt.y)
            }
            val centerPt = Point((sumPt.x / ptList.size), (sumPt.y / ptList.size))

            // Get closest point in partition to central point
            var repPt = ptList[0]
            var oldDist = Math.sqrt(Math.pow(repPt.x - centerPt.x ,2.0) + Math.pow(repPt.y - centerPt.y ,2.0))
            for (j in 1 until ptList.size) {
                val newDist = Math.sqrt(Math.pow(centerPt.x - ptList[j].x ,2.0) + Math.pow(centerPt.y - ptList[j].y ,2.0))
                if (oldDist > newDist) {
                    oldDist = newDist
                    repPt = ptList[j]
                }
            }
            val idx = contourMap[repPt]
            if (idx != null) betterHull.add(idx)
        }
        val ret = MatOfInt()
        ret.fromList(betterHull)
        return ret
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
     * Draw defect points.
     */
    private fun drawDefects(contour: MatOfPoint) {
        for (i in 0 until defects.rows()) {
            val idx = defects.get(i, 0)[2].toInt()
            val point = Point(contour.get(idx, 0)[0], contour.get(idx, 0)[1])
            Imgproc.circle(mRgb, point, 5, Scalar(255.0, 255.0, 0.0),-1 )
        }
    }
}
