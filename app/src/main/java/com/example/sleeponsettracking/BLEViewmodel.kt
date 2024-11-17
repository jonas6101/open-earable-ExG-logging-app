package com.example.sleeponsettracking


import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Advertisement
import com.juul.kable.Filter
import com.juul.kable.Phy
import com.juul.kable.Scanner
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class BLEViewmodel(private val context: Context) : ViewModel() {

    // State to track connection and scanning
    private val _isRecordingScreenActive = MutableStateFlow(false)
    private val _bleState = MutableStateFlow<BLEState>(BLEState.Idle)
    val bleState: StateFlow<BLEState> = _bleState.asStateFlow()
    private val bleDataChannel = Channel<BLEData.DataReceived>(Channel.UNLIMITED)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) // Timestamp format
    private val logBuffer = mutableListOf<String>()
    private val batchInterval = 1000L // Write every 1000 ms (1 second)
    private val maxEntriesPerFile = 20000 // Maximum entries per file

    private var currentFileName: String = generateFileName()
    private var fileEntryCount: Int = 0 // Track the number of entries written to the current file

    // State to track incoming data from BLE device
    private val _bleData = MutableStateFlow<BLEData>(BLEData.NoData)
    val bleData: StateFlow<BLEData> = _bleData.asStateFlow()

    init {
        // Start a background coroutine to process BLE data from the Channel
        viewModelScope.launch(Dispatchers.IO) {
            bleDataChannel.receiveAsFlow().collect { bleData ->
                if (_isRecordingScreenActive.value) {
                    processAndBatchData(bleData)
                }
            }
        }

        // Start a coroutine to periodically write logs to CSV
        startPeriodicLogFlushing()
    }

    // Periodically flush buffer to CSV
    private fun startPeriodicLogFlushing() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(batchInterval)
                if (logBuffer.isNotEmpty()) {
                    flushBufferToCsv(context)
                }
            }
        }
    }

    // Generate a file name based on the current time
    private fun generateFileName(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        return "OpenEarableEEG_$timestamp.csv"
    }

    // Toggle this flag when the recording screen is opened or closed
    fun setRecordingScreenActive(isActive: Boolean) {
        _isRecordingScreenActive.value = isActive
    }

    // Function to scan for devices and update state
    fun startScan(deviceName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _bleState.value = BLEState.Scanning
            try {
                val advertisement = Scanner {
                    filters {
                        match {
                            name = Filter.Name.Exact(deviceName)
                        }
                    }
                }.advertisements.firstOrNull() ?: throw Exception("Device not found")

                _bleState.value = BLEState.DeviceFound
                connectToDevice(advertisement)

            } catch (e: Exception) {
                _bleState.value = BLEState.Error(e.message ?: "Unknown error during scanning")
            }
        }
    }

    fun connectToDevice(advertisement: Advertisement) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _bleState.value = BLEState.Connecting

                val p = peripheral(advertisement) {
                    onServicesDiscovered {
                        requestMtu(512)
                    }
                    phy = Phy.Le2M
                }

                // Observe connection state
                p.let { peripheral ->
                    viewModelScope.launch {
                        peripheral.state.collect { state ->
                            _bleState.value = when (state) {
                                com.juul.kable.State.Connected -> BLEState.Connected
                                else -> BLEState.Disconnected
                            }
                        }
                    }
                }

                // Connect to the device
                p.connect()

                // Discover services
                val services = p.services ?: error("Services have not been discovered")
                val characteristic = services
                    .first { it.serviceUuid == UUID.fromString("0029d054-23d0-4c58-a199-c6bdc16c4975") }
                    .characteristics
                    .first { it.characteristicUuid == UUID.fromString("20a4a273-c214-4c18-b433-329f30ef7275") }

                // Start observing characteristic notifications
                p.observe(characteristic).collect { data ->
                    if (_isRecordingScreenActive.value) {
                        bleDataChannel.trySend(BLEData.DataReceived(data))
                    }
                }

            } catch (e: Exception) {
                _bleState.value = BLEState.Error(e.message ?: "Unknown error during connection")
            }
        }
    }

    // Collect log data in a buffer instead of writing immediately
    private fun processAndBatchData(bleData: BLEData.DataReceived) {
        val byteBuffer = ByteBuffer.wrap(bleData.data).order(ByteOrder.LITTLE_ENDIAN)
        val readings = FloatArray(5) { byteBuffer.getFloat() }
        val rawData = readings.joinToString(",")
        val formattedTimestamp = dateFormat.format(Date())
        val logEntry = "$formattedTimestamp,$rawData\n"

        logBuffer.add(logEntry)
    }

    // Flush the buffer to the CSV file
    private fun flushBufferToCsv(context: Context) {
        try {
            prepareFile(context)

            val logEntries = logBuffer.toList() // Take a snapshot of the current buffer
            logBuffer.clear() // Clear the buffer before writing to avoid blocking new entries

            val resolver = context.contentResolver
            val fileUri = findFileInDocuments(resolver, currentFileName) ?: createNewFile(context, currentFileName)

            fileUri?.let { uri ->
                resolver.openOutputStream(uri, "wa")?.use { outputStream ->
                    BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                        logEntries.forEach { logEntry ->
                            writer.write(logEntry)
                            fileEntryCount++

                            // Create a new file if the entry limit is reached
                            if (fileEntryCount >= maxEntriesPerFile) {
                                currentFileName = generateFileName()
                                fileEntryCount = 0
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CSVLogError", "Error flushing logs to CSV", e)
        }
    }

    // Prepare the file for writing
    private fun prepareFile(context: Context) {
        if (fileEntryCount == 0 || findFileInDocuments(context.contentResolver, currentFileName) == null) {
            currentFileName = generateFileName()
            fileEntryCount = 0
        }
    }

    // Function to log to a CSV file
    private fun createNewFile(context: Context, fileName: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/csv")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
        }
        return resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
    }

    // Function to find an existing file in the Documents directory
    private fun findFileInDocuments(
        resolver: android.content.ContentResolver,
        fileName: String
    ): android.net.Uri? {
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
}
