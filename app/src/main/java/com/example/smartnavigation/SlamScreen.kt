package com.example.smartnavigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sqrt

// -----------------------------------------------------------------------------
// Model + helpers
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
// Background renderer (camera feed) – optimized + correct orientation
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

        // Full-screen quad in NDC
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
            -1.0f, +1.0f, 0.0f,
            +1.0f, -1.0f, 0.0f,
            +1.0f, +1.0f, 0.0f
        )

        // Default UVs – ARCore will transform these based on display geometry
        private val QUAD_TEXCOORDS = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
        )
    }

    fun createOnGlThread(context: Context) {
        // Generate external texture for camera frames
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        // Compile shaders
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

        quadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(quadProgram, vs)
        GLES20.glAttachShader(quadProgram, fs)
        GLES20.glLinkProgram(quadProgram)

        quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(quadProgram, "sTexture")

        // Prepare static vertex + UV buffers
        quadVertices = ByteBuffer
            .allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_COORDS)
                position(0)
            }

        quadTexCoords = ByteBuffer
            .allocateDirect(QUAD_TEXCOORDS.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_TEXCOORDS)
                position(0)
            }

        quadTexCoordsTransformed = ByteBuffer
            .allocateDirect(QUAD_TEXCOORDS.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e("BackgroundRenderer", "Could not compile shader: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
        }
        return shader
    }

    fun draw(frame: Frame) {
        // Disable depth for background
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        // Transform UVs according to current display geometry
        quadTexCoords.position(0)
        quadTexCoordsTransformed.position(0)
        frame.transformDisplayUvCoords(quadTexCoords, quadTexCoordsTransformed)

        GLES20.glUseProgram(quadProgram)

        // Bind external OES texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        // Vertex positions
        quadVertices.position(0)
        GLES20.glVertexAttribPointer(
            quadPositionParam,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            quadVertices
        )
        GLES20.glEnableVertexAttribArray(quadPositionParam)

        // Transformed texture coordinates
        quadTexCoordsTransformed.position(0)
        GLES20.glVertexAttribPointer(
            quadTexCoordParam,
            TEXCOORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            quadTexCoordsTransformed
        )
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)

        // Draw full-screen quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}

// -----------------------------------------------------------------------------
// ARCore renderer
// -----------------------------------------------------------------------------

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

        // Use real display rotation so ARCore can orient the camera correctly
        val rotation = (context as? Activity)
            ?.windowManager
            ?.defaultDisplay
            ?.rotation ?: Surface.ROTATION_0

        session?.setDisplayGeometry(rotation, width, height)
        Log.d("ArCoreRenderer", "Surface changed: ${width}x${height}, rotation=$rotation")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        try {
            // Attach external texture to ARCore session
            currentSession.setCameraTextureName(backgroundRenderer.textureId)

            // Update frame
            val frame = currentSession.update()
            val camera = frame.camera

            // Draw camera background
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

// -----------------------------------------------------------------------------
// SLAM Screen – Compose UI
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
            when (
                ArCoreApk.getInstance().requestInstall(
                    context as Activity,
                    !arInstallAttempted
                )
            ) {
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
                    title = {
                        Text(
                            "SLAM Path Tracker",
                            fontWeight = FontWeight.Bold
                        )
                    },
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
                                    val dist = sqrt(
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

                    // Controls overlay
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color(0xAA000000),
                                        Color.Black
                                    )
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
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
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

// -----------------------------------------------------------------------------
// ARCore view wrapper
// -----------------------------------------------------------------------------

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

// -----------------------------------------------------------------------------
// Path graph screen
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
            // Stats
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Total Points: ${pathPoints.size}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Total Distance: ${totalDistance.format(2)} meters",
                        fontSize = 16.sp
                    )
                    if (pathPoints.size > 1) {
                        val duration =
                            (pathPoints.last().timestamp - pathPoints.first().timestamp) / 1000f
                        Text(
                            "Duration: ${duration.format(1)} seconds",
                            fontSize = 16.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2D Path Graph (Top View: X-Z plane)
            Text(
                "Top View (X-Z Plane)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
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

                val paddingPx = 40f
                val width = size.width - 2 * paddingPx
                val height = size.height - 2 * paddingPx

                val rangeX = maxX - minX
                val rangeZ = maxZ - minZ
                val range = max(rangeX, rangeZ).coerceAtLeast(0.1f)

                val path = Path()
                pathPoints.forEachIndexed { index, point ->
                    val x = paddingPx + ((point.x - minX) / range) * width
                    val y = paddingPx + ((point.z - minZ) / range) * height

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
                val firstX = paddingPx +
                        ((pathPoints.first().x - minX) / range) * width
                val firstZ = paddingPx +
                        ((pathPoints.first().z - minZ) / range) * height
                drawCircle(
                    Color.Green,
                    radius = 12f,
                    center = Offset(firstX, firstZ)
                )

                // Draw end point
                val lastX = paddingPx +
                        ((pathPoints.last().x - minX) / range) * width
                val lastZ = paddingPx +
                        ((pathPoints.last().z - minZ) / range) * height
                drawCircle(
                    Color.Red,
                    radius = 12f,
                    center = Offset(lastX, lastZ)
                )
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
