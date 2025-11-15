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
import androidx.lifecycle.viewmodel.compose.viewModel // <-- ADD THIS IMPORT
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smartnavigation.ui.theme.SmartNavigationTheme // <-- Ensure this is imported

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Use your project's theme
            SmartNavigationTheme {
                val navController = rememberNavController()

                // Create the ViewModel that will be shared
                val sharedViewModel: SharedNavViewModel = viewModel()

                NavHost(navController, startDestination = "home") {
                    composable("home") { HomeScreen(navController) }

                    // Pass the ViewModel to both screens
                    composable("dead_reckoning") { DeadReckoningScreen(sharedViewModel) }
                    composable("slam") { SlamScreen(sharedViewModel) }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(navController: NavController) {
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
            ) { Text("SLAM (AR Pin)") } // <-- Changed text
        }
    }
}