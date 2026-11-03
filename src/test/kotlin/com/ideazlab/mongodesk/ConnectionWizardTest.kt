package com.ideazlab.mongodesk

import com.mongodb.ConnectionString
import kotlin.test.*

class ConnectionWizardTest {
    @Test fun manualCredentialsAreEncodedAndDecodedCorrectly() {
        val uri = manualConnectionUri("localhost", "27017", false, true, "a@b", "p:/?# %", "admin", true)
        val parsed = ConnectionString(uri)
        assertEquals("a@b", parsed.credential!!.userName)
        assertEquals("p:/?# %", String(parsed.credential!!.password!!))
        assertEquals("admin", parsed.credential!!.source)
        assertEquals(true, parsed.sslEnabled)
    }
    @Test fun srvAndIpv6AddressesWork() {
        assertTrue(ConnectionString(manualConnectionUri("example.test", "", true, false, "", "", "", true)).isSrvProtocol)
        assertTrue(manualConnectionUri("::1", "27017", false, false, "", "", "", false).contains("[::1]:27017"))
    }
    @Test fun invalidManualFieldsAreRejected() {
        for ((host, port) in listOf("localhost" to "0", "localhost" to "65536", "bad/host" to "27017", "" to "27017")) {
            assertFailsWith<InputError> { manualConnectionUri(host, port, false, false, "", "", "", false) }
        }
        assertFailsWith<InputError> { manualConnectionUri("localhost", "27017", false, true, "", "", "", false) }
    }
}
