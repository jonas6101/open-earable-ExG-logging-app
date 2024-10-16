package com.example.sleeponsettracking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juul.kable.Advertisement
import com.juul.kable.PlatformAdvertisement
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

private val SCAN_DURATION_MILLIS = TimeUnit.SECONDS.toMillis(10)

sealed class ScanStatus {
    object Stopped : ScanStatus()
    object Scanning : ScanStatus()
    data class Failed(val message: CharSequence) : ScanStatus()
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    val scanner = Scanner()

    private val found = hashMapOf<String, Advertisement>()

    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Stopped)
    val status = _status.asStateFlow()

    private val _advertisements = MutableStateFlow<List<Advertisement>>(emptyList())
    val advertisements = _advertisements.asStateFlow()

    fun start() {
        if (_status.value == ScanStatus.Stopped) return // Scan already in progress.
        _status.value = ScanStatus.Scanning

        viewModelScope.launch {
            withTimeoutOrNull(SCAN_DURATION_MILLIS) {
                scanner
                    .advertisements
                    .catch { cause -> _status.value = ScanStatus.Failed(cause.message ?: "Unknown error") }
                    .onCompletion { cause -> if (cause == null || cause is CancellationException) _status.value =
                        ScanStatus.Stopped
                    }
                    .collect { advertisement ->
                        found[advertisement.address] = advertisement
                        _advertisements.value = found.values.toList()
                    }
            }
        }
    }
}