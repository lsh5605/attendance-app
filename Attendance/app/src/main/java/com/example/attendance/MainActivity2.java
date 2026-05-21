package com.example.attendance;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.recyclerview.widget.RecyclerView;

import com.example.attendance.schedule.ui.ScheduleViewBinder;
import com.example.attendance.schedule.ui.StudentPrefs;
import com.example.attendance.schedule.work.ScheduleSyncWorker;
import com.example.attendance.service.AttendanceEvents;
import com.example.attendance.service.StudentAttendanceService;

/**
 * 학생 화면.
 *
 * Service 전환 후의 책임:
 *   - 권한 요청
 *   - 버튼 클릭 시 StudentAttendanceService에 ACTION_START Intent 발사
 *   - Service의 broadcast 수신해서 Toast로 결과 표시
 */
public class MainActivity2 extends AppCompatActivity {

    private static final int REQ_BLE_PERMS = 1;
    private static final int REQ_NOTIFICATION_PERMS = 2;
    private static final int REQ_UWB_PERMS = 3;

    // Phase 5 A 인증 도입 시 Firebase Auth UID로 교체. 그때까지 SharedPreferences 임시.
    // 학번 미등록 시 fallback (테스트/데모 편의용).
    private static final String DEFAULT_STUDENT_ID = "STU001";

    /** 현재 사용할 학번. prefs에 저장된 값 우선, 없으면 fallback. */
    private String currentStudentId() {
        String saved = StudentPrefs.INSTANCE.getStudentId(this);
        return saved != null ? saved : DEFAULT_STUDENT_ID;
    }

    /** UWB 권한 요청 중 입력된 PIN을 보관 (권한 승인 후 재개용). */
    private String pendingPin;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            String action = i.getAction();
            if (action == null) return;

