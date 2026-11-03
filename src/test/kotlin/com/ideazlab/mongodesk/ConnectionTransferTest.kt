package com.ideazlab.mongodesk

import kotlin.test.*

class ConnectionTransferTest {
    @Test fun roundTripPreservesProfilesAndSecrets() {
        val configs = listOf(ConnectionConfig(name = "Primary", uri = "mongodb://user:secret@localhost:27017"), ConnectionConfig(name = "Other", database = "sample"))
        val backup = ConnectionTransfer.encode(configs, "backup-password".toCharArray())
        assertFalse(backup.contains("user:secret"))
        assertFalse(backup.contains("Primary"))
        assertEquals(configs, ConnectionTransfer.decode(backup, "backup-password".toCharArray()))
    }
    @Test fun wrongPasswordAndTamperingFail() {
        val backup = ConnectionTransfer.encode(listOf(ConnectionConfig()), "backup-password".toCharArray())
        assertFailsWith<InputError> { ConnectionTransfer.decode(backup, "wrong-password".toCharArray()) }
        assertFailsWith<InputError> { ConnectionTransfer.decode(backup.replace("\"version\": 1", "\"version\": 2"), "backup-password".toCharArray()) }
        assertFailsWith<InputError> { ConnectionTransfer.decode("{}", "backup-password".toCharArray()) }
    }
    @Test fun encryptionUsesFreshSaltAndNonce() {
        val configs = listOf(ConnectionConfig())
        assertNotEquals(ConnectionTransfer.encode(configs, "backup-password".toCharArray()), ConnectionTransfer.encode(configs, "backup-password".toCharArray()))
        assertFailsWith<InputError> { ConnectionTransfer.encode(configs, "short".toCharArray()) }
    }
}
