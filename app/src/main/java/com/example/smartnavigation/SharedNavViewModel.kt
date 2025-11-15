package com.example.smartnavigation

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.google.ar.core.Anchor

class SharedNavViewModel : ViewModel() {
    val slamAnchor = mutableStateOf<Anchor?>(null)
    val pinPosition = mutableStateOf<Pair<Float, Float>?>(null)

    fun setPin(anchor: Anchor) {
        slamAnchor.value = anchor
        // Store the X and Z position of the anchor
        pinPosition.value = Pair(anchor.pose.tx(), anchor.pose.tz())
    }

    fun clearPin() {
        slamAnchor.value?.detach()
        slamAnchor.value = null
        pinPosition.value = null
    }
}