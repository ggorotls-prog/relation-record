package app.relrecord;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// 통화가 끝나면 화면 아래에 뜨는 작은 창(2026-11-11 룡 「알림 말고 팝업, 바로 저장」).
// 「다시 연락할 사람인가요?」 + 이름(연락처 이름 채움) + 영역 고르기 + [저장] [아님].
// 기준(2026-11-13 룡): 기록할 사람 = 다시 연락할 사람. 영역 = 무슨 일로 통화했나(일이 아니면 개인).
// 「개인」이 미리 골라져 있어 모르겠으면 [저장]만 누른다. 일 영역을 누르면 그 영역으로 바뀐다.
// 밖을 눌러 닫으면 알림·앱 첫 화면에 남아 나중에 답할 수 있다.
public class AskActivity extends Activity {
    // 화면 값(브랜드 가이드 9절) + 관계 기록 포인트 색
    private static final int BG = Color.parseColor("#ffffff"), INK = Color.parseColor("#1f1f1d"),
            SUB = Color.parseColor("#6b6a64"), LINE = Color.parseColor("#e4e2db"), ACCENT = Color.parseColor("#a35a3c"),
            SOFT = Color.parseColor("#f6f5f1");

    private String phone, contact;
    private EditText name;
    private TextView err;
    private LinearLayout chips;
    private final List<String> areas = new ArrayList<>(), picked = new ArrayList<>();
    private boolean busy, defaulted; // defaulted = 「개인」이 미리 골라진 그대로(아직 손 안 댐)

