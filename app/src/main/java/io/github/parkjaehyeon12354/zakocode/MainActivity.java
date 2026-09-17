package io.github.parkjaehyeon12354.zakocode;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends Activity {
    // PC 판 Zako Code 와 같은 색 (Ui). 회색은 전부 따뜻한 계열로 맞춘다
    static final int BG = 0xFF262624, SIDE = 0xFF1F1E1D, CARD = 0xFF30302E, CARD_HI = 0xFF3C3B38, CARD_DN = 0xFF454440,
            LINE = 0xFF3D3C39, FG = 0xFFF2F0E9, USER_FG = 0xFFD4D1C8, MUTED = 0xFF99968E,
            ACCENT = 0xFFD97757, ACCENT_HI = 0xFFE38B6D, ON_ACCENT = 0xFF261107, CODE_BG = 0xFF1A1918;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT, MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final String TERMUX_SETUP =
            "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings";
    // 모델이 없을 때 버튼 글자. 이유가 보이게 안내 문구를 둔다 (PC 판과 같음)
    static final String NO_MODEL = "모델 없음 — 키를 넣으세요";
    static final String EMPTY_REPLY = "(빈 응답) 추론 모델이면 사고 토큰이 max_tokens 를 다 먹었을 수 있습니다. 다른 모델로 바꿔보세요.";
    static final String GUIDE = "https://docs.google.com/document/d/1Evev8OaYcB11PaZPHLIB7_0txq3h0uIRHE9txeHEXw4/edit?usp=sharing";

    SharedPreferences prefs;
    File chatsFile;
    List<JSONObject> chats;    // {id, title, messages[]} — 최근에 저장한 대화가 맨 위
    JSONObject current;        // 보고 있는 대화. 아직 목록에 없으면 새 대화
    final List<String> models = new ArrayList<>();
    String model = "";
    String update;             // 새 버전을 받을 주소

    View main;                 // 설정이 열리면 흐려지는 쪽
    LinearLayout list, codeBar, chatRows;
    ScrollView scroll, board;
    HorizontalScrollView codeScroll;
    Dashboard dashboard;
    EditText input, keyBox, rootBox;
    Button send, modelButton, updateButton, peek;
    TextView heading, status, updateNote;
    TextView live;             // 답변 글자가 이어 붙는 곳. 명령 블록이 끼면 새로 만든다
    FrameLayout drawer, settings;
    final List<View> sections = new ArrayList<>();
    final List<Button> navs = new ArrayList<>();

    AlertDialog question;      // 떠 있는 "실행할까요?" 창
    Thread worker;
    volatile Agent agent;
    volatile boolean always;   // "계속 허용" — 앱을 끌 때까지 묻지 않는다
    volatile boolean stopping;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("zako", MODE_PRIVATE);
        chatsFile = new File(getFilesDir(), "chats.json");
        chats = loadChats();
        current = newChat();
        setContentView(layout());
        drawChat();
        loadModels();
        checkUpdate(false);   // 켤 때 한 번 조용히 살펴본다

        Intent i = getIntent();
        if (BuildConfig.DEBUG) {
            // 에뮬레이터 끝까지 시험 전용(디버그 빌드에만 있음) — e2e/run.sh
            if (i.hasExtra("e2e_root")) {
                choose("e2e/model");
                start(new Agent(i.getStringExtra("e2e_root"), "e2e-key", "e2e/model"), i.getStringExtra("e2e_prompt"), true);
            }
            if ("settings".equals(i.getStringExtra("e2e_screen"))) openSettings();
            if ("drawer".equals(i.getStringExtra("e2e_screen"))) openDrawer();
        }
    }

    @Override
    public void onBackPressed() {
        if (settings.getVisibility() == View.VISIBLE) closeSettings();
        else if (drawer.getVisibility() == View.VISIBLE) drawer.setVisibility(View.GONE);
        else super.onBackPressed();
    }

    // ── 화면 ────────────────────────────────────────────────

    View layout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        LinearLayout page = vertical();
        main = page;

        // 머리줄 — 어느 대화를 보고 있는지, 지금 무슨 일이 일어나는지
        LinearLayout head = row();
        head.setPadding(dp(4), dp(4), dp(14), dp(4));
        Button menu = button("☰", 0, 0, FG, 10, v -> openDrawer());
        menu.setTextSize(18);
        menu.setContentDescription("대화 목록");
        head.addView(menu, lp(dp(44), dp(44), 0, 0, 0, 0));
        heading = label("새 대화", 16, FG);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(heading, weighted(4, 0));
        // 새 버전이 있을 때만 나타난다. 안드로이드는 저 혼자 설치할 수 없어 받을 주소를 연다
        updateButton = button("", ACCENT, 0, ON_ACCENT, 8, v -> open(update));
        updateButton.setTextSize(12);
        updateButton.setVisibility(View.GONE);
        head.addView(updateButton, lp(WRAP, dp(32), 8, 0, 0, 0));
        status = label("", 12, MUTED);
        status.setMaxWidth(dp(170));
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        head.addView(status, lp(WRAP, WRAP, 8, 0, 0, 0));
        page.addView(head);
        View line = new View(this);
        line.setBackgroundColor(LINE);
        page.addView(line, lp(MATCH, 1, 0, 0, 0, 0));

        // 대화 또는 (빈 대화면) 사용량
        FrameLayout body = new FrameLayout(this);
        scroll = new ScrollView(this);
        list = vertical();
        list.setPadding(dp(16), dp(2), dp(16), dp(16));
        scroll.addView(list);
        body.addView(scroll);
        board = new ScrollView(this);
        board.setFillViewport(true);
        dashboard = new Dashboard(this);
        board.addView(dashboard, new FrameLayout.LayoutParams(MATCH, WRAP));
        body.addView(board);
        page.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1));

        // 마지막 답변의 코드 블록 단추
        codeBar = row();
        codeBar.setPadding(dp(12), dp(6), dp(12), 0);
        codeScroll = new HorizontalScrollView(this);
        codeScroll.setHorizontalScrollBarEnabled(false);
        codeScroll.addView(codeBar);
        page.addView(codeScroll);

        // 입력 — 카드 하나 안에 입력칸·모델·보내기를 모은다
        LinearLayout composer = vertical();
        composer.setBackground(round(CARD, 14, LINE));
        input = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(6);
        input.setPadding(dp(16), dp(14), dp(16), dp(4));
        composer.addView(input);

        LinearLayout bar = row();
        bar.setPadding(dp(6), 0, dp(8), dp(8));
        FrameLayout left = new FrameLayout(this);   // 버튼은 글자만큼만, 길면 … 으로
        modelButton = button(NO_MODEL, CARD, 0, USER_FG, 8, v -> showModels());
        modelButton.setTextSize(13);
        modelButton.setSingleLine(true);
        modelButton.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(modelButton, new FrameLayout.LayoutParams(WRAP, dp(36), Gravity.START | Gravity.CENTER_VERTICAL));
        bar.addView(left, weighted(0, 8));
        send = button("↑", ACCENT, 0, ON_ACCENT, 20, v -> onSend(false));
        send.setTextSize(18);
        send.setPadding(0, 0, 0, 0);
        send.setContentDescription("보내기");
        bar.addView(send, lp(dp(40), dp(40), 0, 0, 0, 0));
        composer.addView(bar);
        page.addView(composer, lp(MATCH, WRAP, 12, 6, 12, 12));

        root.addView(page);
        root.addView(drawer = buildDrawer());
        root.addView(settings = buildSettings());
        fit(root, getWindow());
        return root;
    }

    // 안드로이드 15 는 화면을 상태표시줄 밑까지 늘린다 — 막대와 키보드만큼 직접 비운다
    void fit(View root, Window window) {
        if (Build.VERSION.SDK_INT < 30) return;
        window.setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    // ── 대화 목록 (PC 판 왼쪽 기둥) ─────────────────────────

    FrameLayout buildDrawer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0x99000000);
        layer.setVisibility(View.GONE);
        layer.setOnClickListener(v -> layer.setVisibility(View.GONE));   // 바깥을 누르면 닫는다

        LinearLayout panel = vertical();
        panel.setBackgroundColor(SIDE);
        panel.setPadding(dp(12), dp(14), dp(12), dp(12));
        panel.setClickable(true);   // 판 안을 누른 게 바깥(닫기)으로 새지 않게

        Button fresh = button("＋   새 대화", CARD, LINE, FG, 10, v -> {
            if (busy()) return;
            current = newChat();
            layer.setVisibility(View.GONE);
            drawChat();
        });
        fresh.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        fresh.setPadding(dp(14), 0, dp(14), 0);
        panel.addView(fresh, lp(MATCH, dp(42), 0, 0, 0, 12));

        ScrollView rows = new ScrollView(this);
        chatRows = vertical();
        rows.addView(chatRows);
        panel.addView(rows, new LinearLayout.LayoutParams(MATCH, 0, 1));

        Button gear = button("⚙   설정", CARD, 0, USER_FG, 10, v -> openSettings());
        gear.setTextSize(13);
        gear.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        gear.setPadding(dp(14), 0, dp(14), 0);
        panel.addView(gear, lp(MATCH, dp(40), 0, 12, 0, 0));

        int width = Math.min(dp(300), getResources().getDisplayMetrics().widthPixels * 85 / 100);
        layer.addView(panel, new FrameLayout.LayoutParams(width, MATCH, Gravity.START));
        return layer;
    }

    void openDrawer() {
        chatRows.removeAllViews();
        if (chats.isEmpty()) {
            TextView t = label("아직 대화가 없습니다", 13, USER_FG);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            chatRows.addView(t, lp(MATCH, WRAP, 0, 16, 0, 0));
        }
        for (JSONObject c : chats) {
            boolean picked = c == current;
            LinearLayout r = row();
            r.setBackground(ripple(picked ? CARD_HI : SIDE, 9, 0));
            r.setOnClickListener(v -> {
                if (busy()) return;
                current = c;
                drawer.setVisibility(View.GONE);
                drawChat();
            });
            TextView title = label(c.optString("title"), 15, picked ? FG : USER_FG);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(12), 0, 0, 0);
            r.addView(title, weighted(0, 0));
            TextView x = label("×", 20, MUTED);
            x.setGravity(Gravity.CENTER);
            x.setContentDescription("대화 지우기");
            x.setOnClickListener(v -> delete(c));
            r.addView(x, lp(dp(44), MATCH, 0, 0, 0, 0));
            chatRows.addView(r, lp(MATCH, dp(44), 0, 0, 0, 2));
        }
        hideKeyboard();
        drawer.setVisibility(View.VISIBLE);
    }

    void delete(JSONObject c) {
        if (busy()) return;
        chats.remove(c);
        if (c == current) current = newChat();
        saveChats();
        openDrawer();   // 목록을 다시 채운다
        drawChat();
    }

    /** 답변을 받는 중에는 대화를 바꾸거나 지우지 않는다 — 받는 쪽이 보고 있는 대화를 고쳐 쓰는 중이다. */
    boolean busy() {
        if (worker == null) return false;
        toast("답변이 끝난 뒤에 누르세요");
        return true;
    }

    // ── 대화 그리기 ─────────────────────────────────────────

    void drawChat() {
        JSONArray messages = current.optJSONArray("messages");
        boolean empty = messages.length() == 0;
        heading.setText(empty ? "새 대화" : current.optString("title"));
        list.removeAllViews();
        live = null;
        // 빈 대화에는 글 대신 사용량을 그린다 — 직접 칠한 그림이라 선택되지 않는다
        board.setVisibility(empty ? View.VISIBLE : View.GONE);
        scroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            dashboard.chats = chats;
            dashboard.hint = key().isEmpty() || root().isEmpty() ? "왼쪽 위 ☰ → 설정에서 " + missing() + "." : null;
            dashboard.requestLayout();
            dashboard.invalidate();
            codeBar.removeAllViews();
            codeScroll.setVisibility(View.GONE);
            return;
        }
        String last = null;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String content = m.optString("content");
            if (!"assistant".equals(m.optString("role"))) {
                who(false);
                // 명령 결과도 user 로 저장돼 있다 — 사람이 쓴 글과 구별되게 코드 블록으로
                if (content.startsWith(Agent.RESULTS)) add(content.trim(), FG, CODE_BG, true);
                else add(content.trim(), USER_FG, 0, false);
                continue;
            }
            last = content;
            who(true);
            for (String[] b : Agent.fences(content)) {
                if (b[0] != null) add(b[1].replaceAll("\n+\\z", ""), FG, CODE_BG, true);
                else add(b[1].trim(), FG, 0, false);
            }
        }
        buildCodeButtons(last);
        down();
    }

    // 마지막 답변의 코드 블록마다 복사 단추를 만든다. HTML 이면 열어볼 수도 있다
    void buildCodeButtons(String last) {
        codeBar.removeAllViews();
        int n = 0;
        for (String[] b : last == null ? new ArrayList<String[]>() : Agent.fences(last)) {
            if (b[0] == null) continue;
            n++;
            String code = b[1], name = "코드 " + n + (b[0].isEmpty() ? "" : " · " + b[0]);
            chip("⧉  " + name, v -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("code", code));
                status.setText(name + " 복사됨");
            });
            if (b[0].equals("html") || b[0].equals("svg") || code.toLowerCase(Locale.ROOT).contains("<html")) {
                chip("▷  브라우저로 열기", v -> showHtml(code));
            }
        }
        codeScroll.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    void chip(String text, View.OnClickListener click) {
        Button b = button(text, CARD, LINE, USER_FG, 8, click);
        b.setTextSize(12);
        codeBar.addView(b, lp(WRAP, dp(34), 0, 0, 8, 0));
    }

    // PC 판은 임시 파일을 기본 브라우저로 연다. 폰에서는 앱 안 웹뷰로 — 파일도, 앱과 잇는 다리도 열지 않는다
    void showHtml(String html) {
        Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout box = vertical();
        box.setBackgroundColor(BG);
        LinearLayout top = row();
        top.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(8), dp(6));
        top.addView(button("✕  닫기", CARD, LINE, FG, 8, v -> d.dismiss()), lp(WRAP, dp(36), 0, 0, 0, 0));
        box.addView(top);
        WebView web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        box.addView(web, new LinearLayout.LayoutParams(MATCH, 0, 1));
        d.setContentView(box);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        fit(box, d.getWindow());
        d.setOnDismissListener(x -> web.destroy());
        d.show();
    }

    void who(boolean ai) {
        TextView t = label(ai ? "AI" : "나", 12, ai ? ACCENT : MUTED);
        list.addView(t, lp(WRAP, WRAP, 0, 16, 0, 0));
    }

    TextView add(String s, int fg, int bg, boolean mono) {
        TextView t = label(s, 15, fg);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.15f);
        t.setHighlightColor(0x66D97757);
        if (mono) {
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(13);
        }
        if (bg != 0) {
            t.setBackground(round(bg, 10, 0));
            t.setPadding(dp(12), dp(10), dp(12), dp(10));
        }
        list.addView(t, lp(MATCH, WRAP, 0, 6, 0, 0));
        down();
        return t;
    }

    final Agent.Output ui = new Agent.Output() {
        @Override
        public void text(String piece) {
            if (piece.isEmpty()) return;
            runOnUiThread(() -> {
                if (live == null) {
                    who(true);
                    live = add("", FG, 0, false);
                }
                live.append(piece);
                down();
            });
        }

        @Override
        public void ran(String command, Agent.Result r) {
            String shown = "$ " + command + "\n"
                    + (r == null ? "(거절함)" : r.output + "(종료 코드 " + (r.exitCode == null ? "없음" : r.exitCode) + ")");
            runOnUiThread(() -> {
                add(shown, FG, CODE_BG, true);
                live = null;
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
            openSettings();
            return;
        }
        if (model.isEmpty()) {
            status.setText("모델을 먼저 골라주세요");
            return;
        }
        if (!permissionAsked && Termux.installed(this) && checkSelfPermission(Termux.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Termux.PERMISSION}, 1);
            return;
        }
        input.setText("");
        start(new Agent(root(), key(), model), text, false);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        onSend(true);   // 거절해도 대화는 된다. 명령을 실행할 때 권한 안내가 나온다
    }

    void start(Agent a, String text, boolean auto) {
        JSONObject chat = current;   // 답변은 시작한 대화에 붙는다
        JSONArray messages = chat.optJSONArray("messages");
        String snapshot = messages.toString();
        Agent.say(messages, text);
        int sent = messages.length();
        if (sent == 1) put(chat, "title", text.length() > 30 ? text.substring(0, 30) : text);
        drawChat();
        who(true);
        live = add("", FG, 0, false);
        status.setText("답변 받는 중…");
        agent = a;
        stopping = false;
        send.setText("■");
        Termux termux = new Termux(this);
        worker = new Thread(() -> {
            Agent.Reply reply = null;
            String error = null;
            try {
                reply = a.turn(messages, termux, cmd -> auto || always || ask(cmd), ui);
            } catch (Exception e) {
                error = stopping ? "중단했습니다." : message(e);
            }
            Agent.Reply last = reply;
            String failure = error;
            runOnUiThread(() -> done(chat, text, last, failure, snapshot, sent, auto));
        });
        worker.start();
    }

    void done(JSONObject chat, String text, Agent.Reply reply, String error, String snapshot, int sent, boolean auto) {
        worker = null;
        agent = null;
        question = null;
        live = null;
        send.setText("↑");
        JSONArray messages = chat.optJSONArray("messages");
        boolean empty = error == null && reply != null && reply.text.trim().isEmpty();
        String failed = stopping ? "중단" : "실패";
        if (messages.length() > sent) {
            // 한 번이라도 답을 받았으면 남긴다 — 도중에 끊겨도 받은 것과 실행한 것은 잃지 않는다
            chats.remove(chat);
            chats.add(0, chat);
            status.setText(error != null ? failed : empty ? "빈 응답" : "완료");
            saveChats();   // 실패하면 status 에 "저장 실패" 가 남는다
            drawChat();    // 코드 블록 서식과 복사 단추를 붙인다
        } else {
            // 아무 답도 못 받았으면 방금 보낸 말까지 없던 일로 — 입력칸에 되돌려 둔다
            try {
                chat.put("messages", new JSONArray(snapshot));
            } catch (JSONException ignored) {
            }
            if (input.getText().length() == 0) input.setText(text);
            status.setText(error != null ? failed : "빈 응답");
        }
        if (error != null) add(stopping ? error : "실패: " + error, ACCENT, 0, false);
        else if (empty) add(EMPTY_REPLY, MUTED, 0, false);
        else if (messages.length() > 0 && "user".equals(messages.optJSONObject(messages.length() - 1).optString("role"))) {
            add(Agent.STEPS_NOTE, FG, 0, false);
        }
        if (auto) Log.i("ZakoE2E", error == null && !empty ? "DONE" : "FAIL " + (empty ? "빈 응답" : error));
        if (error != null && error.contains("allow-external-apps")) termuxSetup();
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
            TextView code = label("$ " + cmd, 13, FG);
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

    // ── 대화 파일 ───────────────────────────────────────────

    static JSONObject newChat() {
        JSONObject c = new JSONObject();
        put(c, "id", UUID.randomUUID().toString().replace("-", ""));
        put(c, "title", "새 대화");
        put(c, "messages", new JSONArray());
        return c;
    }

    List<JSONObject> loadChats() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(chatsFile.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c != null && c.optJSONArray("messages") != null) out.add(c);
            }
        } catch (JSONException e) {
            // 깨진 파일을 빈 목록으로 덮어쓰면 기록이 영영 사라진다 — 옆으로 치워 둔다
            chatsFile.renameTo(new File(getFilesDir(), "chats.broken.json"));
        } catch (IOException e) {
            // 파일이 없으면 빈 목록으로 시작한다
        }
        return out;
    }

    void saveChats() {
        try {
            // 쓰다가 앱이 죽어도 원래 파일이 반쪽이 되지 않게, 옆에 다 쓰고 이름만 바꾼다
            File tmp = new File(getFilesDir(), "chats.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(new JSONArray(chats).toString().getBytes(StandardCharsets.UTF_8));
                o.getFD().sync();
            }
            if (!tmp.renameTo(chatsFile)) throw new IOException("파일 이름을 바꾸지 못했습니다");
        } catch (Exception e) {
            // 조용히 넘기면 앱을 끄는 순간 대화가 사라진다
            status.setText("저장 실패: " + message(e));
        }
    }

    // ── 모델 ────────────────────────────────────────────────

    void loadModels() {
        String k = key(), r = root();
        if (k.isEmpty() || r.isEmpty()) return;   // 둘 다 있어야 부른다
        status.setText("모델 목록 받는 중…");
        new Thread(() -> {
            try {
                List<String> ids = new Agent(r, k, "").models();
                if (ids.isEmpty()) throw new IOException("목록이 비어 있습니다");
                runOnUiThread(() -> {
                    models.clear();
                    models.addAll(ids);
                    choose(Agent.pickDefault(ids, prefs.getString("model", "")));
                    status.setText("모델 " + ids.size() + "개");
                    if (current.optJSONArray("messages").length() == 0) drawChat();   // 안내 문구를 지금 상태에 맞춘다
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    clearModels();
                    status.setText("모델 목록 실패: " + message(e));
                });
            }
        }).start();
    }

    void clearModels() {
        models.clear();
        model = "";
        modelButton.setText(NO_MODEL);
    }

    void choose(String id) {
        model = id;
        modelButton.setText(id + "   ▾");
    }

    // 시스템 목록 창은 밝게 뜰 수 있어 직접 칠한 팝업을 버튼에 붙인다
    void showModels() {
        if (models.isEmpty()) {
            if (key().isEmpty() || root().isEmpty()) openSettings();
            else loadModels();
            return;
        }
        ListPopupWindow pop = new ListPopupWindow(this);
        pop.setAnchorView(modelButton);
        pop.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, models) {
            @Override
            public View getView(int i, View reuse, ViewGroup parent) {
                TextView t = (TextView) super.getView(i, reuse, parent);
                t.setTextColor(FG);
                t.setTextSize(13);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setBackgroundColor(models.get(i).equals(model) ? CARD_HI : 0);
                return t;
            }
        });
        pop.setBackgroundDrawable(round(CARD, 10, LINE));
        pop.setWidth(Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(24)));
        pop.setModal(true);
        pop.setOnItemClickListener((parent, v, i, id) -> {
            prefs.edit().putString("model", models.get(i)).apply();   // 고른 걸 기억한다 — 다음에 목록에 있으면 그대로
            choose(models.get(i));
            pop.dismiss();
        });
        pop.show();
        pop.setSelection(Math.max(0, models.indexOf(model)));
    }

    // ── 설정 (PC 판처럼 뒤를 흐리게 남기는 막) ─────────────

    String key() {
        return prefs.getString("key", "");
    }

    String root() {
        return prefs.getString("root", "");
    }

    // 무엇이 비었는지 한 곳에서만 말한다 — 문구가 갈라지면 화면마다 다른 말을 한다
    String missing() {
        return key().isEmpty() && root().isEmpty() ? "API 키와 보낼 곳(Base URL)을 넣으세요"
                : key().isEmpty() ? "API 키를 넣으세요"
                : "보낼 곳(Base URL)을 넣으세요";
    }

    FrameLayout buildSettings() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0xB3141312);
        layer.setVisibility(View.GONE);
        layer.setOnClickListener(v -> closeSettings());   // 시트 바깥을 누르면 닫는다

        LinearLayout sheet = vertical();
        sheet.setBackground(round(BG, 14, LINE));
        sheet.setPadding(dp(10), dp(8), dp(10), dp(18));
        sheet.setClickable(true);

        LinearLayout header = row();
        TextView title = label("설정", 16, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(6), 0, 0, 0);
        header.addView(title, weighted(0, 0));
        Button close = button("✕", 0, 0, USER_FG, 8, v -> closeSettings());
        close.setContentDescription("닫기");
        header.addView(close, lp(dp(40), dp(40), 0, 0, 0, 0));
        sheet.addView(header);

        // 폰은 좁아 PC 판 왼쪽 목록을 위쪽 가로 줄로 둔다
        LinearLayout nav = row();
        String[] names = {"API 키", "저장 위치", "정보"};
        for (int i = 0; i < names.length; i++) {
            int n = i;
            Button b = button(names[i], BG, 0, USER_FG, 8, v -> showSection(n));
            b.setTextSize(13);
            nav.addView(b, lp(WRAP, dp(36), 0, 0, 4, 0));
            navs.add(b);
        }
        // 사용 설명서는 칸이 아니라 구글 문서를 연다
        Button guide = button("사용 설명서", BG, 0, USER_FG, 8, v -> open(GUIDE));
        guide.setTextSize(13);
        nav.addView(guide, lp(WRAP, dp(36), 0, 0, 0, 0));
        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        navScroll.addView(nav);
        sheet.addView(navScroll, lp(MATCH, WRAP, 0, 4, 0, 14));

        FrameLayout body = new FrameLayout(this);
        body.setPadding(dp(8), 0, dp(8), 0);
        body.setMinimumHeight(dp(230));   // 칸을 바꿀 때 시트 높이가 들썩이지 않게
        sections.add(sectionKey());
        sections.add(sectionFolder());
        sections.add(sectionAbout());
        for (View s : sections) body.addView(s);
        sheet.addView(body);

        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER);
        at.leftMargin = at.rightMargin = dp(16);
        layer.addView(sheet, at);
        return layer;
    }

    View sectionKey() {
        LinearLayout p = vertical();
        p.addView(strong("API 키"));
        keyBox = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        p.addView(boxed(keyBox), lp(MATCH, WRAP, 0, 8, 0, 0));

        // 붙여넣은 키가 맞는지 볼 방법이 없으면 오타를 찾을 길이 없다
        LinearLayout buttons = row();
        peek = button("키 보기", CARD, LINE, FG, 8, v -> {
            boolean hidden = keyBox.getTransformationMethod() instanceof PasswordTransformationMethod;
            keyBox.setTransformationMethod(hidden ? null : PasswordTransformationMethod.getInstance());
            keyBox.setSelection(keyBox.length());
            peek.setText(hidden ? "키 가리기" : "키 보기");
        });
        peek.setTextSize(13);
        buttons.addView(peek, lp(WRAP, dp(36), 0, 0, 8, 0));
        Button get = button("키 발급받기", CARD, LINE, FG, 8, v -> open("https://build.nvidia.com"));
        get.setTextSize(13);
        buttons.addView(get, lp(WRAP, dp(36), 0, 0, 0, 0));
        p.addView(buttons, lp(WRAP, WRAP, 0, 8, 0, 0));

        TextView second = strong("보낼 곳 (Base URL)");
        p.addView(second, lp(WRAP, WRAP, 0, 20, 0, 0));
        rootBox = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        p.addView(boxed(rootBox), lp(MATCH, WRAP, 0, 8, 0, 0));
        return p;
    }

    View sectionFolder() {
        LinearLayout p = vertical();
        p.addView(strong("저장 위치"));
        TextView path = label(chatsFile.getParent(), 13, USER_FG);
        path.setTextIsSelectable(true);
        p.addView(path, lp(MATCH, WRAP, 0, 8, 0, 0));
        return p;
    }

    View sectionAbout() {
        LinearLayout p = vertical();
        p.addView(strong("Zako Code"));
        p.addView(label("버전 " + BuildConfig.VERSION_NAME, 13, USER_FG), lp(WRAP, WRAP, 0, 6, 0, 0));
        LinearLayout r = row();
        Button check = button("업데이트 확인", CARD, LINE, FG, 8, v -> checkUpdate(true));
        check.setTextSize(13);
        r.addView(check, lp(WRAP, dp(36), 0, 0, 0, 0));
        updateNote = label("", 13, USER_FG);
        r.addView(updateNote, weighted(10, 0));
        p.addView(r, lp(MATCH, WRAP, 0, 14, 0, 0));
        return p;
    }

    void showSection(int i) {
        for (int n = 0; n < sections.size(); n++) {
            sections.get(n).setVisibility(n == i ? View.VISIBLE : View.GONE);
            navs.get(n).setBackground(ripple(n == i ? CARD : BG, 8, 0));
            navs.get(n).setTextColor(n == i ? FG : USER_FG);
        }
    }

    void openSettings() {
        drawer.setVisibility(View.GONE);
        keyBox.setText(key());
        rootBox.setText(root());
        keyBox.setTransformationMethod(PasswordTransformationMethod.getInstance());
        peek.setText("키 보기");
        showSection(0);
        hideKeyboard();
        // 뒤를 가리지 않고 흐리게 남긴다. 12 미만 안드로이드는 흐림 없이 어두운 막만
        if (Build.VERSION.SDK_INT >= 31) main.setRenderEffect(RenderEffect.createBlurEffect(dp(10), dp(10), Shader.TileMode.CLAMP));
        settings.setVisibility(View.VISIBLE);
    }

    void closeSettings() {
        if (settings.getVisibility() != View.VISIBLE) return;
        settings.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 31) main.setRenderEffect(null);
        hideKeyboard();
        String k = keyBox.getText().toString().trim();
        String r = rootBox.getText().toString().trim().replaceAll("/+$", "");   // 안 떼면 //models 가 된다
        if (k.equals(key()) && r.equals(root())) return;
        prefs.edit().putString("key", k).putString("root", r).apply();
        if (!k.isEmpty() && !r.isEmpty()) {
            loadModels();
        } else {
            clearModels();
            status.setText("");
        }
        if (current.optJSONArray("messages").length() == 0) drawChat();   // 안내 문구를 지금 상태에 맞춘다
    }

    // ── 업데이트 ────────────────────────────────────────────

    void checkUpdate(boolean asked) {
        if (asked) updateNote.setText("확인하는 중…");
        new Thread(() -> {
            try {
                String[] found = Release.check(BuildConfig.VERSION_NAME);
                runOnUiThread(() -> {
                    if (found == null) {
                        if (asked) updateNote.setText("최신 버전입니다");
                        return;
                    }
                    update = found[1];
                    updateButton.setText("새 버전 " + found[0] + " — 받기");
                    updateButton.setVisibility(View.VISIBLE);
                    updateNote.setText("새 버전 " + found[0] + " 이 있습니다");
                });
            } catch (Exception e) {
                // 업데이트가 안 된다고 앱을 못 쓸 이유는 없다 — 물어봤을 때만 알린다
                if (asked) runOnUiThread(() -> updateNote.setText("확인 실패: " + message(e)));
            }
        }).start();
    }

    void termuxSetup() {
        TextView t = label("Termux 를 열고 아래 한 줄을 붙여넣어 실행한 뒤 다시 보내세요.\n\n" + TERMUX_SETUP, 14, FG);
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

    static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    static void put(JSONObject o, String name, Object value) {
        try {
            o.put(name, value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // 이름이 null 일 때만 난다
        }
    }

    void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast("열 수 있는 앱이 없습니다");
        }
    }

    boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    void down() {
        scroll.post(() -> scroll.scrollTo(0, list.getHeight()));
    }

    void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(main.getWindowToken(), 0);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** 가로·세로와 바깥 여백(dp). */
    LinearLayout.LayoutParams lp(int w, int h, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    /** 남은 가로를 다 차지한다. */
    LinearLayout.LayoutParams weighted(int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1);
        p.setMargins(dp(left), 0, dp(right), 0);
        return p;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    GradientDrawable round(int color, int radiusDp, int border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (border != 0) g.setStroke(Math.max(1, dp(1)), border);
        return g;
    }

    Drawable ripple(int fill, int radiusDp, int border) {
        return new RippleDrawable(ColorStateList.valueOf(fill == ACCENT ? ACCENT_HI : CARD_DN),
                round(fill, radiusDp, border), round(0xFFFFFFFF, radiusDp, 0));
    }

    TextView label(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        return t;
    }

    TextView strong(String s) {
        TextView t = label(s, 15, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // 힌트 글자는 두지 않는다 (PC 판과 같음)
    EditText field(int type) {
        EditText e = new EditText(this);
        e.setInputType(type);   // 여러 줄 표시가 없으면 한 줄 칸이 된다. setSingleLine 을 부르면 비밀번호가 드러난다
        e.setTypeface(Typeface.DEFAULT);   // 비밀번호 칸은 고정폭 글꼴로 바뀐다 — 되돌린다
        e.setTextColor(FG);
        e.setTextSize(15);
        e.setBackground(null);
        e.setPadding(0, 0, 0, 0);
        e.setHighlightColor(0x66D97757);
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = round(ACCENT, 0, 0);
            cursor.setSize(dp(2), -1);
            e.setTextCursorDrawable(cursor);
        }
        return e;
    }

    // PC 판 RoundPanel — 입력칸을 둥근 카드에 넣는다
    View boxed(EditText e) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(round(CARD, 9, LINE));
        f.setPadding(dp(12), dp(10), dp(12), dp(10));
        f.addView(e);
        return f;
    }

    Button button(String text, int fill, int border, int fg, int radiusDp, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setStateListAnimator(null);
        b.setBackground(ripple(fill, radiusDp, border));
        b.setOnClickListener(click);
        return b;
    }
}
