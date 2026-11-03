package com.ideazlab.mongodesk

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SingleInstanceTest {
    @Test fun secondLaunchActivatesFirstAndLockCanBeReacquiredAfterExit() {
        val directory = Files.createTempDirectory("mongodesk-instance-test")
        val activated = CountDownLatch(1)
        try {
            SingleInstance.acquire(directory) { activated.countDown() }!!.use {
                assertNull(SingleInstance.acquire(directory) {})
                assertTrue(activated.await(2, TimeUnit.SECONDS))
            }
            assertFalse(Files.exists(directory.resolve("instance.endpoint")))
            SingleInstance.acquire(directory) {}!!.use { assertTrue(Files.exists(directory.resolve("instance.endpoint"))) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun staleEndpointDoesNotPreventNewLaunch() {
        val directory = Files.createTempDirectory("mongodesk-stale-instance-test")
        try {
            Files.writeString(directory.resolve("instance.endpoint"), "1\nstale-token\n")
            SingleInstance.acquire(directory) {}!!.use {
                assertFalse(Files.readString(directory.resolve("instance.endpoint")).contains("stale-token"))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
