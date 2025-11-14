package com.example.smartnavigation


import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.*

@Composable
fun DeadReckoningScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    val position = remember { mutableStateListOf(0f, 0f, 0f) }
    val velocity = remember { mutableStateListOf(0f, 0f, 0f) }
    val pathPoints = remember { mutableStateListOf<Pair<Float, Float>>() }
    var isRunning by remember { mutableStateOf(false) }

    val sensorListener = remember {
        object : SensorEventListener {
            var accelRaw = FloatArray(3)
            var rotationVector = FloatArray(4)
            var lastTimestamp = 0L
            val alpha = 0.6f

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val raw = event.values
                        for (i in 0..2)
                            accelRaw[i] = alpha * accelRaw[i] + (1 - alpha) * raw[i]
                        if (isRunning) integrate(event.timestamp)
                    }

                    Sensor.TYPE_ROTATION_VECTOR -> {
                        if (event.values.size >= 4)
                            rotationVector = event.values.clone()
                        else {
                            val x = event.values[0]
                            val y = event.values[1]
                            val z = event.values[2]
                            rotationVector = floatArrayOf(x, y, z, sqrt(max(0f, 1f - x * x - y * y - z * z)))
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            private fun integrate(ts: Long) {
                if (lastTimestamp == 0L) {
                    lastTimestamp = ts
                    return
                }
                val dt = (ts - lastTimestamp) / 1e9f
                lastTimestamp = ts
                if (dt <= 0f || dt > 0.5f) return

                val aWorld = deviceToWorldAccel(accelRaw, rotationVector)
                for (i in 0..2) {
                    val ax = if (abs(aWorld[i]) < 0.02f) 0f else aWorld[i]
                    velocity[i] += ax * dt
                    position[i] += velocity[i] * dt
                }
                pathPoints.add(position[0] to position[1])
                if (pathPoints.size > 2000) pathPoints.removeAt(0)
            }

            private fun deviceToWorldAccel(accel: FloatArray, q: FloatArray): FloatArray {
                val qx = q[0].toDouble(); val qy = q[1].toDouble(); val qz = q[2].toDouble(); val qw = q[3].toDouble()
                val vx = accel[0].toDouble(); val vy = accel[1].toDouble(); val vz = accel[2].toDouble()

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
        onDispose { sensorManager.unregisterListener(sensorListener) }
    }

    Column(Modifier.padding(16.dp)) {
        Text("Dead Reckoning", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { isRunning = true }, Modifier.weight(1f)) { Text("Start") }
            Button(onClick = { isRunning = false }, Modifier.weight(1f)) { Text("Stop") }
            Button(onClick = {
                for (i in 0..2) { position[i] = 0f; velocity[i] = 0f }
                pathPoints.clear()
            }, Modifier.weight(1f)) { Text("Reset") }
        }
        Spacer(Modifier.height(10.dp))
        Text("Position: X=${position[0]}  Y=${position[1]}  Z=${position[2]}")
        TrajectoryCanvas(points = pathPoints.toList())
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
        val scale = min(size.width / (maxX - minX + 0.1f), size.height / (maxY - minY + 0.1f))
        val cx = size.width / 2f
        val cy = size.height / 2f
        for (i in 0 until points.size - 1) {
            val (x1, y1) = points[i]
            val (x2, y2) = points[i + 1]
            drawLine(
                color = Color.Blue,
                start = Offset(cx + (x1 - (minX + maxX) / 2f) * scale, cy - (y1 - (minY + maxY) / 2f) * scale),
                end = Offset(cx + (x2 - (minX + maxX) / 2f) * scale, cy - (y2 - (minY + maxY) / 2f) * scale),
                strokeWidth = 4f
            )
        }
        val (lx, ly) = points.last()
        drawCircle(Color.Red, 8f, Offset(cx + (lx - (minX + maxX) / 2f) * scale, cy - (ly - (minY + maxY) / 2f) * scale))
    }
}
