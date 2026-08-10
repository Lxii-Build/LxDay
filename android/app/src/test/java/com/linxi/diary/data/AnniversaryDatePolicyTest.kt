package com.linxi.diary.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnniversaryDatePolicyTest {
    @Test
    fun `二月天数区分闰年`() {
        assertEquals(29, AnniversaryDatePolicy.daysInMonth(2024, 2))
        assertEquals(28, AnniversaryDatePolicy.daysInMonth(2025, 2))
    }

    @Test
    fun `切换月份时把日期限制到合法范围`() {
        assertEquals(
            LocalDate.of(2025, 2, 28),
            AnniversaryDatePolicy.clampDate(
                year = 2025,
                month = 2,
                day = 31,
                maxDate = LocalDate.of(2026, 8, 9),
            ),
        )
    }

    @Test
    fun `今天允许而未来日期拒绝`() {
        val today = LocalDate.of(2026, 8, 9)
        assertTrue(AnniversaryDatePolicy.isAllowed(today, today))
        assertFalse(AnniversaryDatePolicy.isAllowed(today.plusDays(1), today))
    }
}
