package com.example

import com.example.builder.BuildNotificationText
import com.example.builder.BuildProgressState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Step67BackgroundBuildTest {

    @Test
    fun serviceNeededOnlyWhileBuildActive() {
        assertFalse(BuildNotificationText.needsService(BuildProgressState.Idle))
        val active = BuildProgressState.Active("App", "Compiling", 0.4f, "", "RUNNING")
        assertTrue(BuildNotificationText.needsService(active))
    }

    @Test
    fun progressTextClampsPercentAndHandlesBlankStep() {
        assertEquals("Compiling (40%)", BuildNotificationText.progressText("Compiling", 0.4f))
        assertEquals("Working... (100%)", BuildNotificationText.progressText("  ", 5f))
        assertEquals("Working... (0%)", BuildNotificationText.progressText("", -1f))
    }

    @Test
    fun titleFallsBackWhenProjectNameBlank() {
        assertEquals("Nexa IDE - Building MyApp", BuildNotificationText.progressTitle(" MyApp "))
        assertEquals("Nexa IDE - Building project", BuildNotificationText.progressTitle(""))
    }
}
