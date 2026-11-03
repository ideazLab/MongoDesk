package com.ideazlab.mongodesk

import java.util.Properties

/** Generated from VERSION by Gradle; the app and its native installers use the same version. */
internal object AppBuild {
    private val metadata = Properties().apply {
        checkNotNull(AppBuild::class.java.getResourceAsStream("/build-info.properties")) { "Missing application version metadata." }.use { load(it) }
    }
    val version: String = checkNotNull(metadata.getProperty("version"))
    val repository: String = checkNotNull(metadata.getProperty("repository"))
}
