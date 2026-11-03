package com.ideazlab.mongodesk

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object BackgroundBackups {
    private val os get() = System.getProperty("os.name")
    private fun executable(): Path {
        val path = Path.of(System.getProperty("jpackage.app-path") ?: ProcessHandle.current().info().command().orElse(""))
        if (!Files.isRegularFile(path) || !path.fileName.toString().equals(if (os.startsWith("Windows")) "MongoDesk.exe" else "MongoDesk", true))
            throw InputError("Open the installed MongoDesk application to register background backups.")
        return path.toAbsolutePath()
    }
    private fun command(vararg args: String): Boolean {
        val process = ProcessBuilder(*args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw InputError("Background scheduler registration timed out.") }
        return process.exitValue() == 0
    }
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun install() {
        val binary = executable()
        val home = Path.of(System.getProperty("user.home"))
        val ok = when {
            os.startsWith("Mac") -> {
                val file = home.resolve("Library/LaunchAgents/com.ideazlab.mongodesk.backups.plist")
                val uid = ProcessBuilder("/usr/bin/id", "-u").start().inputStream.bufferedReader().readText().trim()
                Files.createDirectories(file.parent)
                val content = """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>com.ideazlab.mongodesk.backups</string><key>ProgramArguments</key><array><string>${xml(binary.toString())}</string><string>--backup-worker</string></array><key>StartInterval</key><integer>60</integer><key>RunAtLoad</key><true/><key>ProcessType</key><string>Background</string></dict></plist>"""
                if (!Files.exists(file) || Files.readString(file) != content) {
                    command("/bin/launchctl", "bootout", "gui/$uid", file.toString())
                    Files.writeString(file, content); secureTransferFile(file)
                }
                command("/bin/launchctl", "print", "gui/$uid/com.ideazlab.mongodesk.backups") || command("/bin/launchctl", "bootstrap", "gui/$uid", file.toString())
            }
            os.startsWith("Windows") -> command("schtasks.exe", "/Create", "/F", "/SC", "MINUTE", "/MO", "1", "/TN", "MongoDesk Backups", "/TR", "\"$binary\" --backup-worker", "/IT")
            os.startsWith("Linux") -> {
                val directory = home.resolve(".config/systemd/user"); Files.createDirectories(directory)
                val escaped = binary.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%")
                Files.writeString(directory.resolve("mongodesk-backups.service"), "[Unit]\nDescription=MongoDesk database backups\n[Service]\nType=oneshot\nTimeoutStartSec=7h\nExecStart=\"$escaped\" --backup-worker\n")
                Files.writeString(directory.resolve("mongodesk-backups.timer"), "[Unit]\nDescription=Check MongoDesk backup schedules\n[Timer]\nOnCalendar=*-*-* *:*:00\nPersistent=true\nUnit=mongodesk-backups.service\n[Install]\nWantedBy=timers.target\n")
                command("systemctl", "--user", "daemon-reload") && command("systemctl", "--user", "enable", "--now", "mongodesk-backups.timer")
            }
            else -> false
        }
        if (!ok) throw InputError("Could not register the background scheduler. Check your operating system's background-task permissions and try again.")
    }
    fun wake() {
        when {
            os.startsWith("Mac") -> {
                val uid = ProcessBuilder("/usr/bin/id", "-u").start().inputStream.bufferedReader().readText().trim()
                command("/bin/launchctl", "kickstart", "gui/$uid/com.ideazlab.mongodesk.backups")
            }
            os.startsWith("Windows") -> command("schtasks.exe", "/Run", "/TN", "MongoDesk Backups")
            else -> command("systemctl", "--user", "start", "--no-block", "mongodesk-backups.service")
        }
    }
}
