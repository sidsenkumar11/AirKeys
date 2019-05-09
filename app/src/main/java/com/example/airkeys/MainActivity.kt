package com.example.airkeys

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import org.opencv.android.*
import org.opencv.core.*
import org.opencv.core.Core.*
import org.opencv.imgproc.Imgproc
import kotlin.collections.arrayListOf
import java.util.LinkedList
import org.opencv.core.Core
import java.nio.file.Files.size
import android.view.Surface.ROTATION_270
import android.view.Surface.ROTATION_180
import android.view.Surface.ROTATION_90
import android.view.Surface.ROTATION_0
import android.support.v4.view.ViewCompat.getDisplay
import android.support.v4.view.ViewCompat.getRotation
import android.view.Surface


class MainActivity : Activity(), CameraBridgeViewBase.CvCameraViewListener2 {

    // The tag is prepended to our log messages
    private val TAG = "----------------------- OUR LOG"
    private val MY_PERMISSIONS_REQUEST_CAMERA = 123

    // This is the camera view for OpenCV to use.
    lateinit var mOpenCvCameraView: CameraBridgeViewBase

    // Current frame RGB matrix
    lateinit var mRgba: Mat
    lateinit var mRgbaT: Mat
    lateinit var mRgbaF: Mat

    // After the OpenCV libraries are initialized and loaded, OpenCV can run a function for you.
    // We define this function such that it enables the camera.
    private val mLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> {
                    mOpenCvCameraView.enableView()
                    Log.w(TAG, "OpenCV loaded and camera view enabled!")
                }
                else -> super.onManagerConnected(status)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_CAMERA -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
        }
    }

    // This is called when the app is first run
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.w(TAG, "called onCreate")
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Check if app has camera permissions. If not, request them.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), MY_PERMISSIONS_REQUEST_CAMERA)

            // Permission is not granted
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), MY_PERMISSIONS_REQUEST_CAMERA)

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        } else {
            // Permission has already been granted
        }

        // Show camera and set its listener
        setContentView(R.layout.show_camera)
        mOpenCvCameraView = findViewById(R.id.camera_activity_view) as JavaCameraView
        mOpenCvCameraView.visibility = SurfaceView.VISIBLE
        mOpenCvCameraView.setCvCameraViewListener(this)
    }

    // This is run when the app is paused (goes to background, another app runs, etc)
    override fun onPause() {
        super.onPause()
        mOpenCvCameraView.disableView()
    }

    // This is run when the app is brought back from the background (and apparently right after onCreate() too).
    override fun onResume() {
        Log.w(TAG, "called onResume")
        super.onResume()

        // initDebug() - Loads and initializes OpenCV library from within current application package.
        //             - According to docs, initDebug() should only be used in development and not in production
        if (OpenCVLoader.initDebug()) {
            // This sets the callback function's "connected" status to SUCCESS
            // This is how we let OpenCV know that loading/initializing is done, so it can run the callback function.
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        } else {
            // initAsync() - Loads OpenCV library using OpenCV Manager
            //             - According to docs, initAsync() is the preferred way of loading OpenCV
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, mLoaderCallback)
        }
    }

    // This is run when the app is closed from the background
    override fun onDestroy() {
        super.onDestroy()
        mOpenCvCameraView.disableView()
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        mRgba = Mat(height, width, CvType.CV_8UC4)
        mRgbaT = Mat(height, width, CvType.CV_8UC4)
        mRgbaF = Mat(height, width, CvType.CV_8UC4)
        hand_hist = Mat()
    }

    override fun onCameraViewStopped() {
        mRgba.release()
    }

    // ----------------------------------------------------------------------------
    // Hand tracking stuff
    // ----------------------------------------------------------------------------

    private var hand_hist_created: Boolean = false
    lateinit var hand_hist: Mat
    val traverse_point = LinkedList<Point>()
    val total_rectangle = 9
    lateinit var hand_rect_one_x: List<Int>
    lateinit var hand_rect_one_y: List<Int>
    lateinit var hand_rect_two_x: List<Int>
    lateinit var hand_rect_two_y: List<Int>

    /**
     * Resizes the frame dimensions by some percentage.
     */
    fun rescale_frame(wpercent: Int = 130, hpercent: Int = 130) {
        val width = mRgba.width() * wpercent / 100.0
        val height = mRgba.height() * hpercent / 100.0

        // TODO: Should we use same matrix for destination? Should fx = 0 and fy = 0?
        Imgproc.resize(mRgba, mRgba, Size(width, height), 0.0, 0.0, Imgproc.INTER_AREA)
    }

    /**
     * Callback function for when screen is tapped.
     * Screen tap indicates that user has aligned their hand over rectangles and wishes to create a histogram of it.
     */
    fun screenTapped(view: View) {
        if (!hand_hist_created) {
            hand_histogram()
            hand_hist_created = true
        }
    }

    /**
     * Create the hand histogram from the current frame content.
     */
    fun hand_histogram() {
        // Convert BGR to HSV
        val hsv = Mat()
        Imgproc.cvtColor(mRgba, hsv, Imgproc.COLOR_BGR2HSV)

        // Create a list of 10x10 squares to base a histogram off of.
        val squares = arrayListOf<Mat>(Mat(10 * total_rectangle, 10, hsv.type()))

        // Get total_rectangles 10x10 squares of pixels from the rectangle regions
        // to produce a histogram
        for (i in 0 until total_rectangle) {
            val roi = Rect(hand_rect_one_x[i], hand_rect_one_y[i], 10, 10)
            // TODO: Should we clone the submatrix? Is ths function correct at all?
            val tmp = squares[0].submat(Rect(0, i*10, 10, 10))
            hsv.submat(roi).copyTo(tmp)

//            for (row in 0 until 10) {
//                squares[0][i * 10] = hsv.submat(roi, )
//                //squares[0] = hsv.submat(roi).get(row, col)
//            }
//            squares.add(hsv.submat(roi).clone())
        }
        Imgproc.calcHist(squares, MatOfInt(0, 1), Mat(), hand_hist, MatOfInt(10, 10), MatOfFloat(0.toFloat(), 10.toFloat(), 0.toFloat(), 256.toFloat()))
        normalize(hand_hist, hand_hist, 0.0, 255.0, NORM_MINMAX)
    }

    /**
     * Masks the current image with the hand histogram to figure out where the finger tip is.
     */
    fun hist_masking(): Mat {
        // 1. Convert RGB to HSV (hue, saturation, value).
        // Done because RGB contains information about brightness, and we don't want that.
        val hsv = Mat()
        Imgproc.cvtColor(mRgba, hsv, Imgproc.COLOR_BGR2HSV)

        // 2. Calculates the back projection of the hand-histogram (but only the HS channels since we want to strip brightness)..
        // Range of values for Hue: 0-179, Value: 0-255.
        val dst = Mat()
        Imgproc.calcBackProject(arrayListOf(hsv), MatOfInt(0, 1), hand_hist, dst, MatOfFloat(0.toFloat(), 180.toFloat(), 0.toFloat(), 256.toFloat()), 1.toDouble())

        // 3. Smooth the filtered image
        // Create a structuring element for morphological operations. We want a disk-like image.
        val disc = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(31.0, 31.0))

        // Convolve the image with the structuring element
        Imgproc.filter2D(dst, dst, -1, disc)

        // Applies fixed-level threshold to each array element. We'll be converting it to a binary image.
        val thresh = Mat()
        Imgproc.threshold(dst, thresh, 150.0, 255.0, Imgproc.THRESH_BINARY_INV)

        // Merges three thresh matrix to make 4 channels, since mRgba is a 4-channel matrix.
        val thresh4D = Mat()
        merge(arrayListOf(thresh, thresh, thresh, thresh), thresh4D)

        // 4. In the original image, filter out all pixels that aren't part of the hand.
        val retArr = Mat()
        bitwise_and(mRgba, thresh4D, retArr)
