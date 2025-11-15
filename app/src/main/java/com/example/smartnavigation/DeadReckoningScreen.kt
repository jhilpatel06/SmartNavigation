package com.example.smartnavigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// --- PDR Constants ---
private const val STRIDE_LENGTH_METERS = 0.7f // Average stride length
private const val STEP_THRESHOLD = 10.5f      // Accelerometer magnitude to trigger a step
private const val STEP_DELAY_MS = 250         // Minimum time between steps

@Composable
fun DeadReckoningScreen(viewModel: SharedNavViewModel) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    // Position is now only (X, Z)
    val position = remember { mutableStateOf(0f to 0f) }
    val pathPoints = remember { mutableStateListOf(0f to 0f) }
    var isRunning by remember { mutableStateOf(false) }

    val pinPosition = viewModel.pinPosition.value

    val sensorListener = remember {
        object : SensorEventListener {
            private var rotationMatrix = FloatArray(9)
            private var orientationAngles = FloatArray(3)
            private var lastStepTime = 0L

            override fun onSensorChanged(event: SensorEvent) {
                if (!isRunning) return

                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val (x, y, z) = event.values
                        val magnitude = sqrt(x * x + y * y + z * z)

                        val currentTime = System.currentTimeMillis()
                        if (magnitude > STEP_THRESHOLD && (currentTime - lastStepTime) > STEP_DELAY_MS) {
                            lastStepTime = currentTime
                            onStepDetected()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            private fun onStepDetected() {
                // orientationAngles[0] is the azimuth (yaw) in radians
                val heading = orientationAngles[0]

                // Update position based on heading and stride length
                val (oldX, oldZ) = position.value
                val newX = oldX + STRIDE_LENGTH_METERS * cos(heading)
                val newZ = oldZ - STRIDE_LENGTH_METERS * sin(heading) // Subtract Z because screen Y is down

                position.value = newX to newZ
                pathPoints.add(newX to newZ)
                if (pathPoints.size > 2000) pathPoints.removeAt(0)
            }
        }
    }

    DisposableEffect(isRunning) {
        if (isRunning) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        } else {
            sensorManager.unregisterListener(sensorListener)
        }
        onDispose { sensorManager.unregisterListener(sensorListener) }
    }

    Column(Modifier.padding(16.dp)) {
        Text("Dead Reckoning", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { isRunning = true }, Modifier.weight(1f)) { Text("Start") }
            Button(onClick = { isRunning = false }, Modifier.weight(1f)) { Text("Stop") }
            Button(onClick = {
                position.value = 0f to 0f
                pathPoints.clear()
                pathPoints.add(0f to 0f) // Reset path to origin
            }, Modifier.weight(1f)) { Text("Reset") }
        }
        Spacer(Modifier.height(10.dp))
        Text("DR Position: X=${"%.2f".format(position.value.first)}  Z=${"%.2f".format(position.value.second)}")

        if (pinPosition != null) {
            Text(
                "SLAM Pin: X=${"%.2f".format(pinPosition.first)}  Z=${"%.2f".format(pinPosition.second)}",
                color = Color(0xFF006400) // Dark Green
            )
        } else {
            Text("No SLAM pin set. Go to SLAM screen to set one.", color = Color.Gray)
        }

        TrajectoryCanvas(
            points = pathPoints.toList(),
            pin = pinPosition
        )
    }
}

@Composable
fun TrajectoryCanvas(points: List<Pair<Float, Float>>, pin: Pair<Float, Float>?) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .padding(8.dp)
    ) {
        if (points.isEmpty() && pin == null) return@Canvas

        val allPoints = points.toMutableList()
        if (pin != null) {
            allPoints.add(pin)
        }

        val xs = allPoints.map { it.first }
        val ys = allPoints.map { it.second }
        val minX = (xs.minOrNull() ?: -1f) - 1f
        val maxX = (xs.maxOrNull() ?: 1f) + 1f
        val minY = (ys.minOrNull() ?: -1f) - 1f
        val maxY = (ys.maxOrNull() ?: 1f) + 1f

        val rangeX = if (abs(maxX - minX) < 0.1f) 2f else (maxX - minX)
        val rangeY = if (abs(maxY - minY) < 0.1f) 2f else (maxY - minY)

        val scale = min(size.width / rangeX, size.height / rangeY) * 0.9f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val midX = (minX + maxX) / 2f
        val midY = (minY + maxY) / 2f

        // Draw the Dead Reckoning path
        for (i in 0 until points.size - 1) {
            val (x1, y1) = points[i]
            val (x2, y2) = points[i]
            drawLine(
                color = Color.Blue,
                start = Offset(cx + (x1 - midX) * scale, cy + (y1 - midY) * scale),
                end = Offset(cx + (x2 - midX) * scale, cy + (y2 - midY) * scale),
                strokeWidth = 4f
            )
        }

        // Draw the current DR position
        if (points.isNotEmpty()) {
            val (lx, ly) = points.last()
            drawCircle(Color.Red, 8f, Offset(cx + (lx - midX) * scale, cy + (ly - midY) * scale))
        }

        // Draw the "true" SLAM pin location
        if (pin != null) {
            val (px, py) = pin
            val pinX = cx + (px - midX) * scale
            val pinY = cy + (py - midY) * scale
            drawCircle(Color(0xFF006400), 12f, Offset(pinX, pinY), style = Stroke(width = 5f))
            drawLine(Color(0xFF006400), Offset(pinX - 10, pinY), Offset(pinX + 10, pinY), strokeWidth = 5f)
            drawLine(Color(0xFF006400), Offset(pinX, pinY - 10), Offset(pinX, pinY + 10), strokeWidth = 5f)
        }
    }
}
