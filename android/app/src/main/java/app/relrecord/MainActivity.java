package app.relrecord;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

// 앱 화면 = 관계 기록 화면. 내용은 원본 한 벌 android/relkit의 RelScreen(라이프 RPG 관계 기록 창과 같이 씀, 2026-11-16).
// 이 앱만의 것: 열 때마다 새 버전 확인(플레이 스토어 없이 스스로 업데이트).
public class MainActivity extends app.recordkit.rel.RelScreen {
    private boolean backFromSecurity; // 「업데이트」를 눌러 보안 화면에 갔다 왔는지

    @Override
    protected void checkUpdate() {
        android.content.Context app = getApplicationContext();
        boolean retry = backFromSecurity;
        backFromSecurity = false;
        new Thread(() -> {
            int latest = Updater.latest(app);
            if (latest <= BuildConfig.VERSION_CODE) return;
            if (!Updater.canInstall(this)) { runOnUiThread(this::askInstallPermission); return; }
            if (retry) { Updater.checkAndInstall(app, true, true); return; }
            if (!Updater.askedRecently(app, latest)) runOnUiThread(this::askUpdate);
        }).start();
    }

    // 새 버전이 있을 때: 「업데이트」를 누르면 보안 화면을 연다. 자동 차단을 끄고 돌아오면 설치가 시작된다.
    private void askUpdate() {
        if (isFinishing()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("새 버전이 있어요")
                .setMessage("업데이트하려면 삼성 「자동 차단」을 잠깐 꺼야 해요.\n\n「업데이트」를 누르면 보안 화면이 열려요. 「자동 차단」을 끄고 뒤로 오면 업데이트가 시작돼요.\n(자동 차단은 30분 뒤 저절로 다시 켜져요)")
                .setPositiveButton("업데이트", (d, w) -> {
                    backFromSecurity = true;
                    for (String action : new String[]{Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS}) {
                        try { startActivity(new Intent(action)); return; } catch (Exception ignored) {}
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    // 처음 한 번: 이 앱이 스스로 업데이트할 수 있게 「이 출처 허용」을 켜 달라고 한다
    private void askInstallPermission() {
        if (isFinishing()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("새 버전이 있어요")
                .setMessage("앞으로 저절로 업데이트되게, 다음 화면에서 「이 출처 허용」을 켜 주세요. 한 번만 하면 돼요.")
                .setPositiveButton("켜러 가기", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("나중에", null)
                .show();
    }
}
