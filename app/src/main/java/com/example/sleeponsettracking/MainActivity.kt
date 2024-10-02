package com.example.sleeponsettracking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = Start
            ) {
                composable<Start> {
                    StartScreen(onNavigateToFTT = {navController.navigate(route = FingerTappingTask)},
                        onNavigateToBT = {navController.navigate(route = FingerTappingTask)})

                }
                composable<FingerTappingTask> {
                    FingerTappingTask()
                }
            }

            }
        }
    }
}

@Serializable
object FingerTappingTask

@Serializable
object Start






