package com.example.airkeys

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import org.opencv.android.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if(OpenCVLoader.initDebug()){
            Toast.makeText(this, "OpenCV successfully loaded", Toast.LENGTH_SHORT).show();
        }else{
            Toast.makeText(this, "OpenCV cannot be loaded", Toast.LENGTH_SHORT).show();
        }
    }
}
