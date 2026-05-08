package com.neurodumpling.app.data

import androidx.room.*
import com.neurodumpling.app.models.Node
import com.neurodumpling.app.models.Relationship
import com.neurodumpling.app.models.Space
import kotlinx.coroutines.flow.Flow

@Dao
interface MindMapDao {
    @Query("SELECT * FROM nodes WHERE spaceId = :spaceId")
    fun getNodesBySpace(spaceId: String): Flow<List<Node>>

    @Query("SELECT * FROM relationships WHERE spaceId = :spaceId")
    fun getRelationshipsBySpace(spaceId: String): Flow<List<Relationship>>

    @Query("SELECT * FROM spaces")
    fun getAllSpaces(): Flow<List<Space>>

    @Query("SELECT * FROM spaces WHERE id = :spaceId")
    fun getSpaceById(spaceId: String): Flow<Space?>

    @Query("UPDATE spaces SET name = :name WHERE id = :spaceId")
    suspend fun updateSpaceName(spaceId: String, name: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<Node>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelationships(relationships: List<Relationship>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpaces(spaces: List<Space>)

    @Query("DELETE FROM nodes WHERE spaceId = :spaceId")
    suspend fun clearNodesBySpace(spaceId: String)

    @Query("DELETE FROM relationships WHERE spaceId = :spaceId")
    suspend fun clearRelationshipsBySpace(spaceId: String)

    @Transaction
    suspend fun updateAllForSpace(spaceId: String, nodes: List<Node>, relationships: List<Relationship>) {
        clearNodesBySpace(spaceId)
        clearRelationshipsBySpace(spaceId)
        insertNodes(nodes)
        insertRelationships(relationships)
    }
}

@Database(entities = [Node::class, Relationship::class, Space::class], version = 2)
abstract class MindMapDatabase : RoomDatabase() {
    abstract fun mindMapDao(): MindMapDao
}
