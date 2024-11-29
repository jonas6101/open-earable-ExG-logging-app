import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class USBViewModel(private val context: Context) : ViewModel() {

    private val USB_PERMISSION_ACTION = "com.example.sleeponsettracking.USB_PERMISSION"

    private val _isLoggingActive = MutableStateFlow(false)
    val isLoggingActive: StateFlow<Boolean> = _isLoggingActive

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val logBuffer = mutableListOf<String>()
    private val dataBuffer = mutableListOf<String>()
    private val byteBuffer = mutableListOf<Byte>() // Persistent buffer for accumulating bytes

    private val batchInterval = 1000L // 1 second for periodic log flushing
    private val maxEntriesPerFile = 20000 // Maximum entries per file

    private var currentDataFileName: String = generateFileName("USB_Data")
    private var currentLogFileName: String = generateFileName("App_Logs")
    private var fileEntryCount: Int = 0
    private var serialPort: UsbSerialPort? = null
    private var serialInputOutputManager: SerialInputOutputManager? = null

    private val usbPermissionReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (USB_PERMISSION_ACTION == intent.action) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                if (granted) {
                    appendLog("Permission granted for USB device: ${device?.deviceName}")
                } else {
                    appendLog("Permission denied for USB device")
                }
            }
        }
    }

    init {
        // Register the BroadcastReceiver
        val filter = IntentFilter(USB_PERMISSION_ACTION)
        ContextCompat.registerReceiver(
            context,
            usbPermissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Start periodic log flushing
        startPeriodicLogFlushing()
    }

    fun requestUsbPermission(usbManager: UsbManager) {
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            appendLog("No USB devices detected 1")
        } else {
            devices.values.forEach { device ->
                appendLog("Detected USB device: ${device.deviceName}, Vendor ID: ${device.vendorId}, Product ID: ${device.productId}")
            }
        }

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)


        if (availableDrivers.isEmpty()) {
            appendLog("No USB drivers found Request")
            return
        }

        val driver = availableDrivers.first()
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(USB_PERMISSION_ACTION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
            appendLog("Requested permission for USB device")
        } else {
            appendLog("USB permission already granted")
        }
    }

    fun startLogging(usbManager: UsbManager) {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            appendLog("No USB drivers found Start")
            return
        }

        val driver = availableDrivers.first()
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            appendLog("Permission not granted. Cannot start logging.")
            return
        }

        openUsbDevice(driver, usbManager)
    }

    private fun openUsbDevice(driver: com.hoho.android.usbserial.driver.UsbSerialDriver, usbManager: UsbManager) {
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            appendLog("Failed to open USB device connection")
            return
        }

        serialPort = driver.ports.firstOrNull()
        try {
            serialPort?.apply {
                open(connection)
                setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                dtr = true
                rts = true
            }

            _isLoggingActive.value = true
            startSerialRead()

        } catch (e: Exception) {
            appendLog("Failed to open USB device: ${e.message}")
        }
    }

    private fun startSerialRead() {
        serialInputOutputManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                data.forEach { byte ->
                    if (byte == '\n'.code.toByte()) { // Check for newline
                        if (byteBuffer.size == 4) { // Process if buffer has exactly 4 bytes
                            val rawValue = ByteBuffer.wrap(byteBuffer.toByteArray())
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .float

                            val timestamp = dateFormat.format(Date())
                            val logEntry = "$timestamp,$rawValue\n"
                            dataBuffer.add(logEntry)

                            fileEntryCount++
                            checkFileRotation()
                        }
                        byteBuffer.clear() // Clear buffer
                    } else {
                        byteBuffer.add(byte) // Accumulate bytes
                    }
                }
            }

            override fun onRunError(e: Exception) {
                appendLog("Error in Serial InputOutput Manager: ${e.message}")
            }
        }).also {
            Executors.newSingleThreadExecutor().submit(it)
        }
    }

    fun stopLogging() {
        try {
            serialInputOutputManager?.stop()
            serialInputOutputManager = null
            serialPort?.close()
            serialPort = null
            _isLoggingActive.value = false
            appendLog("Logging stopped successfully.")
        } catch (e: Exception) {
            appendLog("Error while stopping logging: ${e.message}")
        }
    }

    private fun startPeriodicLogFlushing() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(batchInterval)
                if (dataBuffer.isNotEmpty()) {
                    flushBufferToFile(dataBuffer, currentDataFileName)
                    dataBuffer.clear()
                }
                if (logBuffer.isNotEmpty()) {
                    flushBufferToFile(logBuffer, currentLogFileName)
                    logBuffer.clear()
                }
            }
        }
    }

    private fun checkFileRotation() {
        if (fileEntryCount >= maxEntriesPerFile) {
            currentDataFileName = generateFileName("USB_Data")
            fileEntryCount = 0
        }
    }

    private fun flushBufferToFile(buffer: List<String>, fileName: String) {
        try {
            val resolver = context.contentResolver
            val fileUri = findOrCreateFile(resolver, fileName)
            fileUri?.let { uri ->
                resolver.openOutputStream(uri, "wa")?.use { outputStream ->
                    BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                        buffer.forEach { entry -> writer.write(entry) }
                    }
                }
            }
        } catch (e: Exception) {
            appendLog("Failed to write to file $fileName: ${e.message}")
        }
    }

    private fun findOrCreateFile(resolver: android.content.ContentResolver, fileName: String): android.net.Uri? {
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)

        resolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                return MediaStore.Files.getContentUri("external", id)
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/csv")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
        }

        return resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
    }

    private fun generateFileName(prefix: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        return "${prefix}_$timestamp.csv"
    }

    private fun appendLog(message: String) {
        val logEntry = "${dateFormat.format(Date())},$message\n"
        logBuffer.add(logEntry)
    }

    override fun onCleared() {
        context.unregisterReceiver(usbPermissionReceiver)
        super.onCleared()
    }
}
