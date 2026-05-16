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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.attendance.service.AttendanceEvents;
import com.example.attendance.service.ProfessorAttendanceService;

/**
 * 교수 화면.
 *
 * Service 전환 후의 책임:
 *   - 권한 요청
 *   - 버튼 클릭 시 ProfessorAttendanceService에 ACTION_START Intent 발사
 *   - Service가 쏘는 broadcast 수신해서 Toast로 사용자에게 결과 표시
 *
 * 더 이상 Controller를 직접 보유하지 않음. Activity가 죽어도 Service는 살아있음.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_BLE_PERMS = 1;
    private static final int REQ_NOTIFICATION_PERMS = 2;
    private static final int REQ_UWB_PERMS = 3;

    /** 세션 시작 시 학생에게 보여줄 PIN 표시용. */
    private TextView pinText;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            String action = i.getAction();
            if (action == null) return;

            switch (action) {
                case AttendanceEvents.ACTION_SESSION_STARTED: {
                    String code = i.getStringExtra(AttendanceEvents.EXTRA_SESSION_CODE);
                    Log.d("BLE", "세션 시작 broadcast 수신: " + code);
                    if (pinText != null) pinText.setText("PIN: " + code);
                    Toast.makeText(MainActivity.this,
                            "세션 시작: " + code, Toast.LENGTH_SHORT).show();
                    break;
                }
                case AttendanceEvents.ACTION_SESSION_FAILED: {
                    String reason = i.getStringExtra(AttendanceEvents.EXTRA_REASON);
                    Log.e("BLE", "세션 실패 broadcast 수신: " + reason);
                    Toast.makeText(MainActivity.this,
                            reason != null ? reason : "세션 실패", Toast.LENGTH_SHORT).show();
                    break;
                }
                case AttendanceEvents.ACTION_SESSION_EXPIRED: {
                    Log.d("BLE", "세션 만료 broadcast 수신");
                    Toast.makeText(MainActivity.this,
                            "출석 마감", Toast.LENGTH_SHORT).show();
                    // TODO: UWB 검증 단계로 전환
                    break;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        pinText = findViewById(R.id.pinText);

        // Android 13+ 알림 권한 요청 (Foreground Service 알림용)
        requestNotificationPermissionIfNeeded();

        Button startBtn = findViewById(R.id.startBtn);
        startBtn.setOnClickListener(v -> {
            if (hasPermissions()) {
                startProfessorService();
            } else {
                requestBLEPermissions();
            }
        });

        Button studentBtn = findViewById(R.id.studentBtn);
        studentBtn.setOnClickListener(v -> {
            Intent intent = new Intent(getApplicationContext(), MainActivity2.class);
            startActivity(intent);
        });

        Button stopBtn = findViewById(R.id.stopBtn);
        stopBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, ProfessorAttendanceService.class)
                    .setAction(ProfessorAttendanceService.ACTION_STOP);
            startService(i);
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
        filter.addAction(AttendanceEvents.ACTION_SESSION_STARTED);
        filter.addAction(AttendanceEvents.ACTION_SESSION_FAILED);
        filter.addAction(AttendanceEvents.ACTION_SESSION_EXPIRED);

        // ContextCompat이 Android 13+ 분기를 자체 처리해줌.
        // RECEIVER_NOT_EXPORTED = 다른 앱이 우리 Activity에 broadcast 못 쏨 (보안).
        ContextCompat.registerReceiver(this, receiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // 등록 안 된 상태에서 호출되면 무시
        }
    }

    // onDestroy 제거: Activity 죽어도 Service는 살아있어야 함.
    // 명시적 중단은 별도 STOP 버튼이나 ACTION_STOP 인텐트로 (현재는 자동 5분 만료에 맡김).

    private void startProfessorService() {
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

        Intent i = new Intent(this, ProfessorAttendanceService.class)
                .setAction(ProfessorAttendanceService.ACTION_START)
                .putExtra(ProfessorAttendanceService.EXTRA_COURSE_ID, "CS101")
                .putExtra(ProfessorAttendanceService.EXTRA_PROFESSOR_ID, "PROF001");
        ContextCompat.startForegroundService(this, i);
    }

    // ── 권한 ────────────────────────────────────────────────

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestBLEPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
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
                Log.d("BLE", "권한 승인됨 → 세션 시작");
                startProfessorService();
            } else {
                Log.e("BLE", "권한 거부됨");
                Toast.makeText(this, "BLE 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_UWB_PERMS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.d("UWB", "UWB_RANGING 권한 결과: granted=" + granted);
            if (granted) {
                startProfessorService();
            } else {
                Toast.makeText(this, "UWB 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        }
        // REQ_NOTIFICATION_PERMS는 결과 무시 (거부돼도 Service는 동작, 알림만 안 뜸)
    }
}