            switch (action) {
                case AttendanceEvents.ACTION_ATTENDANCE_CONFIRMED: {
                    String code = i.getStringExtra(AttendanceEvents.EXTRA_SESSION_CODE);
                    Log.d("Attendance", "출석 확정 broadcast 수신: " + code);
                    Toast.makeText(MainActivity2.this,
                            "출석 완료: " + code, Toast.LENGTH_SHORT).show();
                    break;
                }
                case AttendanceEvents.ACTION_ATTENDANCE_FAILED: {
                    String reason = i.getStringExtra(AttendanceEvents.EXTRA_REASON);
                    Log.e("Attendance", "출석 실패 broadcast 수신: " + reason);
                    Toast.makeText(MainActivity2.this,
                            reason != null ? reason : "출석 실패", Toast.LENGTH_SHORT).show();
                    break;
                }
                case AttendanceEvents.ACTION_ATTENDANCE_ABSENT: {
                    String attId = i.getStringExtra(AttendanceEvents.EXTRA_ATTENDANCE_ID);
                    Log.w("Attendance", "결석 broadcast 수신: " + attId);
                    Toast.makeText(MainActivity2.this,
                            "결석 처리되었습니다", Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main2);

        requestNotificationPermissionIfNeeded();

        // 학번 등록 UI ─────────────────────────────────────────
        EditText studentIdInput = findViewById(R.id.studentIdInput);
        Button saveStudentIdBtn = findViewById(R.id.saveStudentIdBtn);

        // 기존 학번 채워넣기 (없으면 fallback 표시)
        studentIdInput.setText(currentStudentId());

        saveStudentIdBtn.setOnClickListener(v -> {
            String id = studentIdInput.getText().toString().trim();
            if (id.isEmpty()) {
                Toast.makeText(this, "학번을 입력하세요", Toast.LENGTH_SHORT).show();
                return;
            }
            StudentPrefs.INSTANCE.setStudentId(this, id);
            // 학번 저장과 함께 즉시 1회 동기화 + 12h 주기 등록
            ScheduleSyncWorker.Companion.enqueueOnce(this, id);
            ScheduleSyncWorker.Companion.enqueuePeriodic(this, id);
            Toast.makeText(this, "학번 저장 완료: " + id, Toast.LENGTH_SHORT).show();
        });

        // "지금 동기화" 버튼 ─────────────────────────────────────
        Button syncNowBtn = findViewById(R.id.syncNowBtn);
        syncNowBtn.setOnClickListener(v ->
            ScheduleSyncWorker.Companion.enqueueOnce(this, currentStudentId())
        );

        // 시간표 RecyclerView 부착 (Flow 자동 갱신) ──────────────
        RecyclerView scheduleRecycler = findViewById(R.id.scheduleRecycler);
        new ScheduleViewBinder(this, scheduleRecycler).attach();

        // 학번이 prefs에 이미 있으면 앱 시작 시 12h 주기 등록 보장
        if (StudentPrefs.INSTANCE.getStudentId(this) != null) {
            ScheduleSyncWorker.Companion.enqueuePeriodic(this, currentStudentId());
        }

        // 기존 출석 UI ─────────────────────────────────────────
        Button scanBtn = findViewById(R.id.scanBtn);
        scanBtn.setOnClickListener(v -> {
            if (hasPermissions()) startStudentService();
            else requestBLEPermissions();
        });

        Button stopBtn = findViewById(R.id.stopBtn);
        stopBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, StudentAttendanceService.class)
                    .setAction(StudentAttendanceService.ACTION_STOP);
            startService(i);
        });

        EditText pinInput = findViewById(R.id.pinInput);
        Button submitPinBtn = findViewById(R.id.submitPinBtn);
        submitPinBtn.setOnClickListener(v -> {
            String pin = pinInput.getText().toString().trim();
            if (pin.length() != 4) {
                Toast.makeText(this, "PIN 4자리를 입력하세요", Toast.LENGTH_SHORT).show();
                return;
            }
            submitPin(pin);
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AttendanceEvents.ACTION_ATTENDANCE_CONFIRMED);
        filter.addAction(AttendanceEvents.ACTION_ATTENDANCE_FAILED);
        filter.addAction(AttendanceEvents.ACTION_ATTENDANCE_ABSENT);

        ContextCompat.registerReceiver(this, receiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    // onDestroy 제거 (Activity 죽어도 Service는 살아있어야 함)

    private void startStudentService() {
        // UWB 미지원 기기 분기
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_UWB)) {
            Toast.makeText(this, "이 기기는 UWB를 지원하지 않습니다", Toast.LENGTH_LONG).show();
            return;
        }

        // Samsung은 UWB_RANGING(normal permission)도 runtime처럼 처리 — 명시 요청 필수
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean uwbGranted = checkSelfPermission(Manifest.permission.UWB_RANGING)
                    == PackageManager.PERMISSION_GRANTED;
            if (!uwbGranted) {
                requestPermissions(new String[]{Manifest.permission.UWB_RANGING},
                        REQ_UWB_PERMS);
                return;
            }
        }

        Intent i = new Intent(this, StudentAttendanceService.class)
                .setAction(StudentAttendanceService.ACTION_START)
                .putExtra(StudentAttendanceService.EXTRA_STUDENT_ID, currentStudentId());
        ContextCompat.startForegroundService(this, i);
    }

    /**
     * 수동 PIN 입력으로 출석 등록. BLE 스캔 없이 서버 check-in을 바로 호출.
     * UWB 미지원/권한 미승인 분기는 startStudentService와 동일.
     */
    private void submitPin(String pin) {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_UWB)) {
            Toast.makeText(this, "이 기기는 UWB를 지원하지 않습니다", Toast.LENGTH_LONG).show();
            return;
        }

        // Samsung은 UWB_RANGING(normal permission)도 runtime처럼 처리 — 명시 요청 필수
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.UWB_RANGING)
                    != PackageManager.PERMISSION_GRANTED) {
            pendingPin = pin;
            requestPermissions(new String[]{Manifest.permission.UWB_RANGING}, REQ_UWB_PERMS);
            return;
        }

        Intent i = new Intent(this, StudentAttendanceService.class)
                .setAction(StudentAttendanceService.ACTION_SUBMIT_PIN)
                .putExtra(StudentAttendanceService.EXTRA_STUDENT_ID, currentStudentId())
                .putExtra(StudentAttendanceService.EXTRA_PIN, pin);
        ContextCompat.startForegroundService(this, i);
    }

    // ── 권한 ────────────────────────────────────────────────

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestBLEPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQ_BLE_PERMS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_BLE_PERMS);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION_PERMS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE_PERMS) {
            boolean granted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                Log.d("Attendance", "권한 승인됨 → 출석 시작");
                startStudentService();
            } else {
                Log.e("Attendance", "권한 거부됨");
                Toast.makeText(this, "BLE 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_UWB_PERMS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.d("UWB", "UWB_RANGING 권한 결과: granted=" + granted);
            if (granted) {
                if (pendingPin != null) {
                    String pin = pendingPin;
                    pendingPin = null;
                    submitPin(pin);
                } else {
                    startStudentService();
                }
            } else {
                pendingPin = null;
                Toast.makeText(this, "UWB 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
