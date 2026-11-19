package app.recordkit.rel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

// 폰 안에만 두는 것: 로그인 열쇠, 마지막으로 읽은 통화 시각, 제외 번호, 답 안 한 번호, 보낼 통화.
// 제외 번호·답 안 한 번호는 서버에 올리지 않는다(기록할 사람이 아닌 번호는 폰 밖으로 안 나감).
final class Store {
    private static Store one;
    private final SharedPreferences p;

    private Store(Context c) { p = c.getApplicationContext().getSharedPreferences("rel", Context.MODE_PRIVATE); }

    static synchronized Store get(Context c) {
        if (one == null) one = new Store(c);
        return one;
    }

    // ---- 로그인 ----
    String token() { return p.getString("token", ""); }

    synchronized void setToken(String t) {
        SharedPreferences.Editor e = p.edit().putString("token", t == null ? "" : t);
        // 처음 로그인하면 지금부터의 통화만 본다(지난 통화 기록을 한꺼번에 묻지 않게)
        if (t != null && !t.isEmpty() && p.getLong("lastTs", 0) == 0) e.putLong("lastTs", System.currentTimeMillis());
        if (t == null || t.isEmpty()) e.remove("people").remove("outbox").remove("nameAsks");
        else e.remove("expired");
        e.apply();
    }

    // 로그인이 풀림(서버가 열쇠를 거절). 「나가기」로 나간 것과 가르려고 따로 적는다. 다시 들어가면 지워진다
    synchronized void expire() { setToken(""); p.edit().putBoolean("expired", true).apply(); }
    boolean expired() { return p.getBoolean("expired", false); }

    // 「로그인이 풀렸어요」 알림을 마지막으로 띄운 때(하루 한 번만)
    long loginNoticeAt() { return p.getLong("loginNoticeAt", 0); }
    void setLoginNoticeAt(long t) { p.edit().putLong("loginNoticeAt", t).apply(); }

    long lastTs() { return p.getLong("lastTs", 0); }
    void setLastTs(long t) { p.edit().putLong("lastTs", t).apply(); }

    // ---- 사람 번호(서버에서 받은 것, 내게 보이는 사람만) phone → {id,name} ----
    synchronized JSONObject people() { return obj("people"); }
    synchronized void setPeople(JSONObject o) { p.edit().putString("people", o.toString()).apply(); }

    synchronized void addPersonPhone(String phone, String id, String name) {
        JSONObject o = people();
        try { o.put(phone, new JSONObject().put("id", id).put("name", name == null ? "" : name)); } catch (Exception ignored) {}
        setPeople(o);
    }

    // ---- 이름 맞추기(Names): 폰 연락처 이름 ↔ 관계 기록 이름 ----
    // names: 사람 id → {s 마지막으로 맞춘 관계 기록 이름, c 그때 연락처 이름, has 연락처가 있었나, gone 폰에서 지웠나}
    synchronized JSONObject names() { return obj("names"); }
    synchronized void setNames(JSONObject o) { p.edit().putString("names", o.toString()).apply(); }

    // 서로 달라서 물어볼 사람들 [{id, s, c}]
    String nameAsks() { return p.getString("nameAsks", "[]"); }
    void setNameAsks(JSONArray a) { p.edit().putString("nameAsks", a.toString()).apply(); }

    // 고른 쪽: 사람 id → "s"(관계 기록 이름으로) / "c"(연락처 이름으로)
    synchronized JSONObject namePicks() { return obj("namePicks"); }
    synchronized void namePick(String id, String which) {
        JSONObject o = namePicks();
        try { o.put(id, which); } catch (Exception ignored) {}
        p.edit().putString("namePicks", o.toString()).apply();
    }

    // 앱에서 방금 이름을 저장한 사람: 연락처를 이 이름으로 맞춘다
    Set<String> namePush() { return new HashSet<>(p.getStringSet("namePush", new HashSet<>())); }
    synchronized void addNamePush(String id) {
        Set<String> s = namePush();
        s.add(id);
        p.edit().putStringSet("namePush", s).apply();
    }

