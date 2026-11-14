package app.relrecord;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// 화면은 앱 안의 index.html(assets). 서버는 Native.rpc로만 부른다(rel_ 함수).
// 뒤로 가기: 화면의 appBack()이 열린 창·직전 화면을 닫으면 true, 첫 화면이면 false → 앱 닫힘(새 앱 규칙, 2026-11-11).
public class MainActivity extends Activity {
    static final String ACTION_REGISTER = "app.relrecord.REGISTER";
    static volatile boolean visible;

    private WebView web;
    private boolean loaded, backFromSecurity;
    private String openPhone, openName;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 기록 앱 화면 값(브랜드 가이드 9절): 밝은 화면 고정
        int bg = Color.parseColor("#f6f5f1");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                loaded = true;
                openFromIntent();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; } // 앱 밖으로 안 나감
            // 화면을 그리는 부분(WebView)이 죽어도 앱은 살린다: 화면을 새로 만든다(안 하면 앱이 통째로 꺼진다)
            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                try {
                    android.view.ViewGroup parent = (android.view.ViewGroup) v.getParent();
                    if (parent != null) parent.removeView(v);
                    v.destroy();
                } catch (Exception ignored) {}
                web = null;
                loaded = false;
                recreate();
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        // 상태 표시줄(시계)·아래 버튼줄·키보드에 화면이 가리지 않게(업무 기록 앱과 같은 방식).
        // 안드로이드 15는 화면을 끝까지 그리므로 그만큼 안쪽으로 띄운다.
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(bg);
        root.addView(web);
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return android.view.WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        take(getIntent());
        CallJob.schedulePeriodic(this);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        take(i);
        if (loaded) openFromIntent();
    }

    // 알림의 [기록]으로 열렸으면 그 번호의 등록 화면을 연다
    private void take(Intent i) {
        if (i != null && ACTION_REGISTER.equals(i.getAction())) {
            openPhone = i.getStringExtra("phone");
            openName = i.getStringExtra("name");
        }
    }

    private void openFromIntent() {
        if (openPhone == null) return;
        js("window.openRegister && openRegister(" + JSONObject.quote(openPhone) + "," + JSONObject.quote(openName == null ? "" : openName) + ")");
        openPhone = null;
        openName = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (loaded) js("window.onNative && onNative('resume')");
        Context app = getApplicationContext();
        new Thread(() -> {
            Calls.run(app);
            Calls.repost(app);
            js("window.onNative && onNative('synced')");
        }).start();

        // 앱을 열 때마다 새 버전이 있나 본다(업무 기록 앱과 같은 방식)
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

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (!loaded) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.appBack ? appBack() : false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    // 연락처에서 고름(10) / 새 연락처를 만듦(11): 고른 연락처의 이름·번호를 등록 칸으로
    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            String name = "", number = "";
            try {
                if (code == 10) {
                    try (android.database.Cursor c = getContentResolver().query(uri, new String[]{
                            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                        if (c != null && c.moveToFirst()) { name = c.getString(0); number = c.getString(1); }
                    }
                } else if (code == 11) {
                    long id = -1;
                    try (android.database.Cursor c = getContentResolver().query(uri, new String[]{
                            android.provider.ContactsContract.Contacts._ID, android.provider.ContactsContract.Contacts.DISPLAY_NAME}, null, null, null)) {
                        if (c != null && c.moveToFirst()) { id = c.getLong(0); name = c.getString(1); }
                    }
                    if (id >= 0) try (android.database.Cursor c = getContentResolver().query(
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", new String[]{String.valueOf(id)}, null)) {
                        if (c != null && c.moveToFirst()) number = c.getString(0);
                    }
                }
            } catch (Exception ignored) {}
            js("window.onNative && onNative('contact'," + JSONObject.quote(name == null ? "" : name) + "," + JSONObject.quote(number == null ? "" : number) + ")");
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 2) importContacts();
        js("window.onNative && onNative('perms')");
    }

    private void importContacts() {
        Context app = getApplicationContext();
        new Thread(() -> {
            Set<String> phones = Calls.contactPhones(app);
            int n = Store.get(app).excludeAll(phones);
            boolean ok = app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
            js("window.onNative && onNative('contacts'," + (ok ? phones.size() : -1) + "," + n + ")");
        }).start();
    }

    private void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    // ---- 화면이 부르는 것 ----
    private class Bridge {
        @JavascriptInterface public String token() { return Store.get(MainActivity.this).token(); }

        @JavascriptInterface public void setToken(String t) {
            Store.get(MainActivity.this).setToken(t);
            if (t != null && !t.isEmpty()) new Thread(() -> { Calls.run(getApplicationContext()); js("window.onNative && onNative('synced')"); }).start();
        }

        @JavascriptInterface public String version() { return BuildConfig.VERSION_NAME; }

        // 서버 부르기: 끝나면 화면의 __rpc(cb, status, body)
        @JavascriptInterface public void rpc(String fn, String json, String cb) {
            new Thread(() -> {
                int status;
                String body;
                try {
                    Net.Result r = Net.rpc(fn, json);
                    status = r.status;
                    body = r.body;
                    if (Net.authFailed(r)) Store.get(MainActivity.this).setToken("");
                } catch (Exception e) {
                    status = 0;
                    body = "";
                }
                js("__rpc(" + JSONObject.quote(cb) + "," + status + "," + JSONObject.quote(body) + ")");
            }).start();
        }

        // 답 안 한 번호: [{phone, name, calls:[...]}]
        @JavascriptInterface public String pending() {
            JSONObject o = Store.get(MainActivity.this).pending();
            JSONArray a = new JSONArray();
            for (String phone : Store.keys(o)) {
                try {
                    JSONObject x = o.getJSONObject(phone);
                    a.put(new JSONObject().put("phone", phone).put("name", x.optString("name")).put("calls", x.optJSONArray("calls")));
                } catch (Exception ignored) {}
            }
            return a.toString();
        }

        // 등록을 마친 번호: 모아 둔 통화를 그 사람에게 붙여 보낸다
        @JavascriptInterface public void answered(String phone, String personId, String name) {
            Context app = getApplicationContext();
            Calls.answered(app, Calls.norm(phone), personId, name);
            new Thread(() -> { Calls.send(app); js("window.onNative && onNative('synced')"); }).start();
        }

        @JavascriptInterface public void setAreas(String json) { Store.get(MainActivity.this).setAreas(json); }

        // 「다른 앱 위에 표시」: 통화 끝나면 작은 창으로 묻기
        @JavascriptInterface public void openOverlay() {
            open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }

        @JavascriptInterface public void notPerson(String phone) { Calls.notPerson(getApplicationContext(), Calls.norm(phone)); }

        @JavascriptInterface public String excluded() {
            List<String> l = new ArrayList<>(Store.get(MainActivity.this).excluded());
            java.util.Collections.sort(l);
            return new JSONArray(l).toString();
        }

        @JavascriptInterface public void exclude(String phone) {
            String p = Calls.norm(phone);
            if (p.length() >= 7) Store.get(MainActivity.this).exclude(p);
        }

        @JavascriptInterface public void unexclude(String phone) { Store.get(MainActivity.this).unexclude(phone); }

        @JavascriptInterface public String norm(String phone) { return Calls.norm(phone); }

        // 해야 할 설정
        @JavascriptInterface public String perms() {
            PowerManager pm = getSystemService(PowerManager.class);
            try {
                return new JSONObject()
                        .put("callLog", granted(Manifest.permission.READ_CALL_LOG))
                        .put("phoneState", granted(Manifest.permission.READ_PHONE_STATE))
                        .put("notif", android.os.Build.VERSION.SDK_INT < 33 || granted("android.permission.POST_NOTIFICATIONS"))
                        .put("contacts", granted(Manifest.permission.READ_CONTACTS))
                        .put("overlay", Settings.canDrawOverlays(MainActivity.this))
                        .put("battery", pm == null || pm.isIgnoringBatteryOptimizations(getPackageName()))
                        .toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface public void askPerms() {
            runOnUiThread(() -> {
                List<String> need = new ArrayList<>();
                need.add(Manifest.permission.READ_CALL_LOG);
                need.add(Manifest.permission.READ_PHONE_STATE);
                need.add(Manifest.permission.READ_CONTACTS); // 알림·등록 칸에 폰 연락처 이름을 그대로 쓰려고(번호는 폰 밖으로 안 나감)
                if (android.os.Build.VERSION.SDK_INT >= 33) need.add("android.permission.POST_NOTIFICATIONS");
                requestPermissions(need.toArray(new String[0]), 1);
            });
        }

        // 폰 연락처의 번호를 모두 제외 목록에(폰 안에서만, 서버에 안 올림)
        @JavascriptInterface public void importContacts() {
            runOnUiThread(() -> {
                if (granted(Manifest.permission.READ_CONTACTS)) MainActivity.this.importContacts();
                else requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 2);
            });
        }

        @JavascriptInterface public void openBattery() {
            open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        }

        // 권한이 막혔을 때(「제한된 설정」 등): 앱 정보 화면
        @JavascriptInterface public void openAppSettings() {
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }

        @JavascriptInterface public void syncNow() {
            new Thread(() -> { Calls.run(getApplicationContext()); js("window.onNative && onNative('synced')"); }).start();
        }

        // 복사해 둔 글(녹음 글 붙이기용). 화면이 앞에 있을 때만 읽힌다
        @JavascriptInterface public String clip() {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData d = cm == null ? null : cm.getPrimaryClip();
                if (d == null || d.getItemCount() == 0) return "";
                CharSequence t = d.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) { return ""; }
        }

        // 복사한 때(ms). 없으면 0. 방금 복사한 글인지 보려고
        @JavascriptInterface public double clipTime() {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipDescription d = cm == null ? null : cm.getPrimaryClipDescription();
                return d == null ? 0 : d.getTimestamp();
            } catch (Exception e) { return 0; }
        }

        // 익시오 열기(녹음 글 복사하러): 폰에 깔린 앱 중 이름에 「익시오」/ixi가 있는 것을 찾아 연다. 못 찾으면 false
        @JavascriptInterface public boolean openIxio() {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    String label = String.valueOf(ri.loadLabel(pm)).toLowerCase();
                    String pkg = ri.activityInfo.packageName.toLowerCase();
                    if (label.contains("익시오") || label.contains("ixi") || pkg.contains("ixi")) {
                        Intent i = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                        if (i == null) continue;
                        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return true;
                    }
                }
            } catch (Exception ignored) {}
            return false;
        }

        @JavascriptInterface public void close() { runOnUiThread(MainActivity.this::finish); }

        // 폰 연락처에서 한 사람 고르기(폰 기본 고르기 화면)
        @JavascriptInterface public void pickContact() {
            runOnUiThread(() -> {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 10);
                } catch (Exception e) { js("toast('연락처를 열 수 없어요')"); }
            });
        }

        // 새 연락처 만들기: 폰 연락처 앱이 열리고(적어 둔 이름·번호가 채워짐), 저장하면 그 이름·번호가 등록 칸으로
        @JavascriptInterface public void newContact(String name, String phone) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI)
                            .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name == null ? "" : name)
                            .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, phone == null ? "" : phone)
                            .putExtra("finishActivityOnSaveCompleted", true);
                    startActivityForResult(i, 11);
                } catch (Exception e) { js("toast('연락처 앱을 열 수 없어요')"); }
            });
        }
    }

    private void open(Intent i) {
        runOnUiThread(() -> {
            try { startActivity(i); } catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
        });
    }

    // 새 버전이 있을 때: 「업데이트」를 누르면 보안 화면을 연다. 자동 차단을 끄고 돌아오면 설치가 시작된다.
    private void askUpdate() {
        if (isFinishing()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("새 버전이 있어요")
                .setMessage("업데이트하려면 삼성 「자동 차단」을 잠깐 꺼야 해요.\n\n「업데이트」를 누르면 보안 화면이 열려요. 「자동 차단」을 끄고 뒤로 오면 업데이트가 시작돼요.\n(자동 차단은 30분 뒤 저절로 다시 켜져요)")
                .setPositiveButton("업데이트", (d, w) -> {
                    backFromSecurity = true;
                    openSecuritySettings();
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private void openSecuritySettings() {
        for (String action : new String[]{Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (Exception ignored) {}
        }
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
