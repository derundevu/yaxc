package io.github.derundevu.yaxc.repository

import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.LinkDao

class LinkRepository(private val linkDao: LinkDao) {

    val all = linkDao.all()
    val tabs = linkDao.tabs()

    suspend fun activeLinks(): List<Link> {
        return linkDao.activeLinks()
    }

    suspend fun insert(link: Link) {
        if (link.id == 0L) {
            link.position = linkDao.nextPosition()
        }
        linkDao.insert(link)
    }

    suspend fun update(link: Link) {
        linkDao.update(link)
    }

    suspend fun delete(link: Link) {
        linkDao.delete(link)
    }

    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { index, id ->
            linkDao.updatePosition(id, index)
        }
    }
}
