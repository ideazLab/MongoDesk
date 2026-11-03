package com.ideazlab.mongodesk

import kotlinx.coroutines.*
import org.bson.Document
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID

internal enum class BackupInterval(val label: String) { ONCE("Once"), MINUTES("Minutes"), HOURS("Hours"), DAYS("Days"), WEEKS("Weeks") }
internal data class BackupSchedule(val id: String = UUID.randomUUID().toString(), val name: String, val connectionId: String,
    val database: String, val folder: String, val start: String, val zone: String, val interval: BackupInterval = BackupInterval.DAYS,
    val every: Int = 1, val enabled: Boolean = true, val next: Long? = null, val requested: Boolean = false, val tools: String = DatabaseTools.findDirectory())
internal data class BackupRun(val id: String, val scheduleId: String, val name: String, val database: String, val started: Long,
    val finished: Long? = null, val status: String = "Running", val file: String = "", val message: String = "")
internal data class BackupState(val schedules: List<BackupSchedule> = emptyList(), val runs: List<BackupRun> = emptyList())

internal fun backupStart(schedule: BackupSchedule): Instant {
    val local = try { LocalDateTime.parse(schedule.start) } catch (_: Exception) { throw InputError("Enter a start date and time as yyyy-MM-dd HH:mm.") }
    val zone = try { ZoneId.of(schedule.zone) } catch (_: Exception) { throw InputError("Enter a valid time zone, such as Europe/London.") }
    if (zone.rules.getValidOffsets(local).isEmpty()) throw InputError("That local time does not exist because the clocks change. Choose another time.")
    return local.atZone(zone).toInstant()
}
internal fun validateBackupSchedule(schedule: BackupSchedule) {
    if (!schedule.id.matches(Regex("[A-Za-z0-9-]{1,80}"))) throw InputError("Invalid schedule identifier.")
    if (schedule.name.isBlank() || schedule.connectionId.isBlank()) throw InputError("Enter a schedule name and choose a saved connection.")
    validateTransferDatabase(schedule.database)
    if (schedule.every !in 1..10000) throw InputError("Repeat interval must be between 1 and 10,000.")
    if (!Path.of(schedule.folder).isAbsolute) throw InputError("Choose an absolute backup folder path.")
    backupStart(schedule)
}
/** Advance from the original anchor, skipping missed repetitions instead of replaying a backlog. */
internal fun nextBackupAfter(schedule: BackupSchedule, after: Instant): Instant? {
    val start = backupStart(schedule).atZone(ZoneId.of(schedule.zone))
    if (start.toInstant() > after) return start.toInstant()
    if (schedule.interval == BackupInterval.ONCE) return null
    val unit = when (schedule.interval) { BackupInterval.MINUTES -> ChronoUnit.MINUTES; BackupInterval.HOURS -> ChronoUnit.HOURS; BackupInterval.DAYS -> ChronoUnit.DAYS; else -> ChronoUnit.WEEKS }
    val distance = unit.between(start, after.atZone(start.zone)).coerceAtLeast(0)
    var step = distance / schedule.every
    var candidate = start.plus(Math.multiplyExact(step, schedule.every.toLong()), unit)
    while (candidate.toInstant() <= after) { step++; candidate = start.plus(Math.multiplyExact(step, schedule.every.toLong()), unit) }
    return candidate.toInstant()
}
internal class BackupStore(val directory: Path = Path.of(System.getProperty("user.home"), ".mongodesk", "backups")) {
    private val file get() = directory.resolve("schedules.json")
    companion object { private val processLocks = java.util.concurrent.ConcurrentHashMap<Path, Any>() }
    fun update(change: (BackupState) -> BackupState): BackupState {
        synchronized(processLocks.computeIfAbsent(directory.toAbsolutePath().normalize()) { Any() }) {
        Files.createDirectories(directory); secureTransferFile(directory, true)
        FileChannel.open(directory.resolve("state.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val state = readUnlocked()
                val next = change(state)
                if (next != state || !Files.exists(file)) {
                    val temp = Files.createTempFile(directory, "schedules-", ".tmp")
                    try {
                        secureTransferFile(temp)
                        Files.writeString(temp, encode(next).toJson())
                        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
                    } finally { Files.deleteIfExists(temp) }
                }
                return next
            }
        }
    }
    }
    fun read(): BackupState = update { it }
    private fun readUnlocked(): BackupState {
        if (!Files.exists(file)) return BackupState()
        try {
            val data = Document.parse(Files.readString(file))
            val schedules = data.getList("schedules", Document::class.java).map { d ->
                BackupSchedule(d.getString("id"), d.getString("name"), d.getString("connectionId"), d.getString("database"), d.getString("folder"),
                    d.getString("start"), d.getString("zone"), BackupInterval.valueOf(d.getString("interval")), d.getInteger("every"), d.getBoolean("enabled"),
                    d.get("next", Number::class.java)?.toLong(), d.getBoolean("requested", false), d.getString("tools")).also(::validateBackupSchedule)
            }
            val runs = data.getList("runs", Document::class.java).map { d -> BackupRun(d.getString("id"), d.getString("scheduleId"), d.getString("name"), d.getString("database"),
                d.get("started", Number::class.java).toLong(), d.get("finished", Number::class.java)?.toLong(), d.getString("status"), d.getString("file"), d.getString("message")) }
            return BackupState(schedules, runs)
        } catch (_: Exception) { throw InputError("Backup schedule storage could not be read. Restore ~/.mongodesk/backups/schedules.json from a backup before changing schedules.") }
    }
    fun save(schedule: BackupSchedule) = update { state ->
        validateBackupSchedule(schedule)
        state.copy(schedules = state.schedules.filterNot { it.id == schedule.id } + schedule)
    }
    fun request(id: String) = update { state -> state.copy(schedules = state.schedules.map { if (it.id == id) it.copy(requested = true) else it }) }
    fun delete(id: String) = update { state -> state.copy(schedules = state.schedules.filterNot { it.id == id }) }
    private fun encode(state: BackupState) = Document("version", 1).append("schedules", state.schedules.map { s ->
        Document("id", s.id).append("name", s.name).append("connectionId", s.connectionId).append("database", s.database).append("folder", s.folder)
            .append("start", s.start).append("zone", s.zone).append("interval", s.interval.name).append("every", s.every).append("enabled", s.enabled)
            .append("next", s.next).append("requested", s.requested).append("tools", s.tools)
    }).append("runs", state.runs.takeLast(200).map { r -> Document("id", r.id).append("scheduleId", r.scheduleId).append("name", r.name).append("database", r.database)
        .append("started", r.started).append("finished", r.finished).append("status", r.status).append("file", r.file).append("message", r.message) })
}

