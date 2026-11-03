package com.ideazlab.mongodesk

import java.nio.file.Files
import kotlin.test.*

class ThemeTest {
    @Test fun appearancePreferenceSurvivesRestartAndHasSafeDefault() {
        val directory = Files.createTempDirectory("mongodesk-appearance-test")
        try {
            assertEquals(ThemeMode.SYSTEM, ThemeStore(directory).read())
            ThemeStore(directory).save(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, ThemeStore(directory).read())
            ThemeStore(directory).save(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, ThemeStore(directory).read())
            ThemeStore(directory).save(ThemeMode.SYSTEM)
            assertEquals(ThemeMode.SYSTEM, ThemeStore(directory).read())
            Files.writeString(directory.resolve("appearance.txt"), "invalid")
            assertEquals(ThemeMode.SYSTEM, ThemeStore(directory).read())
            assertEquals(listOf("appearance.txt"), Files.list(directory).use { it.map { path -> path.fileName.toString() }.toList() })
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun onlySystemModeFollowsOperatingSystemAppearance() {
        assertFalse(ThemeMode.LIGHT.isDark(true))
        assertTrue(ThemeMode.DARK.isDark(false))
        assertFalse(ThemeMode.SYSTEM.isDark(false))
        assertTrue(ThemeMode.SYSTEM.isDark(true))
    }
}
