package com.example.airkeys

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.HashMap
import java.util.HashSet

enum class Gesture {
    NONE, DRAW, SPACE, BACKSPACE, PERIOD, CLEAR
}

object GestureClassifier {

    private lateinit var hull: MatOfInt
    private lateinit var defects: MatOfInt4
    private lateinit var mRgb: Mat

    fun init() {
        hull = MatOfInt()
        defects = MatOfInt4()
    }

    /**
     * Uses convexity defects to determine gesture.
     */
    fun classify(mRgb: Mat, contour: MatOfPoint): Gesture {
        this.mRgb = mRgb

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
//            if (angle > 60) count += 1 // <50 degrees, treat as finger
        }

        // Convert finger count to gesture
//        Log.e(TAG, "${count}, ${defects.rows()}")
        when (count) {
            0 -> return Gesture.DRAW
            1 -> return Gesture.SPACE
            2 -> return Gesture.PERIOD
            else -> return Gesture.NONE
        }
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
