package com.example.smartnavigation

import android.Manifest
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.accompanist.permissions.*
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.concurrent.Executor

/**
 * A helper function to format the ARCore Pose object into a readable string.
 */
fun Pose.toShortString(): String {
    val t = translation
    val r = rotationQuaternion
    return "Pos: [${t[0].format(2)}, ${t[1].format(2)}, ${t[2].format(2)}] \n" +
            "Rot: [${r[0].format(2)}, ${r[1].format(2)}, ${r[2].format(2)}, ${r[3].format(2)}]"
}
fun Float.format(digits: Int) = "%.${digits}f".format(this)

/**
 * A simple OpenGL utility to create a GL_TEXTURE_EXTERNAL_OES texture.
 * ARCore uses this to share the camera feed.
 */
object GlUtil {
    fun createExternalTextureId(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
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
        return textureId
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SlamScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 1. ARCore Session State
    var arSession by remember { mutableStateOf<Session?>(null) }
    var arInstallAttempted by remember { mutableStateOf(false) }
    var textureId by remember { mutableStateOf<Int?>(null) } // To hold the GL texture

    // 2. SLAM Tracking State
    val slamPose = remember { mutableStateOf("Initializing SLAM...") }
    val slamTrackingState = remember { mutableStateOf(TrackingState.STOPPED) }

    // 3. Camera Permission
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    // 4. ARCore Session Creation and Management
    LaunchedEffect(cameraPermission.status, arInstallAttempted) {
        if (!cameraPermission.status.isGranted) {
            slamPose.value = "Camera permission denied."
            return@LaunchedEffect
        }

        // Try to create the ARCore session.
        val session = try {
            when (ArCoreApk.getInstance().requestInstall(context as android.app.Activity, !arInstallAttempted)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arInstallAttempted = true
                    slamPose.value = "ARCore installation requested..."
                    return@LaunchedEffect
                }
                ArCoreApk.InstallStatus.INSTALLED -> { /* ARCore is installed. Great. */ }
                else -> { /* Other cases */ }
            }
            Session(context)
        } catch (e: Exception) {
            val message = when (e) {
                is UnavailableApkTooOldException -> "ARCore APK is too old."
                is UnavailableSdkTooOldException -> "ARCore SDK is too old."
                is UnavailableDeviceNotCompatibleException -> "Device not compatible with ARCore."
                is UnavailableUserDeclinedInstallationException -> "User declined ARCore installation."
                else -> "Failed to create ARCore session: ${e.message}"
            }
            Log.e("SlamScreen", message, e)
            slamPose.value = message
            null
        }

        if (session == null) {
            arSession = null
            return@LaunchedEffect
        }

        // Configure and resume the session
        try {
            val newTextureId = GlUtil.createExternalTextureId()
            textureId = newTextureId
            session.setCameraTextureName(newTextureId)

            val config = Config(session)
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            session.configure(config)
            session.resume()
            arSession = session
        } catch (e: CameraNotAvailableException) {
            slamPose.value = "Camera not available."
            arSession = null
        } catch (e: Exception) {
            slamPose.value = "Failed to configure/resume ARCore: ${e.message}"
            arSession = null
        }
    }


    // 5. SLAM Update Loop (The "Game Loop")
    DisposableEffect(arSession) {
        // CORRECTED: Use an 'if' guard clause.
        if (arSession == null) {
            return@DisposableEffect onDispose { }
        }

        // If we reach here, arSession is guaranteed to be non-null.
        val session = arSession!!

        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                try {
                    // This is the core SLAM update.
                    // This now works because the session is configured with a camera texture.
                    val frame = session.update()
                    val camera = frame.camera

                    slamTrackingState.value = camera.trackingState
                    if (camera.trackingState == TrackingState.TRACKING) {
                        slamPose.value = camera.pose.toShortString()
                    } else {
                        slamPose.value = "Tracking lost: ${camera.trackingState}"
                    }

                } catch (e: CameraNotAvailableException) {
                    Log.e("SlamScreen", "Camera not available during update", e)
                    slamPose.value = "Camera not available."
                } catch (e: Exception) {
                    Log.e("SlamScreen", "Error during ARCore update", e)
                    slamPose.value = "ARCore update error."
                }

                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        Choreographer.getInstance().postFrameCallback(frameCallback)

        // This is the onDispose for the successful path.
        // It is correctly the last statement.
        onDispose {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            session.pause()
            session.close()
        }
    }



    // 6. The UI
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("SLAM — ARCore Pose", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (cameraPermission.status.isGranted && textureId != null) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    surfaceTextureId = textureId!!
                )
                // UI Overlay
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xAA000000), Color.Black)
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                ) {
                    Text(
                        "ARCore Tracking: ${slamTrackingState.value}",
                        color = if (slamTrackingState.value == TrackingState.TRACKING) Color.Green else Color.Yellow,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "SLAM Pose Data:",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        slamPose.value,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 24.sp
                    )
                }

            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Camera permission required for SLAM.", color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                            Text("Grant Permission")
                        }
                    }
                }
            }
        }
    }
}

/**
 * CameraX Composable modified to accept a texture ID for surface sharing.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    surfaceTextureId: Int
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = {
            // This factory block is only called once, which is not what we want for texture changes.
            // The logic is moved to the update block.
            PreviewView(it)
        },
        modifier = modifier,
        update = { previewView ->
            // This block is recomposed when inputs like surfaceTextureId change.
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            val preview = Preview.Builder().build()
            val surfaceTexture = SurfaceTexture(surfaceTextureId)

            preview.setSurfaceProvider { request ->
                surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
                    // Release the surface and texture when the consumer is done
                    it.surface.release()
                    surfaceTexture.release()
                }
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
        }
    )
}
