package com.example.airkeys

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.opencv.core.Point
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.Socket

object LetterClassifier {
    private const val TAG = "--------------------------------------------------------"
    private const val IP_ADDR = "104.236.98.126"
    private const val PORT    = 5000

    fun classify(points: List<Point>, rows: Int, cols: Int) {
        // Send JSON of image data over network to server
        try {
            val s = Socket(IP_ADDR, PORT)
            val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
            out.write(pointsToJson(points, rows, cols))
            out.flush()
            out.close()
            s.close()
        } catch (ex: Exception) {
            // TODO: Handle exceptions
            Log.e(TAG, "FAILED SENDING MATRIX OVER NETWORK!")
        }
    }

    private fun pointsToJson(points: List<Point>, rows: Int, cols: Int): String {
        val obj = JsonObject()
        obj.addProperty("rows", rows)
        obj.addProperty("cols", cols)
        obj.addProperty("points", "[" + points.joinToString { "(${it.x},${it.y})" } + "]")
        return Gson().toJson(obj)
    }
}
//
//private fun drawCircles(points: List<Point>) {
//    for (i in 0 until points.size) {
//        Imgproc.circle(image, points[i], 5, Scalar(0.0, 0.0, 0.0), -1)
//    }
//}
