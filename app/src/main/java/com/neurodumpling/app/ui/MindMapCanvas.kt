package com.neurodumpling.app.ui

import android.util.Log
import kotlin.math.*
import kotlin.math.roundToInt

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neurodumpling.app.models.Node
import com.neurodumpling.app.models.Relationship
import com.neurodumpling.app.viewmodel.MindMapViewModel
import androidx.compose.ui.focus.onFocusChanged
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.neurodumpling.app.utils.ExportService
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

@Composable
fun MindMapCanvas(viewModel: MindMapViewModel) {
    val nodes by viewModel.nodes.collectAsState()
    val relationships by viewModel.relationships.collectAsState()
    val isDark by viewModel.isDarkMode.collectAsState()
    val currentMode by viewModel.mode.collectAsState()
    val studioTitle by viewModel.studioTitle.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val dragPreviews = remember { mutableStateMapOf<String, Offset>() }
    val labelDragPreviews = remember { mutableStateMapOf<String, Offset>() }
    
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var activeTool by remember { mutableStateOf("none") }
    var pMenuNodeId by remember { mutableStateOf<String?>(null) }
    var relStartNodeId by remember { mutableStateOf<String?>(null) }
    var mousePos by remember { mutableStateOf(Offset.Zero) }
    
    var isSmartboardMode by remember { mutableStateOf(false) }
    var isPresentationMode by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var showMinimap by remember { mutableStateOf(true) }
    var exportPreviewType by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val json = ExportService.readTextFromUri(context, it)
            if (json != null) {
                viewModel.importFromJson(json)
                scope.launch { drawerState.close() }
            } else {
                android.widget.Toast.makeText(context, "Datei konnte nicht gelesen werden.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            android.widget.Toast.makeText(context, "Berechtigung erteilt! Starten Sie den Export erneut.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    if (exportPreviewType != null) {
        AlertDialog(
            onDismissRequest = { exportPreviewType = null },
            confirmButton = { Button(onClick = { 
                // Check permission for Android 9 and below
                if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                    val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        storagePermissionLauncher.launch(permission)
                        exportPreviewType = null
                        return@Button
                    }
                }

                when(exportPreviewType) {
                    "png" -> viewModel.triggerPngExport(context)
                    "pdf" -> viewModel.triggerPdfExport(context)
                    "svg" -> viewModel.triggerSvgExport(context)
                }
                exportPreviewType = null 
            }, colors = ButtonDefaults.buttonColors(containerColor = AccentColor)) { Text("Exportieren", color = Color.White) } },
            dismissButton = { TextButton(onClick = { exportPreviewType = null }) { Text("Abbrechen") } },
            title = { Text("Export Vorschau", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Format: ${exportPreviewType?.uppercase()}", fontSize = 14.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(modifier = Modifier.size(240.dp).background(Color(0xFFF5F5F0), RoundedCornerShape(12.dp)).border(1.dp, Color.Black.copy(alpha = 0.1f), RoundedCornerShape(12.dp))) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            if (nodes.isNotEmpty()) {
                                val minX = nodes.minOf { it.x }
                                val minY = nodes.minOf { it.y }
                                val maxX = nodes.maxOf { it.x + 150f }
                                val maxY = nodes.maxOf { it.y + 80f }
                                val w = maxX - minX
                                val h = maxY - minY
                                val scale = (min(size.width / w, size.height / h) * 0.9f).coerceAtMost(2f)
                                
                                drawContext.canvas.save()
                                drawContext.canvas.translate((size.width - w * scale)/2, (size.height - h * scale)/2)
                                drawContext.canvas.scale(scale, scale)
                                drawContext.canvas.translate(-minX, -minY)
                                
                                relationships.forEach { rel ->
                                    val f = nodes.find { it.id == rel.fromId }
                                    val t = nodes.find { it.id == rel.toId }
                                    if (f != null && t != null) {
                                        drawLine(Color.Black.copy(alpha = 0.3f), Offset(f.x + 75f, f.y + 40f), Offset(t.x + 75f, t.y + 40f), strokeWidth = 1f/scale)
                                    }
                                }
                                nodes.forEach { node ->
                                    drawRoundRect(Color.Black.copy(alpha = 0.2f), Offset(node.x, node.y), androidx.compose.ui.geometry.Size(150f, 80f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f))
                                }
                                drawContext.canvas.restore()
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Ränder wurden automatisch auf 50% der Knotenbreite optimiert.", fontSize = 11.sp, textAlign = TextAlign.Center, color = Color.Gray)
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp).fillMaxHeight(),
                drawerContainerColor = if (isDark) Color(0xFF121214) else Color(0xFFFDFDFB),
                drawerContentColor = if (isDark) DarkText else LightText,
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp).background(AccentColor.copy(alpha = 0.1f))) {
                    Text("NEURODUMPLING", modifier = Modifier.align(Alignment.Center), color = AccentColor, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                }
                
                Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text("ARBEITSMODUS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                    DrawerItem("Mind Map Mode", "🧠", currentMode == "mindmap") { viewModel.switchMode("mindmap"); scope.launch { drawerState.close() } }
                    DrawerItem("Concept Map Mode", "🕸", currentMode == "conceptmap") { viewModel.switchMode("conceptmap"); scope.launch { drawerState.close() } }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("EINSTELLUNGEN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                    DrawerItem("Thema wechseln", if (isDark) "☀" else "🌙", false) { viewModel.toggleTheme() }
                    DrawerItem("Smartboard Layout", "🖥", isSmartboardMode) { isSmartboardMode = !isSmartboardMode }
                    DrawerItem("Reset Workspace", "↺", false) { viewModel.reset(); scope.launch { drawerState.close() } }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("EXPORT & IMPORT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                    DrawerItem("Export PNG", "🖼", false) { exportPreviewType = "png"; scope.launch { drawerState.close() } }
                    DrawerItem("Export PDF", "📕", false) { exportPreviewType = "pdf"; scope.launch { drawerState.close() } }
                    DrawerItem("Export SVG", "📐", false) { exportPreviewType = "svg"; scope.launch { drawerState.close() } }
                    DrawerItem("Export JSON", "📄", false) { viewModel.triggerJsonExport(context); scope.launch { drawerState.close() } }
                    DrawerItem("Import JSON", "📥", false) { jsonLauncher.launch("*/*") }
                    DrawerItem("Import von Zwischenablage", "⤒", false) { viewModel.triggerImportFromClipboard(context); scope.launch { drawerState.close() } }

                    Spacer(modifier = Modifier.weight(1f))
                    
                    // GitHub & Version info
                    Text(
                        text = "Github: as3-as3",
                        fontSize = 11.sp,
                        color = Color(0xFF6366F1),
                        modifier = Modifier
                            .padding(start = 12.dp, bottom = 4.dp)
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/as3-as3"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Browser konnte nicht geöffnet werden.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                    )
                    
                    val version = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    } catch (e: Exception) { "1.1" }
                    
                    Text(
                        text = "Version $version",
                        fontSize = 10.sp,
                        color = Color.Gray.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 12.dp, bottom = 16.dp)
                    )
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDark) DarkBg else LightBg)
                .pointerInput(activeTool, relStartNodeId) {
                    coroutineScope {
                        launch {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (activeTool == "pan" || activeTool == "none") {
                                    panOffset += pan
                                    zoomScale = (zoomScale * zoom).coerceIn(0.1f, 5f)
                                }
                            }
                        }
                        launch {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (activeTool == "add-node") {
                                        val x = (offset.x - panOffset.x) / zoomScale
                                        val y = (offset.y - panOffset.y) / zoomScale
                                        viewModel.addNode(x = x, y = y)
                                    } else {
                                        selectedNodeId = null
                                        pMenuNodeId = null
                                        relStartNodeId = null
                                    }
                                }
                            )
                        }
                        launch {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    mousePos = event.changes.first().position
                                }
                            }
                        }
                    }
                }
        ) {
            // Workspace Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        translationX = panOffset.x,
                        translationY = panOffset.y,
                        scaleX = zoomScale,
                        scaleY = zoomScale
                    )
            ) {
                // 1. Relationships
                Canvas(modifier = Modifier.fillMaxSize()) {
                    relationships.forEach { rel ->
                        val fromNode = nodes.find { it.id == rel.fromId }
                        val toNode = nodes.find { it.id == rel.toId }
                        if (fromNode != null && toNode != null) {
                            val fromPos = Offset(fromNode.x + (dragPreviews[fromNode.id]?.x ?: 0f), fromNode.y + (dragPreviews[fromNode.id]?.y ?: 0f))
                            val toPos = Offset(toNode.x + (dragPreviews[toNode.id]?.x ?: 0f), toNode.y + (dragPreviews[toNode.id]?.y ?: 0f))
                            
                            val reverseRel = relationships.find { it.fromId == rel.toId && it.toId == rel.fromId }
                            val curve = if (reverseRel != null && (rel.curveOffset ?: 30f) == 30f) {
                                if (rel.id < reverseRel.id) 60f else -60f
                            } else {
                                rel.curveOffset ?: 30f
                            }

                            RelationshipPainter.drawConnection(
                                drawScope = this,
                                from = fromPos,
                                to = toPos,
                                fromSize = Offset(fromNode.width, fromNode.height),
                                toSize = Offset(toNode.width, toNode.height),
                                color = when(rel.color) { "danger" -> DangerColor; "success" -> SuccessColor; else -> AccentColor },
                                curveOffset = curve,
                                labelT = rel.labelT ?: 0.5f
                            )
                        }
                    }
                    
                    // MindMap lines (Skip if explicit relationship exists to avoid double lines)
                    if (currentMode == "mindmap" || currentMode == "conceptmap") {
                        nodes.forEach { node ->
                            node.parentId?.let { pId ->
                                val parent = nodes.find { it.id == pId }
                                if (parent != null) {
                                    val hasExplicitRel = relationships.any { 
                                        (it.fromId == parent.id && it.toId == node.id) || 
                                        (it.fromId == node.id && it.toId == parent.id) 
                                    }
                                    if (!hasExplicitRel) {
                                        RelationshipPainter.drawConnection(
                                            this, 
                                            Offset(parent.x + (dragPreviews[parent.id]?.x ?: 0f), parent.y + (dragPreviews[parent.id]?.y ?: 0f)),
                                            Offset(node.x + (dragPreviews[node.id]?.x ?: 0f), node.y + (dragPreviews[node.id]?.y ?: 0f)),
                                            Offset(parent.width, parent.height), Offset(node.width, node.height),
                                            AccentColor.copy(alpha = 0.3f), 0f, 0.5f, showArrow = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Nodes
                nodes.forEach { node ->
                    MindMapNode(
                        node = node,
                        dragPreviews = dragPreviews,
                        isSelected = node.id == selectedNodeId,
                        onPositionChange = { dx, dy ->
                            val current = dragPreviews[node.id] ?: Offset.Zero
                            dragPreviews[node.id] = Offset(current.x + dx, current.y + dy)
                        },
                        onDragEnd = { 
                            if (activeTool == "rel-red" || activeTool == "rel-green" || activeTool == "concept-rel") {
                                // Drag-to-Connect logic: Find node under mousePos at end of drag
                                val targetNode = nodes.find { target ->
                                    if (target.id == node.id) return@find false
                                    val nx = target.x * zoomScale + panOffset.x
                                    val ny = target.y * zoomScale + panOffset.y
                                    val nw = target.width * zoomScale
                                    val nh = target.height * zoomScale
                                    mousePos.x >= nx && mousePos.x <= nx + nw && mousePos.y >= ny && mousePos.y <= ny + nh
                                }
                                if (targetNode != null) {
                                    viewModel.addRelationship(node.id, targetNode.id, if (activeTool == "rel-red") "danger" else if (activeTool == "rel-green") "success" else "accent")
                                }
                                relStartNodeId = null
                            } else {
                                val finalOffset = dragPreviews[node.id] ?: Offset.Zero
                                viewModel.moveNode(node.id, node.x + finalOffset.x, node.y + finalOffset.y)
                                viewModel.commitNodePosition(node.id)
                                dragPreviews.remove(node.id)
                            }
                        },
                        onSelect = { 
                            if (activeTool == "rel-red" || activeTool == "rel-green" || activeTool == "concept-rel") {
                                if (relStartNodeId == null) {
                                    relStartNodeId = node.id
                                } else if (relStartNodeId == node.id) {
                                    relStartNodeId = null // Toggle to cancel
                                } else {
                                    viewModel.addRelationship(relStartNodeId!!, node.id, if (activeTool == "rel-red") "danger" else if (activeTool == "rel-green") "success" else "accent")
                                    relStartNodeId = null
                                }
                            } else if (activeTool == "delete") {
                                viewModel.deleteNode(node.id)
                            } else {
                                selectedNodeId = node.id 
                            }
                        },
                        onTextChange = { viewModel.updateNodeText(node.id, it) },
                        onAddChild = { text, cat -> viewModel.addNode(parentId = node.id, x = node.x + 250f, y = node.y + 100f, text = text ?: "Pkt", category = cat ?: text) },
                        onCreatePESR = { viewModel.createPESRCluster(node.id) },
                        onColorCycle = { viewModel.cycleNodeColor(node.id) },
                        onTogglePMenu = { pMenuNodeId = if (pMenuNodeId == node.id) null else node.id },
                        pMenuOpenId = pMenuNodeId,
                        activeTool = activeTool,
                        zoomScale = zoomScale,
                        isDark = isDark,
                        relStartNodeId = relStartNodeId
                    )
                }

                // 3. Relationship Labels
                relationships.filter { it.label != null }.forEach { rel ->
                    key(rel.id) {
                        val fromNode = nodes.find { it.id == rel.fromId }
                        val toNode = nodes.find { it.id == rel.toId }
                        if (fromNode != null && toNode != null) {
                            val reverseRel = relationships.find { it.fromId == rel.toId && it.toId == rel.fromId }
                            val curve = if (reverseRel != null && (rel.curveOffset ?: 30f) == 30f) {
                                if (rel.id < reverseRel.id) 60f else -60f
                            } else {
                                rel.curveOffset ?: 0f
                            }
                            
                            RelationshipLabel(
                                rel = rel, 
                                fromNode = fromNode, 
                                toNode = toNode, 
                                labelDragPreviews = labelDragPreviews, 
                                effectiveCurve = curve, 
                                onUpdateCurve = { o, t -> labelDragPreviews[rel.id] = Offset(o, t) }, 
                                onDragEnd = { 
                                    val p = labelDragPreviews[rel.id]
                                    if (p != null) {
                                        viewModel.moveRelationshipCurve(rel.id, p.x, p.y)
                                        viewModel.commitRelationshipCurve(rel.id)
                                        labelDragPreviews.remove(rel.id)
                                    }
                                }, 
                                onUpdateLabel = { viewModel.updateRelationshipLabel(rel.id, it) }, 
                                onDelete = { viewModel.deleteRelationship(rel.id) }, 
                                onColorCycle = { viewModel.cycleRelationshipColor(rel.id) }, 
                                activeTool = activeTool, 
                                zoomScale = zoomScale, 
                                panOffset = panOffset
                            )
                        }
                    }
                }

            // Overlay Feedback (Relationship dragging)
            if (relStartNodeId != null) {
                val startNode = nodes.find { it.id == relStartNodeId }
                if (startNode != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val start = Offset((startNode.x + startNode.width / 2) * zoomScale + panOffset.x, (startNode.y + startNode.height / 2) * zoomScale + panOffset.y)
                        val color = if (activeTool == "rel-red") DangerColor else if (activeTool == "rel-green") SuccessColor else AccentColor
                        drawLine(color, start, mousePos, strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                    }
                }
            }
            }

            }
        }

        // Static UI (Always on top of the Drawer Scrim)
        Box(modifier = Modifier.fillMaxSize()) {
            if (!isPresentationMode && !isExporting) {
                // Header
                Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
                    BasicTextField(
                        value = studioTitle,
                        onValueChange = { viewModel.updateStudioTitle(it) },
                        textStyle = TextStyle(color = if (isDark) Color.White else Color.Black, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center),
                        modifier = Modifier.width(IntrinsicSize.Min).padding(horizontal = 32.dp)
                    )
                }
            }

            // Zoom
            Column(modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = if (isSmartboardMode) 150.dp else 120.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ZoomButton("＋", { zoomScale = (zoomScale * 1.25f).coerceAtMost(5f) }, isDark)
                ZoomButton("－", { zoomScale = (zoomScale / 1.25f).coerceAtLeast(0.1f) }, isDark)
            }

            // Toolbar & Menu
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (isSmartboardMode) 48.dp else 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = { 
                    scope.launch { 
                        if (drawerState.isOpen) drawerState.close() 
                        else drawerState.open() 
                    } 
                }, modifier = Modifier.size(52.dp).background(if (isDark) Color(0xCC1A1A22) else Color(0xCCF0F0F5), CircleShape).border(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x1A000000), CircleShape)) {
                    Text("☰", color = if (isDark) Color.White else Color.Black, fontSize = 20.sp)
                }
                FloatingBottomToolbar(activeTool, { activeTool = if (activeTool == it) "none" else it }, { viewModel.undo() }, { viewModel.redo() }, viewModel.historyCount.collectAsState().value > 0, viewModel.canRedo.collectAsState().value, isDark, currentMode)
            }
        }
    }
}

