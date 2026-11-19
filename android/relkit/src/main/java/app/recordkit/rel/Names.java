package app.recordkit.rel;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 이름 맞추기(2026-11-20 룡): 관계 기록 이름 ↔ 폰 연락처 이름. 한쪽에서 바꾸면 다른 쪽도 같이 바뀐다.
//  - 관계 기록에서 저장·고침 → 같은 번호의 연락처 이름을 바꾼다. 연락처에 없는 번호면 연락처를 새로 만든다(앱에서 방금 저장한 사람만).
//  - 폰 연락처에서 이름을 바꿈 → 관계 기록 이름을 바꾼다(rel_rename_person).
//  - 처음 맞출 때 이미 서로 다르거나, 두 쪽을 다 바꿨으면 → 묻는다(화면 「이름이 서로 달라요」). 고른 쪽으로 맞춘다.
// 어느 쪽이 바뀌었는지는 마지막으로 맞춘 이름(폰 안 names)과 견줘서 안다.
// 안전 규칙(엉뚱한 연락처를 바꾸지 않게):
//  - 번호가 똑같은 연락처만 본다(폰의 느슨한 번호 맞추기를 믿지 않는다).
//  - 사람 하나 ↔ 연락처 하나일 때만 맞춘다. 한 사람의 번호가 연락처 여러 개에 있거나, 한 연락처에 앱의 두 사람이 걸리면 건드리지 않는다.
//  - 연락처를 못 읽은 바퀴에는 아무것도 안 한다. 연락처를 지우는 일은 없다. 폰에서 지운 연락처는 앱에서 다시 저장할 때만 다시 만든다.
//  - 고치는 줄은 폰·구글·삼성 계정 것만(텔레그램 같은 다른 앱의 줄은 그 앱이 되돌려 놓는다).
// Calls.run 안(LOCK)에서 통화를 다 보낸 뒤에 돈다: 앱 열 때 · 저장한 뒤 · 15분 확인.
final class Names {
    private Names() {}

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private static final class Hit {
        final Set<Long> cids = new LinkedHashSet<>();
        String name = "";
        boolean failed;
    }

