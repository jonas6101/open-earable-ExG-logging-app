package com.example.sleeponsettracking


import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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

    private var currentFileName: String = generateFileName()
    private var lastFileHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

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
                // Check if the hour has changed
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (currentHour != lastFileHour) {
                    currentFileName = generateFileName() // Update file name for the new hour
                    lastFileHour = currentHour
                }
                if (logBuffer.isNotEmpty()) {
                    flushBufferToCsv(context, currentFileName)
                }
            }
        }
    }

    // Generate a file name based on the current hour
    private fun generateFileName(): String {
        val dateTime = SimpleDateFormat("yyyy-MM-dd_HH", Locale.getDefault()).format(Date())
        return "OpenEarableEEG_$dateTime.csv"
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

    fun connectToDevice(advertisement : Advertisement) {
        viewModelScope.launch(Dispatchers.IO) {
            try {

                _bleState.value = BLEState.Connecting

                val p = peripheral(advertisement) {
                    onServicesDiscovered {
                        requestMtu(512)
                    }
                    phy = Phy.Le2M
                }

                // Observe connection stat
                p.let { l ->
                    viewModelScope.launch {
                        l.state.collect { state ->
                            if(state == com.juul.kable.State.Connected) {
                                _bleState.value = BLEState.Connected
                            }else{
                                _bleState.value = BLEState.Disconnected
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
                        val channelResult = bleDataChannel.trySend(BLEData.DataReceived(data))
                        // Convert ByteArray to a comma-separated decimal string
                        val byteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                        val readings = FloatArray(5) { byteBuffer.getFloat() }
                        val rawData = readings.joinToString(",")
                        Log.d("channel", rawData)
                    }
                }

            } catch (e: Exception) {
                // Handle any errors in connection, discovery, or observation
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
    private fun flushBufferToCsv(context: Context, fileName: String) {
        try {
            val logEntries = logBuffer.toList() // Take a snapshot of the current buffer
            logBuffer.clear() // Clear the buffer before writing to avoid blocking new entries

            logEntries.forEach { logEntry ->
                logToCsv(context, fileName, logEntry)
            }
        } catch (e: Exception) {
            Log.e("CSVLogError", "Error flushing logs to CSV", e)
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
}