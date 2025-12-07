package com.example.smartnavigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sqrt

// -----------------------------------------------------------------------------
// Data Models + Helpers
// -----------------------------------------------------------------------------

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

// -----------------------------------------------------------------------------
// BackgroundRenderer — Optimized + Official ARCore UV Transform + Orientation Fix
// -----------------------------------------------------------------------------

class BackgroundRenderer {

    var textureId: Int = -1
        private set

    private var quadProgram = 0
    private var quadPositionParam = 0
    private var quadTexCoordParam = 0
    private var textureUniform = 0

    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer
    private lateinit var quadTexCoordsTransformed: FloatBuffer

    companion object {
        private const val FLOAT_SIZE = 4
        private const val COORDS_PER_VERTEX = 3
        private const val TEXCOORDS_PER_VERTEX = 2

        // NDC full-screen quad
        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f, 0f,
            -1f, +1f, 0f,
            +1f, -1f, 0f,
            +1f, +1f, 0f
        )

        // Initial UVs (ARCore will transform them)
        private val QUAD_TEXCOORDS = floatArrayOf(
            0f, 1f,
            0f, 0f,
            1f, 1f,
            1f, 0f
        )
    }

    fun createOnGlThread(context: Context) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val vertexShaderCode = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """.trimIndent()

        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        quadProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }

        quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(quadProgram, "sTexture")

        quadVertices = allocFloatBuffer(QUAD_COORDS)
        quadTexCoords = allocFloatBuffer(QUAD_TEXCOORDS)
        quadTexCoordsTransformed = ByteBuffer.allocateDirect(QUAD_TEXCOORDS.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun allocFloatBuffer(array: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(array.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(array)
                position(0)
            }

    private fun loadShader(type: Int, code: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("BackgroundRenderer", "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
            }
        }

    fun draw(frame: Frame) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        quadTexCoords.position(0)
        quadTexCoordsTransformed.position(0)
        frame.transformDisplayUvCoords(quadTexCoords, quadTexCoordsTransformed)

        GLES20.glUseProgram(quadProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(quadPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(quadPositionParam)

        quadTexCoordsTransformed.position(0)
        GLES20.glVertexAttribPointer(quadTexCoordParam, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoordsTransformed)
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}
// -----------------------------------------------------------------------------
// ARCore Renderer – stable tracking config + per-frame display geometry update
// -----------------------------------------------------------------------------

class ArCoreRenderer(
    private val context: Context,
    private val onPoseUpdate: (String, TrackingState, PathPoint?) -> Unit
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()

    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0

    // Create ARCore session once
    fun createSessionIfNeeded(): Boolean {
        if (session != null) return true

        return try {
            val newSession = Session(context)

            val config = Config(newSession).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO

                // Better tracking stability
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                depthMode = Config.DepthMode.AUTOMATIC
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            }

            newSession.configure(config)
            session = newSession

            Log.d("ArCoreRenderer", "ARCore session created & configured")
            true
        } catch (e: Exception) {
            Log.e("ArCoreRenderer", "Failed to create session", e)
            onPoseUpdate("Session error: ${e.message}", TrackingState.STOPPED, null)
            false
        }
    }

    fun resume() {
        try {
            session?.resume()
            Log.d("ArCoreRenderer", "Session resumed")
        } catch (e: CameraNotAvailableException) {
            Log.e("ArCoreRenderer", "Camera not available", e)
            onPoseUpdate("Camera unavailable", TrackingState.STOPPED, null)
        }
    }

    fun pause() {
        try {
            session?.pause()
            Log.d("ArCoreRenderer", "Session paused")
        } catch (e: Exception) {
            Log.e("ArCoreRenderer", "Error pausing session", e)
        }
    }

    fun destroy() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        Log.d("ArCoreRenderer", "Session destroyed")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        session?.setCameraTextureName(backgroundRenderer.textureId)
        Log.d("ArCoreRenderer", "Surface created; texture = ${backgroundRenderer.textureId}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height

        GLES20.glViewport(0, 0, width, height)

        val rotation = (context as? Activity)
            ?.windowManager
            ?.defaultDisplay
            ?.rotation ?: Surface.ROTATION_0

        session?.setDisplayGeometry(rotation, width, height)
        Log.d("ArCoreRenderer", "Surface changed: ${width}x${height}, rotation=$rotation")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: run {
            onPoseUpdate("Session not ready", TrackingState.STOPPED, null)
            return
        }

        try {
            // 🔥 IMPORTANT: keep display geometry updated every frame
            if (viewportWidth > 0 && viewportHeight > 0) {
                val rotation = (context as? Activity)
                    ?.windowManager
                    ?.defaultDisplay
                    ?.rotation ?: Surface.ROTATION_0
                currentSession.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            }

            currentSession.setCameraTextureName(backgroundRenderer.textureId)

            val frame = currentSession.update()
            val camera = frame.camera

            // Draw the camera image with correct UVs
            backgroundRenderer.draw(frame)

            when (camera.trackingState) {
                TrackingState.TRACKING -> {
                    val pose = camera.pose
                    val point = PathPoint(
                        pose.tx(),
                        pose.ty(),
                        pose.tz(),
                        System.currentTimeMillis()
                    )
                    onPoseUpdate(pose.toShortString(), TrackingState.TRACKING, point)
                }

                TrackingState.PAUSED -> {
                    onPoseUpdate(
                        "Tracking paused – move slowly & point at textured surfaces",
                        TrackingState.PAUSED,
                        null
                    )
                }

                TrackingState.STOPPED -> {
                    onPoseUpdate("Tracking stopped", TrackingState.STOPPED, null)
                }
            }
        } catch (e: CameraNotAvailableException) {
            Log.e("ArCoreRenderer", "Camera not available", e)
            onPoseUpdate("Camera unavailable", TrackingState.STOPPED, null)
        } catch (e: Exception) {
            Log.e("ArCoreRenderer", "Error in onDrawFrame", e)
        }
    }
}

// -----------------------------------------------------------------------------
// ArCoreView – GLSurfaceView + GL-thread-safe lifecycle observer
// -----------------------------------------------------------------------------

@Composable
fun ArCoreView(
    modifier: Modifier = Modifier,
    onPoseUpdate: (String, TrackingState, PathPoint?) -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner
) {
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            object : GLSurfaceView(ctx) {
                private val renderer = ArCoreRenderer(ctx, onPoseUpdate)

                init {
                    setEGLContextClientVersion(2)
                    preserveEGLContextOnPause = true
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)

                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                queueEvent {
                                    renderer.createSessionIfNeeded()
                                    renderer.resume()
                                }
                                onResume()
                            }

                            Lifecycle.Event.ON_PAUSE -> {
                                queueEvent {
                                    renderer.pause()
                                }
                                onPause()
                            }

                            Lifecycle.Event.ON_DESTROY -> {
                                queueEvent {
                                    renderer.destroy()
                                }
                            }

                            else -> Unit
                        }
                    }

                    lifecycleOwner.lifecycle.addObserver(observer)
                }
            }
        }
    )
}
// -----------------------------------------------------------------------------
// SLAM Screen – Main UI (Compose)
// -----------------------------------------------------------------------------

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

    // Camera permission
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    // ARCore installation
    LaunchedEffect(cameraPermission.status) {
        if (!cameraPermission.status.isGranted) {
            arCoreError = "Camera permission required"
            return@LaunchedEffect
        }

        try {
            val installResult = ArCoreApk.getInstance().requestInstall(
                context as Activity,
                !arInstallAttempted
            )
            when (installResult) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arInstallAttempted = true
                    slamPose = "Installing ARCore..."
                    return@LaunchedEffect
                }

                ArCoreApk.InstallStatus.INSTALLED -> {
                    arCoreError = null
                }
            }
        } catch (e: Exception) {
            arCoreError = when (e) {
                is UnavailableApkTooOldException -> "ARCore APK too old"
                is UnavailableSdkTooOldException -> "SDK too old"
                is UnavailableDeviceNotCompatibleException -> "Device not compatible"
                is UnavailableUserDeclinedInstallationException -> "User declined ARCore installation"
                else -> "ARCore error: ${e.message}"
            }
            Log.e("SlamScreen", "ARCore install error", e)
        }
    }

    // ---------------- GRAPH SCREEN ----------------
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
        return
    }

    // ---------------- MAIN UI ----------------
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

                        if (isRecording && point != null && state == TrackingState.TRACKING) {
                            if (pathPoints.isNotEmpty()) {
                                val last = pathPoints.last()
                                val dx = point.x - last.x
                                val dy = point.y - last.y
                                val dz = point.z - last.z
                                val dist = sqrt(dx * dx + dy * dy + dz * dz)

                                if (dist > 0.01f) {
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

                // Controls overlay
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
                    // Status indicator
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Points: ${pathPoints.size}", color = Color.Gray)
                        Text("Distance: ${totalDistance.format(2)}m", color = Color.Gray)
                    }

                    Spacer(Modifier.height(16.dp))

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
                            enabled = trackingState == TrackingState.TRACKING,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color.Red else Color.Green
                            )
                        ) {
                            Text(if (isRecording) "Stop Recording" else "Start Recording")
                        }

                        if (!isRecording && pathPoints.size > 1) {
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
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!cameraPermission.status.isGranted) {
                        Text("Camera Permission Required", color = Color.White, fontSize = 20.sp)
                        Spacer(Modifier.height(12.dp))
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

// -----------------------------------------------------------------------------
// Path Graph Screen
// -----------------------------------------------------------------------------

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

            Text("Top View (X-Z Plane)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFFF5F5F5))
            ) {
                if (pathPoints.size < 2) return@Canvas

                val xs = pathPoints.map { it.x }
                val zs = pathPoints.map { it.z }

                val minX = xs.minOrNull() ?: 0f
                val maxX = xs.maxOrNull() ?: 0f
                val minZ = zs.minOrNull() ?: 0f
                val maxZ = zs.maxOrNull() ?: 0f

                val padding = 40f
                val width = size.width - padding * 2
                val height = size.height - padding * 2

                val range = max(maxX - minX, maxZ - minZ).coerceAtLeast(0.1f)

                val path = Path()

                pathPoints.forEachIndexed { index, p ->
                    val x = padding + ((p.x - minX) / range) * width
                    val y = padding + ((p.z - minZ) / range) * height

                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(path, color = Color.Blue, style = Stroke(width = 4f))

                // Start point
                drawCircle(
                    Color.Green,
                    radius = 12f,
                    center = Offset(
                        padding + ((pathPoints.first().x - minX) / range) * width,
                        padding + ((pathPoints.first().z - minZ) / range) * height
                    )
                )

                // End point
                drawCircle(
                    Color.Red,
                    radius = 12f,
                    center = Offset(
                        padding + ((pathPoints.last().x - minX) / range) * width,
                        padding + ((pathPoints.last().z - minZ) / range) * height
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}