internal class BackupEngine(private val store: BackupStore = BackupStore(), private val clock: Clock = Clock.systemUTC(),
    private val execute: suspend (BackupSchedule, Path) -> Unit = { schedule, file ->
        val config = withContext(Dispatchers.IO) { ConnectionProfiles().read(schedule.connectionId) }
        withTimeout(6 * 60 * 60 * 1000L) {
            MongoSession.connect(config).use { session ->
                DatabaseTransferService().export(DatabaseTransferContext(session, schedule.database), DatabaseExportSource.DATABASE, file, schedule.tools, {})
            }
        }
    }) {
    suspend fun tick() = withContext(Dispatchers.IO) {
        Files.createDirectories(store.directory); secureTransferFile(store.directory, true)
        FileChannel.open(store.directory.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) return@withContext
            lock.use {
                store.update { state -> state.copy(runs = state.runs.map { if (it.status == "Running") it.copy(status = "Interrupted", finished = clock.millis(), message = "The previous worker stopped before completion. Check the destination folder.") else it }) }
                val ids = store.read().schedules.filter { it.requested || (it.enabled && (it.next ?: Long.MAX_VALUE) <= clock.millis()) }.map { it.id }
                for (id in ids) {
                    ensureActive()
                    var claimed: BackupSchedule? = null
                    var run: BackupRun? = null
                    store.update { state ->
                        val schedule = state.schedules.firstOrNull { it.id == id }
                        val now = clock.instant()
                        if (schedule == null || (!schedule.requested && (!schedule.enabled || (schedule.next ?: Long.MAX_VALUE) > now.toEpochMilli()))) state
                        else {
                            val due = schedule.enabled && (schedule.next ?: Long.MAX_VALUE) <= now.toEpochMilli()
                            val next = if (due) nextBackupAfter(schedule, now)?.toEpochMilli() else schedule.next
                            claimed = schedule
                            run = BackupRun(UUID.randomUUID().toString(), id, schedule.name, schedule.database, now.toEpochMilli())
                            state.copy(schedules = state.schedules.map { if (it.id == id) it.copy(requested = false, next = next, enabled = if (due && next == null) false else it.enabled) else it }, runs = (state.runs + run!!).takeLast(200))
                        }
                    }
                    val schedule = claimed ?: continue
                    val current = run ?: continue
                    var output = ""
                    try {
                        val folder = Path.of(schedule.folder)
                        Files.createDirectories(folder)
                        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(clock.instant())
                        val file = folder.resolve("${schedule.database}-$stamp-${current.id}.archive.gz")
                        output = file.toString()
                        execute(schedule, file)
                        val info = inspectDatabaseArchive(file)
                        if (schedule.database !in info.collections) throw InputError("The archive did not contain the scheduled database. No verified backup was recorded.")
                        store.update { state -> state.copy(runs = state.runs.map { if (it.id == current.id) it.copy(status = "Success", finished = clock.millis(), file = output, message = "Archive created and header verified.") else it }) }
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { store.update { state -> state.copy(runs = state.runs.map { if (it.id == current.id) it.copy(status = "Interrupted", finished = clock.millis(), file = output, message = "Backup interrupted. Check destination before restoring.") else it }) } }
                        throw e
                    } catch (e: Exception) {
                        store.update { state -> state.copy(runs = state.runs.map { if (it.id == current.id) it.copy(status = "Failed", finished = clock.millis(), file = output, message = connectionError(e).take(1600)) else it }) }
                    }
                }
            }
        }
    }
}
