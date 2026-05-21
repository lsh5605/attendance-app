package com.example.attendance.schedule.ui

import android.content.Context

/**
 * 학번 보관용 SharedPreferences 래퍼.
 *
 * Phase 5 A에서 Firebase Auth UID로 교체 예정 — 그때까지 임시 저장소.
 * Java에서 호출 시 StudentPrefs.INSTANCE.getStudentId(context) 형태.
 */
object StudentPrefs {

    private const val PREFS_NAME = "student_prefs"
    private const val KEY_STUDENT_ID = "studentId"

    /** 저장된 학번 반환. 등록 안 됐으면 null. */
    fun getStudentId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STUDENT_ID, null)

    /** 학번 저장 (덮어쓰기). */
    fun setStudentId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STUDENT_ID, id)
            .apply()
    }
}
