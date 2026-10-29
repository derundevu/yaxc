package io.github.derundevu.yaxc.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AntifilterHelperTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun ipv4AndIpv6AcceptHostAndFullCidrBoundaryRanges() {
        val rules = listOf(
            "0.0.0.0", "255.255.255.255", "0.0.0.0/0", "255.255.255.255/32",
            "::", "2001:db8::1", "::/0", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128",
            "2001:db8::192.0.2.1/128",
        )
        assertEquals(rules, AntifilterHelper.parseRules(rules.joinToString("\n")))
    }

    @Test
    fun commentsBlankLinesAndDuplicatesPreserveFirstOccurrenceOrder() {
        assertEquals(
            listOf("192.0.2.0/24", "2001:db8::/32", "198.51.100.1"),
            AntifilterHelper.parseRules(
                """# synthetic list
                    |
                    | 192.0.2.0 / 024 # first IPv4 rule
                    |2001:db8::/032
                    |192.0.2.0/24
                    |198.51.100.1 # host
                    |2001:db8:: /32
                    | # trailing comment
                """.trimMargin(),
            ),
        )
    }

    @Test
    fun invalidAddressesAndPrefixesRejectEntireListWithOriginalLineNumber() {
        val invalidRules = listOf(
            "256.0.0.1", "192.0.2", "192.0.2.1.4", "192.0..1", "0192.0.2.1",
            "+192.0.2.1", "192.0.2.1/-1", "192.0.2.1/33", "192.0.2.1/+1",
            "192.0.2.1/", "192.0.2.1/24/1", "192.0.2.1/2147483648",
            "2001:db8::/129", "2001:db8::/-1", "2001:db8::/+64", "2001:db8::/",
            "2001:db8:::1", "2001:db8::g", "fe80::1%1", "not-an-address",
        )
        invalidRules.forEach { rule ->
            val failure = assertThrows("Must reject $rule", IllegalArgumentException::class.java) {
                AntifilterHelper.parseRules("# heading\n192.0.2.1\n$rule\n198.51.100.1")
            }
            assertTrue("Failure must identify source line: $rule", failure.message!!.contains("line 3:"))
        }
    }

    @Test
    fun installWritesOnlyValidatedDeduplicatedRulesAndReturnsInstalledCount() {
        val source = files.newFile("source.lst").apply {
            writeText("# heading\n192.0.2.0/024\n\n2001:db8::/32\n192.0.2.0/24\n")
        }
        val target = files.newFile("antifilter.lst").apply { writeText("198.51.100.1\n") }
        assertEquals(2, AntifilterHelper.installValidated(source, target))
        assertEquals("192.0.2.0/24\n2001:db8::/32\n", target.readText())
        assertEquals(listOf("192.0.2.0/24", "2001:db8::/32"), AntifilterHelper.readRules(target))
        assertEquals(setOf("source.lst", "antifilter.lst"), files.root.list()!!.toSet())
    }

    @Test
    fun emptyMissingAndLateInvalidSourceNeverAlterInstalledBytes() {
        val target = files.newFile("antifilter.lst").apply { writeText("198.51.100.1\n") }
        val original = target.readBytes().toList()
        val source = files.newFile("source.lst")
        listOf("", "# comments only\n \n", "192.0.2.1\n2001:db8::/129\n").forEach { text ->
            source.writeText(text)
            assertThrows(IllegalArgumentException::class.java) {
                AntifilterHelper.installValidated(source, target)
            }
            assertEquals(original, target.readBytes().toList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AntifilterHelper.installValidated(File(files.root, "missing.lst"), target)
        }
        assertEquals(original, target.readBytes().toList())
        assertEquals(setOf("source.lst", "antifilter.lst"), files.root.list()!!.toSet())
    }

    @Test
    fun concurrentInstallsPublishOneCompleteListWithoutSharedValidationFile() {
        val target = files.newFile("antifilter.lst").apply { writeText("198.51.100.1\n") }
        val firstRules = (1..200).map { "192.0.2.$it" }
        val secondRules = (1..200).map { "198.51.100.$it" }
        val first = files.newFile("first.lst").apply { writeText(firstRules.joinToString("\n")) }
        val second = files.newFile("second.lst").apply { writeText(secondRules.joinToString("\n")) }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val futures = workers.invokeAll(listOf(
                Callable { AntifilterHelper.installValidated(first, target) },
                Callable { AntifilterHelper.installValidated(second, target) },
            ))
            futures.forEach { assertEquals(200, it.get(5, TimeUnit.SECONDS)) }
            val installed = AntifilterHelper.readRules(target)
            assertTrue(installed == firstRules || installed == secondRules)
            assertEquals(setOf("first.lst", "second.lst", "antifilter.lst"), files.root.list()!!.toSet())
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
