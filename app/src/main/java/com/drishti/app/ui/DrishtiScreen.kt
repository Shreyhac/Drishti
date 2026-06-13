package com.drishti.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drishti.app.AppState
import com.drishti.app.viewmodel.DrishtiViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun DrishtiScreen(
    cameraGrantedFlow: StateFlow<Boolean>,
    micGrantedFlow: StateFlow<Boolean>,
    scanTriggerFlow: StateFlow<Int>,
    pttStartFlow: StateFlow<Int>,
    pttStopFlow: StateFlow<Int>,
    onRequestPermissions: () -> Unit,
    vm: DrishtiViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val statusText by vm.statusText.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val cameraGranted by cameraGrantedFlow.collectAsStateWithLifecycle()
    val micGranted by micGrantedFlow.collectAsStateWithLifecycle()
    val scanTrigger by scanTriggerFlow.collectAsStateWithLifecycle()
    val pttStart by pttStartFlow.collectAsStateWithLifecycle()
    val pttStop by pttStopFlow.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(cameraGranted) {
        if (cameraGranted) vm.cameraManager.bindCamera(lifecycleOwner, previewView, vm.surveillanceAnalyzer)
    }

    // Volume DOWN → capture
    LaunchedEffect(scanTrigger) {
        if (scanTrigger > 0 && cameraGranted) {
            val bmp = vm.cameraManager.captureFrame()
            bmp?.let { vm.onScanTap(it) }
        }
    }

    // Volume UP hold → push to talk start
    LaunchedEffect(pttStart) {
        if (pttStart > 0 && micGranted) vm.startListening()
    }

    // Volume UP release → push to talk stop
    LaunchedEffect(pttStop) {
        if (pttStop > 0) vm.stopListening()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // Camera preview
        if (cameraGranted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera access needed", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPermissions) { Text("Grant Permissions") }
                }
            }
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "DRISHTI",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 5.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF00FF88) else Color(0xFFFF4444))
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    if (isOnline) "ONLINE" else "OFFLINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOnline) Color(0xFF00FF88) else Color(0xFFFF4444)
                )
            }
        }

        // Bottom overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.95f)))
                )
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // PTT hint when listening
            val isListening = state is AppState.Listening
            if (isListening) {
                Text(
                    "Listening… release Vol+ to send",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF00FF88),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            AnimatedContent(
                targetState = statusText,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "status"
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language cycle button
                OutlinedButton(
                    onClick = { vm.cycleLanguage() },
                    modifier = Modifier.size(58.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(0.35f))
                ) {
                    Text(language.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                }

                // Scan button
                ScanButton(
                    state = state,
                    enabled = cameraGranted,
                    onClick = {
                        scope.launch {
                            val bmp = vm.cameraManager.captureFrame()
                            bmp?.let { vm.onScanTap(it) }
                        }
                    }
                )

                // Mic button (tap to toggle, or use Volume UP for PTT)
                IconButton(
                    onClick = {
                        if (isListening) vm.stopListening()
                        else if (micGranted) vm.startListening()
                        else onRequestPermissions()
                    },
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) Color(0xFF00FF88).copy(0.2f) else Color.White.copy(0.08f)
                        )
                        .border(
                            1.dp,
                            if (isListening) Color(0xFF00FF88) else Color.White.copy(0.3f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop" else "Ask",
                        tint = if (isListening) Color(0xFF00FF88) else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Vol↓ scan  •  Vol↑ hold = talk",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(0.3f)
            )
        }

        // Loading overlay
        if (state is AppState.Loading) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00FF88), strokeWidth = 3.dp)
                    Spacer(Modifier.height(20.dp))
                    Text("Loading Drishti…", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Checking model & connectivity", color = Color.White.copy(0.5f), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ScanButton(state: AppState, enabled: Boolean, onClick: () -> Unit) {
    val busy = state is AppState.Scanning || state is AppState.Thinking

    val scale by animateFloatAsState(
        targetValue = if (busy) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btnScale"
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    Box(Modifier.size(92.dp).scale(scale), contentAlignment = Alignment.Center) {
        if (busy) {
            Box(Modifier.size(92.dp).clip(CircleShape).background(Color.White.copy(pulseAlpha)))
        }
        FilledIconButton(
            onClick = { if (!busy && enabled) onClick() },
            modifier = Modifier.size(80.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (busy) Color(0xFF1A1A1A) else Color.White,
                disabledContainerColor = Color.White.copy(0.3f)
            ),
            enabled = !busy && enabled
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = Color(0xFF00FF88),
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Scan",
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}
