package io.github.derundevu.yaxc.helper

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionFailureStateTest {
    @Before
    fun setUp() {
        ConnectionFailureState.clear()
    }

    @After
    fun tearDown() {
        ConnectionFailureState.clear()
    }


    @Test
    fun staleDismissalCannotEraseANewerFailureWithAnIdenticalDiagnostic() {
        val old = ConnectionFailure("same diagnostic", GeodataFailure("geosite.dat", GeodataProblem.Missing))
        val newer = ConnectionFailure("same diagnostic", GeodataFailure("geosite.dat", GeodataProblem.Missing))
        ConnectionFailureState.report(old)
        ConnectionFailureState.report(newer)

        assertFalse(ConnectionFailureState.clear(old))
        assertSame(newer, ConnectionFailureState.failure.value)
        assertTrue(ConnectionFailureState.clear(newer))
        assertNull(ConnectionFailureState.failure.value)
        assertFalse(ConnectionFailureState.clear(newer))
    }

}
