package com.yugainanal.app.media

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryCaptureDateTest {
    @Test
    fun photoDateWithExplicitOffsetIsGroupedInKoreanTime() {
        assertEquals(LocalDate.of(2026, 11, 19), parsePhotoCaptureDate("2026:11:18 18:30:00", "+00:00"))
    }

    @Test
    fun photoDateWithoutOffsetRetainsItsEmbeddedDay() {
        assertEquals(LocalDate.of(2026, 11, 18), parsePhotoCaptureDate("2026:11:18 23:58:00", null))
        assertNull(parsePhotoCaptureDate("2026:02:30 10:00:00", "+09:00"))
    }

    @Test
    fun videoDateAcceptsAndroidCompactAndIsoForms() {
        val expected = LocalDate.of(2026, 11, 19)
        assertEquals(expected, parseVideoCaptureDate("20261118T183000.000Z"))
        assertEquals(expected, parseVideoCaptureDate("2026-11-18T18:30:00+0000"))
    }

    @Test
    fun absentOrMalformedVideoMetadataFallsBackToTheCaller() {
        assertNull(parseVideoCaptureDate(null))
        assertNull(parseVideoCaptureDate("unknown"))
    }
}
