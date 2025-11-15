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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadReckoningScreen() {

    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    // State variables
    val position = remember { mutableStateListOf(0f, 0f, 0f) }
    val velocity = remember { mutableStateListOf(0f, 0f, 0f) }
    val pathPoints = remember { mutableStateListOf<Pair<Float, Float>>() }
    var isRunning by remember { mutableStateOf(false) }

    val sensorListener = remember {
        object : SensorEventListener {

            var accelRaw = FloatArray(3)
            var rotationVector = FloatArray(4)
            var lastTimestamp = 0L
            val alpha = 0.60f // smoothing strength

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {

                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val r = event.values
                        accelRaw[0] = alpha * accelRaw[0] + (1 - alpha) * r[0]
                        accelRaw[1] = alpha * accelRaw[1] + (1 - alpha) * r[1]
                        accelRaw[2] = alpha * accelRaw[2] + (1 - alpha) * r[2]

                        if (isRunning) integrate(event.timestamp)
                    }

                    Sensor.TYPE_ROTATION_VECTOR -> {
                        rotationVector = if (event.values.size >= 4)
                            event.values.clone()
                        else {
                            val x = event.values[0]
                            val y = event.values[1]
                            val z = event.values[2]
                            floatArrayOf(
                                x, y, z,
                                sqrt(max(0f, 1f - x * x - y * y - z * z))
                            )
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            // FIXED DRIFT LOGIC
            private fun integrate(ts: Long) {
                if (lastTimestamp == 0L) {
                    lastTimestamp = ts
                    return
                }

                val dt = (ts - lastTimestamp) / 1e9f
                lastTimestamp = ts

                if (dt <= 0f || dt > 0.15f) return

                // Convert accel to world coordinates
                val aWorld = deviceToWorldAccel(accelRaw, rotationVector)

                // HARD THRESHOLD — removes micro movements
                val ax = if (abs(aWorld[0]) < 0.08f) 0f else aWorld[0]
                val ay = if (abs(aWorld[1]) < 0.08f) 0f else aWorld[1]
                val az = if (abs(aWorld[2]) < 0.08f) 0f else aWorld[2]

                // Velocity update
                velocity[0] += ax * dt
                velocity[1] += ay * dt
                velocity[2] += az * dt

                // DAMPING → prevents infinite sliding
                velocity[0] *= 0.90f
                velocity[1] *= 0.90f
                velocity[2] *= 0.90f

                // Position update
                position[0] += velocity[0] * dt
                position[1] += velocity[1] * dt
                position[2] += velocity[2] * dt

                // DEADZONE → kills tiny drift
                if (abs(position[0]) < 0.002f) position[0] = 0f
                if (abs(position[1]) < 0.002f) position[1] = 0f
                if (abs(position[2]) < 0.002f) position[2] = 0f

                pathPoints.add(position[0] to position[1])
                if (pathPoints.size > 2000) pathPoints.removeAt(0)
            }

            private fun deviceToWorldAccel(accel: FloatArray, q: FloatArray): FloatArray {
                val qx = q[0].toDouble()
                val qy = q[1].toDouble()
                val qz = q[2].toDouble()
                val qw = q[3].toDouble()

                val vx = accel[0].toDouble()
                val vy = accel[1].toDouble()
                val vz = accel[2].toDouble()

                val ix = qw * vx + qy * vz - qz * vy
                val iy = qw * vy + qz * vx - qx * vz
                val iz = qw * vz + qx * vy - qy * vx
                val iw = -qx * vx - qy * vy - qz * vz

                val wx = ix * qw - iw * qx + iy * qz - iz * qy
                val wy = iy * qw - iw * qy + iz * qx - ix * qz
                val wz = iz * qw - iw * qz + ix * qy - iy * qx

                return floatArrayOf(wx.toFloat(), wy.toFloat(), wz.toFloat())
            }
        }
    }

    // Register / unregister sensors
    DisposableEffect(isRunning) {
        if (isRunning) {
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.also {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            sensorManager.unregisterListener(sensorListener)
        }

        onDispose {
            sensorManager.unregisterListener(sensorListener)
        }
    }

    // UI
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Dead Reckoning", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { isRunning = true }, Modifier.weight(1f)) { Text("Start") }
                Button(onClick = { isRunning = false }, Modifier.weight(1f)) { Text("Stop") }
                Button(onClick = {
                    position[0] = 0f; velocity[0] = 0f
                    position[1] = 0f; velocity[1] = 0f
                    position[2] = 0f; velocity[2] = 0f
                    pathPoints.clear()
                }, Modifier.weight(1f)) { Text("Reset") }
            }

            Spacer(Modifier.height(10.dp))

            Text("Position: X=${position[0]}  Y=${position[1]}  Z=${position[2]}")

            TrajectoryCanvas(points = pathPoints.toList())
        }
    }
}

@Composable
fun TrajectoryCanvas(points: List<Pair<Float, Float>>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .padding(8.dp)
    ) {

        if (points.isEmpty()) return@Canvas

        val xs = points.map { it.first }
        val ys = points.map { it.second }

        val minX = xs.minOrNull() ?: 0f
        val maxX = xs.maxOrNull() ?: 0f
        val minY = ys.minOrNull() ?: 0f
        val maxY = ys.maxOrNull() ?: 0f

        val scale = min(
            size.width / (maxX - minX + 0.1f),
            size.height / (maxY - minY + 0.1f)
        )

        val cx = size.width / 2f
        val cy = size.height / 2f

        // Path lines
        for (i in 0 until points.size - 1) {
            val (x1, y1) = points[i]
            val (x2, y2) = points[i + 1]

            drawLine(
                color = Color.Blue,
                start = Offset(
                    cx + (x1 - (minX + maxX) / 2f) * scale,
                    cy - (y1 - (minY + maxY) / 2f) * scale
                ),
                end = Offset(
                    cx + (x2 - (minX + maxX) / 2f) * scale,
                    cy - (y2 - (minY + maxY) / 2f) * scale
                ),
                strokeWidth = 4f
            )
        }

        // Last point
        val (lx, ly) = points.last()
        drawCircle(
            Color.Red,
            radius = 8f,
            center = Offset(
                cx + (lx - (minX + maxX) / 2f) * scale,
                cy - (ly - (minY + maxY) / 2f) * scale
            )
        )
    }
}
