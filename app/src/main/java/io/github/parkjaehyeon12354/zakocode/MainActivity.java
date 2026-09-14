package io.github.parkjaehyeon12354.zakocode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends Activity {
    // PC 판 Zako Code 와 같은 색
    static final int BG = 0xFF262624, CARD = 0xFF30302E, FG = 0xFFF2F0E9, USER_FG = 0xFFD4D1C8,
            ACCENT = 0xFFD97757, ON_ACCENT = 0xFF261107, CODE_BG = 0xFF1A1918;
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT, MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    static final String TERMUX_SETUP =
            "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings";

    SharedPreferences prefs;
    JSONArray messages = Agent.newChat();
    LinearLayout list;
    ScrollView scroll;
    EditText input;
    Button send, modelButton;
    TextView current;          // 답변 글자가 이어 붙는 곳. 명령 블록이 끼면 새로 만든다
    AlertDialog question;      // 떠 있는 "실행할까요?" 창
    Thread worker;
    volatile Agent agent;
    volatile boolean always;   // "계속 허용" — 앱을 끌 때까지 묻지 않는다
    volatile boolean stopping;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("zako", MODE_PRIVATE);
        setContentView(layout());
        Intent i = getIntent();
        if (BuildConfig.DEBUG && i.hasExtra("e2e_root")) {
            // 에뮬레이터 끝까지 시험 전용(디버그 빌드에만 있음) — e2e/run.sh
            start(new Agent(i.getStringExtra("e2e_root"), "e2e-key", "e2e/model"), i.getStringExtra("e2e_prompt"), true);
        }
    }

    // ── 화면 ────────────────────────────────────────────────

    View layout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(8), dp(8), dp(8));
        TextView title = label("Zako Code", 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        head.addView(button("새 대화", 0, FG, v -> newChat()));
        head.addView(button("설정", 0, FG, v -> settings(null)));
        root.addView(head);

        scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(16));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setBackground(round(CARD, 22));
        input = new EditText(this);
        input.setTextColor(FG);
        input.setTextSize(16);
        input.setBackground(null);
        input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(16), dp(14), dp(16), dp(4));
        composer.addView(input);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(8), dp(8));
        modelButton = button(modelName(), 0, USER_FG, v -> pickModel(null));
        modelButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        modelButton.setSingleLine(true);
        modelButton.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(modelButton, new LinearLayout.LayoutParams(0, WRAP, 1));
        send = button("↑", ACCENT, ON_ACCENT, v -> onSend(false));
        send.setTextSize(18);
        send.setPadding(0, 0, 0, 0);
        bar.addView(send, new LinearLayout.LayoutParams(dp(42), dp(42)));
        composer.addView(bar);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.setMargins(dp(12), dp(4), dp(12), dp(12));
        root.addView(composer, lp);

        if (Build.VERSION.SDK_INT >= 30) {
            // 안드로이드 15 는 화면을 상태표시줄 밑까지 늘린다 — 막대와 키보드만큼 직접 비운다
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, in) -> {
                Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    TextView add(String s, int fg, int bg, boolean mono) {
        TextView t = label(s, 15);
        t.setTextColor(fg);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.15f);
        if (mono) {
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(13);
        }
        if (bg != 0) {
            t.setBackground(round(bg, 14));
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(12);
        list.addView(t, lp);
        down();
        return t;
    }

    final Agent.Output ui = new Agent.Output() {
        @Override
        public void text(String piece) {
            if (piece.isEmpty()) return;
            runOnUiThread(() -> {
                if (current == null) current = add("", FG, 0, false);
                current.append(piece);
                down();
            });
        }

        @Override
        public void ran(String command, Agent.Result r) {
            String shown = "$ " + command + "\n"
                    + (r == null ? "(거절함)" : r.output + "(종료 코드 " + (r.exitCode == null ? "없음" : r.exitCode) + ")");
            runOnUiThread(() -> {
                add(shown, FG, CODE_BG, true);
                current = null;
            });
        }
    };

    // ── 보내기 ──────────────────────────────────────────────

    void onSend(boolean permissionAsked) {
        if (worker != null) {
            stop();
            return;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (key().isEmpty() || root().isEmpty()) {
            settings(() -> onSend(false));
            return;
        }
        if (prefs.getString("model", "").isEmpty()) {
            pickModel(() -> onSend(false));
            return;
        }
        if (!permissionAsked && Termux.installed(this) && checkSelfPermission(Termux.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Termux.PERMISSION}, 1);
            return;
        }
        input.setText("");
        start(new Agent(root(), key(), prefs.getString("model", "")), text, false);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        onSend(true);   // 거절해도 대화는 된다. 명령을 실행할 때 권한 안내가 나온다
    }

    void start(Agent a, String text, boolean auto) {
        add(text, USER_FG, CARD, false);
        String snapshot = messages.toString();
        int before = messages.length();
        Agent.say(messages, text);
        agent = a;
        stopping = false;
        current = null;
        send.setText("■");
        Termux termux = new Termux(this);
        worker = new Thread(() -> {
            String error = null;
            try {
                a.turn(messages, termux, cmd -> auto || always || ask(cmd), ui);
            } catch (Exception e) {
                error = stopping ? "중단했습니다." : (e.getMessage() != null ? e.getMessage() : e.toString());
            }
            String failure = error;
            runOnUiThread(() -> done(text, failure, snapshot, before, auto));
        });
        worker.start();
    }

    void done(String text, String error, String snapshot, int before, boolean auto) {
        worker = null;
        agent = null;
        current = null;
        question = null;
        send.setText("↑");
        if (auto) Log.i("ZakoE2E", error == null ? "DONE" : "FAIL " + error);
        if (error == null) return;
        add(error, ACCENT, 0, false);
        if (messages.length() <= before + 1) {
            // 아무 답도 못 받았으면 방금 보낸 말까지 없던 일로 — 입력칸에 되돌려 둔다
            try {
                messages = new JSONArray(snapshot);
            } catch (JSONException ignored) {
            }
            if (input.getText().length() == 0) input.setText(text);
        }
        if (error.contains("allow-external-apps")) termuxSetup();
    }

    void stop() {
        stopping = true;
        Thread w = worker;
        if (w != null) w.interrupt();
        Agent a = agent;
        if (a != null) new Thread(a::cancel).start();   // 끊는 것도 네트워크라 화면 스레드에서 하지 않는다
        if (question != null) question.dismiss();
    }

    /** 일하는 스레드에서 부른다. 사용자가 누를 때까지 기다린다. */
    boolean ask(String cmd) throws InterruptedException {
        BlockingQueue<Integer> answer = new ArrayBlockingQueue<>(1);
        runOnUiThread(() -> {
            if (!alive()) {
                answer.offer(0);
                return;
            }
            TextView code = label("$ " + cmd, 13);
            code.setTypeface(Typeface.MONOSPACE);
            code.setTextIsSelectable(true);
            code.setPadding(dp(24), dp(8), dp(24), dp(8));
            ScrollView box = new ScrollView(this);
            box.addView(code);
            question = new AlertDialog.Builder(this)
                    .setTitle("이 명령을 실행할까요?")
                    .setView(box)
                    .setPositiveButton("실행", (d, w) -> answer.offer(1))
                    .setNeutralButton("계속 허용", (d, w) -> answer.offer(2))
                    .setNegativeButton("거절", (d, w) -> answer.offer(0))
                    .setOnCancelListener(d -> answer.offer(0))
                    .show();
        });
        int a = answer.take();
        if (a == 2) always = true;
        return a > 0;
    }

    void newChat() {
        if (worker != null) {
            toast("답변이 끝난 뒤에 누르세요");
            return;
        }
        messages = Agent.newChat();
        list.removeAllViews();
        current = null;
    }

    // ── 설정 ────────────────────────────────────────────────

    String key() {
        return prefs.getString("key", "");
    }

    String root() {
        return prefs.getString("root", "");
    }

    String modelName() {
        String m = prefs.getString("model", "");
        return m.isEmpty() ? "모델 고르기" : m;
    }

    void settings(Runnable then) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText key = field(key(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText root = field(root(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(label("API 키", 14));
        form.addView(key);
        TextView second = label("보낼 곳 (Base URL)", 14);
        second.setPadding(0, dp(12), 0, 0);
        form.addView(second);
        form.addView(root);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("설정")
                .setView(form)
                .setPositiveButton("저장", null)
                .setNegativeButton("취소", null)
                .show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            String r = root.getText().toString().trim().replaceAll("/+$", "");
            if (k.isEmpty() || r.isEmpty()) {
                toast("API 키와 보낼 곳을 둘 다 넣어야 합니다");
                return;
            }
            prefs.edit().putString("key", k).putString("root", r).apply();
            d.dismiss();
            if (then != null) then.run();
        });
    }

    void pickModel(Runnable then) {
        if (key().isEmpty() || root().isEmpty()) {
            settings(() -> pickModel(then));
            return;
        }
        modelButton.setText("모델 불러오는 중");
        Agent a = new Agent(root(), key(), "");
        new Thread(() -> {
            try {
                List<String> names = a.models();
                runOnUiThread(() -> {
                    modelButton.setText(modelName());
                    if (!alive()) return;
                    if (names.isEmpty()) {
                        toast("모델 목록이 비어 있습니다");
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("모델")
                            .setItems(names.toArray(new String[0]), (d, i) -> {
                                prefs.edit().putString("model", names.get(i)).apply();
                                modelButton.setText(modelName());
                                if (then != null) then.run();
                            })
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    modelButton.setText(modelName());
                    toast("모델 목록을 못 받았습니다: " + e.getMessage());
                });
            }
        }).start();
    }

    void termuxSetup() {
        TextView t = label("Termux 를 열고 아래 한 줄을 붙여넣어 실행한 뒤 다시 보내세요.\n\n" + TERMUX_SETUP, 14);
        t.setTextIsSelectable(true);
        t.setPadding(dp(24), dp(8), dp(24), 0);
        new AlertDialog.Builder(this)
                .setTitle("Termux 설정이 필요합니다")
                .setView(t)
                .setPositiveButton("복사", (d, w) -> {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("termux", TERMUX_SETUP));
                    toast("복사했습니다");
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    // ── 도우미 ──────────────────────────────────────────────

    boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    void down() {
        scroll.post(() -> scroll.scrollTo(0, list.getHeight()));
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    Drawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    TextView label(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(FG);
        t.setTextSize(sp);
        return t;
    }

    EditText field(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(FG);
        e.setSingleLine(true);
        e.setInputType(type);
        return e;
    }

    Button button(String text, int bg, int fg, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(bg, 21), round(0xFFFFFFFF, 21)));
        b.setOnClickListener(click);
        return b;
    }
}
