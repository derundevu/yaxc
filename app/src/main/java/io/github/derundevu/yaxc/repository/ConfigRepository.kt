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
            var finalRules = parsed.rules

            if (
                settings.coreRoutingDefaultsVersion < CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION &&
                parsed.unsupportedRuleCount == 0 &&
                CoreRoutingHelper.shouldUpgradeSeededDefaults(finalRules)
            ) {
                val ruIpRule = CoreRoutingHelper.defaultRuIpRule()
                finalRules = finalRules.toMutableList().apply { add(2, ruIpRule) }
            }

            val migratedRouting = CoreRoutingHelper.buildRoutingJson(
                domainStrategy = parsed.domainStrategy,
                rules = finalRules,
                ruleOrder = if (finalRules.size == parsed.rules.size) parsed.ruleOrder else emptyList(),
                preservedTopLevel = parsed.preservedTopLevel,
            )
            val migratedUiRulesRaw = CoreRoutingHelper.encodeUiRules(finalRules, parsed.ruleOrder)

            val updated = config.copy(routing = migratedRouting)
            if (migratedRouting != config.routing) {
                configDao.update(updated)
            }
            settings.coreRoutingUiRules = migratedUiRulesRaw
            if (settings.coreRoutingDefaultsVersion < CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION) {
                settings.coreRoutingDefaultsVersion = CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION
            }
            return updated
        }
        val seededRules = CoreRoutingHelper.defaultSeededRules()
        val seeded = Config(
            routing = CoreRoutingHelper.buildRoutingJson(
                domainStrategy = "IPIfNonMatch",
                rules = seededRules,
            ),
            routingMode = Config.Mode.Merge,
        )
        configDao.insert(seeded)
        settings.coreRoutingUiRules = CoreRoutingHelper.encodeUiRules(seededRules)
        settings.coreRoutingDefaultsVersion = CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION
        return seeded
    }

    suspend fun update(config: Config) {
        configDao.update(config)
    }
}
