package com.example.attendance.schedule.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ScheduleDao 계측 테스트.
 *
 * 인메모리 DB 사용 — 실제 디스크에 안 씀, 테스트마다 깨끗한 DB.
 * allowMainThreadQueries는 테스트 편의용 (운영 코드는 IO 스레드 사용).
 */
@RunWith(AndroidJUnit4::class)
class ScheduleDaoTest {

    private lateinit var db: ScheduleDatabase
    private lateinit var dao: ScheduleDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScheduleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.scheduleDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAll_then_getAll_returnsInserted() = runBlocking {
        val schedules = listOf(
            schedule("CS101", "운영체제", "MONDAY", "14:00", "14:50", "공학관 301"),
            schedule("CS201", "자료구조", "TUESDAY", "10:00", "10:50", "공학관 205"),
            schedule("MATH101", "미적분학", "WEDNESDAY", "09:00", "09:50", "자연대 102")
        )

        dao.insertAll(schedules)

        val all = dao.getAll()
        assertEquals(3, all.size)
    }

    @Test
    fun getByDayOfWeek_filtersCorrectly() = runBlocking {
        dao.insertAll(
            listOf(
                schedule("CS101", "운영체제", "MONDAY", "14:00", "14:50", "공학관 301"),
                schedule("CS102", "OS실습", "MONDAY", "15:00", "16:50", "공학관 301"),
                schedule("MATH101", "미적분학", "TUESDAY", "09:00", "09:50", "자연대 102")
            )
        )

        val monday = dao.getByDayOfWeek("MONDAY")
        assertEquals(2, monday.size)
        // ORDER BY startTime 검증
        assertEquals("14:00", monday[0].startTime)
        assertEquals("15:00", monday[1].startTime)
    }

    @Test
    fun replaceAll_isAtomic_andClearsPrevious() = runBlocking {
        // 초기 데이터 3개
        dao.insertAll(
            listOf(
                schedule("OLD1", "구과목1", "MONDAY", "10:00", "10:50", "구 강의실"),
                schedule("OLD2", "구과목2", "TUESDAY", "11:00", "11:50", "구 강의실"),
                schedule("OLD3", "구과목3", "WEDNESDAY", "12:00", "12:50", "구 강의실")
            )
        )
        assertEquals(3, dao.getAll().size)

        // sync 시뮬레이션 — 새 시간표 2개로 교체
        dao.replaceAll(
            listOf(
                schedule("NEW1", "신과목1", "THURSDAY", "13:00", "13:50", "신 강의실"),
                schedule("NEW2", "신과목2", "FRIDAY", "14:00", "14:50", "신 강의실")
            )
        )

        val after = dao.getAll()
        assertEquals(2, after.size)
        assertTrue(after.all { it.subjectId.startsWith("NEW") })
    }

    @Test
    fun deleteAll_emptiesTable() = runBlocking {
        dao.insertAll(
            listOf(schedule("CS101", "운영체제", "MONDAY", "14:00", "14:50", "공학관 301"))
        )
        assertEquals(1, dao.getAll().size)

        dao.deleteAll()
        assertEquals(0, dao.getAll().size)
    }

    @Test
    fun observeAll_emitsCurrentSnapshot() = runBlocking {
        dao.insertAll(
            listOf(schedule("CS101", "운영체제", "MONDAY", "14:00", "14:50", "공학관 301"))
        )

        val snapshot = dao.observeAll().first()
        assertEquals(1, snapshot.size)
        assertEquals("CS101", snapshot[0].subjectId)
    }

    private fun schedule(
        subjectId: String,
        subjectName: String,
        dayOfWeek: String,
        startTime: String,
        endTime: String,
        location: String
    ) = ScheduleEntity(
        subjectId = subjectId,
        subjectName = subjectName,
        dayOfWeek = dayOfWeek,
        startTime = startTime,
        endTime = endTime,
        location = location
    )
}
