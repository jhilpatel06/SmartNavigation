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
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadReckoningScreen() {

    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    val position = remember { mutableStateListOf(0f, 0f, 0f) }
    val velocity = remember { mutableStateListOf(0f, 0f, 0f) }
    val path = remember { mutableStateListOf<Triple<Float, Float, Float>>() }
    var isRunning by remember { mutableStateOf(false) }

    // UI Sliders for 3D Rotation
    var roll by remember { mutableStateOf(0f) }
    var pitch by remember { mutableStateOf(0f) }
    var yaw by remember { mutableStateOf(0f) }

    val listener = remember {
        object : SensorEventListener {

            var accel = FloatArray(3)
            var rot = FloatArray(4)
            var lastTS = 0L
            val alpha = 0.6f

            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {

                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        accel[0] = alpha * accel[0] + (1 - alpha) * e.values[0]
                        accel[1] = alpha * accel[1] + (1 - alpha) * e.values[1]
                        accel[2] = alpha * accel[2] + (1 - alpha) * e.values[2]
                        if (isRunning) integrate(e.timestamp)
                    }

                    Sensor.TYPE_ROTATION_VECTOR -> {
                        rot = if (e.values.size >= 4) e.values.clone()
                        else {
                            val x = e.values[0]
                            val y = e.values[1]
                            val z = e.values[2]
                            floatArrayOf(
                                x, y, z,
                                sqrt(max(0f, 1f - x * x - y * y - z * z))
                            )
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            // FIXED DRIFT + SHOW COORDINATES
            private fun integrate(ts: Long) {
                if (lastTS == 0L) {
                    lastTS = ts
                    return
                }

                val dt = (ts - lastTS) / 1e9f
                lastTS = ts

                if (dt <= 0f || dt > 0.2f) return

                val aw = toWorld(accel, rot)

                // Threshold to remove noise
                val th = 0.03f

                val ax = if (abs(aw[0]) < th) 0f else aw[0]
                val ay = if (abs(aw[1]) < th) 0f else aw[1]

                // FIX: Prevent Z drift (gravity noise)
                val az = if (abs(aw[2]) < 0.15f) 0f else aw[2]

                // Vel update
                velocity[0] += ax * dt
                velocity[1] += ay * dt
                velocity[2] += az * dt

                velocity[0] *= 0.92f
                velocity[1] *= 0.92f
                velocity[2] *= 0.92f

                // Position update
                position[0] += velocity[0] * dt
                position[1] += velocity[1] * dt
                position[2] += velocity[2] * dt

                path.add(Triple(position[0], position[1], position[2]))
                if (path.size > 3000) path.removeAt(0)
            }

            // Quaternion rotation
            private fun toWorld(a: FloatArray, q: FloatArray): FloatArray {
                val (x, y, z, w) = q
                val vx = a[0].toDouble()
                val vy = a[1].toDouble()
                val vz = a[2].toDouble()

                val ix = w * vx + y * vz - z * vy
                val iy = w * vy + z * vx - x * vz
                val iz = w * vz + x * vy - y * vx
                val iw = -x * vx - y * vy - z * vz

                val px = ix * w - iw * x + iy * z - iz * y
                val py = iy * w - iw * y + iz * x - ix * z
                val pz = iz * w - iw * z + ix * y - iy * x

                return floatArrayOf(px.toFloat(), py.toFloat(), pz.toFloat())
            }
        }
    }

    DisposableEffect(isRunning) {
        if (isRunning) {
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.also {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } else sensorManager.unregisterListener(listener)

        onDispose { sensorManager.unregisterListener(listener) }
    }

    // UI
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Dead Reckoning 3D", fontWeight = FontWeight.Bold) }
            )
        }
    ) { pad ->

        Column(
            Modifier.padding(pad).padding(12.dp)
        ) {

            // Buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ isRunning = true }, Modifier.weight(1f)) { Text("Start") }
                Button({ isRunning = false }, Modifier.weight(1f)) { Text("Stop") }
                Button({
                    position[0] = 0f; velocity[0] = 0f
                    position[1] = 0f; velocity[1] = 0f
                    position[2] = 0f; velocity[2] = 0f
                    path.clear()
                }, Modifier.weight(1f)) { Text("Reset") }
            }

            Spacer(Modifier.height(10.dp))

            // SHOW COORDINATES
            Text(
                "X = %.3f   Y = %.3f   Z = %.3f".format(
                    position[0], position[1], position[2]
                ),
                fontWeight = FontWeight.Bold,
                color = Color.Yellow
            )

            Spacer(Modifier.height(12.dp))

            Text("3D View Rotation", fontWeight = FontWeight.Bold)

            SliderControl("Roll", roll) { roll = it }
            SliderControl("Pitch", pitch) { pitch = it }
            SliderControl("Yaw", yaw) { yaw = it }

            Spacer(Modifier.height(12.dp))

            ThreeDCanvas(path, roll, pitch, yaw)
        }
    }
}

// ───────────────────────────────────────────────
// SLIDERS
// ───────────────────────────────────────────────

@Composable
fun SliderControl(label: String, value: Float, onChange: (Float) -> Unit) {
    Text("$label: ${value.toInt()}°")
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = -180f..180f
    )
}

// ───────────────────────────────────────────────
// 3D GRAPH + AXES + GRID
// ───────────────────────────────────────────────

@Composable
fun ThreeDCanvas(
    points: List<Triple<Float, Float, Float>>,
    roll: Float,
    pitch: Float,
    yaw: Float
) {

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .padding(6.dp)
    ) {
        if (points.isEmpty()) return@Canvas

        val cx = size.width / 2f
        val cy = size.height / 2f

        val r = roll * PI / 180
        val p = pitch * PI / 180
        val w = yaw * PI / 180

        fun rot3D(pt: Triple<Float, Float, Float>): Offset {
            var (x, y, z) = pt

            // Yaw
            val x1 = x * cos(w) - y * sin(w)
            val y1 = x * sin(w) + y * cos(w)

            // Pitch
            val y2 = y1 * cos(p) - z * sin(p)
            val z1 = y1 * sin(p) + z * cos(p)

            // Roll
            val x2 = x1 * cos(r) + z1 * sin(r)
            val z2 = -x1 * sin(r) + z1 * cos(r)

            val scale = 180f
            return Offset(cx + x2.toFloat() * scale, cy - y2.toFloat() * scale)
        }

        // GRID
        val g = 0.2f
        val c = 6
        for (i in -c..c) {
            val s = i * g
            drawLine(Color(0x22FFFFFF), rot3D(Triple(s, -c * g, 0f)), rot3D(Triple(s, c * g, 0f)), 1f)
            drawLine(Color(0x22FFFFFF), rot3D(Triple(-c * g, s, 0f)), rot3D(Triple(c * g, s, 0f)), 1f)
        }

        // AXES
        val o = rot3D(Triple(0f, 0f, 0f))
        drawLine(Color.Red, o, rot3D(Triple(1f, 0f, 0f)), 4f)
        drawLine(Color.Green, o, rot3D(Triple(0f, 1f, 0f)), 4f)
        drawLine(Color.Blue, o, rot3D(Triple(0f, 0f, 1f)), 4f)

        // PATH
        for (i in 0 until points.size - 1) {
            drawLine(Color.Cyan, rot3D(points[i]), rot3D(points[i + 1]), 3f)
        }

        drawCircle(Color.Yellow, 6f, rot3D(points.last()))
    }
}
