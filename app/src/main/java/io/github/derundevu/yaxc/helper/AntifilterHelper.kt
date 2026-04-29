package io.github.derundevu.yaxc.helper

import java.io.File
import java.net.Inet6Address
import java.net.InetAddress

object AntifilterHelper {
    const val DEFAULT_URL = "https://antifilter.download/list/allyouneed.lst"
    const val ROUTING_RULE_TAG = "yaxc-antifilter-direct"

    fun readRules(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return parseRules(file.readText())
    }

    fun installValidated(source: File, target: File): Int {
        val rules = readRules(source)
        if (rules.isEmpty()) {
            throw IllegalArgumentException("Antifilter list is empty")
        }

        val validated = File(target.parentFile, "${target.name}.validated")
        validated.writeText(rules.joinToString(separator = "\n", postfix = "\n"))
        if (!validated.renameTo(target)) {
            target.writeText(validated.readText())
            validated.delete()
        }
        return rules.size
    }

    fun parseRules(raw: String): List<String> {
        val rules = LinkedHashSet<String>()
        raw.lineSequence().forEachIndexed { index, line ->
            val value = line.substringBefore('#').trim()
            if (value.isEmpty()) return@forEachIndexed
            rules += normalizeRule(value, index + 1)
        }
        return rules.toList()
    }

    private fun normalizeRule(value: String, lineNumber: Int): String {
        val parts = value.split('/', limit = 2)
        val address = parts[0].trim()
        val prefix = parts.getOrNull(1)?.trim()

        return when {
            address.contains('.') -> normalizeIpv4Rule(address, prefix, value, lineNumber)
            address.contains(':') -> normalizeIpv6Rule(address, prefix, value, lineNumber)
            else -> invalid(value, lineNumber)
        }
    }

    private fun normalizeIpv4Rule(
        address: String,
        prefix: String?,
        rawValue: String,
        lineNumber: Int,
    ): String {
        if (!isValidIpv4Address(address)) invalid(rawValue, lineNumber)
        val normalizedPrefix = prefix?.toIntOrNull()
        if (prefix != null && normalizedPrefix !in 0..32) invalid(rawValue, lineNumber)
        return if (prefix == null) address else "$address/$normalizedPrefix"
    }

    private fun normalizeIpv6Rule(
        address: String,
        prefix: String?,
        rawValue: String,
        lineNumber: Int,
    ): String {
        val parsed = runCatching { InetAddress.getByName(address) }.getOrNull()
        if (parsed !is Inet6Address) invalid(rawValue, lineNumber)
        val normalizedPrefix = prefix?.toIntOrNull()
        if (prefix != null && normalizedPrefix !in 0..128) invalid(rawValue, lineNumber)
        return if (prefix == null) address else "$address/$normalizedPrefix"
    }

    private fun isValidIpv4Address(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                !(part.length > 1 && part.startsWith('0')) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun invalid(value: String, lineNumber: Int): Nothing {
        throw IllegalArgumentException("Invalid Antifilter entry at line $lineNumber: $value")
    }
}
