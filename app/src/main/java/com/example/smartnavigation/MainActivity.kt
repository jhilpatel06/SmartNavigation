package com.example.smartnavigation

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = "home") {
                    composable("home") { HomeScreen(navController) }
                    composable("dead_reckoning") { DeadReckoningScreen(navController) }
                    composable("slam") { SlamScreen(navController) }
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
            ) { Text("Dead Reckoning (3D Scribble)") }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { navController.navigate("slam") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SLAM (Camera)") }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SlamScreen(navController: NavController) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        Box(modifier = Modifier.fillMaxSize()) {
            CameraPreview()
            Button(
                onClick = { navController.popBackStack() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Text("Home")
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera permission is required for SLAM.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("Request Permission")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { navController.popBackStack() }) {
                Text("Home")
            }
        }
    }
}

@Composable
fun CameraPreview() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = {
            val previewView = PreviewView(it)
            val preview = Preview.Builder().build()
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            preview.setSurfaceProvider(previewView.surfaceProvider)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
            }, ContextCompat.getMainExecutor(it))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

/* ----------------------- DeadReckoning 3D Screen ----------------------- */
@Composable
fun DeadReckoningScreen(navController: NavController) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    // state: 3D position/velocity in meters / m/s
    val position = remember { mutableStateListOf(0f, 0f, 0f) }
    val velocity = remember { mutableStateListOf(0f, 0f, 0f) }
    // recorded path of 3D points
    val pathPoints = remember { mutableStateListOf<Triple<Float, Float, Float>>() }
    var isRunning by remember { mutableStateOf(false) }

    // viewer controls
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(-90f) } // Start with a top-down view
    var roll by remember { mutableFloatStateOf(0f) }
    var viewScale by remember { mutableFloatStateOf(80f) }

    // parameters for drift reduction
    val stationaryThreshold = 0.2f
    val stationaryRequiredCount = 5
    val accelLowpassAlpha = 0.4f
    val accelDeadzone = 0.08f
    val velocityDamping = 0.998f
    val biasLearningRate = 0.001f // Slightly faster bias learning

    val sensorListener = remember {
        object : SensorEventListener {
            var accelRaw = FloatArray(3)
            var rotationVector = floatArrayOf(0f, 0f, 0f, 1f)
            var lastTimestamp = 0L
            var accelBias = FloatArray(3)
            var stationaryCounter = 0

            fun reset() {
                lastTimestamp = 0L
                accelBias.fill(0f)
                stationaryCounter = 0
                position.fill(0f)
                velocity.fill(0f)
            }

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val raw = event.values
                        accelRaw[0] = accelLowpassAlpha * accelRaw[0] + (1 - accelLowpassAlpha) * raw[0]
                        accelRaw[1] = accelLowpassAlpha * accelRaw[1] + (1 - accelLowpassAlpha) * raw[1]
                        accelRaw[2] = accelLowpassAlpha * accelRaw[2] + (1 - accelLowpassAlpha) * raw[2]
                        if (isRunning) integrate(event.timestamp)
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        if (event.values.size >= 4) {
                            rotationVector = event.values.clone()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            private fun isStationary(acc: FloatArray): Boolean {
                val mag = sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2])
                return mag < stationaryThreshold
            }

            private fun integrate(ts: Long) {
                if (lastTimestamp == 0L) {
                    lastTimestamp = ts
                    return
                }
                val dt = (ts - lastTimestamp) / 1e9f
                lastTimestamp = ts
                if (dt <= 0f || dt > 0.5f || dt.isNaN()) return

                if (isStationary(accelRaw)) {
                    stationaryCounter++
                } else {
                    stationaryCounter = 0
                }

                if (stationaryCounter > stationaryRequiredCount) {
                    velocity.fill(0f)
                    // If stationary, slowly learn the accelerometer bias in the DEVICE frame
                    for (i in 0..2) {
                        accelBias[i] = accelBias[i] * (1f - biasLearningRate) + accelRaw[i] * biasLearningRate
                    }
                } else {
                    // Correct for bias in the device's coordinate system FIRST
                    val correctedAccelDevice = floatArrayOf(
                        accelRaw[0] - accelBias[0],
                        accelRaw[1] - accelBias[1],
                        accelRaw[2] - accelBias[2]
                    )

                    // Now, rotate the corrected acceleration into the world frame
                    val aWorld = deviceToWorldAccel(correctedAccelDevice, rotationVector)

                    val axc = if (abs(aWorld[0]) < accelDeadzone) 0f else aWorld[0]
                    val ayc = if (abs(aWorld[1]) < accelDeadzone) 0f else aWorld[1]
                    val azc = if (abs(aWorld[2]) < accelDeadzone) 0f else aWorld[2]

                    velocity[0] += axc * dt
                    velocity[1] += ayc * dt
                    velocity[2] += azc * dt

                    velocity[0] *= velocityDamping
                    velocity[1] *= velocityDamping
                    velocity[2] *= velocityDamping
                }

                position[0] += velocity[0] * dt
                position[1] += velocity[1] * dt
                position[2] += velocity[2] * dt

                pathPoints.add(Triple(position[0], position[1], position[2]))
                if (pathPoints.size > 4000) pathPoints.removeFirst()
            }

            private fun deviceToWorldAccel(accel: FloatArray, rotationVec: FloatArray): FloatArray {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVec)

                val (ax, ay, az) = accel

                val worldAccelX = rotationMatrix[0] * ax + rotationMatrix[1] * ay + rotationMatrix[2] * az
                val worldAccelY = rotationMatrix[3] * ax + rotationMatrix[4] * ay + rotationMatrix[5] * az
                val worldAccelZ = rotationMatrix[6] * ax + rotationMatrix[7] * ay + rotationMatrix[8] * az

                return floatArrayOf(worldAccelX, worldAccelY, worldAccelZ)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Dead Reckoning (3D)", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { navController.popBackStack() }) {
                Text("Home")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                sensorListener.reset()
                isRunning = true
            }, Modifier.weight(1f)) { Text("Start") }
            Button(onClick = { isRunning = false }, Modifier.weight(1f)) { Text("Stop") }
            Button(onClick = {
                pathPoints.clear()
                sensorListener.reset()
            }, Modifier.weight(1f)) { Text("Reset") }
        }

        TrajectoryCanvas3D(pathPoints.toList(), yaw, pitch, roll, viewScale) { newScale ->
            viewScale = newScale.coerceIn(20f, 500f)
        }

        // controls
        Column(modifier = Modifier
            .verticalScroll(rememberScrollState())
            .background(Color.White.copy(alpha = 0.8f))
            .padding(8.dp)) {
            Text("Yaw: ${yaw.toInt()}°")
            Slider(value = yaw, onValueChange = { yaw = it }, valueRange = -180f..180f)
            Text("Pitch: ${pitch.toInt()}°")
            Slider(value = pitch, onValueChange = { pitch = it }, valueRange = -90f..90f)
            Text("Roll: ${roll.toInt()}°")
            Slider(value = roll, onValueChange = { roll = it }, valueRange = -180f..180f)
            Text("Zoom: ${viewScale.toInt()}%")
            Slider(value = viewScale, onValueChange = { viewScale = it }, valueRange = 20f..500f)
        }
    }
}

