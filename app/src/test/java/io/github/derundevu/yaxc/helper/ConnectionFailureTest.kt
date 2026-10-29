package io.github.derundevu.yaxc.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream

class ConnectionFailureTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun quotedMixedCaseNativeOpenFailureSurvivesOuterGeodataContext() {
        val diagnostic = "infra/conf: invalid field rule > common/geodata: illegal domain rule: geosite:ru > " +
            "CoMmOn/GeOdAtA: FaIlEd To OpEn \"geosite.dat\" > open /assets/geosite.dat: no such file or directory"

        val failure = classify(diagnostic)

        assertEquals(diagnostic, failure.diagnostic)
        assertEquals(GeodataFailure("geosite.dat", GeodataProblem.Missing), failure.geodata)
    }

    @Test
    fun explicitAbsenceUsesTheNativeCauseEvenIfFileWasReplacedAfterFailure() {
        asset("geoip.dat", entry("RU"))

        assertEquals(
            GeodataFailure("geoip.dat", GeodataProblem.Missing),
            classify("common/geodata: failed to open 'geoip.dat' > open /assets/geoip.dat: no such file or directory").geodata,
        )
    }

    @Test
    fun permissionDiagnosticIsNotConvertedToMissingBecauseFileIsAbsentLocally() {
        for (cause in listOf("permission denied", "operation not permitted", "EACCES", "access is denied")) {
            assertEquals(
                cause,
                GeodataFailure("geosite.dat", GeodataProblem.Unreadable),
                classify("common/geodata: failed to open geosite.dat > open /private/geosite.dat: $cause").geodata,
            )
        }
    }

    @Test
    fun nativeUnmarshalContextsAreInvalidRatherThanMissingGroups() {
        for ((type, filename) in listOf("IP" to "geoip.dat", "Site" to "geosite.dat")) {
            assertEquals(
                GeodataFailure(filename, GeodataProblem.Invalid, "RU"),
                classify("common/geodata: error unmarshal $type in '$filename':RU > proto: cannot parse invalid wire-format data").geodata,
            )
        }
        assertEquals(
            GeodataFailure("geosite.dat", GeodataProblem.Invalid),
            classify("common/geodata: failed to unmarshal \"geosite.dat\" > unexpected EOF").geodata,
        )
    }

    @Test
    fun cleanEntryBoundaryEofIdentifiesTheRequestedMissingGroupForCheckAndLoad() {
        asset("geosite.dat", entry("OTHER") + entry("ANOTHER"))
        asset("geoip.dat", entry("US"))

        assertEquals(
            GeodataFailure("geosite.dat", GeodataProblem.MissingGroup, "CATEGORY-RU"),
            classify("common/geodata: failed to check code CATEGORY-RU from geosite.dat > EOF").geodata,
        )
        assertEquals(
            GeodataFailure("geoip.dat", GeodataProblem.MissingGroup, "RU"),
            classify("common/geodata: failed to load code 'RU' from \"geoip.dat\" > EOF").geodata,
        )
    }

    @Test
    fun framingScanHandlesMultibyteEntryLengthsWithoutDecodingDomainPayloads() {
        val domain = byteArrayOf(0x12) + varint(140) + ByteArray(140) { 'a'.code.toByte() }
        val domains = byteArrayOf(0x12) + varint(domain.size) + domain
        asset("geosite.dat", entry("OTHER", domains) + entry("ANOTHER"))

        assertEquals(
            GeodataFailure("geosite.dat", GeodataProblem.MissingGroup, "CATEGORY-RU"),
            classify(checkEof()).geodata,
        )
    }

    @Test
    fun bareEofInsideTagOrLengthIsInvalidEvenAfterACompleteEntry() {
        val truncatedHeaders = listOf(
            byteArrayOf(0x0a),
            byteArrayOf(0x0a, 0x80.toByte()),
            byteArrayOf(0x0a, 0x80.toByte(), 0x80.toByte()),
        )
        for (header in truncatedHeaders) {
            for (prefix in listOf(byteArrayOf(), entry("OTHER"))) {
                asset("geosite.dat", prefix + header)
                assertEquals(GeodataProblem.Invalid, classify(checkEof()).geodata?.problem)
            }
        }
    }

    @Test
    fun bareEofInsideADeclaredRecordBodyIsInvalidNotMissingGroup() {
        for (bytes in listOf(
            byteArrayOf(0x0a, 0x05),
            byteArrayOf(0x0a, 0x05, 0x0a),
            entry("OTHER") + byteArrayOf(0x0a, 0x05),
            entry("OTHER") + byteArrayOf(0x0a, 0x05, 0x0a, 0x03),
        )) {
            asset("geosite.dat", bytes)
            assertEquals(
                GeodataFailure("geosite.dat", GeodataProblem.Invalid, "CATEGORY-RU"),
                classify(checkEof()).geodata,
            )
        }
    }

    @Test
    fun emptyInvalidTagZeroLengthAndOverflowingLengthCannotLookLikeMissingGroups() {
        val invalidFrames = listOf(
            byteArrayOf(),
            byteArrayOf(0x12, 0x02, 0x0a, 0x00),
            byteArrayOf(0x0a, 0x00),
            byteArrayOf(0x0a) + ByteArray(10) { 0xff.toByte() },
        )
        for (bytes in invalidFrames) {
            asset("geosite.dat", bytes)
            assertEquals(GeodataProblem.Invalid, classify(checkEof()).geodata?.problem)
        }
    }

    @Test
    fun explicitCorruptionDoesNotDependOnTheCurrentFileContents() {
        asset("geosite.dat", entry("OTHER"))
        for (cause in listOf("unexpected EOF", "common/geodata: invalid body length: 0", "common/geodata: varint overflow")) {
            assertEquals(
                GeodataProblem.Invalid,
                classify("common/geodata: failed to check code CATEGORY-RU from geosite.dat > $cause").geodata?.problem,
            )
        }
    }

    @Test
    fun unavailableEofFileIsNotMisreportedAsAMissingGroup() {
        assertEquals(GeodataProblem.Missing, classify(checkEof()).geodata?.problem)

        File(directory.root, "geosite.dat").mkdir()
        assertEquals(GeodataProblem.Unreadable, classify(checkEof()).geodata?.problem)
    }

    @Test
    fun unknownGeodataCausesRemainUnreadableRatherThanGuessingAbsenceOrMissingGroup() {
        for (diagnostic in listOf(
            "common/geodata: failed to open geoip.dat > synthetic IO failure",
            "common/geodata: failed to check code CATEGORY-RU from geosite.dat > synthetic lookup failure",
        )) {
            assertEquals(GeodataProblem.Unreadable, classify(diagnostic).geodata?.problem)
        }
    }

    @Test
    fun filenameMentionsOutsideExplicitNativeFailureContextsStayGenericVerbatim() {
        for (diagnostic in listOf(
            "infra/conf: invalid address: geosite.dat",
            "infra/conf: failed to load geoip.dat > EOF",
            "common/geodata: illegal domain rule: geosite.dat > common/geodata: empty code",
            "common/geodata: failed to check code GEOIP.DAT from custom.dat > EOF",
            "invalid config string: failed to open geosite.dat: permission denied",
        )) {
            val failure = classify(diagnostic)
            assertNull(diagnostic, failure.geodata)
            assertEquals(diagnostic, failure.diagnostic)
        }
    }

    @Test
    fun customExtFilesIncludingStandardBasenamesAtOtherPathsStayGeneric() {
        for (filename in listOf("custom.dat", "my-geosite.dat", "geosite.dat.backup", "/custom/geosite.dat", "ext:geoip.dat", "GeoSite.Dat", "GEOIP.DAT")) {
            for (message in listOf(
                "failed to open '$filename' > no such file or directory",
                "failed to check code RU from \"$filename\" > EOF",
                "error unmarshal Site in $filename:RU > unexpected EOF",
            )) {
                assertNull(message, classify("common/geodata: $message").geodata)
            }
        }
    }

    private fun classify(diagnostic: String): ConnectionFailure =
        ConnectionFailure.fromDiagnostic(diagnostic, directory.root)

    private fun checkEof(): String =
        "common/geodata: failed to check code CATEGORY-RU from geosite.dat > EOF"

    private fun asset(filename: String, bytes: ByteArray) {
        File(directory.root, filename).writeBytes(bytes)
    }

    private fun entry(group: String, tail: ByteArray = byteArrayOf()): ByteArray {
        val code = group.toByteArray(Charsets.UTF_8)
        val body = byteArrayOf(0x0a) + varint(code.size) + code + tail
        return byteArrayOf(0x0a) + varint(body.size) + body
    }

    private fun varint(value: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var remaining = value
        while (remaining >= 0x80) {
            output.write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        output.write(remaining)
        return output.toByteArray()
    }
}
