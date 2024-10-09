package com.example.sleeponsettracking


import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benasher44.uuid.uuidFrom
import com.juul.kable.Advertisement
import com.juul.kable.Filter
import com.juul.kable.Scanner
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class BLEViewmodel() : ViewModel() {

    // State to track connection and scanning
    private val _bleState = MutableStateFlow<BLEState>(BLEState.Idle)
    val bleState: StateFlow<BLEState> = _bleState.asStateFlow()

    // State to track incoming data from BLE device
    private val _bleData = MutableStateFlow<BLEData>(BLEData.NoData)
    val bleData: StateFlow<BLEData> = _bleData.asStateFlow()

    // Function to scan for devices and update state
    fun startScan(deviceName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _bleState.value = BLEState.Scanning
            try {
                Log.d("test", "_______")
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

                val p = peripheral(advertisement)

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
                    .first { it.serviceUuid == uuidFrom("00001815-0000-1000-8000-00805f9b34fb") }
                    .characteristics
                    .first { it.characteristicUuid == uuidFrom("00002a56-0000-1000-8000-00805f9b34fb") }

                // Start observing characteristic notifications
                p.observe(characteristic).collect { data ->
                    // Update the EEG data in the MutableStateFlow
                    _bleData.value = BLEData.DataReceived(data.toList())
                }

            } catch (e: Exception) {
                // Handle any errors in connection, discovery, or observation
                _bleState.value = BLEState.Error(e.message ?: "Unknown error during connection")
            }
        }

    }
}