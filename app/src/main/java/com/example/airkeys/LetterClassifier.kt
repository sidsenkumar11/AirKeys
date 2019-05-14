package com.example.airkeys

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.opencv.core.CvType.CV_8UC3
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.Socket

object LetterClassifier {
    private const val TAG = "--------------------------------------------------------"
    private lateinit var image: Mat
    private const val IP_ADDR = "104.236.98.126"
    private const val PORT    = 5000

    fun classify(points: List<Point>, rows: Int, cols: Int) {
        // Create a matrix with circles and points
        image = Mat(rows, cols, CV_8UC3, Scalar(0.0,0.0,0.0))
        drawCircles(points)

        // Flatten to 1 row, row*col columns
        image = image.reshape(0, 1)

        // Send JSON of image data over network to server
        val serialMat = matToJson(image)
        sendData(serialMat)
    }

    private fun sendData(outMsg: String) {
        try {
            // Creating new socket connection to the IP (first parameter) and its opened port (second parameter)
            val s = Socket(IP_ADDR, PORT)

            // Initialize output stream to write message to the socket stream
            val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))

            // Write message to stream
            out.write(outMsg)

            // Flush the data from the stream to indicate end of message
            out.flush()

            // Close the output stream
            out.close()

            // Close the socket connection
            s.close()
        } catch (ex: Exception) {
            //:TODO Handle exceptions
            Log.e(TAG, "FAILED SENDING MATRIX OVER NETWORK!")
        }
    }

    private fun drawCircles(points: List<Point>) {
        for (i in 0 until points.size) {
            Imgproc.circle(image, points[i], 5, Scalar(255.0, 255.0, 255.0), -1)
        }
    }

    private fun matToJson(mat: Mat): String {
        val obj = JsonObject()

        if (mat.isContinuous) {
            val cols = mat.cols()
            val rows = mat.rows()
            val elemSize = mat.elemSize().toInt()

            // Get data from first row
            val data = ByteArray(cols * rows * elemSize)
            mat.get(0, 0, data)
            obj.addProperty("rows", mat.rows())
            obj.addProperty("cols", mat.cols())
            obj.addProperty("type", mat.type())

            // We cannot set binary data to a json object, so:
            // Encoding data byte array to Base64.
            val dataString = String(Base64.encode(data, Base64.DEFAULT))

            obj.addProperty("data", dataString)

            val gson = Gson()
            return gson.toJson(obj)
        } else {
            Log.e("----------------------------------", "Mat not continuous.")
        }
        return "{}"
    }
}
