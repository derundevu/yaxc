package io.github.derundevu.yaxc.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.derundevu.yaxc.dto.ProfileList
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query(
        "SELECT `profiles`.`id`, `profiles`.`index`, `profiles`.`name`, `profiles`.`link_id` AS `link`, `profiles`.`config`" +
        "  FROM `profiles`" +
        "  LEFT JOIN `links` ON `profiles`.`link_id` = `links`.`id`" +
        "  WHERE `links`.`is_active` IS NULL OR `links`.`is_active` = 1" +
        "  ORDER BY COALESCE(`links`.`position`, -1) ASC, `profiles`.`index` ASC"
    )
    fun all(): Flow<List<ProfileList>>

    @Query("SELECT * FROM profiles WHERE link_id = :linkId ORDER BY `index` DESC")
    suspend fun linkProfiles(linkId: Long): List<Profile>

    @Query("SELECT * FROM profiles WHERE `id` = :id")
    suspend fun find(id: Long): Profile

    @Insert
    suspend fun insert(profile: Profile): Long

    @Insert
    suspend fun insertAll(profiles: List<Profile>): List<Long>

    @Update
    suspend fun update(profile: Profile)

    @Update
    suspend fun updateAll(profiles: List<Profile>)

    @Delete
    suspend fun delete(profile: Profile)

    @Delete
    suspend fun deleteAll(profiles: List<Profile>)

    @Query("UPDATE profiles SET `index` = :index WHERE `id` = :id")
    suspend fun updateIndex(index: Int, id: Long)

    @Query(
        "UPDATE profiles" +
        "  SET `index` = `index` + 1" +
        "  WHERE (:linkId IS NULL AND `link_id` IS NULL) OR `link_id` = :linkId"
    )
    suspend fun fixInsertIndex(linkId: Long?)

    @Query(
        "UPDATE profiles" +
        "  SET `index` = `index` - 1" +
        "  WHERE `index` > :index" +
        "  AND ((:linkId IS NULL AND `link_id` IS NULL) OR `link_id` = :linkId)"
    )
    suspend fun fixDeleteIndex(index: Int, linkId: Long?)

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
    suspend fun create(profile: Profile) {
        insert(profile)
        fixInsertIndex(profile.linkId)
    }

    @Transaction
    suspend fun remove(profile: Profile) {
        delete(profile)
        fixDeleteIndex(profile.index, profile.linkId)
    }

    @Transaction
    suspend fun moveUp(start: Int, end: Int, exclude: Long) {
        updateIndex(start, exclude)
        fixMoveUpIndex(start, end, exclude)
    }

    @Transaction
    suspend fun moveDown(start: Int, end: Int, exclude: Long) {
        updateIndex(start, exclude)
        fixMoveDownIndex(end, start, exclude)
    }
}
