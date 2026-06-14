package com.drishti.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drishti.app.AppState
import com.drishti.app.ScanMode
import com.drishti.app.ScanRecord
import com.drishti.app.ml.HAND_CONNECTIONS
import com.drishti.app.viewmodel.DrishtiViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DrishtiScreen(
    cameraGrantedFlow: StateFlow<Boolean>,
    micGrantedFlow: StateFlow<Boolean>,
    scanTriggerFlow: StateFlow<Int>,
    pttStartFlow: StateFlow<Int>,
    pttStopFlow: StateFlow<Int>,
    fallFlow: StateFlow<Int>,
    onRequestPermissions: () -> Unit,
    vm: DrishtiViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val statusText by vm.statusText.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val scanMode by vm.scanMode.collectAsStateWithLifecycle()
    val ttsSpeed by vm.ttsSpeed.collectAsStateWithLifecycle()
    val scanHistory by vm.scanHistory.collectAsStateWithLifecycle()
    val isAutoScanning by vm.isAutoScanning.collectAsStateWithLifecycle()
    val handLandmarks by vm.handLandmarks.collectAsStateWithLifecycle()
    val obstacleMode by vm.obstacleMode.collectAsStateWithLifecycle()
    val findTarget by vm.findTarget.collectAsStateWithLifecycle()
    val fallCountdown by vm.fallCountdown.collectAsStateWithLifecycle()
    val cameraGranted by cameraGrantedFlow.collectAsStateWithLifecycle()
    val micGranted by micGrantedFlow.collectAsStateWithLifecycle()
    val scanTrigger by scanTriggerFlow.collectAsStateWithLifecycle()
    val pttStart by pttStartFlow.collectAsStateWithLifecycle()
    val pttStop by pttStopFlow.collectAsStateWithLifecycle()
    val fallSignal by fallFlow.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = remember { PreviewView(context) }

    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(cameraGranted) {
        if (cameraGranted) vm.cameraManager.bindCamera(lifecycleOwner, previewView, vm.handAnalyzer, vm.objectAnalyzer)
    }

    // Accelerometer fall → start the cancelable SOS countdown
    LaunchedEffect(fallSignal) {
        if (fallSignal > 0) vm.onFallDetected()
    }

    // Vol DOWN or shake: cancel a fall countdown first; else stop TTS if speaking; else scan
    LaunchedEffect(scanTrigger) {
        if (scanTrigger > 0) {
            if (fallCountdown > 0) {
                vm.cancelFall()
            } else if (state is AppState.Speaking) {
                vm.stopSpeaking()
            } else if (cameraGranted) {
                val bmp = vm.cameraManager.captureFrame()
                bmp?.let { vm.onScanTap(it) }
            }
        }
    }

    LaunchedEffect(pttStart) {
        if (pttStart > 0 && micGranted) vm.startListening()
    }

    LaunchedEffect(pttStop) {
        if (pttStop > 0) vm.stopListening()
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF060A14))) {

        // Camera preview — tap anywhere on camera surface to scan
        if (cameraGranted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Camera viewfinder. Tap to scan." }
                    .clickable(onClickLabel = "Scan scene") {
                        scope.launch {
                            if (state is AppState.Speaking) {
                                vm.stopSpeaking()
                            } else {
                                val bmp = vm.cameraManager.captureFrame()
                                bmp?.let { vm.onScanTap(it) }
                            }
                        }
                    }
            )

            // Hand landmark overlay — drawn live whenever a hand is in view (any mode)
            if (handLandmarks.size == 21) {
                val dotColor = Color(0xFF00FFCC)
                val lineColor = Color(0xFF00FFCC).copy(alpha = 0.7f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // MediaPipe landmarks are normalized to image (x right, y down)
                    // Camera preview fills screen — map directly to canvas size
                    fun lm(i: Int) = androidx.compose.ui.geometry.Offset(
                        handLandmarks[i].first * size.width,
                        handLandmarks[i].second * size.height
                    )
                    // Draw bone connections
                    for ((a, b) in HAND_CONNECTIONS) {
                        drawLine(lineColor, lm(a), lm(b), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    }
                    // Draw joint dots
                    for (i in 0 until 21) {
                        val dotSize = if (i == 0 || i == 4 || i == 8 || i == 12 || i == 16 || i == 20) 7.dp.toPx() else 4.dp.toPx()
                        drawCircle(dotColor, dotSize, lm(i))
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera access needed", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.semantics { contentDescription = "Grant camera permission" }
                    ) { Text("Grant Permissions") }
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
                "✦ DRISHTI",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp,
                modifier = Modifier.semantics { contentDescription = "Drishti app" }
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // TTS speed chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF7C4DFF).copy(0.22f))
                        .border(1.dp, Color(0xFF7C4DFF).copy(0.5f), RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Change speech speed, currently ${ttsSpeed.label}") { vm.cycleTtsSpeed() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        ttsSpeed.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB39DDB),
                        fontWeight = FontWeight.Bold
                    )
                }
                // SOS button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF1744))
                        .clickable(onClickLabel = "Send emergency SOS") { vm.triggerSos() }
                        .semantics { role = Role.Button; contentDescription = "SOS emergency button" },
                    contentAlignment = Alignment.Center
                ) {
                    Text("SOS", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp)
                }
                // Online status dot
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = if (isOnline) "Online" else "Offline"
                    }
                ) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(if (isOnline) Color(0xFF00E5FF) else Color(0xFFFF4444))
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        if (isOnline) "ONLINE" else "OFFLINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOnline) Color(0xFF00E5FF) else Color(0xFFFF4444),
                        fontSize = 9.sp
                    )
                }
            }
        }

        // Bottom panel — frosted glass with deep indigo tint
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.18f to Color(0xCC060A14),
                        1f to Color(0xF2060A14)
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode chips row with emoji icons
            fun modeEmoji(m: ScanMode) = when (m) {
                ScanMode.NORMAL   -> "🌍"
                ScanMode.BARCODE  -> "⬛"
                ScanMode.CURRENCY -> "💵"
                ScanMode.FACE     -> "👤"
                ScanMode.COLOR    -> "🎨"
                ScanMode.DOCUMENT -> "📄"
                ScanMode.SIGN     -> "🤟"
                ScanMode.AUTO     -> "🔄"
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(ScanMode.entries) { mode ->
                    val isSelected = scanMode == mode || (mode == ScanMode.AUTO && isAutoScanning)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (isSelected)
                                    Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF)))
                                else
                                    Brush.horizontalGradient(listOf(Color.White.copy(0.09f), Color.White.copy(0.09f)))
                            )
                            .border(
                                width = if (isSelected) 0.dp else 1.dp,
                                color = Color.White.copy(0.18f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable(onClickLabel = "Switch to ${mode.label} mode") { vm.setScanMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { role = Role.Button; contentDescription = "${mode.label} scan mode${if (isSelected) ", selected" else ""}" },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(modeEmoji(mode), fontSize = 11.sp)
                            Text(
                                text = mode.label,
                                color = if (isSelected) Color.White else Color.White.copy(0.72f),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            val isListening = state is AppState.Listening

            // Contextual status banner
            AnimatedVisibility(visible = isListening) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF00E5FF).copy(0.12f))
                        .padding(vertical = 4.dp)
                ) {
                    Text("🎤  Listening — release Vol+ to send", style = MaterialTheme.typography.labelSmall, color = Color(0xFF00E5FF))
                }
            }
            AnimatedVisibility(visible = isAutoScanning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFD700).copy(0.1f))
                        .padding(vertical = 4.dp)
                ) {
                    Text("🔄  Auto-scanning every 5 s — tap a mode to stop", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFD700))
                }
            }
            AnimatedVisibility(visible = obstacleMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF6D00).copy(0.14f))
                        .padding(vertical = 4.dp)
                ) {
                    Text("🦯  Walking assistant on — say stop to turn off", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9E40))
                }
            }
            AnimatedVisibility(visible = findTarget != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF00E5FF).copy(0.12f))
                        .padding(vertical = 4.dp)
                ) {
                    Text("🔍  Looking for ${findTarget ?: ""} — say stop to cancel", style = MaterialTheme.typography.labelSmall, color = Color(0xFF00E5FF))
                }
            }

            // Status text — main output
            AnimatedContent(
                targetState = statusText,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "status"
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(0.9f),
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .semantics { contentDescription = text }
                )
            }

            Spacer(Modifier.height(4.dp))

            // Control row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language button
                OutlinedButton(
                    onClick = { vm.cycleLanguage() },
                    modifier = Modifier
                        .size(54.dp)
                        .semantics { contentDescription = "Change language, currently ${language.displayName}"; role = Role.Button },
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFF7C4DFF).copy(0.6f))
                ) {
                    Text(language.displayName, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color(0xFFB39DDB))
                }

                // Scan button — larger, gradient glow
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

                // Mic button
                IconButton(
                    onClick = {
                        if (isListening) vm.stopListening()
                        else if (micGranted) vm.startListening()
                        else onRequestPermissions()
                    },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening)
                                Brush.radialGradient(listOf(Color(0xFF00E5FF).copy(0.25f), Color.Transparent))
                            else
                                Brush.radialGradient(listOf(Color.White.copy(0.06f), Color.Transparent))
                        )
                        .border(1.dp, if (isListening) Color(0xFF00E5FF) else Color.White.copy(0.25f), CircleShape)
                        .semantics { contentDescription = if (isListening) "Stop listening" else "Ask a question by voice"; role = Role.Button }
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (isListening) Color(0xFF00E5FF) else Color.White.copy(0.85f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // History button
                IconButton(
                    onClick = { showHistory = !showHistory },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (showHistory) Color(0xFF7C4DFF).copy(0.18f) else Color.White.copy(0.06f))
                        .border(1.dp, if (showHistory) Color(0xFF7C4DFF).copy(0.7f) else Color.White.copy(0.2f), CircleShape)
                        .semantics { contentDescription = if (showHistory) "Hide scan history" else "Show scan history"; role = Role.Button }
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = if (showHistory) Color(0xFFB39DDB) else Color.White.copy(0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Tap camera • Vol↓ scan • Vol↑ hold = talk • shake = scan",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(0.25f),
                textAlign = TextAlign.Center,
                fontSize = 10.sp
            )
        }

        // History panel
        AnimatedVisibility(
            visible = showHistory,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 200.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recent Scans",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "tap to replay",
                            color = Color.White.copy(0.4f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (scanHistory.isEmpty()) {
                        Text("No scans yet", color = Color.White.copy(0.4f), style = MaterialTheme.typography.bodySmall)
                    } else {
                        scanHistory.forEachIndexed { i, record ->
                            HistoryRow(record = record, onClick = {
                                vm.replayScan(record)
                                showHistory = false
                            })
                            if (i < scanHistory.lastIndex) {
                                HorizontalDivider(color = Color.White.copy(0.08f), modifier = Modifier.padding(vertical = 6.dp))
                            }
                        }
                    }
                }
            }
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

        // Fall-detection emergency overlay — full-screen, tap anywhere to cancel
        if (fallCountdown > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFFB00020).copy(0.92f))
                    .clickable(onClickLabel = "Cancel emergency SOS") { vm.cancelFall() }
                    .semantics { contentDescription = "Fall detected. Sending S O S in $fallCountdown seconds. Double tap to cancel." },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 64.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("FALL DETECTED", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Sending SOS in", color = Color.White.copy(0.9f), fontSize = 16.sp)
                    Text("$fallCountdown", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 72.sp)
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .clickable(onClickLabel = "Cancel emergency SOS") { vm.cancelFall() }
                            .padding(horizontal = 36.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("I'M OK — CANCEL", color = Color(0xFFB00020), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Tap anywhere or press a volume key to cancel", color = Color.White.copy(0.8f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(record: ScanRecord, onClick: () -> Unit) {
    val timeStr = remember(record.timestamp) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(record.timestamp))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Replay: ${record.text}") { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.width(52.dp)) {
            Text(
                record.mode.label,
                color = Color(0xFF00FF88),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                timeStr,
                color = Color.White.copy(0.3f),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = record.text,
            color = Color.White.copy(0.75f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
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

    Box(
        Modifier
            .size(96.dp)
            .scale(scale)
            .semantics { contentDescription = if (busy) "Scanning in progress" else "Scan button, double tap to scan"; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        // Glow ring
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    if (busy)
                        Brush.radialGradient(listOf(Color(0xFF7C4DFF).copy(pulseAlpha), Color.Transparent))
                    else
                        Brush.radialGradient(listOf(Color(0xFF00E5FF).copy(0.18f), Color.Transparent))
                )
        )
        FilledIconButton(
            onClick = { if (!busy && enabled) onClick() },
            modifier = Modifier
                .size(80.dp)
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF), Color(0xFF00E5FF))),
                    shape = CircleShape
                ),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (busy) Color(0xFF0F1225) else Color(0xFF141830),
                disabledContainerColor = Color(0xFF0F1225).copy(0.5f)
            ),
            enabled = !busy && enabled
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = Color(0xFF7C4DFF),
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}
