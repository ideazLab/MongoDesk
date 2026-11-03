package com.ideazlab.mongodesk

/** Explicit maintenance entry point; never runs during normal startup. */
object MigrateLocalCredentials {
    @JvmStatic fun main(args: Array<String>) {
        try {
            val moved = kotlinx.coroutines.runBlocking { migrateKeychainProfiles(ConnectionProfiles()) }
            println("Moved $moved saved connection(s) to verified encrypted local storage; legacy entries removed.")
        } catch (_: Exception) {
            System.err.println("Migration incomplete. Verified local copies and remaining legacy entries were retained. Retry the move from Connections.")
            kotlin.system.exitProcess(1)
        }
    }
}
