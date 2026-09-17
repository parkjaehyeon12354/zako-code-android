package io.github.parkjaehyeon12354.zakocode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 사용량 — 대화 파일(chats.json)에서 그때그때 센다. 따로 기록하지 않는다. PC 판 UsageStats 와 같은 규칙.
 * 안드로이드 API 를 쓰지 않아 JVM 에서 시험한다(UsageTest).
 */
final class Usage {
    static final class ModelUse {
        final String model;
        int messages;
        long tokens;

        ModelUse(String model) {
            this.model = model;
        }
    }

    int sessions, messages, activeDays, streak, longest;
    long tokens;
    Integer peakHour;          // 시각이 찍힌 메시지가 없으면 null
    String topModel = "—";
    final Map<LocalDate, Integer> perDay = new HashMap<>();
    final List<ModelUse> models = new ArrayList<>();

    /** days 가 null 이면 전체. 날짜는 폰의 시간대(zone)로 자른다. */
    static Usage compute(List<JSONObject> chats, Integer days, LocalDate today, ZoneId zone) {
        Usage u = new Usage();
        LocalDate from = days == null ? null : today.plusDays(1 - days);
        Map<Integer, Integer> hours = new LinkedHashMap<>();       // 처음 나온 순서 — 동점이면 먼저 나온 시간
        Map<String, ModelUse> byModel = new LinkedHashMap<>();
        for (JSONObject chat : chats) {
            JSONArray list = chat.optJSONArray("messages");
            boolean inside = false;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m == null) continue;
                long at = m.optLong("at");
                // 이 기능 전 메시지는 시각이 0 — '전체' 에만 들고 날짜 칸에는 안 잡힌다
                ZonedDateTime local = at > 0 ? Instant.ofEpochMilli(at).atZone(zone) : null;
                if (from != null && (local == null || local.toLocalDate().isBefore(from))) continue;
                inside = true;
                u.messages++;
                u.tokens += m.optLong("tokens");
                if (local != null) {
                    u.perDay.merge(local.toLocalDate(), 1, Integer::sum);
                    hours.merge(local.getHour(), 1, Integer::sum);
                }
                String model = m.optString("model", "");
                if ("assistant".equals(m.optString("role")) && !model.isEmpty()) {
                    ModelUse use = byModel.computeIfAbsent(model, ModelUse::new);
                    use.messages++;
                    use.tokens += m.optLong("tokens");
                }
            }
            if (inside) u.sessions++;
        }

        int[] s = streaks(u.perDay.keySet(), today);
        u.streak = s[0];
        u.longest = s[1];
        u.activeDays = u.perDay.size();
        int best = 0;
        for (Map.Entry<Integer, Integer> h : hours.entrySet()) {
            if (h.getValue() > best) {
                best = h.getValue();
                u.peakHour = h.getKey();
            }
        }
        u.models.addAll(byModel.values());
        u.models.sort((a, b) -> b.messages - a.messages);   // 안정 정렬 — 동점이면 먼저 나온 모델
        if (!u.models.isEmpty()) u.topModel = u.models.get(0).model;
        return u;
    }

    /** {현재, 최장}. 오늘 아직 안 썼으면 어제까지 이어진 걸 현재 연속으로 본다 — 아침에 켜자마자 0 이 되면 이상하다. */
    static int[] streaks(Set<LocalDate> active, LocalDate today) {
        int longest = 0;
        for (LocalDate day : active) {
            if (active.contains(day.minusDays(1))) continue;   // 이어진 줄의 첫날에서만 센다
            int n = 1;
            while (active.contains(day.plusDays(n))) n++;
            longest = Math.max(longest, n);
        }
        LocalDate start = active.contains(today) ? today : today.minusDays(1);
        int current = 0;
        while (active.contains(start.minusDays(current))) current++;
        return new int[]{current, longest};
    }

    static String n0(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    static String compact(long v) {
        if (v >= 1_000_000) return decimal(v >= 10_000_000 ? "0" : "0.#").format(v / 1_000_000d) + "M";
        if (v >= 100_000) return decimal("0").format(v / 1_000d) + "K";
        return n0(v);
    }

    // .NET 서식처럼 반은 올린다 (자바 기본은 짝수 쪽으로 내린다)
    private static DecimalFormat decimal(String pattern) {
        DecimalFormat f = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f;
    }

    static String hour(Integer h) {
        if (h == null) return "—";
        return h < 12 ? "오전 " + (h == 0 ? 12 : h) + "시" : "오후 " + (h == 12 ? 12 : h - 12) + "시";
    }

    static String shortModel(String model) {
        return model.substring(model.lastIndexOf('/') + 1);
    }
}
