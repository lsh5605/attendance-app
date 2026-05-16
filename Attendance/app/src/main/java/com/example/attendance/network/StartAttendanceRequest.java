package com.example.attendance.network;

/**
 * POST /api/attendance/start 요청 바디.
 * Gson은 필드로 직렬화 — getter 불필요.
 */
public class StartAttendanceRequest {
    private final String courseId;
    private final String professorId;
    private final String professorUwbAddress;

    public StartAttendanceRequest(String courseId, String professorId, String professorUwbAddress) {
        this.courseId = courseId;
        this.professorId = professorId;
        this.professorUwbAddress = professorUwbAddress;
    }
}
