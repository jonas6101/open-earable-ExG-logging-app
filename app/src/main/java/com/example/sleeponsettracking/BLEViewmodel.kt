package com.example.sleeponsettracking

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH
import no.nordicsemi.android.ble.data.Data
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BLEViewModel(private val context: Context) : ViewModel() {

    private val _bleState = MutableStateFlow<BLEState>(BLEState.Idle)
    val bleState: StateFlow<BLEState> = _bleState.asStateFlow()

    private val bleDataChannel = Channel<BLEData.DataReceived>(Channel.UNLIMITED)
    private val logBuffer = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val batchInterval = 1000L // Write logs every second
    private val maxEntriesPerFile = 20000
    private var fileEntryCount = 0
    private var currentFileName: String = generateFileName()

    private var bleManager: MyBLEManager? = null

    private val _isRecordingScreenActive = MutableStateFlow(false)
    fun setRecordingScreenActive(isActive: Boolean) {
        _isRecordingScreenActive.value = isActive
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            bleDataChannel.receiveAsFlow().collect { bleData ->
                if (_isRecordingScreenActive.value) {
                    processAndBatchData(bleData)
                }
            }
        }

        startPeriodicLogFlushing()
    }

    fun startScan(deviceAddress: String) {
        bleManager = MyBLEManager(context, bleDataChannel)
        val device = bleManager?.getDeviceFromAddress(deviceAddress)
        if (device != null) {
            connectToDevice(device)
        } else {
            _bleState.value = BLEState.Error("Device not found")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _bleState.value = BLEState.Connecting
                bleManager?.let { manager ->
                    manager.connect(device)
                        .useAutoConnect(false)
                        .retry(3, 100)
                        .done {
                            Log.d("BLE", "Connected successfully!")
                            _bleState.value = BLEState.Connected
                        }
                        .fail { _, status ->
                            Log.e("BLE", "Connection failed: $status")
                            _bleState.value = BLEState.Disconnected
                        }
                        .enqueue()
                }
            } catch (e: Exception) {
                Log.e("BLE", "Error connecting to device: ${e.message}")
                _bleState.value = BLEState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun processAndBatchData(bleData: BLEData.DataReceived) {
        val rawData = bleData.readings.joinToString(",")
        val logEntry = "${bleData.timestamp},$rawData\n"
        logBuffer.add(logEntry)
    }

    private fun startPeriodicLogFlushing() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(batchInterval)
                if (logBuffer.isNotEmpty()) {
                    flushBufferToCsv(context)
                }
            }
        }
    }

    private fun flushBufferToCsv(context: Context) {
        try {
            prepareFile(context)
            val logEntries = logBuffer.toList()
            logBuffer.clear()
            val fileUri = createOrFindFile(context)

            fileUri?.let { uri ->
                context.contentResolver.openOutputStream(uri, "wa")?.use { outputStream ->
                    BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                        logEntries.forEach { logEntry ->
                            writer.write(logEntry)
                            fileEntryCount++
                            if (fileEntryCount >= maxEntriesPerFile) {
                                currentFileName = generateFileName()
                                fileEntryCount = 0
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CSVLogError", "Error writing logs to CSV: ${e.message}")
        }
    }

    private fun createOrFindFile(context: Context): Uri? {
        val resolver = context.contentResolver
        val existingFileUri = queryFileInDocuments(resolver, currentFileName)
        return existingFileUri ?: createNewFile(context, currentFileName)
    }

    private fun queryFileInDocuments(resolver: android.content.ContentResolver, fileName: String): Uri? {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        resolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val fileId = cursor.getLong(idColumn)
                return MediaStore.Files.getContentUri("external", fileId)
            }
        }
        return null
    }

    private fun createNewFile(context: Context, fileName: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/csv")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
        }
        return resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
    }

    private fun generateFileName(): String {
        val timestamp = SimpleDateFormat("HH-mm-ss", Locale.getDefault()).format(Date())
        return "OpenEarableEEG_$timestamp.csv"
    }

    private fun prepareFile(context: Context) {
        if (fileEntryCount == 0 || createOrFindFile(context) == null) {
            currentFileName = generateFileName()
            fileEntryCount = 0
        }
    }
}

class MyBLEManager(context: Context, private val bleDataChannel: Channel<BLEData.DataReceived>) : BleManager(context) {
    private var myCharacteristic: android.bluetooth.BluetoothGattCharacteristic? = null

    override fun getGattCallback(): BleManagerGattCallback {
        return object : BleManagerGattCallback() {
            override fun isRequiredServiceSupported(gatt: android.bluetooth.BluetoothGatt): Boolean {
                val service = gatt.getService(UUID.fromString("0029d054-23d0-4c58-a199-c6bdc16c4975"))
                myCharacteristic = service?.getCharacteristic(UUID.fromString("20a4a273-c214-4c18-b433-329f30ef7275"))
                return myCharacteristic != null
            }

            override fun initialize() {
                requestConnectionPriority(CONNECTION_PRIORITY_HIGH).enqueue()
                myCharacteristic?.let {
                    setNotificationCallback(it)
                        .with { _, data -> handleDataReceived(data) }
                }
                enableNotifications(myCharacteristic).enqueue()
            }

            override fun onServicesInvalidated() {
                myCharacteristic = null
            }
        }
    }

    private fun handleDataReceived(data: Data) {
        val rawBytes = data.value ?: return
        if (rawBytes.size < 20) {
            Log.e("BLE", "Invalid packet size: ${rawBytes.size}")
            return
        }

        val byteBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val timestamp = byteBuffer.int.toUInt()
        val readings = FloatArray(4)
        for (i in readings.indices) {
            readings[i] = byteBuffer.float
        }

        Log.d("BLE", "Timestamp: $timestamp, Readings: ${readings.joinToString()}")
        val formattedTimestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp.toLong()))
        bleDataChannel.trySend(BLEData.DataReceived(readings, formattedTimestamp))
    }

    fun getDeviceFromAddress(address: String): BluetoothDevice? {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        return try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e("BLE", "Invalid MAC address: $address")
            null
        }
    }
}
