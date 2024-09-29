package com.example.sleeponsettracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.sleeponsettracking.ui.theme.SleepOnsetTrackingTheme


@Composable
fun StartScreen() {
    var earableConnected = true

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .padding(16.dp)
                .size(150.dp, 150.dp), Alignment.TopCenter) {

            //Info for connection state
            if (earableConnected) {
                Text(
                    "OpenEarable device connected!",
                    Modifier.padding(top = 20.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    "No OpenEarable device connected!",
                    Modifier.padding(top = 20.dp),
                    textAlign = TextAlign.Center
                )
            }

            //Connected Button
            Button(onClick = {}, Modifier.padding(top = 100.dp)) {
                if (earableConnected) {
                    Text("Connected")
                } else {
                    Text("Connect")
                }
            }
        }

        Box(Modifier.padding(16.dp) ,Alignment.TopCenter){
            Button(onClick = {}) {
                Text("Start FTT")
            }
        }
    }
}

@Preview
@Composable
fun StartScreenPreview() {
    SleepOnsetTrackingTheme {
        StartScreen()
    }
}