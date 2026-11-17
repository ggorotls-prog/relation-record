package app.recordkit.rel;

import android.content.Context;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 관계 기록 폰 기능·화면 원본 한 벌(2026-11-16 룡 「기록 앱은 늘 내용물이 똑같다」). 관계 기록 앱(app.relrecord)과 라이프 RPG가 이 모듈을 같이 쓴다.
// 앱마다 다른 것은 각 앱의 얇은 이어받기 클래스에만(클래스 이름은 그 앱 것 그대로: 통화 받기·작업 설정이 그 이름에 붙어 있다).
public final class Rel {
    private Rel() {}

    /** 관계 기록 단독 앱 */
    public static final String STANDALONE = "app.relrecord";

    private static final Map<Class<?>, Class<?>> found = new HashMap<>();
    private static List<Class<?>> mine;

    // 이 모듈의 창·서비스(base)를 이 앱이 등록한 이어받기 클래스로 바꾼다
    static Class<?> cls(Context c, Class<?> base) {
        synchronized (found) {
            Class<?> k = found.get(base);
            if (k != null) return k;
            if (mine == null) mine = scan(c);
            for (Class<?> x : mine) {
                if (base.isAssignableFrom(x)) { found.put(base, x); return x; }
            }
            found.put(base, base);
            return base;
        }
    }

    private static List<Class<?>> scan(Context c) {
        List<Class<?>> out = new ArrayList<>();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(),
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES | PackageManager.MATCH_DISABLED_COMPONENTS);
            List<ComponentInfo> all = new ArrayList<>();
            if (pi.activities != null) java.util.Collections.addAll(all, pi.activities);
            if (pi.receivers != null) java.util.Collections.addAll(all, pi.receivers);
            if (pi.services != null) java.util.Collections.addAll(all, pi.services);
            ClassLoader cl = c.getClassLoader();
            for (ComponentInfo ci : all) {
                try { out.add(Class.forName(ci.name, false, cl)); } catch (Throwable ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    // 둘 다 깔렸을 때(라이프 RPG + 관계 기록 앱) 통화 모으기·묻기 알림은 관계 기록 앱만(설계안 11절 원칙 ④). 같은 통화가 두 번 올라가지 않게.
    public static boolean quiet(Context c) {
        if (STANDALONE.equals(c.getPackageName())) return false;
        try { c.getPackageManager().getPackageInfo(STANDALONE, 0); return true; } catch (Exception e) { return false; }
    }

    /** 15분 통화 확인 걸기(라이프 RPG는 마을을 열 때) */
    public static void start(Context c) { CallJob.schedulePeriodic(c); }

    /** 관계 기록 로그인 열쇠가 있나 */
    public static boolean signedIn(Context c) { return !Store.get(c).token().isEmpty(); }

    /** 다른 길로 받은 관계 기록 열쇠를 넣는다(라이프 RPG 자동 로그인). 처음이면 지금부터의 통화만 본다 */
    public static void useToken(Context c, String t) {
        if (t == null || t.isEmpty()) return;
        Store.get(c).setToken(t);
        Calls.cancelLoginNotice(c);
        new Thread(() -> Calls.run(c.getApplicationContext())).start();
    }

    static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }
}