//        Log.i(TAG, "ABOUT TO DUMP")
//        Log.i(TAG, retArr.dump())
//        Log.i(TAG, "FINISHED DUMP")
        return retArr
    }

    /**
     * Finds contours in an input image. Contours are useful for object detection/recognition.
     */
    fun contours(hist_mask_image: Mat): List<MatOfPoint> {
        // Convert to grayscale
        val gray_hist_mask_image = Mat()
        Imgproc.cvtColor(hist_mask_image, gray_hist_mask_image, Imgproc.COLOR_BGR2GRAY)

        // Applies fixed-level threshold to each array element so we can get contours from it.
        // This converts the grayscale image to a binary image.
        val thresh = Mat()
        Imgproc.threshold(gray_hist_mask_image, thresh,0.0, 255.0, Imgproc.THRESH_BINARY)

        // Find contours in a binary image.
        val conts = arrayListOf<MatOfPoint>()
        Imgproc.findContours(thresh, conts, Mat(), Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
        return conts
    }

    /**
     * Given a list of contours, returns the contour with the greatest area.
     */
    fun max_contour(contour_list: List<MatOfPoint>): MatOfPoint {
        var max_i = 0
        var max_area = 0.0
        for (i in 0 until contour_list.size-1) {
            val cnt = contour_list[i]
            val area_cnt = Imgproc.contourArea(cnt)
            if (area_cnt > max_area) {
                max_area = area_cnt
                max_i = i
            }
        }
        return contour_list[max_i]
    }

    /**
     * Computes the centroid of the hand-contour.
     */
    fun centroid(max_contour: Mat): Point? {
        val moment = Imgproc.moments(max_contour)
        if (moment.m00 != 0.0) {
            val cx = moment.m10 / moment.m00
            val cy = moment.m01 / moment.m00
            return Point(cx, cy)
        }
        return null
    }

//    fun argMax(vals: Mat): Int {
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
    fun farthest_point(defects: MatOfInt4, contour: MatOfPoint, centroid: Point?): Point? {
        if (centroid == null) return null

        // MatOfInt4: 1 element matrix at 0,0 -> n rows, 1 column matrix -> each row has 4 ints
//
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
        val nums = contour.get(0, 0)
        return Point(nums[0], nums[1])
    }

    /**
     * Draws circles in the last several spots that a finger was last seen.
     * The circles decrease in size to indicate they were seen longer ago.
     */
    fun draw_circles() {
        for (i in 0 until traverse_point.size) {

            Imgproc.circle(mRgba, traverse_point[i], 5 - (5 * i * 3) / 100, Scalar(0.0, 255.0, 255.0), -1)
        }
    }

    /**
     * Track finger movements and draws trailing circles.
     * Assumes hand histogram has already been created.
     */
    fun manage_image_opr() {

        // 1. Mask away everything but the hand.
        val hist_mask_image = hist_masking()

        // 2. Identify the finger tip.
        val contour_list = contours(hist_mask_image)
        Log.w(TAG, "Size of contour list: " + contour_list.size.toString())

        // Get the max area contour and calculate its centroid.
        val max_cont: MatOfPoint = max_contour(contour_list)
        val cnt_centroid = centroid(max_cont)

        // Draw a circle at the centroid.
        Imgproc.circle(mRgba, cnt_centroid, 5, Scalar(255.0, 0.0, 255.0), -1)

        // Compute convex hull of hand figure.
        val hull = MatOfInt()
        Imgproc.convexHull(max_cont, hull)

        // Compute convexity defects (spaces between hull and actual hand contour).
        val defects = MatOfInt4()
        Imgproc.convexityDefects(max_cont, hull, defects)

        // Compute furthest point from a defect to the centroid. This point is assumed to be the finger tip.
        val far_point = farthest_point(defects, max_cont, cnt_centroid)
        if (far_point != null) {
            Log.w(TAG, "Centroid : " + cnt_centroid.toString() + ", farthest Point : " + far_point.toString())

            // Create a circle at the finger tip.
            Imgproc.circle(mRgba, far_point, 5, Scalar(0.0, 0.0, 255.0), -1)

            // Add location to list of recently seen fingertip positions.
            if (traverse_point.size < 20) {
                traverse_point.add(far_point)
            } else {
                traverse_point.removeFirst()
                traverse_point.add(far_point)
            }
        }

        // Draw trailing circles.
        draw_circles()
    }

    /**
     * Draws several rectangles in a frame.
     */
    fun draw_rect() {
        // Get dimensions
        val rows = mRgba.rows()
        val cols = mRgba.cols()

        // Generate lists of points for rectangles
//        hand_rect_one_x = arrayListOf(6 * rows / 20, 6 * rows / 20, 6 * rows / 20, 9 * rows / 20, 9 * rows / 20, 9 * rows / 20, 12 * rows / 20,
//            12 * rows / 20, 12 * rows / 20)
//        hand_rect_one_y = arrayListOf(9 * cols / 20, 10 * cols / 20, 11 * cols / 20, 9 * cols / 20, 10 * cols / 20, 11 * cols / 20, 9 * cols / 20,
//            10 * cols / 20, 11 * cols / 20)
        val biasX = 4
        val biasY = -6
        val divider = 15
        hand_rect_one_x = arrayListOf(
            (biasX+6) * rows / divider, (biasX+6) * rows / divider, (biasX+6) * rows / divider,
            (biasX+9) * rows / divider, (biasX+9) * rows / divider, (biasX+9) * rows / divider,
            (biasX+12) * rows / divider, (biasX+12) * rows / divider, (biasX+12) * rows / divider
        )
        hand_rect_one_y = arrayListOf(
            (biasY+9) * cols / divider, (biasY+10) * cols / divider, (biasY+11) * cols / divider,
            (biasY+9) * cols / divider, (biasY+10) * cols / divider, (biasY+11) * cols / divider,
            (biasY+9) * cols / divider, (biasY+10) * cols / divider, (biasY+11) * cols / divider
        )

        hand_rect_two_x = hand_rect_one_x.map {it + 10} // original value -- > it + 10
        hand_rect_two_y = hand_rect_one_y.map {it + 10} // original value -- > it + 10

        // Draw each rectangle
        for (i in 0 until total_rectangle) {
            Imgproc.rectangle(mRgba, Point(hand_rect_one_x[i].toDouble(), hand_rect_one_y[i].toDouble()),
                Point(hand_rect_two_x[i].toDouble(), hand_rect_two_y[i].toDouble()), Scalar(0.0, 255.0, 0.0), 1)
        }
    }

    /**
     * On receiving a frame, first flip it to normal viewing direction.
     * Then, either draw rectangles to help a user create a histogram of their hand
     * or draw circles following their finger tips if the histogram already exists.
     */
    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        mRgba = inputFrame.rgba()

        // Rotate mRgba 90 degrees
//        Core.transpose(mRgba, mRgbaT)
//        Imgproc.resize(mRgbaT, mRgbaF, mRgbaF.size(), 0.0, 0.0, 0)
//        Core.flip(mRgbaF, mRgba, 1)


        // If we haven't created a histogram of skin samples yet, draw rectangles to help users align their hands where we'll sample.
        // Otherwise, draw finger-tracing circles.
        if (hand_hist_created) {
//            manage_image_opr()
            return hist_masking()
        } else {
            draw_rect()
        }

        return mRgba
    }
}
