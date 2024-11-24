import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class USBViewModel(private val context: Context) : ViewModel() {

    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging

    private val usbDataChannel = Channel<String>(Channel.UNLIMITED)
    private val logBuffer = mutableListOf<String>()
    private val batchInterval = 1000L // Buffer flush interval
    private val maxEntriesPerFile = 20000 // Max entries per file

    private var currentFileName: String = generateFileName()
    private var fileEntryCount = 0
    private var serialPort: UsbSerialPort? = null

    init {
        startPeriodicLogFlushing()
    }

    fun startLogging(usbManager: UsbManager) {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            Log.e("USBViewModel", "No USB device found")
            return
        }

        val driver = availableDrivers.first()
        val connection = usbManager.openDevice(driver.device)
        serialPort = driver.ports.first()

        if (connection == null) {
            Log.e("USBViewModel", "Failed to open connection")
            return
        }

        serialPort?.apply {
            open(connection)
            setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }

        _isLogging.value = true

        startSerialRead()
    }

    fun stopLogging() {
        _isLogging.value = false
        serialPort?.close()
    }

    fun startSerialRead() {
        val serialInputOutputManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                // Handle the incoming data
                val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val receivedData = String(data) // Convert bytes to a string if applicable
                usbDataChannel.trySend("$timestamp, $receivedData")
            }

            override fun onRunError(e: Exception) {
                Log.e("SerialIOManager", "Error in Serial InputOutput Manager", e)
            }
        })

        // Start the Serial InputOutput Manager in a separate thread
        Executors.newSingleThreadExecutor().submit(serialInputOutputManager)
    }


    private fun startPeriodicLogFlushing() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(batchInterval)
                if (logBuffer.isNotEmpty()) {
                    flushBufferToCsv()
                }
            }
        }
    }

    private fun flushBufferToCsv() {
        try {
            val logEntries = logBuffer.toList()
            logBuffer.clear()

            val file = context.getExternalFilesDir(null)?.resolve(currentFileName) ?: return
            file.bufferedWriter().use { writer ->
                logEntries.forEach { entry ->
                    writer.write(entry)
                    fileEntryCount++
                    if (fileEntryCount >= maxEntriesPerFile) {
                        currentFileName = generateFileName()
                        fileEntryCount = 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("USBViewModel", "Failed to flush logs to CSV", e)
        }
    }

    private fun generateFileName(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        return "EEG_Data_$timestamp.csv"
    }
}
