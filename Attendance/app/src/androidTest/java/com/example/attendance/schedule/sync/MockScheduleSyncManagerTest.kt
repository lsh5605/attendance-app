package com.example.attendance.schedule.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.attendance.schedule.data.ScheduleDao
import com.example.attendance.schedule.data.ScheduleDatabase
import com.example.attendance.schedule.data.ScheduleEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MockScheduleSyncManager 계측 테스트.
 *
 * sync() 호출 후 DAO 상태를 검증.
 * 인메모리 DB로 격리 — 실제 디스크에 안 씀.
 */
@RunWith(AndroidJUnit4::class)
class MockScheduleSyncManagerTest {

    private lateinit var db: ScheduleDatabase
    private lateinit var dao: ScheduleDao
    private lateinit var syncer: MockScheduleSyncManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScheduleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.scheduleDao()
        syncer = MockScheduleSyncManager(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sync_intoEmptyDb_populatesMockData() = runBlocking {
        val countBefore = dao.getAll().size
        assertEquals(0, countBefore)

        val synced = syncer.sync("STU001")

        val all = dao.getAll()
        assertEquals(MockScheduleSyncManager.MOCK_DATA.size, synced)
        assertEquals(MockScheduleSyncManager.MOCK_DATA.size, all.size)
        // mock의 첫 과목이 들어와 있는지 확인
        assertTrue(all.any { it.subjectId == "CS101" })
    }

    @Test
    fun sync_replacesPreviousData() = runBlocking {
        // 다른 데이터 미리 채워둠
        dao.insertAll(
            listOf(
                ScheduleEntity(
                    subjectId = "OLD", subjectName = "구과목",
                    dayOfWeek = "MONDAY", startTime = "08:00", endTime = "08:50",
                    location = "구강의실"
                )
            )
        )
        assertEquals(1, dao.getAll().size)

        syncer.sync("STU001")

        val all = dao.getAll()
        assertEquals(MockScheduleSyncManager.MOCK_DATA.size, all.size)
        // 기존 OLD 데이터는 사라져야 함
        assertTrue(all.none { it.subjectId == "OLD" })
    }

    @Test
    fun sync_isIdempotent_withSameStudentId() = runBlocking {
        syncer.sync("STU001")
        val firstAll = dao.getAll()

        syncer.sync("STU001")
        val secondAll = dao.getAll()

        assertEquals(firstAll.size, secondAll.size)
        // 다시 sync 해도 데이터 개수 동일 (replaceAll 동작)
    }

    @Test
    fun sync_ignoresStudentId_inMockImpl() = runBlocking {
        syncer.sync("STU001")
        val firstAll = dao.getAll().map { it.subjectId }.toSet()

        syncer.sync("DIFFERENT_STUDENT")
        val secondAll = dao.getAll().map { it.subjectId }.toSet()

        // mock 구현은 studentId 무시 — 같은 시간표 반환
        assertEquals(firstAll, secondAll)
    }
}
