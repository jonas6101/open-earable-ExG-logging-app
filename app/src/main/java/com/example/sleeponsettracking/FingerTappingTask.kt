package com.example.sleeponsettracking

import android.os.Environment
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import java.io.IOException
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.sleeponsettracking.ui.theme.SleepOnsetTrackingTheme
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
fun FingerTappingTask() {
    Button(onClick = { logDateTimeToCSV()}) {
        Modifier.fillMaxSize()
    }
}

fun logDateTimeToCSV() {
    // Get current date and time
    val currentDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    // Get external storage directory (You can adjust this to your app's private storage)
    val externalStorageDir = Environment.getExternalStorageDirectory()
    val file = File(externalStorageDir, "log.csv")

    try {
        val fileWriter = FileWriter(file, true) // 'true' to append to the file
        fileWriter.append("$currentDateTime\n")
        fileWriter.flush()
        fileWriter.close()
        Log.d("CSV_LOG", "Logged: $currentDateTime")
    } catch (e: IOException) {
        e.printStackTrace()
        Log.e("CSV_LOG", "Error writing to CSV: ${e.message}")
    }
}

@Preview
@Composable
fun FingerTappingTaskPreview(){
    SleepOnsetTrackingTheme {
        FingerTappingTask()
    }
}