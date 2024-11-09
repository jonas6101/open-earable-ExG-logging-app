package com.example.sleeponsettracking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.sleeponsettracking.ui.theme.SleepOnsetTrackingTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SleepOnsetTrackingTheme {
                val context = LocalContext.current
                val bleViewmodel = BLEViewmodel(context)
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = Start
                ) {
                    composable<Start> {
                        StartScreen(
                            bleViewmodel = bleViewmodel,
                            onNavigateToRecording = { navController.navigate(route = Recording) })
                    }

                    composable<Recording> {
                        Recording(bleViewmodel)
                    }
                }

            }
        }
    }
}

@Serializable
object Recording

@Serializable
object Start






