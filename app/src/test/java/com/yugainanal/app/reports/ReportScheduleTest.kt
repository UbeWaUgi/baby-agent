package com.yugainanal.app.reports

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportScheduleTest {
    @Test
    fun upcomingTimeAndCurrentMinuteRunOnTheSameDate() {
        val now = LocalDateTime.of(2026, 11, 20, 19, 0, 45)
        assertEquals(now.toLocalDate(), firstScheduledDate(now, LocalTime.of(19, 0)))
        assertEquals(now.toLocalDate(), firstScheduledDate(now, LocalTime.of(20, 0)))
    }

    @Test
    fun aPassedTimeRunsOnTheNextDateAcrossMonthBoundary() {
        val now = LocalDateTime.of(2026, 11, 30, 23, 59)
        assertEquals(LocalDate.of(2026, 12, 1), firstScheduledDate(now, LocalTime.of(0, 0)))
    }
}
