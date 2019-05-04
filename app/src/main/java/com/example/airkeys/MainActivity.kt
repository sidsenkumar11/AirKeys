package com.example.airkeys

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import org.opencv.android.*
import org.opencv.core.Mat

class MainActivity : Activity(), CameraBridgeViewBase.CvCameraViewListener2 {

    // The tag is prepended to our log messages
    private val TAG = "----------------------- OUR LOG"
    private val MY_PERMISSIONS_REQUEST_CAMERA = 123

    // This is the camera view for OpenCV to use.
    lateinit var mOpenCvCameraView: CameraBridgeViewBase

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
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

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
        mOpenCvCameraView = findViewById(R.id.camera_activity_view)
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
//        mRgba = Mat(height, width, CvType.CV_8UC4)
//        mRgbaF = Mat(height, width, CvType.CV_8UC4)
//        mRgbaT = Mat(height, width, CvType.CV_8UC4)
    }

    override fun onCameraViewStopped() {
//        mRgba.release()
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame): Mat {
        return inputFrame.rgba()
//        mRgba = inputFrame!!.rgba()
//
//        // Rotate mRgba 90 degrees
//        Core.transpose(mRgba, mRgbaT)
//        Imgproc.resize(mRgbaT, mRgbaF, mRgbaF.size(), 0.0, 0.0, 0)
//        Core.flip(mRgbaF, mRgba, 1)
//        return mRgba
    }
}
