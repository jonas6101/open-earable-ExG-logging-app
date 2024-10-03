package com.example.sleeponsettracking

import android.content.ContentValues
import android.graphics.Paint.Align
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import java.io.IOException
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.sleeponsettracking.ui.theme.SleepOnsetTrackingTheme
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import androidx.compose.ui.platform.LocalContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter


@Composable
fun FingerTappingTask() {
    val context = LocalContext.current
    val currentTime = getCurrentTime()

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Button(onClick = {

            logTimeToCsv(context = context, "test.csv", "$currentTime\n")
        }, modifier = Modifier.fillMaxSize()) {
            Text("Tap")
        }
    }
}

// Function to get the current time in a human-readable format
fun getCurrentTime(): String {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return dateFormat.format(Date())
}

// Function to log the current time to a CSV file in the global Documents directory
fun logTimeToCsv(context: Context, fileName: String, logEntry: String) {
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

            val newUri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
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
fun findFileInDocuments(resolver: android.content.ContentResolver, fileName: String): android.net.Uri? {
    val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME)
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