    static void open(Context c, String phone, String name, JSONObject call) {
        Intent i = new Intent(c, AskActivity.class)
                .putExtra("phone", phone).putExtra("name", name == null ? "" : name)
                .putExtra("call", call == null ? "" : call.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { c.startActivity(i); } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Store.get(this).token().isEmpty()) { finish(); return; }
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setGravity(Gravity.BOTTOM);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.35f);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setFinishOnTouchOutside(true);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        show(i);
    }

    private void show(Intent i) {
        phone = Calls.norm(i.getStringExtra("phone"));
        if (phone.length() < 7) { finish(); return; }
        // 그새 등록·제외됐으면 닫는다
        if (Store.get(this).people().has(phone) || Store.get(this).excluded().contains(phone)) { Calls.cancel(this, phone); finish(); return; }
        contact = i.getStringExtra("name");
        if (contact == null || contact.isEmpty()) contact = Calls.contactName(this, phone);
        String line = "";
        try {
            String s = i.getStringExtra("call");
            if (s != null && !s.isEmpty()) line = " · " + Calls.callLine(new JSONObject(s));
        } catch (Exception ignored) {}

        areas.clear();
        picked.clear();
        areas.add("개인"); // 미리 골라진 칸이 보이게 맨 앞에
        try {
            JSONArray a = new JSONArray(Store.get(this).areas());
            for (int k = 0; k < a.length(); k++) areas.add(a.getString(k));
        } catch (Exception ignored) {}
        picked.add("개인");
        defaulted = true;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG);
        float r = dp(16);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(g);

        card.addView(text("다시 연락할 사람인가요?", 19, true, INK));
        card.addView(text("택배·배달·광고·한 번 묻고 끝난 곳은 [아님]", 12, false, SUB), m(dp(2), 0));
        TextView sub = text(Calls.pretty(phone) + line, 14, false, SUB);
        card.addView(sub, m(dp(4), dp(14)));

        name = new EditText(this);
        name.setText(contact == null ? "" : contact);
        name.setHint("이름 (폰 연락처 이름 그대로)");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setTextSize(16);
        name.setTextColor(INK);
        name.setPadding(dp(14), dp(12), dp(14), dp(12));
        name.setBackground(box(SOFT, LINE));
        card.addView(name, m(0, dp(6)));
        TextView hint = text("이름이 없으면 번호로 저장돼요. 나중에 고칠 수 있어요.", 12, false, SUB);
        card.addView(hint, m(0, dp(14)));

        card.addView(text("무슨 일로 통화했나요?", 14, true, INK), m(0, dp(2)));
        card.addView(text("일이 아니거나 모르겠으면 「개인」 그대로 저장 (나중에 바꿀 수 있어요). 관계 기록은 모두 나만 봐요", 12, false, SUB), m(0, dp(8)));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chips);
        card.addView(hs, m(0, dp(6)));
        drawChips();

        err = text("", 13, false, Color.parseColor("#b3261e"));
        card.addView(err, m(dp(4), dp(10)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView no = button("아님", false);
        no.setOnClickListener(v -> {
            if (busy) return;
            Calls.notPerson(getApplicationContext(), phone);
            Toast.makeText(this, "제외했어요. 다시 안 물어요", Toast.LENGTH_SHORT).show();
            finish();
        });
        TextView yes = button("저장", true);
        yes.setOnClickListener(v -> save());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(50), 1);
        l1.setMargins(0, 0, dp(8), 0);
        row.addView(no, l1);
        row.addView(yes, new LinearLayout.LayoutParams(0, dp(50), 2));
        card.addView(row);

        setContentView(card);
    }

    private void drawChips() {
        chips.removeAllViews();
        for (String a : areas) {
            int at = picked.indexOf(a);
            TextView t = text((at == 0 ? "① " : "") + a, 15, false, at >= 0 ? Color.WHITE : INK);
            t.setPadding(dp(14), dp(9), dp(14), dp(9));
            t.setBackground(at >= 0 ? box(ACCENT, ACCENT) : box(BG, LINE));
            t.setOnClickListener(v -> {
                // 미리 골라진 「개인」에서 일 영역을 처음 누르면 더하지 않고 그 영역으로 바꾼다(통화가 그 영역에 들어가게)
                if (defaulted && !picked.contains(a)) { picked.clear(); picked.add(a); }
                else if (picked.contains(a)) picked.remove(a); else picked.add(a);
                defaulted = false;
                drawChips();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(6), 0);
            chips.addView(t, lp);
        }
    }

    private void save() {
        if (busy) return;
        if (picked.isEmpty()) { err.setText("영역을 하나 이상 골라 주세요."); return; }
        String n = name.getText().toString().trim();
        if (n.isEmpty()) n = Calls.pretty(phone);
        final String fn = n;
        busy = true;
        err.setText("저장하는 중…");
        Context app = getApplicationContext();
        new Thread(() -> {
            String why;
            try {
                JSONObject body = new JSONObject()
                        .put("p_token", Store.get(app).token())
                        .put("p_id", JSONObject.NULL)
                        .put("p_name", fn)
                        .put("p_areas", new JSONArray(picked))
                        .put("p_phones", new JSONArray().put(phone));
                Net.Result r = Net.rpc("rel_save_person", body.toString());
                JSONObject o = r.status == 200 ? new JSONObject(r.body) : null;
                if (o != null && o.has("id")) {
                    Calls.answered(app, phone, o.getString("id"), fn);
                    Calls.send(app);
                    runOnUiThread(() -> {
                        Toast.makeText(app, fn + " 저장했어요", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }
                why = o != null ? o.optString("error", "저장하지 못했어요.") : Net.authFailed(r) ? "앱에서 다시 들어가 주세요." : "저장하지 못했어요. (" + r.status + ")";
            } catch (Exception e) {
                why = "인터넷이 안 돼요. 알림에 남겨 둘게요.";
            }
            final String w = why;
            runOnUiThread(() -> { busy = false; err.setText(w); });
        }).start();
    }

    // ---- 작은 도우미 ----
    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s, boolean main) {
        TextView t = text(s, 16, true, main ? Color.WHITE : ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(main ? box(ACCENT, ACCENT) : box(BG, LINE));
        return t;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private LinearLayout.LayoutParams m(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, top, 0, bottom);
        return lp;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
