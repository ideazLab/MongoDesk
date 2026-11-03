package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.*
import org.bson.BsonDocument
import java.nio.file.Files
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class BackupSchedulerTest {
    private fun schedule(folder: String, start: String = "2026-11-03T10:00", zone: String = "Europe/London", interval: BackupInterval = BackupInterval.DAYS) =
        BackupSchedule(name = "Daily fixture", connectionId = "profile-id", database = "fixture", folder = folder, start = start, zone = zone, interval = interval)
    @Test fun recurrenceSkipsMissedRunsAndKeepsDailyWallClockTimeAcrossDst() {
        val daily = schedule("/tmp/backups", "2026-03-28T09:00")
        assertEquals(Instant.parse("2026-03-29T08:00:00Z"), nextBackupAfter(daily, Instant.parse("2026-03-28T09:00:00Z")))
        val minutes = schedule("/tmp/backups", "2026-11-03T10:00", interval = BackupInterval.MINUTES).copy(every = 5)
        assertEquals(Instant.parse("2026-11-03T10:15:00Z"), nextBackupAfter(minutes, Instant.parse("2026-11-03T10:11:00Z")))
        assertNull(nextBackupAfter(minutes.copy(interval = BackupInterval.ONCE), Instant.parse("2026-11-03T10:00:00Z")))
        assertFailsWith<InputError> { backupStart(daily.copy(start = "2026-03-29T01:30")) }
        assertFailsWith<InputError> { validateBackupSchedule(daily.copy(every = 0)) }
        assertFailsWith<InputError> { validateBackupSchedule(daily.copy(folder = "relative")) }
    }
    @Test fun persistenceKeepsSchedulesAndHistoryWithoutCredentialsAndProtectsCorruptState() {
        val root = Files.createTempDirectory("mongodesk-schedule-store")
        try {
            val store = BackupStore(root); val value = schedule(root.toString()).copy(next = 1234)
            store.save(value); store.request(value.id)
            val reloaded = BackupStore(root).read().schedules.single()
            assertEquals(value.copy(requested = true), reloaded)
            assertFalse(Files.readString(root.resolve("schedules.json")).contains("mongodb://"))
            val corrupt = "{broken"; Files.writeString(root.resolve("schedules.json"), corrupt)
            assertFailsWith<InputError> { store.save(value) }
            assertEquals(corrupt, Files.readString(root.resolve("schedules.json")))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun failedRunsAdvanceAndDoNotLoopOrLoseManualRequests() = runBlocking<Unit> {
        val root = Files.createTempDirectory("mongodesk-scheduler-failure")
        try {
            val store = BackupStore(root.resolve("state")); val now = Instant.parse("2026-11-03T12:00:00Z")
            val value = schedule(root.resolve("archives").toString()).copy(next = Instant.parse("2026-11-03T10:00:00Z").toEpochMilli())
            store.save(value)
            val calls = AtomicInteger()
            val engine = BackupEngine(store, Clock.fixed(now, ZoneOffset.UTC)) { _, _ -> calls.incrementAndGet(); throw InputError("Fixture backup failure") }
            engine.tick(); engine.tick()
            assertEquals(1, calls.get()); assertEquals("Failed", store.read().runs.single().status)
            assertEquals(Instant.parse("2026-11-04T10:00:00Z").toEpochMilli(), store.read().schedules.single().next)
            store.request(value.id); engine.tick(); assertEquals(2, calls.get()); assertEquals(2, store.read().runs.size)
            assertFalse(store.read().schedules.single().requested)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun workerLockPreventsOverlappingProcessesAndRecoversInterruptedHistory() = runBlocking<Unit> {
        val root = Files.createTempDirectory("mongodesk-scheduler-lock")
        try {
            val store = BackupStore(root.resolve("state")); val now = Instant.parse("2026-11-03T12:00:00Z")
            val value = schedule(root.resolve("archives").toString(), interval = BackupInterval.ONCE).copy(next = now.toEpochMilli())
            store.save(value)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
            val engine = BackupEngine(store, Clock.fixed(now, ZoneOffset.UTC)) { _, _ -> calls.incrementAndGet(); entered.complete(Unit); release.await(); throw InputError("Fixture") }
            val first = async { engine.tick() }; entered.await()
            engine.tick(); release.complete(Unit); first.await()
            assertEquals(1, calls.get()); assertFalse(store.read().schedules.single().enabled)
            store.update { it.copy(runs = it.runs + BackupRun("stale", value.id, value.name, value.database, now.toEpochMilli())) }
            engine.tick(); assertEquals("Interrupted", store.read().runs.last().status)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun scheduledArchiveIsRestorableAndSuccessIsPersisted() = runBlocking<Unit> {
        val tools = DatabaseTools.findDirectory(); if (tools.isBlank()) return@runBlocking
        val root = Files.createTempDirectory("mongodesk-scheduled-archive")
        val server = MongoServer(MemoryBackend()); val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                session.client.getDatabase("fixture").getCollection<BsonDocument>("items").insertOne(BsonDocument.parse("{_id:1,value:'backed up'}"))
                val store = BackupStore(root.resolve("state")); val now = Instant.parse("2026-11-03T12:00:00Z")
                val value = schedule(root.resolve("archives").toString(), interval = BackupInterval.ONCE).copy(next = now.toEpochMilli(), tools = tools)
                store.save(value)
                BackupEngine(store, Clock.fixed(now, ZoneOffset.UTC)) { s, file ->
                    DatabaseTransferService().export(DatabaseTransferContext(session, s.database), DatabaseExportSource.DATABASE, file, tools, {})
                }.tick()
                val run = store.read().runs.single(); assertEquals("Success", run.status)
                val file = java.nio.file.Path.of(run.file); assertTrue(Files.isRegularFile(file))
                DatabaseTransferService().restore(DatabaseTransferContext(session, "fixture"), file, "fixture", "restored", DatabaseImportMode.NEW, tools, {})
                assertEquals(1, session.documents("restored", "items", "{}", 0).size)
            }
        } finally { server.shutdownNow(); root.toFile().deleteRecursively() }
    }
}
