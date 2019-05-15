package com.example.airkeys

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.*
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.opencv.android.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class MainActivity : Activity(), CameraBridgeViewBase.CvCameraViewListener2 {

    // This is the camera view for OpenCV to use.
    private lateinit var mOpenCvCameraView: CameraBridgeViewBase
    private val MY_PERMISSIONS_REQUEST_CAMERA = 123
    private var emulated = false
    private lateinit var mRgb: Mat

    /**
     * Set camera view and load OpenCV libraries.
     */
    private fun init() {
        // Check if we're in an emulator
        emulated = Build.FINGERPRINT.contains("generic")

        // Show camera and set its listener
        setContentView(R.layout.show_camera)
        mOpenCvCameraView = findViewById(R.id.camera_activity_view)
        mOpenCvCameraView.visibility = SurfaceView.VISIBLE
        mOpenCvCameraView.setCvCameraViewListener(this)
        mOpenCvCameraView.setMaxFrameSize(640, 480)

        // Load OpenCV libraries
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

        // Keep screen on; don't automatically lock screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize matrices
        FrameHandler.init(emulated)
        this.mRgb = Mat()
    }

    /**
     * After the OpenCV libraries are initialized and loaded, OpenCV can run a function for you.
     * We define this function such that it enables the camera.
     */
    private val mLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> mOpenCvCameraView.enableView()
                else -> super.onManagerConnected(status)
            }
        }
    }

    /**
     * Callback function for permissions granted/denied.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_CAMERA -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // Permission was granted.
                    init()
                } else {
                    // Permission was denied.
                    Toast.makeText(this, "Camera permission needed for AirKeys!", Toast.LENGTH_LONG).show()
                    finish()
                }
                return
            }
            // Add other 'when' lines to check for other permissions this app might request.
            else -> {}
        }
    }

    /**
     * Callback function for when screen is tapped.
     * Screen tap indicates that user has aligned their hand over rectangles and wishes to create a histogram of it.
     */
    fun screenTapped(view: View) {
        if (!FrameHandler.histCreated) {
            FrameHandler.shouldCreateHist = true
            FrameHandler.histCreated = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // This is run when app is first created.

        // Check if app has camera permissions. If not, request them.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), MY_PERMISSIONS_REQUEST_CAMERA)
        } else {
            init()
        }
    }

    override fun onPause() {
        super.onPause() // This is run when the app is paused (goes to background, another app runs, etc)
        if (::mOpenCvCameraView.isInitialized) mOpenCvCameraView.disableView()
    }

    override fun onDestroy() {
        super.onDestroy() // This is run when the app is closed from the background
        if (::mOpenCvCameraView.isInitialized) mOpenCvCameraView.disableView()
    }

    override fun onCameraViewStarted(width: Int, height: Int) {}
    override fun onCameraViewStopped() {}

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        // Get frame and strip alpha channel.
        Imgproc.cvtColor(inputFrame.rgba(), mRgb, Imgproc.COLOR_RGBA2RGB)

        /*
            OpenCV stupidity - https://github.com/opencv/opencv/issues/11118
            For some emulators, frame.rgba() will actually return bgra so the colors will be blueish.
            We'll make sure it's always RGB before continuing.
        */
        if (emulated) {
            Imgproc.cvtColor(mRgb, mRgb, Imgproc.COLOR_BGR2RGB)
            // Flip image for display if using a front-facing camera (eg. laptop)
            Core.flip(mRgb, mRgb, 1)
        }

        // Process frame and toast character if found.
        val character = FrameHandler.process(mRgb)
        if (character != null) {
            runOnUiThread {
                Toast.makeText(this, character, Toast.LENGTH_LONG).show()
            }
        }
        return mRgb
    }
}
