package com.example.airkeys

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
<<<<<<< Updated upstream
import android.widget.Toast
import org.opencv.android.*
=======
import android.view.SurfaceView
import android.widget.Toast
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.JavaCameraView
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
>>>>>>> Stashed changes

class MainActivity : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2 {

    lateinit var cameraViewBridgeBase: CameraBridgeViewBase;
    lateinit var mat1: Mat;
    lateinit var mat2: Mat;
    lateinit var mat3: Mat;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
<<<<<<< Updated upstream
        if (OpenCVLoader.initDebug()) {
            Toast.makeText(this, "openCv successfully loaded", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "openCv cannot be loaded", Toast.LENGTH_SHORT).show()
=======

        cameraViewBridgeBase = findViewById(R.id.myCameraView) as JavaCameraView;
        cameraViewBridgeBase.setVisibility(SurfaceView.VISIBLE);
        cameraViewBridgeBase.setCvCameraViewListener(this);
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame?): Mat {
        mat1 = inputFrame!!.rgba();
        return mat1;
    }

    override fun onCameraViewStopped() {
        mat1.release();
        mat2.release();
        mat3.release();
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        mat1 = Mat(width, height, CvType.CV_8UC4);
        mat2 = Mat(width, height, CvType.CV_8UC4);
        mat3 = Mat(width, height, CvType.CV_8UC4);
    }

    override fun onPause() {
        super.onPause()
        if (cameraViewBridgeBase != null) {
            cameraViewBridgeBase.disableView();
        }
    }

    override fun onResume() {
        super.onResume()
        if (OpenCVLoader.initDebug()) {
            Toast.makeText(applicationContext, "OpenCV loaded successfully", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(applicationContext, "Could not load OpenCV", Toast.LENGTH_SHORT).show();
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (cameraViewBridgeBase != null) {
            cameraViewBridgeBase.disableView();
>>>>>>> Stashed changes
        }
    }
}