@Composable
fun TrajectoryCanvas3D(points: List<Triple<Float, Float, Float>>, yaw: Float, pitch: Float, roll: Float, scale: Float, onScaleChange: (Float) -> Unit) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
            .background(Color.LightGray)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onScaleChange(scale * zoom)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        val rotationMatrix = getRotationMatrix(yaw, pitch, roll)

        fun project(p: Triple<Float, Float, Float>): Offset {
            val rotated = multiplyMV(rotationMatrix, floatArrayOf(p.first, p.second, p.third))
            return Offset(cx + rotated[0] * scale, cy - rotated[1] * scale)
        }
        
        // --- Draw Grid --- 
        val gridSize = 4f // meters
        val gridStep = 0.5f
        val gridColor = Color.DarkGray.copy(alpha = 0.5f)
        val axisColorX = Color.Red.copy(alpha = 0.7f)
        val axisColorY = Color.Green.copy(alpha = 0.7f)
        val axisColorZ = Color(0f, 0f, 0.7f, 0.7f) // Darker Blue

        fun draw3DLine(start: Triple<Float, Float, Float>, end: Triple<Float, Float, Float>, color: Color, strokeWidth: Float = 1f) {
            val start2D = project(start)
            val end2D = project(end)
            drawLine(color, start2D, end2D, strokeWidth)
        }

        // Draw axes
        draw3DLine(Triple(0f, 0f, 0f), Triple(gridSize, 0f, 0f), axisColorX, 3f)
        draw3DLine(Triple(0f, 0f, 0f), Triple(0f, gridSize, 0f), axisColorY, 3f)
        draw3DLine(Triple(0f, 0f, 0f), Triple(0f, 0f, gridSize), axisColorZ, 3f)

        // Draw XY plane grid
        var i = -gridSize
        while (i <= gridSize) {
            if (i != 0f) { // Avoid redrawing over axes
                draw3DLine(Triple(i, -gridSize, 0f), Triple(i, gridSize, 0f), gridColor) // Lines parallel to Y
                draw3DLine(Triple(-gridSize, i, 0f), Triple(gridSize, i, 0f), gridColor) // Lines parallel to X
            }
            i += gridStep
        }
        // --- End Grid ---

        if (points.isNotEmpty()) {
            val path = androidx.compose.ui.graphics.Path()
            path.moveTo(project(points.first()).x, project(points.first()).y)
            for (i in 1 until points.size) {
                val p = project(points[i])
                path.lineTo(p.x, p.y)
            }
            drawPath(path, Color.Blue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))

            // draw current position circle
            val lastPoint = project(points.last())
            drawCircle(Color.Red, radius = 8f, center = lastPoint)

            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 30f
                }
                val coord = points.last()
                val txt = "(${String.format(Locale.US, "%.2f", coord.first)}, ${String.format(Locale.US, "%.2f", coord.second)}, ${String.format(Locale.US, "%.2f", coord.third)}) m"
                canvas.nativeCanvas.drawText(txt, 10f, h - 10f, paint)
            }

        } else {
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 40f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                canvas.nativeCanvas.drawText("No data yet — press Start", cx, cy, paint)
            }
        }
    }
}

fun getRotationMatrix(yaw: Float, pitch: Float, roll: Float): Array<FloatArray> {
    val cy = cos(yaw * PI / 180f).toFloat()
    val sy = sin(yaw * PI / 180f).toFloat()
    val cp = cos(pitch * PI / 180f).toFloat()
    val sp = sin(pitch * PI / 180f).toFloat()
    val cr = cos(roll * PI / 180f).toFloat()
    val sr = sin(roll * PI / 180f).toFloat()

    return arrayOf(
        floatArrayOf(cy * cr + sy * sp * sr, -cy * sr + sy * sp * cr, sy * cp),
        floatArrayOf(sr * cp, cr * cp, -sp),
        floatArrayOf(-sy * cr + cy * sp * sr, sy * sr + cy * sp * cr, cy * cp)
    )
}

fun multiplyMV(m: Array<FloatArray>, v: FloatArray): FloatArray {
    return floatArrayOf(
        m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
        m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
        m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
    )
}
