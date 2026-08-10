package com.linxi.diary.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelationshipDaysTest {
    @Test
    fun `纪念日当天是第1天`() {
        assertEquals(
            1L,
            RelationshipDays.dayNumber(
                anniversary = LocalDate.of(2026, 8, 9),
                today = LocalDate.of(2026, 8, 9),
            ),
        )
    }

    @Test
    fun `跨闰日按本地日期计算`() {
        assertEquals(
            3L,
            RelationshipDays.dayNumber(
                anniversary = LocalDate.of(2024, 2, 28),
                today = LocalDate.of(2024, 3, 1),
            ),
        )
    }

    @Test
    fun `未来纪念日不返回负数`() {
        assertNull(
            RelationshipDays.dayNumber(
                anniversary = LocalDate.of(2026, 8, 10),
                today = LocalDate.of(2026, 8, 9),
            )
        )
    }
}
