package io.github.derundevu.yaxc.transfer

import android.content.SharedPreferences
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import org.json.JSONObject

/** Explicit allowlist: do not copy the preference file or its installation/runtime keys. */
internal class DeviceTransferPreferences private constructor(private val values: Map<String, Any>) {
    fun encode(): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }

    fun writeTo(editor: SharedPreferences.Editor) {
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                else -> error("Unknown portable preference type")
            }
        }
    }

    companion object {
        private val fields = listOf(
            text("languageTag", Settings::languageTag, max = 256),
            text("themeStyle", { themeStyle.value }, choices = YaxcThemeStyle.entries.map { it.value }.toSet()),
            text("pingAddress", Settings::pingAddress),
            text("pingType", { pingType.value }, choices = Settings.PingType.entries.map { it.value }.toSet()),
            integer("pingTimeout", Settings::pingTimeout, 1..Int.MAX_VALUE),
            text("userAgent", Settings::userAgent),
            integer("refreshLinksInterval", Settings::refreshLinksIntervalMinutes,
                Settings.MIN_REFRESH_LINKS_INTERVAL_MINUTES..Int.MAX_VALUE),
            flag("bypassLan", Settings::bypassLan),
            flag("enableIpV6", Settings::enableIpV6),
            flag("socksUdp", Settings::socksUdp),
            text("primaryDns", Settings::primaryDns),
            text("secondaryDns", Settings::secondaryDns),
            text("primaryDnsV6", Settings::primaryDnsV6),
            text("secondaryDnsV6", Settings::secondaryDnsV6),
            text("encryptedDns", Settings::encryptedDns),
            integer("tunMtu", Settings::tunMtu, 1..65_535),
            text("tunAddress", Settings::tunAddress, max = 256),
            integer("tunPrefix", Settings::tunPrefix, 0..32),
            text("tunAddressV6", Settings::tunAddressV6, max = 256),
            integer("tunPrefixV6", Settings::tunPrefixV6, 0..128),
            text("geoIpAddress", Settings::geoIpAddress),
            text("geoSiteAddress", Settings::geoSiteAddress),
            text("customGeoIpAddress", Settings::customGeoIpAddress),
            text("customGeoSiteAddress", Settings::customGeoSiteAddress),
        )

        fun capture(settings: Settings): DeviceTransferPreferences = decode(JSONObject().apply {
            fields.forEach { put(it.key, it.capture(settings)) }
        })

        fun decode(json: JSONObject): DeviceTransferPreferences {
            json.transferKeys(*fields.map { it.key }.toTypedArray())
            return DeviceTransferPreferences(fields.associate { it.key to it.decode(json) })
        }

        private fun text(
            key: String,
            capture: Settings.() -> String,
            max: Int = DeviceTransferJson.MAX_TEXT,
            choices: Set<String>? = null,
        ) = Field(key, capture) { json ->
            json.transferText(key, max).also {
                require((choices == null || it in choices) && it.none(Char::isISOControl)) { "Invalid $key preference." }
            }
        }

        private fun flag(key: String, capture: Settings.() -> Boolean) =
            Field(key, capture) { it.transferBoolean(key) }

        private fun integer(key: String, capture: Settings.() -> Int, range: IntRange) =
            Field(key, capture) { it.transferValue(key).transferInt(key, range) }

        private data class Field(val key: String, val capture: Settings.() -> Any, val decode: (JSONObject) -> Any)
    }
}
