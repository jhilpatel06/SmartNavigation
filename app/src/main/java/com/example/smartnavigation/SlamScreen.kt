package com.example.smartnavigation

import android.Manifest
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.accompanist.permissions.*
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PathPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestamp: Long
)

fun Pose.toShortString(): String {
    val t = translation
    val r = rotationQuaternion
    return "X: ${t[0].format(2)}m  Y: ${t[1].format(2)}m  Z: ${t[2].format(2)}m\n" +
            "Quat: [${r[0].format(2)}, ${r[1].format(2)}, ${r[2].format(2)}, ${r[3].format(2)}]"
}

fun Float.format(digits: Int) = "%.${digits}f".format(this)

class BackgroundRenderer {
    private var quadProgram = 0
    private var quadPositionParam = 0
    private var quadTexCoordParam = 0
    var textureId = -1
        private set

    private val QUAD_COORDS = floatArrayOf(
        -1.0f, -1.0f, 0.0f,
        -1.0f, +1.0f, 0.0f,
        +1.0f, -1.0f, 0.0f,
        +1.0f, +1.0f, 0.0f
    )

    private val QUAD_TEXCOORDS = floatArrayOf(
        0.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 1.0f,
        1.0f, 0.0f
    )

    fun createOnGlThread(context: Context) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val textureTarget = 0x8D65 // GL_TEXTURE_EXTERNAL_OES

        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vertexShader = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """.trimIndent()

        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vs, vertexShader)
        GLES20.glCompileShader(vs)

        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fs, fragmentShader)
        GLES20.glCompileShader(fs)

        quadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(quadProgram, vs)
        GLES20.glAttachShader(quadProgram, fs)
        GLES20.glLinkProgram(quadProgram)
        GLES20.glUseProgram(quadProgram)

        quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glBindTexture(0x8D65, textureId)

        GLES20.glUseProgram(quadProgram)
        GLES20.glVertexAttribPointer(quadPositionParam, 3, GLES20.GL_FLOAT, false, 0,
            java.nio.ByteBuffer.allocateDirect(QUAD_COORDS.size * 4).apply {
                order(java.nio.ByteOrder.nativeOrder())
                asFloatBuffer().put(QUAD_COORDS)
                position(0)
            })
        GLES20.glVertexAttribPointer(quadTexCoordParam, 2, GLES20.GL_FLOAT, false, 0,
            java.nio.ByteBuffer.allocateDirect(QUAD_TEXCOORDS.size * 4).apply {
                order(java.nio.ByteOrder.nativeOrder())
                asFloatBuffer().put(QUAD_TEXCOORDS)
                position(0)
            })

        GLES20.glEnableVertexAttribArray(quadPositionParam)
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}

class ArCoreRenderer(
    private val context: Context,
    private val onPoseUpdate: (String, TrackingState, PathPoint?) -> Unit
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private var isTracking = false

    fun createSession(): Boolean {
        return try {
            if (session != null) return true

            val newSession = Session(context)
            val config = Config(newSession).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            newSession.configure(config)
            session = newSession
            Log.d("ArCoreRenderer", "Session created successfully")
            true
        } catch (e: Exception) {
            Log.e("ArCoreRenderer", "Failed to create session", e)
            false
        }
    }

    fun resume() {
        try {
            session?.resume()
            isTracking = true
            Log.d("ArCoreRenderer", "Session resumed")
        } catch (e: CameraNotAvailableException) {
            Log.e("ArCoreRenderer", "Camera not available", e)
        }
    }

    fun pause() {
        session?.pause()
        isTracking = false
        Log.d("ArCoreRenderer", "Session paused")
    }

    fun destroy() {
        session?.close()
        session = null
        isTracking = false
        Log.d("ArCoreRenderer", "Session destroyed")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        session?.setCameraTextureName(backgroundRenderer.textureId)
        Log.d("ArCoreRenderer", "Surface created, texture ID: ${backgroundRenderer.textureId}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
        Log.d("ArCoreRenderer", "Surface changed: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        try {
            currentSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = currentSession.update()
            val camera = frame.camera

            backgroundRenderer.draw(frame)

            val trackingState = camera.trackingState
            if (trackingState == TrackingState.TRACKING && isTracking) {
                val pose = camera.pose
                val point = PathPoint(
                    pose.tx(),
                    pose.ty(),
                    pose.tz(),
                    System.currentTimeMillis()
                )
                onPoseUpdate(pose.toShortString(), trackingState, point)
            } else {
                onPoseUpdate("State: ${trackingState.name}", trackingState, null)
            }
        } catch (e: CameraNotAvailableException) {
            Log.e("ArCoreRenderer", "Camera not available", e)
            onPoseUpdate("Camera unavailable", TrackingState.STOPPED, null)
        } catch (e: Exception) {
            Log.e("ArCoreRenderer", "Error in onDrawFrame", e)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SlamScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var slamPose by remember { mutableStateOf("Initializing SLAM...") }
    var trackingState by remember { mutableStateOf(TrackingState.STOPPED) }
    var arCoreError by remember { mutableStateOf<String?>(null) }
    var arInstallAttempted by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var showGraph by remember { mutableStateOf(false) }

    val pathPoints = remember { mutableStateListOf<PathPoint>() }
    var totalDistance by remember { mutableStateOf(0f) }

    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    LaunchedEffect(cameraPermission.status) {
        if (!cameraPermission.status.isGranted) {
            arCoreError = "Camera permission required"
            return@LaunchedEffect
        }

        try {
            when (ArCoreApk.getInstance().requestInstall(
                context as android.app.Activity,
                !arInstallAttempted
            )) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arInstallAttempted = true
                    slamPose = "Installing ARCore..."
                    return@LaunchedEffect
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    arCoreError = null
                }
                else -> {}
            }
        } catch (e: Exception) {
            arCoreError = when (e) {
                is UnavailableApkTooOldException -> "ARCore APK too old"
                is UnavailableSdkTooOldException -> "SDK too old"
                is UnavailableDeviceNotCompatibleException -> "Device not compatible"
                is UnavailableUserDeclinedInstallationException -> "Installation declined"
                else -> "ARCore error: ${e.message}"
            }
            Log.e("SlamScreen", "ARCore error", e)
        }
    }

    if (showGraph) {
        PathGraphScreen(
            pathPoints = pathPoints.toList(),
            totalDistance = totalDistance,
            onBack = {
                showGraph = false
                pathPoints.clear()
                totalDistance = 0f
            }
        )
    } else {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("SLAM Path Tracker", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                if (cameraPermission.status.isGranted && arCoreError == null) {
                    ArCoreView(
                        modifier = Modifier.fillMaxSize(),
                        onPoseUpdate = { pose, state, point ->
                            slamPose = pose
                            trackingState = state
                            if (isRecording && point != null) {
                                if (pathPoints.isNotEmpty()) {
                                    val last = pathPoints.last()
                                    val dist = kotlin.math.sqrt(
                                        (point.x - last.x) * (point.x - last.x) +
                                                (point.y - last.y) * (point.y - last.y) +
                                                (point.z - last.z) * (point.z - last.z)
                                    )
                                    if (dist > 0.01f) { // Only add if moved > 1cm
                                        totalDistance += dist
                                        pathPoints.add(point)
                                    }
                                } else {
                                    pathPoints.add(point)
                                }
                            }
                        },
                        lifecycleOwner = lifecycleOwner
                    )

                    // Controls Overlay
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0xAA000000), Color.Black)
                                )
                            )
                            .padding(16.dp)
                    ) {
                        // Status
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(
                                        when (trackingState) {
                                            TrackingState.TRACKING -> Color.Green
                                            TrackingState.PAUSED -> Color.Yellow
                                            else -> Color.Red
                                        },
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "ARCore: ${trackingState.name}",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            slamPose,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 20.sp
                        )

                        Spacer(Modifier.height(8.dp))

                        // Stats
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Points: ${pathPoints.size}",
                                color = Color(0xFFBBBBBB),
                                fontSize = 14.sp
                            )
                            Text(
                                "Distance: ${totalDistance.format(2)}m",
                                color = Color(0xFFBBBBBB),
                                fontSize = 14.sp
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Control Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isRecording) {
                                        isRecording = false
                                        if (pathPoints.size > 1) {
                                            showGraph = true
                                        }
                                    } else {
                                        isRecording = true
                                        pathPoints.clear()
                                        totalDistance = 0f
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = trackingState == TrackingState.TRACKING,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRecording) Color.Red else Color.Green
                                )
                            ) {
                                Text(if (isRecording) "Stop Recording" else "Start Recording")
                            }

                            if (pathPoints.size > 1 && !isRecording) {
                                Button(
                                    onClick = { showGraph = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("View Path")
                                }
                            }
                        }
                    }
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            if (!cameraPermission.status.isGranted) {
                                Text(
                                    "Camera Permission Required",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                                    Text("Grant Permission")
                                }
                            } else if (arCoreError != null) {
                                Text(arCoreError!!, color = Color.Red, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArCoreView(
    modifier: Modifier = Modifier,
    onPoseUpdate: (String, TrackingState, PathPoint?) -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                preserveEGLContextOnPause = true
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)

                val renderer = ArCoreRenderer(ctx, onPoseUpdate)

                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            renderer.createSession()
                            onResume()
                            renderer.resume()
                        }
                        Lifecycle.Event.ON_PAUSE -> {
                            onPause()
                            renderer.pause()
                        }
                        Lifecycle.Event.ON_DESTROY -> {
                            renderer.destroy()
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathGraphScreen(
    pathPoints: List<PathPoint>,
    totalDistance: Float,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recorded Path") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 24.sp)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.White)
                .padding(16.dp)
        ) {
            // Stats
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Total Points: ${pathPoints.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Total Distance: ${totalDistance.format(2)} meters", fontSize = 16.sp)
                    if (pathPoints.size > 1) {
                        val duration = (pathPoints.last().timestamp - pathPoints.first().timestamp) / 1000f
                        Text("Duration: ${duration.format(1)} seconds", fontSize = 16.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2D Path Graph (Top View: X-Z plane)
            Text("Top View (X-Z Plane)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFFF5F5F5))
            ) {
                if (pathPoints.size < 2) return@Canvas

                val xValues = pathPoints.map { it.x }
                val zValues = pathPoints.map { it.z }

                val minX = xValues.minOrNull() ?: 0f
                val maxX = xValues.maxOrNull() ?: 0f
                val minZ = zValues.minOrNull() ?: 0f
                val maxZ = zValues.maxOrNull() ?: 0f

                val padding = 40f
                val width = size.width - 2 * padding
                val height = size.height - 2 * padding

                val rangeX = maxX - minX
                val rangeZ = maxZ - minZ
                val range = max(rangeX, rangeZ).coerceAtLeast(0.1f)

                val path = Path()
                pathPoints.forEachIndexed { index, point ->
                    val x = padding + ((point.x - minX) / range) * width
                    val y = padding + ((point.z - minZ) / range) * height

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                drawPath(
                    path = path,
                    color = Color.Blue,
                    style = Stroke(width = 4f)
                )

                // Draw start point
                val firstX = padding + ((pathPoints.first().x - minX) / range) * width
                val firstZ = padding + ((pathPoints.first().z - minZ) / range) * height
                drawCircle(Color.Green, radius = 12f, center = Offset(firstX, firstZ))

                // Draw end point
                val lastX = padding + ((pathPoints.last().x - minX) / range) * width
                val lastZ = padding + ((pathPoints.last().z - minZ) / range) * height
                drawCircle(Color.Red, radius = 12f, center = Offset(lastX, lastZ))
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }
        }
    }
}