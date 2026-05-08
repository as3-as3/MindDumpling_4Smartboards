package com.neurodumpling.app.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nodes")
data class Node(
    @PrimaryKey val id: String,
    val spaceId: String = "default",
    val parentId: String? = null,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float = 180f,
    val height: Float = 50f,
    val color: String? = null,
    val category: String? = null // For PESR: P, E, S, R
)

@Entity(tableName = "relationships")
data class Relationship(
    @PrimaryKey val id: String,
    val spaceId: String = "default",
    val fromId: String,
    val toId: String,
    val color: String,
    val label: String? = null,
    val labelT: Float = 0.5f,
    val curveOffset: Float = 30f
)

@Entity(tableName = "spaces")
data class Space(
    @PrimaryKey val id: String,
    val name: String,
    val type: String // "mindmap" or "conceptmap"
)

data class MindMapData(
    val nodes: List<Node>,
    val relationships: List<Relationship>
)
