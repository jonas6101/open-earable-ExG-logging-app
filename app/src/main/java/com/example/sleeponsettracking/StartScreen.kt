package com.example.sleeponsettracking


import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sleeponsettracking.ui.theme.SleepOnsetTrackingTheme


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    bleViewmodel: BLEViewmodel,
    onNavigateToRecording: () -> Unit
) {
    val connectionState by bleViewmodel.bleState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Box(
            modifier = Modifier.fillMaxSize(), // Fill the top bar size
            contentAlignment = Alignment.Center // Center the title
        ) {
            Text("OpenEarableExG EEG Logging", color = Color.White)
        } },

            colors = TopAppBarColors(containerColor = Color.Black, actionIconContentColor = Color.Black, scrolledContainerColor = Color.Black, navigationIconContentColor = Color.Black, titleContentColor = Color.White)

        )}
    ) { innerPadding ->

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(50.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Box(
                Modifier
                    .size(150.dp, 150.dp), Alignment.TopCenter
            ) {

                when (connectionState) {
                    is BLEState.Idle -> {
                        Image(
                            painter = painterResource(id = R.drawable.logo), // Replace with your PNG resource name
                            contentDescription = "My PNG Image",
                            modifier =Modifier.size(70.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    is BLEState.Scanning -> {
                        Text(text = "Scanning...",Modifier.padding(top = 40.dp), textAlign = TextAlign.Center, color = Color.White)
                        CircularProgressIndicator()
                    }
                    is BLEState.DeviceFound -> {
                        Text(text = "Device Found",Modifier.padding(top = 20.dp), textAlign = TextAlign.Center, color = Color.White)
                    }
                    is BLEState.Connecting -> {
                        Text(text = "Connecting...",Modifier.padding(top = 20.dp), textAlign = TextAlign.Center, color = Color.White)
                        CircularProgressIndicator()
                    }
                    is BLEState.Connected -> {
                        Text(text = "Connected",Modifier.padding(top = 20.dp), textAlign = TextAlign.Center, color = Color.White)
                    }
                    is BLEState.Disconnected -> {
                        Text(text = "Disconnected",Modifier.padding(top = 20.dp), textAlign = TextAlign.Center, color = Color.White)
                    }
                    is BLEState.Error -> {
                        Text(text = "Error: ${(connectionState as BLEState.Error).message}",Modifier.padding(top = 20.dp), textAlign = TextAlign.Center, color = Color.White)
                    }}


                //Connected Button
                Button(
                    onClick = { bleViewmodel.startScan("OpenEarable-57C7") },
                    Modifier
                        .padding(top = 100.dp)
                        .matchParentSize()
                ) {
                    if (connectionState == BLEState.Connected) {
                        Text("Connected")
                    } else {
                        Text("Connect")
                    }
                }
            }

            Box(Modifier.size(150.dp, 150.dp), Alignment.TopCenter) {
                Button(onClick = onNavigateToRecording, Modifier.matchParentSize()) {
                    Text("Start Recording")
                }
            }
        }
    }
}
