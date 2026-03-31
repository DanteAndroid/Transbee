package com.danteandroid.whisperit.utils

object OsUtils {
    fun isMacOs(): Boolean {
        val name = System.getProperty("os.name")?.lowercase().orEmpty()
        return name.contains("mac") || name.contains("darwin")
    }
}
