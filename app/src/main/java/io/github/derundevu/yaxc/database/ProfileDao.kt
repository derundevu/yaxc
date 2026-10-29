package io.github.derundevu.yaxc.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.derundevu.yaxc.dto.ProfileList
import kotlinx.coroutines.flow.Flow

data class ProfilePosition(val id: Long, val index: Int)

@Dao
interface ProfileDao {
    @Query(
        "SELECT `profiles`.`id`, `profiles`.`index`, `profiles`.`name`, `profiles`.`link_id` AS `link`, `profiles`.`config`" +
        "  FROM `profiles`" +
        "  LEFT JOIN `links` ON `profiles`.`link_id` = `links`.`id`" +
        "  WHERE `links`.`is_active` IS NULL OR `links`.`is_active` = 1" +
        "  ORDER BY `profiles`.`index` ASC"
    )
    fun all(): Flow<List<ProfileList>>

    @Query("SELECT * FROM profiles WHERE link_id = :linkId ORDER BY `index` DESC")
    suspend fun linkProfiles(linkId: Long): List<Profile>

    @Query("SELECT * FROM profiles WHERE `id` = :id")
    suspend fun find(id: Long): Profile

    @Insert
    suspend fun insert(profile: Profile): Long

    @Update
    suspend fun update(profile: Profile)

    @Query("UPDATE profiles SET link_id = :linkId, name = :name, config = :config WHERE id = :id")
    suspend fun updateContent(id: Long, linkId: Long?, name: String, config: String): Int

    @Delete
    suspend fun delete(profile: Profile)

    @Query("SELECT `index` FROM profiles WHERE id = :id")
    suspend fun indexOf(id: Long): Int?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Query("UPDATE profiles SET `index` = :index WHERE `id` = :id")
    suspend fun updateIndex(index: Int, id: Long)

    @Query("SELECT `id`, `index` FROM profiles ORDER BY `index` ASC, `id` ASC")
    suspend fun orderedPositions(): List<ProfilePosition>

    @Transaction
    suspend fun normalizeIndices() {
        orderedPositions().forEachIndexed { index, profile ->
            if (profile.index != index) updateIndex(index, profile.id)
        }
    }

    @Query("UPDATE profiles SET `index` = `index` + 1")
    suspend fun fixInsertIndex()

    @Query("UPDATE profiles SET `index` = `index` - 1 WHERE `index` > :index")
    suspend fun fixDeleteIndex(index: Int)

    @Query(
        "UPDATE profiles" +
        "  SET `index` = `index` + 1" +
        "  WHERE `index` >= :start" +
        "  AND `index` < :end" +
        "  AND `id` NOT IN (:exclude)"
    )
    suspend fun fixMoveUpIndex(start: Int, end: Int, exclude: Long)

    @Query(
        "UPDATE profiles" +
        "  SET `index` = `index` - 1" +
        "  WHERE `index` > :start" +
        "  AND `index` <= :end" +
        "  AND `id` NOT IN (:exclude)"
    )
    suspend fun fixMoveDownIndex(start: Int, end: Int, exclude: Long)

    @Transaction
    suspend fun create(profile: Profile): Long {
        fixInsertIndex()
        return insert(profile.copy(index = 0))
    }

    @Transaction
    suspend fun remove(profile: Profile) {
        val index = indexOf(profile.id) ?: return
        delete(profile)
        fixDeleteIndex(index)
    }

    @Transaction
    suspend fun moveUp(start: Int, end: Int, exclude: Long) {
        val current = indexOf(exclude) ?: return
        val destination = start.coerceIn(0, count() - 1)
        if (current == destination) return
        updateIndex(destination, exclude)
        if (current > destination) fixMoveUpIndex(destination, current, exclude)
        else fixMoveDownIndex(current, destination, exclude)
    }

    @Transaction
    suspend fun moveDown(start: Int, end: Int, exclude: Long) {
        val current = indexOf(exclude) ?: return
        val destination = start.coerceIn(0, count() - 1)
        if (current == destination) return
        updateIndex(destination, exclude)
        if (current > destination) fixMoveUpIndex(destination, current, exclude)
        else fixMoveDownIndex(current, destination, exclude)
    }
}
