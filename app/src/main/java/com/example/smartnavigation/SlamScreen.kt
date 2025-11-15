package com.example.smartnavigation

import android.Manifest
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import com.google.accompanist.permissions.*
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import kotlin.math.atan2
import kotlin.math.sqrt

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SlamScreen(viewModel: SharedNavViewModel) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val context = LocalContext.current
    var arFragment: ArFragment? by remember { mutableStateOf(null) }
    var pinModel: ModelRenderable? by remember { mutableStateOf(null) }

    // --- Navigation State ---
    var distanceToPin by remember { mutableStateOf<Float?>(null) }
    var directionAngle by remember { mutableStateOf(0f) } // Angle to rotate the arrow

    // Load the 3D model
    LaunchedEffect(Unit) {
        if (pinModel == null) {
            ModelRenderable.builder()
                .setSource(context, R.raw.pin_model)
                .build()
                .thenAccept { model -> pinModel = model }
                .exceptionally { null }
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    if (cameraPermission.status.isGranted) {
        Box(Modifier.fillMaxSize()) { // Use Box to allow overlay
            Column(Modifier.fillMaxSize()) {
                Text(
                    "SLAM — Tap Floor to 'Pin' Location",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(16.dp)
                )

                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            val view = View.inflate(ctx, R.layout.ar_scene_layout, null)
                            val fragment = (view.context as FragmentActivity)
                                .supportFragmentManager
                                .findFragmentById(R.id.ar_fragment) as ArFragment

                            arFragment = fragment

                            // Add the Scene Update listener for navigation
                            fragment.arSceneView.scene.addOnUpdateListener(Scene.OnUpdateListener {
                                onSceneUpdate(it, viewModel, arFragment, onUpdate = { dist, angle ->
                                    distanceToPin = dist
                                    directionAngle = angle
                                })
                            })

                            fragment.setOnTapArPlaneListener { hitResult, plane, _ ->
                                if (pinModel != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                                    viewModel.clearPin()
                                    removeAllAnchors(fragment)
                                    val anchor = hitResult.createAnchor()
                                    viewModel.setPin(anchor)
                                    placePinObject(fragment, anchor, pinModel!!)
                                }
                            }
                            view
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Button(
                    onClick = {
                        viewModel.clearPin()
                        arFragment?.let { removeAllAnchors(it) }
                        distanceToPin = null // Clear navigation UI
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) { Text("Clear Pin") }
            }

            // --- Navigation Overlay UI ---
            if (distanceToPin != null && viewModel.slamAnchor.value != null) {
                NavigationOverlay(distance = distanceToPin!!, angle = directionAngle)
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission required for SLAM.")
        }
    }
}

// This function runs on every AR frame to calculate direction and distance
private fun onSceneUpdate(
    frameTime: FrameTime,
    viewModel: SharedNavViewModel,
    arFragment: ArFragment?,
    onUpdate: (distance: Float, angle: Float) -> Unit
) {
    val pinAnchor = viewModel.slamAnchor.value ?: return
    val camera = arFragment?.arSceneView?.scene?.camera ?: return

    // 1. Get positions
    val pinPosition = pinAnchor.pose
    val cameraPosition = camera.worldPosition

    // 2. Calculate distance (on the 2D floor plane)
    val dx = pinPosition.tx() - cameraPosition.x
    val dz = pinPosition.tz() - cameraPosition.z
    val distance = sqrt(dx * dx + dz * dz)

    // 3. Calculate direction vector and angle
    val directionToPin = Vector3(dx, 0f, dz).normalized()
    val cameraForward = camera.forward.let { Vector3(it.x, 0f, it.z).normalized() }

    // Angle between camera forward and pin direction in degrees
    val angleRad = atan2(cameraForward.cross(directionToPin).y, cameraForward.dot(directionToPin))
    val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

    onUpdate(distance, -angleDeg) // Negate because screen rotation is opposite
}

@Composable
fun BoxScope.NavigationOverlay(distance: Float, angle: Float) {
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "%.1f m".format(distance),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium).padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        Image(
            imageVector = Icons.Default.ArrowUpward,
            contentDescription = "Directional Arrow",
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier
                .size(64.dp)
                .rotate(angle) // Rotate the arrow to point the way
        )
    }
}

private fun removeAllAnchors(arFragment: ArFragment) {
    arFragment.arSceneView.scene.children.toList().forEach { node ->
        if (node is AnchorNode) {
            node.anchor?.detach()
            node.setParent(null)
        }
    }
}

private fun placePinObject(arFragment: ArFragment, anchor: Anchor, model: ModelRenderable) {
    val anchorNode = AnchorNode(anchor)
    val node = TransformableNode(arFragment.transformationSystem)
    node.renderable = model
    node.setParent(anchorNode)
    arFragment.arSceneView.scene.addChild(anchorNode)
    node.select()
}