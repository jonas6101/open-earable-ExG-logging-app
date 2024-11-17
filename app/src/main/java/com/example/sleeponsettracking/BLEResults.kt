package com.example.sleeponsettracking

sealed class BLEState {
    object Idle : BLEState()
    object Scanning : BLEState()
    object DeviceFound: BLEState()
    object Connecting : BLEState()
    object Connected : BLEState()
    object Disconnected : BLEState()
    data class Error(val message: String) : BLEState()
}

sealed class BLEData {
    object NoData : BLEData() // When no data is available yet

    data class DataReceived(val data: ByteArray, val timestamp: String) : BLEData()
}