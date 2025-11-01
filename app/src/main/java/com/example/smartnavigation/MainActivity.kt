package com.example.smartnavigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    // Sensor state (internal)
    private var accelRaw = FloatArray(3) { 0f }              // sensor frame linear acceleration (m/s^2)
    private var rotationVector = FloatArray(4) { 0f }        // rotation vector quaternion (x,y,z,w)

    // Integration state (world frame)
    private var velocity = FloatArray(3) { 0f }             // m/s
    private var position = FloatArray(3) { 0f }             // m

    // Timestamp (ns)
    private var lastTimestamp: Long = 0L

    // UI / Compose state
    private val pathPoints = mutableStateListOf<Pair<Float, Float>>() // (x,y) in meters
    private var smoothingAlpha = 0.6f // low-pass smoothing factor for accel

    // Controls
    private var isRunningState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DRScreen()
                }
            }
        }
    }

    // ------------------------
    // Sensor lifecycle helpers
    // ------------------------
    private fun registerSensors() {
        // Use LINEAR_ACCELERATION (gravity removed) and ROTATION_VECTOR for orientation
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    // ------------------------
    // SensorEventListener
    // ------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // smooth accel to reduce noise
                val raw = event.values
                accelRaw[0] = smoothingAlpha * accelRaw[0] + (1 - smoothingAlpha) * raw[0]
                accelRaw[1] = smoothingAlpha * accelRaw[1] + (1 - smoothingAlpha) * raw[1]
                accelRaw[2] = smoothingAlpha * accelRaw[2] + (1 - smoothingAlpha) * raw[2]
                // integrate if running
                if (isRunningState.value) integrate(event.timestamp)
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                // rotation vector as quaternion (x,y,z,w) stored for use during integration
                val rv = FloatArray(4)
                // Android gives rotation vector (x,y,z[,w]) - convert to quaternion
                if (event.values.size >= 4) {
                    rv[0] = event.values[0]
                    rv[1] = event.values[1]
                    rv[2] = event.values[2]
                    rv[3] = event.values[3]
                } else {
                    // compute w from (x,y,z)
                    rv[0] = event.values[0]
                    rv[1] = event.values[1]
                    rv[2] = event.values[2]
                    val x = rv[0]; val y = rv[1]; val z = rv[2]
                    rv[3] = sqrt(max(0f, 1f - x * x - y * y - z * z))
                }
                rotationVector = rv
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------
    // Integration: accel (device frame) -> world frame -> v/p
    // ------------------------
    private fun integrate(eventTimestamp: Long) {
        if (lastTimestamp == 0L) {
            lastTimestamp = eventTimestamp
            return
        }

        val dt = (eventTimestamp - lastTimestamp) / 1e9f // seconds
        lastTimestamp = eventTimestamp

        // ignore unreasonable dt
        if (dt <= 0f || dt > 0.5f) return

        // Convert accel from device frame to world frame using rotation quaternion
        val aWorld = deviceToWorldAccel(accelRaw, rotationVector)

        // Simple integration (very basic) - note: large drift expected without fusion/correction
        for (i in 0..2) {
            // small threshold to reduce noise-driven drift
            val ax = if (abs(aWorld[i]) < 0.02f) 0f else aWorld[i]
            velocity[i] += ax * dt
            position[i] += velocity[i] * dt
        }

        // Update UI path (x,y). We use position X = position[0], Y = position[1]
        // We cap the number of points to avoid memory growth
        val x = position[0]
        val y = position[1]
        // use post to update state list safely
        runOnUiThread {
            pathPoints.add(x to y)
            if (pathPoints.size > 2000) pathPoints.removeAt(0)
        }
    }

    // Convert acceleration vector from device (phone) frame to world frame using quaternion
    // rotationVector: quaternion (x,y,z,w)
    private fun deviceToWorldAccel(accel: FloatArray, rotationQuat: FloatArray): FloatArray {
        // quaternion rotation: v' = q * v * q_conj
        val qx = rotationQuat[0].toDouble()
        val qy = rotationQuat[1].toDouble()
        val qz = rotationQuat[2].toDouble()
        val qw = rotationQuat[3].toDouble()

        // vector as quaternion (vx,vy,vz, 0)
        val vx = accel[0].toDouble()
        val vy = accel[1].toDouble()
        val vz = accel[2].toDouble()

        // q * v
        val ix =  qw * vx + qy * vz - qz * vy
        val iy =  qw * vy + qz * vx - qx * vz
        val iz =  qw * vz + qx * vy - qy * vx
        val iw = -qx * vx - qy * vy - qz * vz

        // (q * v) * q_conj
        val wx = ix * qw - iw * qx + iy * qz - iz * qy
        val wy = iy * qw - iw * qy + iz * qx - ix * qz
        val wz = iz * qw - iw * qz + ix * qy - iy * qx

        return floatArrayOf(wx.toFloat(), wy.toFloat(), wz.toFloat())
    }

    // ------------------------
    // Compose UI
    // ------------------------
    @Composable
    fun DRScreen() {
        val isRunning by isRunningState
        // snapshot of path for compose
        val points by remember { derivedStateOf { pathPoints.toList() } }

        Column(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)) {

            Text("SmartNav — Dead Reckoning", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!isRunning) {
                        // reset timestamps to avoid giant dt on start
                        lastTimestamp = 0L
                        // reset integration state? keep for resume behaviour; here we keep existing position
                        registerSensors()
                        isRunningState.value = true
                    }
                }, modifier = Modifier.weight(1f)) { Text("Start") }

                Button(onClick = {
                    if (isRunning) {
                        unregisterSensors()
                        isRunningState.value = false
                    }
                }, modifier = Modifier.weight(1f)) { Text("Stop") }

                Button(onClick = {
                    // Reset positions & path
                    for (i in 0..2) { position[i] = 0f; velocity[i] = 0f }
                    lastTimestamp = 0L
                    pathPoints.clear()
                }, modifier = Modifier.weight(1f)) { Text("Reset") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Numeric readout
            Text("Position (m): X = %.2f  Y = %.2f  Z = %.2f".format(position[0], position[1], position[2]))
            Text("Velocity (m/s): X = %.2f  Y = %.2f  Z = %.2f".format(velocity[0], velocity[1], velocity[2]))
            Spacer(modifier = Modifier.height(12.dp))

            TrajectoryCanvas(points = points)
        }
    }

    @Composable
    fun TrajectoryCanvas(points: List<Pair<Float, Float>>) {
        // draw path; scale to fit
        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .padding(8.dp)
        ) {
            if (points.isEmpty()) return@Canvas

            // compute bounding box in meters
            val xs = points.map { it.first }
            val ys = points.map { it.second }
            val minX = xs.minOrNull() ?: 0f
            val maxX = xs.maxOrNull() ?: 0f
            val minY = ys.minOrNull() ?: 0f
            val maxY = ys.maxOrNull() ?: 0f

            val widthMeters = max(0.1f, maxX - minX)
            val heightMeters = max(0.1f, maxY - minY)

            // leave margin
            val margin = 30f
            val scaleX = (size.width - margin * 2f) / widthMeters
            val scaleY = (size.height - margin * 2f) / heightMeters
            val scale = min(scaleX, scaleY)

            // center offset
            val cx = size.width / 2f
            val cy = size.height / 2f

            // draw axes
            drawLine(Color.LightGray, Offset(0f, cy), Offset(size.width, cy), 1f)
            drawLine(Color.LightGray, Offset(cx, 0f), Offset(cx, size.height), 1f)

            // draw path
            for (i in 0 until points.size - 1) {
                val (x1, y1) = points[i]
                val (x2, y2) = points[i + 1]

                val sx1 = cx + (x1 - (minX + maxX) / 2f) * scale
                val sy1 = cy - (y1 - (minY + maxY) / 2f) * scale

                val sx2 = cx + (x2 - (minX + maxX) / 2f) * scale
                val sy2 = cy - (y2 - (minY + maxY) / 2f) * scale

                drawLine(
                    color = Color(0xFF0D6EFD),
                    start = Offset(sx1, sy1),
                    end = Offset(sx2, sy2),
                    strokeWidth = 4f
                )
            }

            // current point dot
            val (lx, ly) = points.last()
            val sx = cx + (lx - (minX + maxX) / 2f) * scale
            val sy = cy - (ly - (minY + maxY) / 2f) * scale
            drawCircle(Color.Red, radius = 8f, center = Offset(sx, sy))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterSensors() } catch (_: Exception) {}
    }
}
