package com.example.smartnavigation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = "home") {
                    composable("home") { HomeScreen(navController) }
                    composable("dead_reckoning") { DeadReckoningScreen() }
                    composable("slam") { SlamScreen() }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(navController: androidx.navigation.NavController) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Smart Navigation", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { navController.navigate("dead_reckoning") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Dead Reckoning") }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { navController.navigate("slam") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SLAM (Camera)") }
        }
    }
}
