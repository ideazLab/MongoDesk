package com.ideazlab.mongodesk

import com.github.javakeyring.Keyring
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class OsKeychainTest {
    @Test fun realKeychainRoundTrip() {
        assumeTrue("Opt-in test for the machine's unlocked OS keychain", System.getenv("MONGODESK_TEST_KEYCHAIN") == "1")
        val id = "verification-${UUID.randomUUID()}"
        val store = OsSecretStore()
        try {
            store.put(id, "MongoDesk non-sensitive persistence verification")
            assertEquals("MongoDesk non-sensitive persistence verification", OsSecretStore().get(id))
        } finally { runCatching { Keyring.create().deletePassword("MongoDesk", id) } }
    }
}
