package app.relrecord;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

// 플레이 스토어 없이 스스로 업데이트 (2026-10-18 사장님 요청, 0원).
// GitHub가 만든 APK가 사이트 /app/에 올라가면, 앱이 /app/version.json을 보고 더 새 버전이면 받아서 깐다.
// 삼성 「자동 차단」이 켜져 있으면 설치가 막힌다(2026-10-18 확인). 그래서 앱을 보고 있을 때만 설치하고,
// 「업데이트」를 누르면 보안 화면을 열어 자동 차단을 잠깐 끄게 한 뒤 돌아오면 설치한다(MainActivity).
// 플레이 스토어(내부 테스트)로 옮기기 전까지 쓰는 방식.
final class Updater {
    private static final String PREFS = "rel-update";
    private static final String CH_UPDATE = "update";
    private static final Object LOCK = new Object();

    private Updater() {}

    // 새 버전 번호(없으면 0)
    static int latest(Context ctx) {
        try {
            Net.Result r = Net.get("/app/version.json?t=" + System.currentTimeMillis());
            if (r.status != 200) return 0;
            return new JSONObject(r.body).optInt("versionCode", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    // 확인해서 새 버전이면 받아서 설치까지. 백그라운드에서 부른다.
    static void checkAndInstall(Context ctx, boolean force) {
        checkAndInstall(ctx, force, false);
    }

    // 앱을 안 보고 있을 때: 설치하지 않고 새 버전이 있다고 한 번만 알린다
    static void notifyIfNew(Context ctx) {
        int code = latest(ctx);
        if (code <= BuildConfig.VERSION_CODE) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getInt("notified", 0) == code) return;
        p.edit().putInt("notified", code).apply();
        notify(ctx, "새 버전이 있어요", "눌러서 앱을 열고 업데이트해요", openApp(ctx));
    }

    // 「업데이트」 창을 10분 안에 또 띄우지 않으려고
    static boolean askedRecently(Context ctx, int code) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getInt("asked", 0) == code && System.currentTimeMillis() - p.getLong("askedAt", 0) < 600_000) return true;
        p.edit().putInt("asked", code).putLong("askedAt", System.currentTimeMillis()).apply();
        return false;
    }

    // retry: 사장님이 자동 차단을 끄고 돌아왔을 때. 앞서 막힌 기록과 상관없이 바로 설치한다.
    static void checkAndInstall(Context ctx, boolean force, boolean retry) {
        synchronized (LOCK) {
            try {
                Net.Result r = Net.get("/app/version.json?t=" + System.currentTimeMillis());
                if (r.status != 200) return;
                JSONObject v = new JSONObject(r.body);
                int code = v.optInt("versionCode", 0);
                if (code <= BuildConfig.VERSION_CODE) return;

                SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                // 같은 버전을 1시간 안에 또 받지 않는다(설치 확인을 기다리는 중일 수 있음)
                // 같은 버전은 다시 시작하지 않는다: 앱을 열 때는 10분, 15분 확인 때는 1시간 동안.
                // (2026-10-18 고침: 업데이트 창이 닫힐 때마다 처음부터 다시 시작해서 창이 계속 떴음)
                long wait = force ? 600_000 : 3600_000;
                if (!retry && p.getInt("tried", 0) == code && System.currentTimeMillis() - p.getLong("at", 0) < wait) return;
                // 설치하다 실패한 버전은 이유를 알린 뒤 1시간 동안 다시 하지 않는다
                if (!retry && p.getInt("failed", 0) == code && System.currentTimeMillis() - p.getLong("failedAt", 0) < 3600_000) return;
                p.edit().putInt("tried", code).putLong("at", System.currentTimeMillis()).apply();

                if (!canInstall(ctx)) {
                    // 설치 허용을 아직 안 했으면: 앱을 열면 허용 화면으로 안내한다
                    notify(ctx, "새 버전이 있어요", "눌러서 앱을 열면 업데이트해요", openApp(ctx));
                    return;
                }
                File apk = download(ctx, Net.BASE + v.optString("apk", "/app/relation-record.apk"));
                if (apk == null || !v.optString("sha256").equalsIgnoreCase(sha256(apk))) return; // 받다 끊기면 다음에
                install(ctx, apk);
            } catch (Exception ignored) {
                // 인터넷이 안 되면 다음 확인 때
            }
        }
    }

    private static File download(Context ctx, String url) throws Exception {
        File out = new File(ctx.getCacheDir(), "update.apk");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            if (c.getResponseCode() != 200) return null;
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        } finally {
            c.disconnect();
        }
        return out;
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void install(Context ctx, File apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        // 전에 열어 둔 설치가 남아 있으면 버리고 새로 한다(겹쳐서 창이 여러 번 뜨지 않게)
        for (PackageInstaller.SessionInfo old : pi.getMySessions()) {
            try { pi.abandonSession(old.getSessionId()); } catch (Exception ignored) {}
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app", 0, apk.length())) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(ctx, UpdateReceiver.class);
            PendingIntent status = PendingIntent.getBroadcast(ctx, id, done, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(status.getIntentSender());
        }
    }

    // 설치 실패: 이유를 남기고 알린다(자동 차단·저장 공간 등)
    static void failed(Context ctx, int status, String message) {
        int code = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("tried", 0);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("failed", code).putLong("failedAt", System.currentTimeMillis())
                .putString("error", status + " " + (message == null ? "" : message)).apply();
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return; // 사장님이 취소함
        String why = status == PackageInstaller.STATUS_FAILURE_BLOCKED ? "자동 차단이 막았어요. 앱을 열고 「업데이트」를 다시 눌러 주세요"
                : status == PackageInstaller.STATUS_FAILURE_STORAGE ? "저장 공간이 모자라요"
                : status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ? "지금 앱과 맞지 않아요"
                : "설치가 안 됐어요";
        notify(ctx, "업데이트하지 못했어요", why + (message == null || message.isEmpty() ? "" : " (" + message + ")"), openApp(ctx));
    }

    static PendingIntent openApp(Context ctx) {
        return PendingIntent.getActivity(ctx, 4, new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void notify(Context ctx, String title, String text, PendingIntent tap) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_UPDATE, "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(ctx, CH_UPDATE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        try { nm.notify(9001, n); } catch (SecurityException ignored) {}
    }
}
