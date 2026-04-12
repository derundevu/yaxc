package io.github.derundevu.yaxc.repository

import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.ConfigDao

class ConfigRepository(private val configDao: ConfigDao) {

    suspend fun get(): Config {
        val config = configDao.get()
        if (config != null) return config
        return Config().also { configDao.insert(it) }
    }

    suspend fun update(config: Config) {
        configDao.update(config)
    }
}