    static void sync(Context c) {
        if (!allowed(c)) return;
        Store st = Store.get(c);
        if (st.token().isEmpty()) return;

        // 사람별로 묶기: id → 이름, id → 번호들
        JSONObject people = st.people();
        Map<String, String> nameOf = new LinkedHashMap<>();
        Map<String, List<String>> phonesOf = new HashMap<>();
        for (String phone : Store.keys(people)) {
            JSONObject x = people.optJSONObject(phone);
            if (x == null) continue;
            String id = x.optString("id");
            if (id.isEmpty()) continue;
            nameOf.put(id, x.optString("name").trim());
            List<String> l = phonesOf.get(id);
            if (l == null) phonesOf.put(id, l = new ArrayList<>());
            l.add(phone);
        }

        JSONObject names = st.names(), next = new JSONObject();
        JSONObject picks = st.namePicks();
        Set<String> push = st.namePush();
        JSONArray asks = new JSONArray();
        ContentResolver cr = c.getContentResolver();

        // 1) 사람마다 연락처 찾기 + 한 연락처에 앱의 몇 사람이 걸리나
        Map<String, Hit> hits = new HashMap<>();
        Map<Long, Integer> used = new HashMap<>();
        for (String id : nameOf.keySet()) {
            Hit h = find(cr, phonesOf.get(id));
            hits.put(id, h);
            for (long cid : h.cids) { Integer n = used.get(cid); used.put(cid, n == null ? 1 : n + 1); }
        }

        // 2) 맞추기
        for (Map.Entry<String, String> e : nameOf.entrySet()) {
            String id = e.getKey(), s = e.getValue();
            JSONObject was = names.optJSONObject(id);
            boolean marked = push.contains(id) || picks.has(id);
            try {
                Hit h = hits.get(id);
                if (s.isEmpty() || h == null || h.failed) { keep(next, id, was); continue; } // 못 읽었으면 이번에는 그대로
                List<String> phones = phonesOf.get(id);
                boolean wantS = push.contains(id) || "s".equals(picks.optString(id)); // 관계 기록 이름으로(앱에서 방금 저장했거나 골랐음)
                boolean wantC = !wantS && "c".equals(picks.optString(id));           // 연락처 이름으로(골랐음)

                if (h.cids.isEmpty()) {
                    // 연락처에 없는 번호: 앱에서 방금 저장한 사람만 새로 만든다(처음 맞출 때 한꺼번에 만들지 않는다)
                    boolean gone = was != null && (was.optBoolean("has") || was.optBoolean("gone"));
                    if (wantS && !numberLike(s) && create(cr, s, phones)) {
                        st.nameLog("새 연락처: " + s);
                        next.put(id, state(s, s, true, false));
                    } else next.put(id, state(s, "", false, gone));
                    if (marked) st.nameDone(id);
                    continue;
                }

                // 사람 하나 ↔ 연락처 하나가 아니면 건드리지 않는다
                long cid = h.cids.iterator().next();
                Integer n = used.get(cid);
                if (h.cids.size() > 1 || (n != null && n > 1)) { keep(next, id, was); if (marked) st.nameDone(id); continue; }

                String cn = h.name;
                boolean had = was != null && was.optBoolean("has");
                String dir; // "c" = 연락처를 관계 기록 이름으로, "s" = 관계 기록을 연락처 이름으로, "" = 그대로, "?" = 묻기
                if (same(s, cn)) dir = "";
                else if (wantS) dir = "c";
                else if (wantC) dir = "s";
                else if (numberLike(cn)) dir = numberLike(s) ? "" : "c";   // 연락처에 이름이 없음
                else if (numberLike(s)) dir = "s";                          // 관계 기록에 번호로만 저장해 둠
                else if (!had) dir = "?";                                   // 처음 맞추는데(또는 연락처가 새로 보이는데) 서로 다름
                else {
                    boolean sCh = !same(s, was.optString("s")), cCh = !same(cn, was.optString("c"));
                    dir = sCh && cCh ? "?" : sCh ? "c" : cCh ? "s" : "";
                }
                if ("s".equals(dir) && cn.length() > 60) dir = ""; // 서버 이름은 60자까지

                if ("c".equals(dir)) {
                    if (!rename(cr, cid, s)) { keep(next, id, was); continue; } // 못 고쳤으면 다음에 다시(표시도 남긴다)
                    st.nameLog("연락처: " + cn + " → " + s);
                    Hit again = find(cr, phones);
                    next.put(id, state(s, again.failed || again.cids.isEmpty() ? s : again.name, true, false));
                } else if ("s".equals(dir)) {
                    int r = renameServer(st, id, cn);
                    if (r == 0) { keep(next, id, was); continue; }              // 인터넷이 안 됨: 다음에 다시(고른 것도 남겨 둔다)
                    if (r == 1) {
                        st.nameLog("관계 기록: " + s + " → " + cn);
                        next.put(id, state(cn, cn, true, false));
                        for (String ph : phones) st.addPersonPhone(ph, id, cn);
                    } else next.put(id, state(s, cn, true, false));              // 서버가 거절: 그대로 두고 다시 안 한다
                } else if ("?".equals(dir)) {
                    keep(next, id, was);
                    asks.put(new JSONObject().put("id", id).put("s", s).put("c", cn));
                    continue; // 고르기 전에는 그대로 둔다
                } else next.put(id, state(s, cn, true, false));
                if (marked) st.nameDone(id);
            } catch (Exception ex) {
                keep(next, id, was);
            }
        }
        st.setNames(next);
        st.setNameAsks(asks);
    }

    private static void keep(JSONObject next, String id, JSONObject was) {
        if (was != null) try { next.put(id, was); } catch (Exception ignored) {}
    }

    private static JSONObject state(String s, String cn, boolean has, boolean gone) throws Exception {
        return new JSONObject().put("s", s).put("c", cn == null ? "" : cn).put("has", has).put("gone", gone);
    }

    // 이름이 번호뿐인가(이름 없이 번호로 저장한 사람·이름 없는 연락처)
    static boolean numberLike(String s) {
        return s == null || s.trim().isEmpty() || s.matches("[0-9+\\-() .]+");
    }

    // 같은 이름인가: 빈칸은 빼고 견준다(폰이 한글 이름의 빈칸을 빼고 보여 줄 때가 있다)
    static boolean same(String a, String b) {
        return (a == null ? "" : a).replaceAll("\\s+", "").equals((b == null ? "" : b).replaceAll("\\s+", ""));
    }

