package app.relrecord;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

// 통화가 끝나면: 폰 통화 기록에서 새 통화를 읽어
//  등록된 사람 → 묻지 않고 서버에 기록 / 제외 번호 → 건너뜀 / 처음 번호(연결된 통화) → 「기록할 사람인가요?」 알림 [기록] [아님].
// 어떤 전화 앱(익시오 등)으로 통화해도 폰 통화 기록은 같으므로 동작한다. 녹음·통화 내용은 읽지 않는다.
final class Calls {
    static final String CH_ASK = "ask";
    private static final Object LOCK = new Object();

    private Calls() {}

    // 전체 한 바퀴: 번호 목록 받기 → 새 통화 읽기 → 보내기
    static void run(Context c) {
        synchronized (LOCK) {
            Store st = Store.get(c);
            if (st.token().isEmpty()) return;
            refreshPeople(c);
            if (st.token().isEmpty()) return; // 로그인이 풀렸으면 멈춤
            scan(c);
            send(c);
        }
    }

    // 번호 → 사람(내게 보이는 사람만). 인터넷이 안 되면 전에 받은 목록을 쓴다.
    static void refreshPeople(Context c) {
        Store st = Store.get(c);
        try {
            Net.Result r = Net.rpc("rel_phones", new JSONObject().put("p_token", st.token()).toString());
            if (Net.authFailed(r)) { st.setToken(""); return; }
            if (r.status != 200) return;
            JSONArray a = new JSONArray(r.body);
            JSONObject map = new JSONObject();
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                JSONArray ph = p.optJSONArray("phones");
                if (ph == null) continue;
                for (int j = 0; j < ph.length(); j++)
                    map.put(ph.getString(j), new JSONObject().put("id", p.getString("id")).put("name", p.optString("name")));
            }
            st.setPeople(map);
        } catch (Exception ignored) {}
    }

    private static void scan(Context c) {
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return;
        Store st = Store.get(c);
        long last = st.lastTs();
        if (last == 0) { st.setLastTs(System.currentTimeMillis()); return; }
        JSONObject people = st.people();
        Set<String> excluded = st.excluded();
        long newest = last;
        String[] cols = {CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION};
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols,
                CallLog.Calls.DATE + " > ?", new String[]{String.valueOf(last)}, CallLog.Calls.DATE + " ASC")) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String phone = norm(cur.getString(0));
                int type = cur.getInt(1);
                long at = cur.getLong(2);
                int dur = cur.getInt(3);
                newest = Math.max(newest, at);
                String dir = dir(type);
                if (phone.length() < 7 || dir == null) continue; // 번호 숨김·음성사서함 등
                JSONObject call = new JSONObject()
                        .put("dir", dir).put("at", iso(at)).put("dur", dur).put("key", at + "_" + phone);
                JSONObject who = people.optJSONObject(phone);
                if (who != null) {
                    call.put("person_id", who.getString("id"));
                    st.addOutbox(call);
                } else if (excluded.contains(phone)) {
                    // 제외 번호: 아무것도 안 함
                } else if (!"missed".equals(dir) && dur > 0) {
                    // 처음 번호와 실제로 통화했을 때만 묻는다(못 받은 전화·안 받은 발신은 안 물음)
                    String name = contactName(c, phone);
                    st.addPending(phone, name, call);
                    ask(c, phone, name, call, false);
                }
            }
        } catch (Exception ignored) {
            // 권한이 막혔거나 통화 기록을 못 읽으면 다음 번에
        }
        if (newest > last) st.setLastTs(newest);
    }

    // 보낼 통화를 서버로. 다 보냈거나 보낼 게 없으면 true
    static boolean send(Context c) {
        Store st = Store.get(c);
        JSONArray a = st.outbox();
        if (a.length() == 0) return true;
        try {
            Net.Result r = Net.rpc("rel_add_calls", new JSONObject().put("p_token", st.token()).put("p_calls", a).toString());
            if (Net.authFailed(r)) { st.setToken(""); return false; }
            if (r.status != 200 || !new JSONObject(r.body).optBoolean("ok")) return false;
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < a.length(); i++) keys.add(a.getJSONObject(i).optString("key"));
            st.removeSent(keys);
            st.setLastSent(System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // [기록]으로 등록을 마친 번호: 모아 둔 통화를 그 사람 기록으로 옮기고 알림을 닫는다
    static void answered(Context c, String phone, String personId, String name) {
        Store st = Store.get(c);
        st.addPersonPhone(phone, personId, name);
        JSONObject x = st.takePending(phone);
        if (x != null) {
            JSONArray calls = x.optJSONArray("calls");
            for (int i = 0; calls != null && i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call == null) continue;
                try { st.addOutbox(call.put("person_id", personId)); } catch (Exception ignored) {}
            }
        }
        cancel(c, phone);
    }

    // [아님]: 제외 목록에 넣고 다시 안 묻는다
    static void notPerson(Context c, String phone) {
        Store st = Store.get(c);
        st.exclude(phone);
        st.takePending(phone);
        cancel(c, phone);
    }

    // 알림: 「기록할 사람인가요?」 [기록] [아님]. 답할 때까지 남는다(밀어서 지우기 어렵게).
    static void ask(Context c, String phone, String name, JSONObject call, boolean silent) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH_ASK, "기록할 사람인가요?", NotificationManager.IMPORTANCE_HIGH));
        int id = notifId(phone);
        Intent rec = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_REGISTER)
                .putExtra("phone", phone).putExtra("name", name == null ? "" : name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pRec = PendingIntent.getActivity(c, id, rec, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent no = new Intent(c, AskReceiver.class).setAction(AskReceiver.NOT_PERSON).putExtra("phone", phone);
        PendingIntent pNo = PendingIntent.getBroadcast(c, id, no, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String who = (name == null || name.isEmpty()) ? pretty(phone) : name + " · " + pretty(phone);
        String line = call == null ? "" : " · " + callLine(call);
        Notification.Builder nb = new Notification.Builder(c, CH_ASK)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFFA35A3C)
                .setContentTitle("기록할 사람인가요?")
                .setContentText(who + line)
                .setContentIntent(pRec)
                .addAction(new Notification.Action.Builder(null, "기록", pRec).build())
                .addAction(new Notification.Action.Builder(null, "아님", pNo).build())
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        // 지워진 알림을 다시 띄울 때는 소리·진동 없이
        if (silent && android.os.Build.VERSION.SDK_INT >= 31) nb.setSilent(true);
        Notification n = nb.build();
        try { nm.notify(id, n); } catch (SecurityException ignored) {}
    }

    // 지워진 알림을 다시 띄운다(답 안 한 번호가 남아 있는 동안). 15분 작업·앱 열 때.
    static void repost(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Set<Integer> showing = new HashSet<>();
        try { for (StatusBarNotification s : nm.getActiveNotifications()) showing.add(s.getId()); } catch (Exception ignored) {}
        JSONObject pend = Store.get(c).pending();
        for (String phone : Store.keys(pend)) {
            if (showing.contains(notifId(phone))) continue;
            JSONObject x = pend.optJSONObject(phone);
            JSONArray calls = x == null ? null : x.optJSONArray("calls");
            JSONObject lastCall = calls == null || calls.length() == 0 ? null : calls.optJSONObject(calls.length() - 1);
            ask(c, phone, x == null ? "" : x.optString("name"), lastCall, true);
        }
    }

    static void cancel(Context c, String phone) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(notifId(phone));
    }

    private static int notifId(String phone) { return 1000 + Math.abs(phone.hashCode() % 100000); }

    static String dir(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return "in";
            case CallLog.Calls.OUTGOING_TYPE: return "out";
            case CallLog.Calls.MISSED_TYPE:
            case CallLog.Calls.REJECTED_TYPE:
            case CallLog.Calls.BLOCKED_TYPE: return "missed";
            default: return null; // 음성사서함 등
        }
    }

    // 숫자만, +82 → 0 (서버 rel_norm과 같게)
    static String norm(String s) {
        if (s == null) return "";
        String d = s.replaceAll("[^0-9]", "");
        if (d.startsWith("82") && d.length() >= 11) d = "0" + d.substring(2);
        return d;
    }

    static String pretty(String d) {
        if (d.length() == 11) return d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7);
        if (d.length() == 10 && d.startsWith("02")) return "02-" + d.substring(2, 6) + "-" + d.substring(6);
        if (d.length() == 10) return d.substring(0, 3) + "-" + d.substring(3, 6) + "-" + d.substring(6);
        return d;
    }

    private static String callLine(JSONObject call) {
        String dir = call.optString("dir");
        int dur = call.optInt("dur");
        String d = "in".equals(dir) ? "받은 전화" : "out".equals(dir) ? "건 전화" : "못 받은 전화";
        if (dur > 0) d += " " + (dur >= 60 ? (dur / 60) + "분 " : "") + (dur % 60) + "초";
        return d;
    }

    static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date(ms));
    }

    // 폰 연락처에 저장된 이름(있으면). 연락처 권한이 없으면 빈 칸
    static String contactName(Context c, String phone) {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "";
        try (Cursor cur = c.getContentResolver().query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone)),
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {}
        return "";
    }

    // 폰 연락처의 모든 번호(제외 목록에 넣을 때)
    static Set<String> contactPhones(Context c) {
        Set<String> out = new HashSet<>();
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return out;
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String p = norm(cur.getString(0));
                if (p.length() >= 7) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