@Composable
fun DrawerItem(label: String, icon: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, 
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), 
        color = if (isSelected) AccentColor.copy(alpha = 0.15f) else Color.Transparent, 
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 18.sp, modifier = Modifier.width(32.dp))
            Text(label, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) AccentColor else Color.Unspecified)
        }
    }
}

@Composable
fun MindMapNode(
    node: Node,
    dragPreviews: SnapshotStateMap<String, Offset>,
    isSelected: Boolean,
    onPositionChange: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onTextChange: (String) -> Unit,
    onSelect: () -> Unit,
    onAddChild: (String?, String?) -> Unit,
    onCreatePESR: () -> Unit,
    onColorCycle: () -> Unit,
    onTogglePMenu: () -> Unit,
    pMenuOpenId: String?,
    activeTool: String,
    zoomScale: Float,
    isDark: Boolean,
    relStartNodeId: String?
) {
    val dragPreview = dragPreviews[node.id] ?: Offset.Zero
    Box(
        modifier = Modifier
            .offset { IntOffset((node.x + dragPreview.x).roundToInt(), (node.y + dragPreview.y).roundToInt()) }
            .sizeIn(minWidth = 140.dp, maxWidth = 300.dp)
            .background(
                color = when (node.color) {
                    "accent" -> AccentColor.copy(alpha = if (isDark) 0.8f else 0.2f)
                    "danger" -> DangerColor.copy(alpha = if (isDark) 0.8f else 0.2f)
                    "success" -> SuccessColor.copy(alpha = if (isDark) 0.8f else 0.2f)
                    else -> if (isDark) DarkNodeBg else LightNodeBg
                },
                shape = RoundedCornerShape(16.dp)
            )
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) AccentColor else if (isDark) Color(0x1AFFFFFF) else Color(0x1A000000), RoundedCornerShape(16.dp))
            .pointerInput(node.id, zoomScale, activeTool) {
                detectTapGestures(onTap = { onSelect() }, onLongPress = { onTogglePMenu() })
            }
            .pointerInput(node.id, zoomScale, activeTool, relStartNodeId) {
                detectDragGestures(
                    onDragStart = { if (activeTool != "pan") onSelect() },
                    onDrag = { change, delta -> 
                        if (activeTool == "none" || activeTool == "delete" || activeTool == "cluster") {
                            change.consume()
                            onPositionChange(delta.x / zoomScale, delta.y / zoomScale) 
                        }
                    },
                    onDragEnd = { onDragEnd() }
                )
            }
            .padding(4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
            node.category?.let { cat ->
                Surface(color = if (cat == "P") DangerColor else if (cat == "E") Color(0xFFFACC15) else if (cat == "S") Color(0xFFF97316) else SuccessColor, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(cat, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            BasicTextField(
                value = node.text, 
                onValueChange = onTextChange, 
                enabled = activeTool == "none" || activeTool == "add-node",
                textStyle = TextStyle(color = if (isDark) Color.White else Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                modifier = Modifier.onFocusChanged {
                    val defaultTexts = setOf("Neuer Punkt", "Pkt", "Problem", "Einfluss/Herkunft", "Symptome", "Ressourcen", "Brainstorm")
                    if (it.isFocused && defaultTexts.contains(node.text)) {
                        onTextChange("")
                    }
                }
            )
            
            if (isSelected || activeTool == "p_mode" || activeTool == "cluster") {
                Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pMenuOpenId == node.id) {
                        listOf("P", "E", "S", "R").forEach { cat ->
                            IconButton(onClick = { onAddChild(cat, cat); onTogglePMenu() }, modifier = Modifier.size(28.dp).background(AccentColor.copy(alpha = 0.2f), CircleShape)) {
                                Text(cat, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentColor)
                            }
                        }
                    } else {
                        IconButton(onClick = { if (activeTool == "p_mode") onTogglePMenu() else onAddChild(null, null) }, modifier = Modifier.size(28.dp).background(AccentColor.copy(alpha = 0.2f), CircleShape)) { Text("+", color = AccentColor) }
                        IconButton(onClick = onCreatePESR, modifier = Modifier.size(28.dp).background(SuccessColor.copy(alpha = 0.2f), CircleShape)) { Text("📋", fontSize = 12.sp) }
                        IconButton(onClick = onColorCycle, modifier = Modifier.size(28.dp).background(Color.Gray.copy(alpha = 0.1f), CircleShape)) { Text("🎨", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingBottomToolbar(activeTool: String, onToolSelect: (String) -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, canUndo: Boolean, canRedo: Boolean, isDark: Boolean, mode: String) {
    Surface(shape = RoundedCornerShape(32.dp), color = if (isDark) Color(0xCC1A1A22) else Color(0xCCF0F0F5), modifier = Modifier.height(72.dp), border = BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x1A000000)), shadowElevation = 12.dp) {
        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton("↩", "Undo", false, onUndo, canUndo, isDark = isDark)
            ToolButton("↪", "Redo", false, onRedo, canRedo, isDark = isDark)
            ToolButton("✋", "Pan", activeTool == "pan", { onToolSelect("pan") }, isDark = isDark)
            ToolButton("＋", "Add", activeTool == "add-node", { onToolSelect("add-node") }, isDark = isDark)
            if (mode == "mindmap") {
                ToolButton("P", "Patient", activeTool == "p_mode", { onToolSelect("p_mode") }, color = DangerColor, isDark = isDark)
                ToolButton("●", "-", activeTool == "rel-red", { onToolSelect("rel-red") }, color = DangerColor, isDark = isDark)
                ToolButton("●", "+", activeTool == "rel-green", { onToolSelect("rel-green") }, color = SuccessColor, isDark = isDark)
            }
            ToolButton("🔗", "Link", activeTool == "concept-rel", { onToolSelect("concept-rel") }, isDark = isDark)
            ToolButton("🗑", "Del", activeTool == "delete", { onToolSelect("delete") }, color = DangerColor, isDark = isDark)
        }
    }
}

@Composable
fun ToolButton(icon: String, label: String, isActive: Boolean, onClick: () -> Unit, enabled: Boolean = true, color: Color = Color.Unspecified, isDark: Boolean) {
    val finalColor = if (color == Color.Unspecified) (if (isDark) Color.White else Color.Black) else color
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp).background(if (isActive) finalColor.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(16.dp))) {
        Text(icon, style = TextStyle(fontSize = 22.sp, color = if (enabled) finalColor else Color.Gray))
    }
}

@Composable
fun RelationshipLabel(rel: Relationship, fromNode: Node, toNode: Node, labelDragPreviews: SnapshotStateMap<String, Offset>, effectiveCurve: Float, onUpdateCurve: (Float, Float) -> Unit, onDragEnd: () -> Unit, onUpdateLabel: (String) -> Unit, onDelete: () -> Unit, onColorCycle: () -> Unit, activeTool: String, zoomScale: Float, panOffset: Offset) {
    // 1. Calculate base points (Centers of nodes)
    val startX = fromNode.x + fromNode.width / 2; val startY = fromNode.y + fromNode.height / 2
    val endX = toNode.x + toNode.width / 2; val endY = toNode.y + toNode.height / 2
    val dx = endX - startX; val dy = endY - startY
    val dist = sqrt(dx*dx + dy*dy).coerceAtLeast(1f)
    
    // 2. Calculate Directional Vectors
    val lineX = dx / dist; val lineY = dy / dist // Vector parallel to the line
    val pnx = -dy / dist; val pny = dx / dist    // Vector perpendicular to the line (for curve)
    
    // 3. Local State Tracking for Fluid Dragging (Prevents Global Recomposition Jitter)
    var localPreview by remember(rel.id, effectiveCurve) { 
        mutableStateOf(Offset(effectiveCurve, rel.labelT ?: 0.5f)) 
    }
    
    // 4. Calculate Control Point and Label Position based on LOCAL state
    val cpX = (startX+endX)/2 + pnx * localPreview.x; val cpY = (startY+endY)/2 + pny * localPreview.x
    val invT = 1-localPreview.y; val lX = invT*invT*startX + 2*invT*localPreview.y*cpX + localPreview.y*localPreview.y*endX
    val lY = invT*invT*startY + 2*invT*localPreview.y*cpY + localPreview.y*localPreview.y*endY
    
    Box(modifier = Modifier
        .offset { IntOffset(lX.roundToInt(), lY.roundToInt()) }
        .pointerInput(rel.id, activeTool) {
            detectTapGestures(
                onTap = { 
                    if (activeTool == "delete") onDelete() 
                    else onColorCycle() // Optional: Cycle color on normal tap if desired
                }
            )
        }
        .pointerInput(rel.id, zoomScale, activeTool) {
            detectDragGestures(
                onDrag = { change, delta -> 
                    if (activeTool == "none") {
                        change.consume()
                        val deltaCurve = (delta.x / zoomScale * pnx + delta.y / zoomScale * pny)
                        val deltaT = (delta.x / zoomScale * lineX + delta.y / zoomScale * lineY) / dist
                        
                        localPreview = Offset(
                            localPreview.x + deltaCurve, 
                            (localPreview.y + deltaT).coerceIn(0.1f, 0.9f)
                        )
                    }
                }, 
                onDragEnd = {
                    if (activeTool == "none") {
                        onUpdateCurve(localPreview.x, localPreview.y)
                        onDragEnd()
                    }
                }
            )
        }
        .background(Color(0xCC1A1A1A), RoundedCornerShape(8.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        BasicTextField(value = rel.label ?: "", onValueChange = onUpdateLabel, textStyle = TextStyle(color = Color.White, fontSize = 12.sp))
    }
}

@Composable
fun ZoomButton(icon: String, onClick: () -> Unit, isDark: Boolean) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp).background(if (isDark) Color(0xCC1A1A1E) else Color(0xCCFFFFFF), CircleShape).border(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x1A000000), CircleShape)) {
        Text(icon, color = if (isDark) Color.White else Color.Black, fontSize = 18.sp)
    }
}

@Composable
fun LegendItem(label: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        Text(text = label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
