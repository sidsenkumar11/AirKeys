package com.example.airkeys

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.opencv.core.Point
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

object LetterClassifier {
    private const val TAG = "-----------------------"
    private const val IP_ADDR = "104.236.98.126"
    private const val PORT    = 5000

    fun classify(points: List<Point>, rows: Int, cols: Int): String {
        try {
            // Send JSON of image data over network to server
            val s = Socket(IP_ADDR, PORT)
            val outWriter = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
            outWriter.write(pointsToJson(points, rows, cols))
            outWriter.flush()

            // Retrieve letter response
            val inReader = BufferedReader(InputStreamReader(s.getInputStream()))
            val response = inReader.readLine()

            // Close socket and file descriptors
            outWriter.close()
            inReader.close()
            s.close()
            return response
        } catch (ex: Exception) {
            // TODO: Handle exceptions
            Log.e(TAG, "FAILED SENDING MATRIX OVER NETWORK!")
            return "Failed to classify over network"
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