    // 맞추기를 마친 사람: 고른 것·방금 저장 표시를 지운다
    synchronized void nameDone(String id) {
        Set<String> s = namePush();
        JSONObject o = namePicks();
        if (!s.contains(id) && !o.has(id)) return;
        s.remove(id);
        o.remove(id);
        p.edit().putStringSet("namePush", s).putString("namePicks", o.toString()).apply();
    }

    // 이름을 바꾼 기록(최근 30줄, 폰 안에만): 잘못 바뀌었을 때 옛 이름을 찾으려고
    synchronized void nameLog(String line) {
        JSONArray a = arr("nameLog"), keep = new JSONArray();
        a.put(new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date()) + " " + line);
        for (int i = Math.max(0, a.length() - 30); i < a.length(); i++) keep.put(a.optString(i));
        p.edit().putString("nameLog", keep.toString()).apply();
    }
    String nameLogs() { return p.getString("nameLog", "[]"); }

    // ---- 제외 번호 ----
    Set<String> excluded() { return new HashSet<>(p.getStringSet("excluded", new HashSet<>())); }

    synchronized void exclude(String phone) {
        Set<String> s = excluded();
        s.add(phone);
        p.edit().putStringSet("excluded", s).apply();
    }

    synchronized int excludeAll(Set<String> phones) {
        Set<String> s = excluded();
        int before = s.size();
        s.addAll(phones);
        p.edit().putStringSet("excluded", s).apply();
        return s.size() - before;
    }

    synchronized void unexclude(String phone) {
        Set<String> s = excluded();
        s.remove(phone);
        p.edit().putStringSet("excluded", s).apply();
    }

    // ---- 답 안 한 번호: phone → {name, calls:[{dir,at,dur,key}]} ----
    synchronized JSONObject pending() { return obj("pending"); }

    synchronized void addPending(String phone, String name, JSONObject call) {
        JSONObject o = pending();
        try {
            JSONObject x = o.optJSONObject(phone);
            if (x == null) x = new JSONObject().put("calls", new JSONArray());
            if (name != null && !name.isEmpty()) x.put("name", name);
            x.getJSONArray("calls").put(call);
            o.put(phone, x);
        } catch (Exception ignored) {}
        p.edit().putString("pending", o.toString()).apply();
    }

    synchronized JSONObject takePending(String phone) {
        JSONObject o = pending();
        JSONObject x = o.optJSONObject(phone);
        o.remove(phone);
        p.edit().putString("pending", o.toString()).apply();
        return x;
    }

    // ---- 보낼 통화 ----
    synchronized JSONArray outbox() { return arr("outbox"); }

    synchronized void addOutbox(JSONObject call) {
        JSONArray a = outbox();
        a.put(call);
        p.edit().putString("outbox", a.toString()).apply();
    }

    // 보낸 것만 뺀다(보내는 사이에 새로 들어온 것은 남김)
    synchronized void removeSent(Set<String> keys) {
        JSONArray a = outbox(), keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && !keys.contains(x.optString("key"))) keep.put(x);
        }
        p.edit().putString("outbox", keep.toString()).apply();
    }

    // 내가 고를 수 있는 일 영역(서버 rel_sync의 me.areas, 화면이 받아 둔다). 작은 창에서 쓴다
    String areas() { return p.getString("areas", "[]"); }
    void setAreas(String json) { p.edit().putString("areas", json == null ? "[]" : json).apply(); }

    long lastSent() { return p.getLong("lastSent", 0); }
    void setLastSent(long t) { p.edit().putLong("lastSent", t).apply(); }

    private JSONObject obj(String k) {
        try { return new JSONObject(p.getString(k, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private JSONArray arr(String k) {
        try { return new JSONArray(p.getString(k, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static Iterable<String> keys(JSONObject o) {
        return () -> {
            Iterator<String> it = o.keys();
            return it;
        };
    }
}
