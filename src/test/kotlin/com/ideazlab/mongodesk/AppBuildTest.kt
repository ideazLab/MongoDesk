package com.ideazlab.mongodesk

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AppBuildTest {
    @Test fun runtimeVersionMatchesTheSharedReleaseVersion() {
        assertEquals(Files.readString(Path.of("VERSION")).trim(), AppBuild.version)
        assertEquals("ideazLab/MongoDesk", AppBuild.repository)
    }
}