    // 이 번호들의 연락처를 찾는다. 번호가 똑같은 것만(폰은 뒤 몇 자리만 같아도 찾아 준다). 못 읽었으면 failed
    private static Hit find(ContentResolver cr, List<String> phones) {
        Hit h = new Hit();
        if (phones == null) { h.failed = true; return h; }
        for (String phone : phones) {
            try (Cursor cur = cr.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone)),
                    new String[]{ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.NUMBER},
                    null, null, null)) {
                if (cur == null) { h.failed = true; return h; }
                while (cur.moveToNext()) {
                    if (!phone.equals(Calls.norm(cur.getString(2)))) continue;
                    if (h.cids.add(cur.getLong(0)) && h.cids.size() == 1) {
                        String n = cur.getString(1);
                        h.name = n == null ? "" : n.trim();
                    }
                }
            } catch (Exception e) {
                h.failed = true;
                return h;
            }
        }
        return h;
    }

    // 고쳐도 되는 계정인가: 폰 안(계정 없음)·구글·삼성. 다른 앱(텔레그램 등)이 만든 줄은 안 고친다
    private static boolean mine(String type) {
        return type == null || type.isEmpty() || "com.google".equals(type)
                || type.startsWith("vnd.sec.contact") || "com.osp.app.signin".equals(type) || type.startsWith("com.samsung");
    }

    // 연락처 이름 바꾸기: 이름 줄을 고친다(성·이름 칸은 폰이 알아서 다시 나눈다). 이름 줄이 없으면 하나 넣는다. 고쳤으면 true
    private static boolean rename(ContentResolver cr, long contactId, String name) {
        try {
            List<Long> raws = new ArrayList<>();
            try (Cursor cur = cr.query(ContactsContract.RawContacts.CONTENT_URI,
                    new String[]{ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE},
                    ContactsContract.RawContacts.CONTACT_ID + "=? AND " + ContactsContract.RawContacts.DELETED + "=0",
                    new String[]{String.valueOf(contactId)}, null)) {
                while (cur != null && cur.moveToNext()) if (mine(cur.getString(1))) raws.add(cur.getLong(0));
            }
            if (raws.isEmpty()) return false;
            int done = 0;
            for (long raw : raws) {
                ContentValues v = new ContentValues();
                v.put(StructuredName.DISPLAY_NAME, name);
                done += cr.update(ContactsContract.Data.CONTENT_URI, v,
                        ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(raw), StructuredName.CONTENT_ITEM_TYPE});
            }
            if (done > 0) return true;
            ContentValues i = new ContentValues();
            i.put(ContactsContract.Data.RAW_CONTACT_ID, raws.get(0));
            i.put(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
            i.put(StructuredName.DISPLAY_NAME, name);
            return cr.insert(ContactsContract.Data.CONTENT_URI, i) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // 새 연락처: 폰에서 가장 많이 쓰는 구글 계정에 만든다(구글 주소록에도 올라가게). 구글 계정이 없으면 폰에만
    private static boolean create(ContentResolver cr, String name, List<String> phones) {
        try {
            String[] acc = googleAccount(cr);
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, acc == null ? null : acc[1])
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, acc == null ? null : acc[0]).build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, name).build());
            for (String p : phones) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, Calls.pretty(p))
                        .withValue(Phone.TYPE, Phone.TYPE_MOBILE).build());
            }
            cr.applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String[] googleAccount(ContentResolver cr) {
        Map<String, Integer> count = new HashMap<>();
        String best = null;
        try (Cursor cur = cr.query(ContactsContract.RawContacts.CONTENT_URI, new String[]{ContactsContract.RawContacts.ACCOUNT_NAME},
                ContactsContract.RawContacts.ACCOUNT_TYPE + "=? AND " + ContactsContract.RawContacts.DELETED + "=0",
                new String[]{"com.google"}, null)) {
            while (cur != null && cur.moveToNext()) {
                String a = cur.getString(0);
                if (a == null) continue;
                Integer n = count.get(a);
                count.put(a, n == null ? 1 : n + 1);
                if (best == null || count.get(a) > count.get(best)) best = a;
            }
        } catch (Exception ignored) {}
        return best == null ? null : new String[]{best, "com.google"};
    }

    // 서버 이름 바꾸기: 0 = 못 함(인터넷·로그인, 다음에 다시) · 1 = 바꿈 · 2 = 서버가 거절(다시 안 함)
    private static int renameServer(Store st, String id, String name) {
        try {
            Net.Result r = Net.rpc("rel_rename_person", new JSONObject().put("p_token", st.token()).put("p_id", id).put("p_name", name).toString());
            if (Net.authFailed(r)) { st.expire(); return 0; }
            if (r.status != 200) return 0;
            return new JSONObject(r.body).has("id") ? 1 : 2;
        } catch (Exception e) {
            return 0;
        }
    }
}
