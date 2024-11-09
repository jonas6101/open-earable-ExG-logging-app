package com.example.sleeponsettracking

import android.app.Activity
import android.content.ContentValues
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Recording(bleViewmodel: BLEViewmodel) {
    val context = LocalContext.current

    // Observe the bleData state from the ViewModel
    val bleData by bleViewmodel.bleData.collectAsState()

    DisposableEffect(Unit) {
        bleViewmodel.setRecordingScreenActive(true)
        onDispose {
            bleViewmodel.setRecordingScreenActive(false)
        }
    }


    DisposableEffect(Unit) {
        val window = context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        insetsController.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onDispose {
            insetsController.apply {
                show(WindowInsetsCompat.Type.statusBars())
                show(WindowInsetsCompat.Type.navigationBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }
    }

    /*
    // Write the Bluetooth data to CSV whenever it's received
    LaunchedEffect(bleData) {

        if (bleData is BLEData.DataReceived) {
            val dateFormat =
                SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) // Timestamp format
            // Unpack the BLE data (assuming it's in ByteArray format)
            val byteBuffer = ByteBuffer.wrap((bleData as BLEData.DataReceived).data)
                .order(ByteOrder.LITTLE_ENDIAN)
            val readings = FloatArray(5) { byteBuffer.getFloat() } // Assuming 5 float values
            val currentTimestamp = Date() // Current time for the notification


            // Calculate the timestamp for each reading


            // Convert the data to µV
            val rawData = readings.joinToString(",")

            // Format the timestamp for logging
            val formattedTimestamp = dateFormat.format(currentTimestamp)

            // Create a log entry in CSV format: "timestamp, raw_data, filtered_data"
            val logEntry = "$formattedTimestamp,$rawData\n"
            Log.d("fuck", logEntry)
            // Use the logToCsv function to append the data to a CSV file
            logToCsv(context, "OpenEarableEEG_BLE.csv", logEntry)


        }
    }
    *
     */

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Text(
            "Your data is being recorded. Please do not lock the phone!",
            color = Color.DarkGray,
            textAlign = TextAlign.Center
        )
    }
}

// Function to log the current time to a CSV file in the global Documents directory
fun logToCsv(context: Context, fileName: String, logEntry: String) {
    try {
        val resolver = context.contentResolver

        // Check if the file already exists
        val existingUri = findFileInDocuments(resolver, fileName)

        if (existingUri != null) {
            // If file exists, append to it
            resolver.openOutputStream(existingUri, "wa")?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                    writer.write(logEntry)
                }
            }
        } else {
            // If file does not exist, create a new file and write the first log entry
            val contentValues = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/csv")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
            }

            val newUri =
                resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            newUri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                        writer.write(logEntry)
                    }
                }
            } ?: run {
                Log.e("CSVLogError", "Failed to create CSV file URI")
            }
        }
    } catch (e: Exception) {
        Log.e("CSVLogError", "Error logging time to CSV", e)
    }
}

// Function to find an existing file in the Documents directory
fun findFileInDocuments(
    resolver: android.content.ContentResolver,
    fileName: String
): android.net.Uri? {
    val projection =
        arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME)
    val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
    val selectionArgs = arrayOf(fileName)

    resolver.query(
        MediaStore.Files.getContentUri("external"),
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor: Cursor? ->
        if (cursor != null && cursor.moveToFirst()) {
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val fileId = cursor.getLong(idIndex)
            return MediaStore.Files.getContentUri("external", fileId)
        }
    }
    return null
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}