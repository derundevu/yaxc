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

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM links")
    suspend fun nextPosition(): Int

    @Query("UPDATE links SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    @Transaction
    suspend fun updatePositions(ids: List<Long>) {
        ids.forEachIndexed { index, id ->
            updatePosition(id, index)
        }
    }

    @Insert
    suspend fun insert(link: Link): Long

    @Update
    suspend fun update(link: Link)

    @Delete
    suspend fun delete(link: Link)
}
