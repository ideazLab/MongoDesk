package com.ideazlab.mongodesk

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** OS file lock enforces one instance per user, shared by packaged and Gradle launches. */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val endpoint: Path,
    private val token: String,
    private val activate: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val listener = Thread({
        while (!closed.get()) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 1000
                    if (DataInputStream(socket.getInputStream()).readUTF() == token) {
                        activate()
                        DataOutputStream(socket.getOutputStream()).apply { writeUTF("OK"); flush() }
                    }
                }
            } catch (_: Exception) { /* A malformed/local request must not stop the listener. */ }
        }
    }, "mongodesk-instance-listener").apply { isDaemon = true; start() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(endpoint) }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(
            directory: Path = Path.of(System.getProperty("user.home"), ".mongodesk"),
            activate: () -> Unit
        ): SingleInstance? {
            Files.createDirectories(directory)
            val endpoint = directory.resolve("instance.endpoint")
            val channel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                catch (e: Exception) { channel.close(); throw e }
            if (lock == null) {
                channel.close()
                // Another launch may hold the lock while it is still publishing its endpoint.
                repeat(30) {
                    if (notifyExisting(endpoint)) return null
                    Thread.sleep(50)
                }
                throw IllegalStateException("MongoDesk is already running or starting. Please try opening it again shortly.")
            }
            var server: ServerSocket? = null
            var temp: Path? = null
            try {
                server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
                val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
                temp = Files.createTempFile(directory, "instance-", ".tmp")
                if (Files.getFileStore(temp).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
                }
                Files.writeString(temp, "${server.localPort}\n$token\n")
                try { Files.move(temp, endpoint, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, endpoint, StandardCopyOption.REPLACE_EXISTING) }
                return SingleInstance(channel, lock, server, endpoint, token, activate)
            } catch (e: Exception) {
                runCatching { server?.close() }; runCatching { lock.release() }; runCatching { channel.close() }
                throw e
            } finally { temp?.let { Files.deleteIfExists(it) } }
        }

        private fun notifyExisting(endpoint: Path): Boolean = runCatching {
            val lines = Files.readAllLines(endpoint)
            require(lines.size == 2)
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", lines[0].toInt()), 250)
                socket.soTimeout = 500
                DataOutputStream(socket.getOutputStream()).apply { writeUTF(lines[1]); flush() }
                DataInputStream(socket.getInputStream()).readUTF() == "OK"
            }
        }.getOrDefault(false)
    }
}
