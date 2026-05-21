package com.example.attendance.schedule.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * TimeUtils 계측 테스트.
 *
 * "현재 시각"은 실행 시점에 따라 다르므로 절대값 비교 대신 다음 검증:
 *   - 반환값이 미래 시각인가
 *   - dayOfWeek/시각이 의도와 맞는가
 *   - offsetMinutes가 적용됐는가
 */
@RunWith(AndroidJUnit4::class)
class TimeUtilsTest {

    @Test
    fun nextOccurrence_isInFuture() {
        val now = System.currentTimeMillis()
        val trigger = TimeUtils.nextOccurrenceMillis("MONDAY", "14:00")
        assertTrue("trigger($trigger) should be > now($now)", trigger > now)
    }

    @Test
    fun nextOccurrence_landsOnRequestedDay() {
        val trigger = TimeUtils.nextOccurrenceMillis("WEDNESDAY", "10:00")
        val cal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertTrue(
            "expected WEDNESDAY (=${Calendar.WEDNESDAY}), got ${cal.get(Calendar.DAY_OF_WEEK)}",
            cal.get(Calendar.DAY_OF_WEEK) == Calendar.WEDNESDAY
        )
    }

    @Test
    fun nextOccurrence_landsOnRequestedHourMinute() {
        val trigger = TimeUtils.nextOccurrenceMillis("FRIDAY", "13:30")
        val cal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertTrue(
            "expected 13:30, got ${cal.get(Calendar.HOUR_OF_DAY)}:${cal.get(Calendar.MINUTE)}",
            cal.get(Calendar.HOUR_OF_DAY) == 13 && cal.get(Calendar.MINUTE) == 30
        )
    }

    @Test
    fun offsetMinutes_appliedCorrectly_minus5() {
        // 14:00에서 5분 전 → 13:55
        val trigger = TimeUtils.nextOccurrenceMillis("MONDAY", "14:00", offsetMinutes = -5)
        val cal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertTrue(
            "expected 13:55, got ${cal.get(Calendar.HOUR_OF_DAY)}:${cal.get(Calendar.MINUTE)}",
            cal.get(Calendar.HOUR_OF_DAY) == 13 && cal.get(Calendar.MINUTE) == 55
        )
    }

    @Test
    fun offsetMinutes_crossingHourBoundary() {
        // 09:00에서 5분 전 → 08:55
        val trigger = TimeUtils.nextOccurrenceMillis("THURSDAY", "09:00", offsetMinutes = -5)
        val cal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertTrue(
            "expected 08:55, got ${cal.get(Calendar.HOUR_OF_DAY)}:${cal.get(Calendar.MINUTE)}",
            cal.get(Calendar.HOUR_OF_DAY) == 8 && cal.get(Calendar.MINUTE) == 55
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownDayOfWeek_throws() {
        TimeUtils.nextOccurrenceMillis("FUNDAY", "10:00")
    }
}
