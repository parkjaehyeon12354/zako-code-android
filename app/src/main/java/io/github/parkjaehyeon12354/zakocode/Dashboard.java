package io.github.parkjaehyeon12354.zakocode;

import static io.github.parkjaehyeon12354.zakocode.MainActivity.CARD;
import static io.github.parkjaehyeon12354.zakocode.MainActivity.CARD_HI;
import static io.github.parkjaehyeon12354.zakocode.MainActivity.FG;
import static io.github.parkjaehyeon12354.zakocode.MainActivity.USER_FG;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 빈 대화 화면 — PC 판 UsagePanel. 글자를 컨트롤로 두지 않고 직접 칠하므로 선택되지 않는다. */
final class Dashboard extends View {
    static final int[] HEAT = {0xFF8FB4F0, 0xFF6A9BED, 0xFF4A7FE6, 0xFF2F63D8};

    List<JSONObject> chats = new ArrayList<>();
    String hint;               // 키나 보낼 곳이 비었을 때 맨 위에 쓰는 한 줄
    private boolean modelsTab;
    private Integer days;      // null 이면 전체

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> areas = new ArrayList<>();
    private final List<Runnable> clicks = new ArrayList<>();

    Dashboard(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // 스크롤 안에 있다 — 내용이 화면보다 길면 그만큼 늘어나 스크롤된다
        int w = MeasureSpec.getSize(widthSpec);
        int need = (int) Math.ceil(draw(null, w, 0)) + 2 * dp(16);
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? Math.max(MeasureSpec.getSize(heightSpec), need) : need;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float content = draw(null, getWidth(), 0);
        draw(c, getWidth(), Math.max(dp(16), (getHeight() - content) / 2));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        for (int i = 0; i < areas.size(); i++) {
            if (!areas.get(i).contains(e.getX(), e.getY())) continue;
            clicks.get(i).run();
            requestLayout();   // 탭에 따라 높이가 바뀐다
            invalidate();
            performClick();
            break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** canvas 가 null 이면 재기만 한다. 끝난 y 를 돌려준다. */
    private float draw(Canvas c, int viewWidth, float y) {
        areas.clear();
        clicks.clear();
        float width = Math.min(viewWidth - dp(32), dp(660)), x = (viewWidth - width) / 2f;

        if (hint != null) {
            text.setTextSize(sp(15));
            text.setTypeface(Typeface.DEFAULT);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(FG);
            StaticLayout l = StaticLayout.Builder.obtain(hint, 0, hint.length(), text, (int) Math.max(1, width))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            if (c != null) {
                c.save();
                c.translate(x, y);
                l.draw(c);
                c.restore();
            }
            y += l.getHeight() + dp(20);
        }

        Usage u = Usage.compute(chats, days, LocalDate.now(), ZoneId.systemDefault());

        float tx = pill(c, "개요", x, y, !modelsTab, () -> modelsTab = false, true);
        pill(c, "모델", tx + dp(4), y, modelsTab, () -> modelsTab = true, true);
        float rx = x + width;
        String[] labels = {"7d", "30일", "전체"};
        Integer[] values = {7, 30, null};
        for (int i = 0; i < labels.length; i++) {
            Integer v = values[i];
            rx = pill(c, labels[i], rx, y, days == null ? v == null : days.equals(v), () -> days = v, false) - dp(4);
        }
        y += dp(38);

        return modelsTab ? models(c, u, x, y, width) : overview(c, u, x, y, width);
    }

    // 탭 한 칸. 누를 자리를 기록해 두고, 다음 칸이 붙을 가장자리를 돌려준다
    private float pill(Canvas c, String s, float edge, float y, boolean on, Runnable click, boolean fromLeft) {
        text.setTextSize(sp(14));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        float w = text.measureText(s) + dp(16), left = fromLeft ? edge : edge - w;
        if (on) box(c, left, y, w, dp(28), 8, CARD_HI);
        label(c, s, left, y, w, dp(28), 14, on ? FG : USER_FG, on, Paint.Align.CENTER);
        areas.add(new RectF(left, y - dp(8), left + w, y + dp(36)));   // 손가락은 28dp 보다 굵다
        clicks.add(click);
        return fromLeft ? left + w : left;
    }

    private float overview(Canvas c, Usage u, float x, float y, float width) {
        String[][] cards = {
                {"세션", Usage.n0(u.sessions)}, {"메시지", Usage.n0(u.messages)},
                {"총 토큰", Usage.compact(u.tokens)}, {"활성 일수", Usage.n0(u.activeDays)},
                {"현재 연속 일수", u.streak + "일"}, {"최장 연속 일수", u.longest + "일"},
                {"최다 사용 시간", Usage.hour(u.peakHour)}, {"즐겨 사용한 모델", Usage.shortModel(u.topModel)},
        };
        // 넓은 화면은 PC 판처럼 4칸, 폰은 2칸
        int cols = width >= dp(560) ? 4 : 2, rows = (cards.length + cols - 1) / cols;
        float gap = dp(8), h = dp(58), cw = (width - gap * (cols - 1)) / cols;
        for (int i = 0; i < cards.length; i++) {
            float l = x + (i % cols) * (cw + gap), t = y + (i / cols) * (h + gap);
            box(c, l, t, cw, h, 8, CARD);
            label(c, cards[i][0], l + dp(12), t + dp(8), cw - dp(20), dp(18), 12, USER_FG, false, Paint.Align.LEFT);
            label(c, cards[i][1], l + dp(12), t + dp(27), cw - dp(20), dp(24), 17, FG, true, Paint.Align.LEFT);
        }
        y += rows * h + (rows - 1) * gap + dp(14);

        // 잔디 — 세로 한 줄이 한 주(위가 일요일), 오른쪽 끝 줄에 오늘. 좁으면 칸이 9dp 밑으로 안 내려가게 주를 줄인다
        float cg = dp(3);
        int weeks = (int) Math.max(1, Math.min(27, (width + cg) / (dp(9) + cg)));
        float cell = (width - (weeks - 1) * cg) / weeks;
        LocalDate today = LocalDate.now();
        LocalDate first = today.minusDays(today.getDayOfWeek().getValue() % 7 + (weeks - 1) * 7L);
        int max = 1;
        for (int n : u.perDay.values()) max = Math.max(max, n);
        for (int w = 0; w < weeks; w++) {
            for (int dow = 0; dow < 7; dow++) {
                LocalDate day = first.plusDays(w * 7L + dow);
                if (day.isAfter(today)) continue;
                Integer n = u.perDay.get(day);
                int color = n == null ? CARD : HEAT[Math.min(3, (n * 4 - 1) / max)];
                box(c, x + w * (cell + cg), y + dow * (cell + cg), cell, cell, 2, color);
            }
        }
        return y + 7 * cell + 6 * cg;
    }

    private float models(Canvas c, Usage u, float x, float y, float width) {
        if (u.models.isEmpty()) {
            label(c, "아직 기록이 없습니다", x, y + dp(20), width, dp(24), 15, USER_FG, false, Paint.Align.CENTER);
            return y + dp(44);
        }
        int top = u.models.get(0).messages;
        for (int i = 0; i < Math.min(8, u.models.size()); i++) {
            Usage.ModelUse m = u.models.get(i);
            box(c, x, y, width, dp(52), 8, CARD);
            String meta = "메시지 " + Usage.n0(m.messages) + " · 토큰 " + Usage.compact(m.tokens);
            text.setTextSize(sp(12));
            text.setTypeface(Typeface.DEFAULT);
            float mw = text.measureText(meta);
            label(c, m.model, x + dp(12), y + dp(6), width - dp(32) - mw, dp(22), 14, FG, false, Paint.Align.LEFT);
            label(c, meta, x + dp(12), y + dp(6), width - dp(24), dp(22), 12, USER_FG, false, Paint.Align.RIGHT);
            float track = width - dp(24);
            box(c, x + dp(12), y + dp(34), track, dp(6), 3, CARD_HI);
            box(c, x + dp(12), y + dp(34), Math.max(dp(6), track * m.messages / top), dp(6), 3, HEAT[3]);
            y += dp(60);
        }
        return y - dp(8);
    }

    private void box(Canvas c, float left, float top, float w, float h, float radiusDp, int color) {
        if (c == null) return;
        fill.setColor(color);
        c.drawRoundRect(left, top, left + w, top + h, dp(radiusDp), dp(radiusDp), fill);
    }

    // 한 줄 글자를 칸 안에 세로 가운데로. 넘치면 … 으로 줄인다
    private void label(Canvas c, String s, float left, float top, float w, float h, float sizeSp, int color, boolean bold, Paint.Align align) {
        if (c == null) return;
        text.setTextSize(sp(sizeSp));
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        text.setTextAlign(align);
        text.setColor(color);
        CharSequence t = TextUtils.ellipsize(s, text, Math.max(0, w), TextUtils.TruncateAt.END);
        float bx = align == Paint.Align.LEFT ? left : align == Paint.Align.CENTER ? left + w / 2 : left + w;
        c.drawText(t, 0, t.length(), bx, top + h / 2 - (text.ascent() + text.descent()) / 2, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
