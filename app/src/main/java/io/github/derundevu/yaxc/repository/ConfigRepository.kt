package io.github.derundevu.yaxc.repository

import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.ConfigDao
import io.github.derundevu.yaxc.helper.CoreRoutingHelper

class ConfigRepository(
    private val configDao: ConfigDao,
    private val settings: Settings,
) {

    suspend fun get(): Config {
        val config = configDao.get()
        if (config != null) {
            val storedUiRules = CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules)
            val parsed = CoreRoutingHelper.parseRoutingJson(
                rawJson = config.routing,
                storedUiRules = storedUiRules,
            )
            var finalRules = CoreRoutingHelper.migrateLegacyRules(parsed.rules)
            var finalUiRules = CoreRoutingHelper.migrateLegacyRules(storedUiRules)

            if (
                settings.coreRoutingDefaultsVersion < CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION &&
                CoreRoutingHelper.shouldUpgradeSeededDefaults(finalRules)
            ) {
                finalRules = CoreRoutingHelper.defaultSeededRules()
                finalUiRules = CoreRoutingHelper.defaultSeededRules()
            }
            if (settings.coreRoutingDefaultsVersion < CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION) {
                settings.coreRoutingDefaultsVersion = CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION
            }

            val migratedRouting = CoreRoutingHelper.buildRoutingJson(
                domainStrategy = parsed.domainStrategy,
                rules = finalRules,
                preservedRules = parsed.preservedRules,
                preservedTopLevel = parsed.preservedTopLevel,
            )
            val migratedUiRulesRaw = CoreRoutingHelper.encodeUiRules(finalUiRules)

            if (migratedRouting != config.routing || migratedUiRulesRaw != settings.coreRoutingUiRules) {
                val updated = config.copy(routing = migratedRouting)
                settings.coreRoutingUiRules = migratedUiRulesRaw
                configDao.update(updated)
                return updated
            }
            return config
        }
        val seededRules = CoreRoutingHelper.defaultSeededRules()
        val seeded = Config(
            routing = CoreRoutingHelper.buildRoutingJson(
                domainStrategy = "IPIfNonMatch",
                rules = seededRules,
            ),
            routingMode = Config.Mode.Merge,
        )
        settings.coreRoutingUiRules = CoreRoutingHelper.encodeUiRules(seededRules)
        settings.coreRoutingDefaultsVersion = CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION
        configDao.insert(seeded)
        return seeded
    }

    suspend fun update(config: Config) {
        configDao.update(config)
    }
}
