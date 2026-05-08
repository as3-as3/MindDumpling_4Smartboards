package com.neurodumpling.app.viewmodel
import android.util.Log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.neurodumpling.app.data.MindMapDatabase
import com.neurodumpling.app.models.MindMapData
import com.neurodumpling.app.models.Node
import com.neurodumpling.app.models.Relationship
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import java.util.*
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.neurodumpling.app.utils.ExportService
import kotlin.math.roundToInt

class MindMapViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        MindMapDatabase::class.java, "mindmap-db"
    ).fallbackToDestructiveMigration().build()
    private val dao = db.mindMapDao()

    private val _currentSpaceId = MutableStateFlow("mindmap_1")
    val currentSpaceId = _currentSpaceId.asStateFlow()

    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes = _nodes.asStateFlow()

    private val _relationships = MutableStateFlow<List<Relationship>>(emptyList())
    val relationships = _relationships.asStateFlow()

    private val _studioTitle = MutableStateFlow("Studio")
    val studioTitle = _studioTitle.asStateFlow()

    init {
        viewModelScope.launch {
            _currentSpaceId.collectLatest { spaceId ->
                Log.d("ND_VM", "Switching to Space: $spaceId")
                dao.getNodesBySpace(spaceId).collect { 
                    Log.d("ND_VM", "Loaded ${it.size} nodes for $spaceId")
                    _nodes.value = it 
                }
            }
        }
        viewModelScope.launch {
            _currentSpaceId.collectLatest { spaceId ->
                dao.getRelationshipsBySpace(spaceId).collect { _relationships.value = it }
            }
        }
        viewModelScope.launch {
            _currentSpaceId.collectLatest { spaceId ->
                dao.getSpaceById(spaceId).collect { space ->
                    if (space != null) _studioTitle.value = space.name
                    else {
                        // Create default space if missing
                        dao.insertSpaces(listOf(com.neurodumpling.app.models.Space(spaceId, "Studio", if (spaceId.contains("mindmap")) "mindmap" else "conceptmap")))
                    }
                }
            }
        }
    }

    private val _mode = MutableStateFlow("mindmap")
    val mode = _mode.asStateFlow()

    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode = _isDarkMode.asStateFlow()

    private val _history = MutableStateFlow<List<MindMapData>>(emptyList())
    val historyCount = _history.map { it.size }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    fun saveToHistory() {
        val currentData = MindMapData(_nodes.value, _relationships.value)
        _history.update { (it + currentData).takeLast(30) }
    }

    fun undo() {
        val hist = _history.value
        if (hist.isEmpty()) return
        
        // Save current state to redo stack before undoing
        val currentData = MindMapData(_nodes.value, _relationships.value)
        _redoHistory.update { (it + currentData).takeLast(30) }
        
        val last = hist.last()
        _history.update { it.dropLast(1) }
        _nodes.value = last.nodes
        _relationships.value = last.relationships
        viewModelScope.launch {
            dao.updateAllForSpace(_currentSpaceId.value, last.nodes, last.relationships)
        }
    }

    private val _redoHistory = MutableStateFlow<List<MindMapData>>(emptyList())
    val canRedo = _redoHistory.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun redo() {
        val redoHist = _redoHistory.value
        if (redoHist.isEmpty()) return
        
        val next = redoHist.last()
        _redoHistory.update { it.dropLast(1) }
        
        // Save current to undo history
        saveToHistory()
        
        _nodes.value = next.nodes
        _relationships.value = next.relationships
        viewModelScope.launch {
            dao.updateAllForSpace(_currentSpaceId.value, next.nodes, next.relationships)
        }
    }

    fun addNode(parentId: String? = null, x: Float, y: Float, text: String = "Neuer Punkt", category: String? = null) {
        saveToHistory()
        val nodeText = category ?: text
        val nodeColor = when(category) {
            "P" -> "#ef4444"
            "E" -> "#facc15"
            "S" -> "#f97316"
            "R" -> "#22c55e"
            else -> null
        }
        val newNode = Node(
            id = UUID.randomUUID().toString().take(8),
            spaceId = _currentSpaceId.value,
            parentId = parentId,
            text = nodeText,
            x = x,
            y = y,
            category = category,
            color = nodeColor
        )
        viewModelScope.launch {
            dao.insertNodes(listOf(newNode))
        }
    }

    fun moveNode(id: String, x: Float, y: Float) {
        Log.d("ND_VM", "moveNode id:$id to $x, $y")
        _nodes.update { list ->
            list.map { if (it.id == id) it.copy(x = x, y = y) else it }
        }
    }

    fun commitNodePosition(id: String) {
        viewModelScope.launch {
            val node = _nodes.value.find { it.id == id } ?: return@launch
            // Snap to Grid (20px)
            val snappedNode = node.copy(
                x = (node.x / 20f).roundToInt() * 20f,
                y = (node.y / 20f).roundToInt() * 20f
            )
            _nodes.update { list -> list.map { if (it.id == id) snappedNode else it } }
            dao.insertNodes(listOf(snappedNode))
        }
    }

    fun updateNodeText(id: String, text: String) {
        _nodes.update { list ->
            list.map { if (it.id == id) it.copy(text = text) else it }
        }
        viewModelScope.launch {
            val node = _nodes.value.find { it.id == id } ?: return@launch
            dao.insertNodes(listOf(node))
        }
    }

    fun updateNodeSize(id: String, width: Float, height: Float) {
        _nodes.update { list ->
            list.map { if (it.id == id) it.copy(width = width, height = height) else it }
        }
        viewModelScope.launch {
            val node = _nodes.value.find { it.id == id } ?: return@launch
            dao.insertNodes(listOf(node))
        }
    }

    fun deleteNode(id: String) {
        saveToHistory()
        val remainingNodes = _nodes.value.filter { it.id != id }.map {
            if (it.parentId == id) it.copy(parentId = null) else it
        }
        val remainingRels = _relationships.value.filter { it.fromId != id && it.toId != id }
        _nodes.value = remainingNodes
        _relationships.value = remainingRels
        viewModelScope.launch {
            dao.updateAllForSpace(_currentSpaceId.value, remainingNodes, remainingRels)
        }
    }

    fun addRelationship(fromId: String, toId: String, color: String = "accent", label: String = "Link") {
        saveToHistory()
        val newRel = Relationship(
            id = UUID.randomUUID().toString().take(8),
            spaceId = _currentSpaceId.value,
            fromId = fromId,
            toId = toId,
            color = color,
            label = label
        )
        viewModelScope.launch {
            dao.insertRelationships(listOf(newRel))
        }
    }

    fun deleteRelationship(id: String) {
        saveToHistory()
        val remainingRels = _relationships.value.filter { it.id != id }
        _relationships.value = remainingRels
        viewModelScope.launch {
            dao.updateAllForSpace(_currentSpaceId.value, _nodes.value, remainingRels)
        }
    }

    fun moveRelationshipCurve(id: String, curveOffset: Float, labelT: Float) {
        _relationships.update { list ->
            list.map { if (it.id == id) it.copy(curveOffset = curveOffset, labelT = labelT) else it }
        }
    }

    fun commitRelationshipCurve(id: String) {
        viewModelScope.launch {
            val rel = _relationships.value.find { it.id == id } ?: return@launch
            dao.insertRelationships(listOf(rel))
        }
    }

    fun updateRelationshipLabel(id: String, label: String) {
        _relationships.update { list ->
            list.map { if (it.id == id) it.copy(label = label) else it }
        }
        viewModelScope.launch {
            val rel = _relationships.value.find { it.id == id } ?: return@launch
            dao.insertRelationships(listOf(rel))
        }
    }

    fun reset() {
        saveToHistory()
        _nodes.value = emptyList()
        _relationships.value = emptyList()
        viewModelScope.launch {
            dao.clearNodesBySpace(_currentSpaceId.value)
            dao.clearRelationshipsBySpace(_currentSpaceId.value)
            addNode(text = "Brainstorm", x = 707f, y = 500f)
        }
    }

    fun switchMode(newMode: String) {
        Log.d("ND_VM", "switchMode: $newMode")
        _mode.value = newMode
        _currentSpaceId.value = if (newMode == "mindmap") "mindmap_1" else "conceptmap_1"
    }

    fun exportData(): String {
        val root = JSONObject()
        val nodesArr = JSONArray()
        _nodes.value.forEach { node ->
            val n = JSONObject()
            n.put("id", node.id)
            n.put("parentId", node.parentId)
            n.put("text", node.text)
            n.put("x", node.x)
            n.put("y", node.y)
            n.put("category", node.category)
            nodesArr.put(n)
        }
        val relsArr = JSONArray()
        _relationships.value.forEach { rel ->
            val r = JSONObject()
            r.put("id", rel.id)
            r.put("fromId", rel.fromId)
            r.put("toId", rel.toId)
            r.put("color", rel.color)
            r.put("label", rel.label)
            relsArr.put(r)
        }
        root.put("nodes", nodesArr)
        root.put("relationships", relsArr)
        root.put("mode", mode.value)
        return root.toString(2)
    }

    fun importData(json: String) {
        saveToHistory()
        try {
            val root = JSONObject(json)
            val nodesArr = root.getJSONArray("nodes")
            val newNodes = mutableListOf<Node>()
            for (i in 0 until nodesArr.length()) {
                val n = nodesArr.getJSONObject(i)
                newNodes.add(Node(
                    id = n.getString("id"),
                    parentId = if (n.isNull("parentId")) null else n.getString("parentId"),
                    text = n.getString("text"),
                    x = n.getDouble("x").toFloat(),
                    y = n.getDouble("y").toFloat(),
                    category = if (n.isNull("category")) null else n.getString("category")
                ))
            }
            val relsArr = root.getJSONArray("relationships")
            val newRels = mutableListOf<Relationship>()
            for (i in 0 until relsArr.length()) {
                val r = relsArr.getJSONObject(i)
                newRels.add(Relationship(
                    id = r.getString("id"),
                    fromId = r.getString("fromId"),
                    toId = r.getString("toId"),
                    color = r.getString("color"),
                    label = if (r.isNull("label")) null else r.getString("label")
                ))
            }
            viewModelScope.launch {
                dao.updateAllForSpace(_currentSpaceId.value, newNodes, newRels)
                _mode.value = root.optString("mode", "mindmap")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerImportFromClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val json = clip.getItemAt(0).text?.toString()
            if (!json.isNullOrEmpty()) {
                importData(json)
                android.widget.Toast.makeText(context, "Daten importiert", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun updateStudioTitle(title: String) {
        _studioTitle.value = title
        viewModelScope.launch {
            dao.updateSpaceName(_currentSpaceId.value, title)
        }
    }

    fun applyPESR() {
        saveToHistory()
        val updatedNodes = _nodes.value.map { node ->
            val category = when {
                node.text.lowercase().contains(Regex("problem|schwer|hindernis|stop|bug")) -> "P"
                node.text.lowercase().contains(Regex("einfluss|herkunft|ursache|grund")) -> "E"
                node.text.lowercase().contains(Regex("symptome|anzeichen|merkmal|erscheinung")) -> "S"
                node.text.lowercase().contains(Regex("ressource|mittel|hilfe|tool|stärke")) -> "R"
                else -> null
            }
            node.copy(category = category)
        }
        _nodes.value = updatedNodes
        viewModelScope.launch {
            dao.insertNodes(updatedNodes)
        }
    }

    fun createPESRCluster(parentId: String) {
        saveToHistory()
        val parent = _nodes.value.find { it.id == parentId } ?: return
        
        val startX = parent.x
        val startY = parent.y + 150f
        
        val pNode = Node(UUID.randomUUID().toString().take(8), _currentSpaceId.value, parentId, "Problem", startX, startY, category = "P", color = "#ef4444")
        val eNode = Node(UUID.randomUUID().toString().take(8), _currentSpaceId.value, pNode.id, "Einfluss/Herkunft", startX - 250f, startY + 150f, category = "E", color = "#facc15")
        val sNode = Node(UUID.randomUUID().toString().take(8), _currentSpaceId.value, pNode.id, "Symptome", startX, startY + 150f, category = "S", color = "#f97316")
        val rNode = Node(UUID.randomUUID().toString().take(8), _currentSpaceId.value, pNode.id, "Ressourcen", startX + 250f, startY + 150f, category = "R", color = "#22c55e")
        
        val newNodes = listOf(pNode, eNode, sNode, rNode)
        val newRels = listOf(
            Relationship(UUID.randomUUID().toString().take(8), _currentSpaceId.value, parentId, pNode.id, "accent", "Diagnose"),
            Relationship(UUID.randomUUID().toString().take(8), _currentSpaceId.value, pNode.id, eNode.id, "accent", "zeigt sich"),
            Relationship(UUID.randomUUID().toString().take(8), _currentSpaceId.value, pNode.id, sNode.id, "accent", "basiert auf"),
            Relationship(UUID.randomUUID().toString().take(8), _currentSpaceId.value, pNode.id, rNode.id, "accent", "nutzt")
        )
        
        viewModelScope.launch {
            dao.insertNodes(newNodes)
            dao.insertRelationships(newRels)
        }
    }

    fun triggerPngExport(context: Context) {
        try {
            val uri = ExportService.exportToPng(context, _nodes.value, _relationships.value, false, mode.value, studioTitle.value)
            if (uri != null) {
                android.widget.Toast.makeText(context, "Gespeichert in Dokumente/NeuroDumpling", android.widget.Toast.LENGTH_LONG).show()
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/png")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Open Export"))
            }
        } catch (e: Exception) {
            Log.e("ND_VM", "PNG Export failed", e)
        }
    }

    fun triggerSvgExport(context: Context) {
        try {
            val uri = ExportService.exportToSvg(context, _nodes.value, _relationships.value, false, mode.value, studioTitle.value)
            if (uri != null) {
                android.widget.Toast.makeText(context, "Gespeichert in Dokumente/NeuroDumpling", android.widget.Toast.LENGTH_LONG).show()
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/svg+xml")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Open SVG"))
            }
        } catch (e: Exception) {
            Log.e("ND_VM", "SVG Export failed", e)
        }
    }

    fun triggerPdfExport(context: Context) {
        try {
            val uri = ExportService.exportToPdf(context, _nodes.value, _relationships.value, false, mode.value, studioTitle.value)
            if (uri != null) {
                android.widget.Toast.makeText(context, "Gespeichert in Dokumente/NeuroDumpling", android.widget.Toast.LENGTH_LONG).show()
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Open PDF"))
            }
        } catch (e: Exception) {
            Log.e("ND_VM", "PDF Export failed", e)
        }
    }

    private fun shareFile(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export NeuroDumpling"))
    }

    fun toggleTheme() {
        _isDarkMode.value = !_isDarkMode.value
    }
    
    fun cycleNodeColor(nodeId: String) {
        val node = _nodes.value.find { it.id == nodeId } ?: return
        val colors = listOf(null, "accent", "danger", "success", "info")
        val currentIndex = colors.indexOf(node.color)
        val nextIndex = (currentIndex + 1) % colors.size
        val updatedNode = node.copy(color = colors[nextIndex])
        _nodes.update { list ->
            list.map { if (it.id == nodeId) updatedNode else it }
        }
        viewModelScope.launch {
            dao.insertNodes(listOf(updatedNode))
        }
    }

    fun cycleRelationshipColor(relId: String) {
        val rel = _relationships.value.find { it.id == relId } ?: return
        val colors = listOf("accent", "red", "green", "blue", "info")
        val currentIndex = colors.indexOf(rel.color)
        val nextIndex = (currentIndex + 1) % colors.size
        val updatedRel = rel.copy(color = colors[nextIndex])
        _relationships.update { list ->
            list.map { if (it.id == relId) updatedRel else it }
        }
        viewModelScope.launch {
            dao.insertRelationships(listOf(updatedRel))
        }
    }

    fun applyGridLayout() {
        saveToHistory()
        val currentNodes = _nodes.value
        if (currentNodes.isEmpty()) return
        
        val gridStepX = 400f
        val gridStepY = 250f
        val startX = 200f
        val startY = 200f
        
        val rootNodes = currentNodes.filter { it.parentId == null }
        val updatedNodes = mutableListOf<Node>()
        
        var currentY = startY
        rootNodes.forEach { root ->
            val levelNodes = getHierarchy(root.id, currentNodes)
            levelNodes.forEach { (node, depth) ->
                updatedNodes.add(node.copy(
                    x = startX + (depth * gridStepX),
                    y = currentY
                ))
                currentY += gridStepY
            }
            currentY += gridStepY // Gap between trees
        }
        
        _nodes.value = updatedNodes
        viewModelScope.launch {
            dao.insertNodes(updatedNodes)
        }
    }

    private fun getHierarchy(rootId: String, allNodes: List<Node>, depth: Int = 0): List<Pair<Node, Int>> {
        val result = mutableListOf<Pair<Node, Int>>()
        val root = allNodes.find { it.id == rootId } ?: return result
        result.add(root to depth)
        
        allNodes.filter { it.parentId == rootId }.forEach { child ->
            result.addAll(getHierarchy(child.id, allNodes, depth + 1))
        }
        return result
    }

    fun triggerJsonExport(context: Context) {
        val root = JSONObject()
        root.put("title", _studioTitle.value)
        root.put("mode", mode.value)
        
        val nodesArray = JSONArray()
        _nodes.value.forEach { node ->
            val n = JSONObject()
            n.put("id", node.id)
            n.put("text", node.text)
            n.put("x", node.x)
            n.put("y", node.y)
            n.put("width", node.width)
            n.put("height", node.height)
            n.put("parentId", node.parentId ?: JSONObject.NULL)
            n.put("category", node.category ?: JSONObject.NULL)
            n.put("color", node.color ?: JSONObject.NULL)
            nodesArray.put(n)
        }
        root.put("nodes", nodesArray)
        
        val relsArray = JSONArray()
        _relationships.value.forEach { rel ->
            val r = JSONObject()
            r.put("id", rel.id)
            r.put("fromId", rel.fromId)
            r.put("toId", rel.toId)
            r.put("color", rel.color)
            r.put("label", rel.label ?: JSONObject.NULL)
            r.put("curveOffset", rel.curveOffset)
            r.put("labelT", rel.labelT)
            relsArray.put(r)
        }
        root.put("relationships", relsArray)
        
        ExportService.exportToJson(context, root.toString(2))
    }

    fun importFromJson(jsonString: String) {
        try {
            val root = JSONObject(jsonString)
            _studioTitle.value = root.optString("title", "Studio")
            switchMode(root.optString("mode", "mindmap"))
            
            val nodesArray = root.getJSONArray("nodes")
            val newNodes = mutableListOf<Node>()
            for (i in 0 until nodesArray.length()) {
                val n = nodesArray.getJSONObject(i)
                newNodes.add(Node(
                    id = n.getString("id"),
                    spaceId = _currentSpaceId.value,
                    text = n.getString("text"),
                    x = n.getDouble("x").toFloat(),
                    y = n.getDouble("y").toFloat(),
                    width = n.optDouble("width", 150.0).toFloat(),
                    height = n.optDouble("height", 80.0).toFloat(),
                    parentId = if (n.isNull("parentId")) null else n.getString("parentId"),
                    category = if (n.isNull("category")) null else n.getString("category"),
                    color = if (n.isNull("color")) null else n.getString("color")
                ))
            }
            
            val relsArray = root.getJSONArray("relationships")
            val newRels = mutableListOf<Relationship>()
            for (i in 0 until relsArray.length()) {
                val r = relsArray.getJSONObject(i)
                newRels.add(Relationship(
                    id = r.getString("id"),
                    spaceId = _currentSpaceId.value,
                    fromId = r.getString("fromId"),
                    toId = r.getString("toId"),
                    color = r.getString("color"),
                    label = if (r.isNull("label")) null else r.getString("label"),
                    curveOffset = r.optDouble("curveOffset", 30.0).toFloat(),
                    labelT = r.optDouble("labelT", 0.5).toFloat()
                ))
            }
            
            viewModelScope.launch {
                dao.updateAllForSpace(_currentSpaceId.value, newNodes, newRels)
            }
        } catch (e: Exception) {
            Log.e("ND_VM", "Import failed", e)
        }
    }
}
