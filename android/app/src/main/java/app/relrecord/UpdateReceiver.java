package app.relrecord;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

// 스스로 업데이트한 결과. 안드로이드가 "설치할까요?"를 물어야 하면(처음 한 번 등) 그 화면을 띄우거나 알림으로 남긴다.
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Updater.failed(ctx, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            return;
        }
        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm == null) return;
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (MainActivity.visible) {
            try { ctx.startActivity(confirm); return; } catch (Exception ignored) {}
        }
        // 앱을 안 보고 있으면 알림으로: 누르면 설치 확인 화면
        Updater.notify(ctx, "새 버전이 있어요", "눌러서 업데이트해요",
                PendingIntent.getActivity(ctx, 5, confirm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
}
