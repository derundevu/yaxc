package io.github.derundevu.yaxc.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkDao {
    @Query("SELECT * FROM links ORDER BY position ASC, id ASC")
    fun all(): Flow<List<Link>>

    @Query("SELECT * FROM links WHERE is_active = 1 ORDER BY position ASC, id ASC")
    fun tabs(): Flow<List<Link>>

    @Query("SELECT * FROM links WHERE is_active = 1 ORDER BY position ASC, id ASC")
    suspend fun activeLinks(): List<Link>

    @Query("SELECT * FROM links WHERE id = :id")
    suspend fun find(id: Long): Link?

    @Query("SELECT id FROM links ORDER BY position ASC, id ASC")
    suspend fun orderedIds(): List<Long>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM links")
    suspend fun nextPosition(): Int

    @Query("UPDATE links SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    @Transaction
    suspend fun updatePositions(ids: List<Long>) {
        val remaining = orderedIds().toMutableSet()
        var position = 0
        ids.forEach { id ->
            if (remaining.remove(id)) updatePosition(id, position++)
        }
        remaining.forEach { id -> updatePosition(id, position++) }
    }

    @Insert
    suspend fun insert(link: Link): Long

    @Transaction
    suspend fun insertAtEnd(link: Link): Long {
        if (link.id == 0L) link.position = nextPosition()
        return insert(link)
    }

    @Update
    suspend fun update(link: Link)

    @Delete
    suspend fun delete(link: Link)
}
