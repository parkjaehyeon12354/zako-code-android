package io.github.parkjaehyeon12354.zakocode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** PC 판 SelfTest 의 사용량 점검과 같은 값이 나오는가. */
public class UsageTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    static long at(int daysAgo, int hour, int minute) {
        return TODAY.minusDays(daysAgo).atTime(hour, minute).atZone(SEOUL).toInstant().toEpochMilli();
    }

    static JSONObject m(String role, long at, String model, long tokens) throws Exception {
        JSONObject o = Agent.msg(role, "x").put("at", at);
        if (model != null) o.put("model", model).put("tokens", tokens);
        return o;
    }

    static JSONObject chat(JSONObject... messages) throws Exception {
        return new JSONObject().put("id", "c").put("title", "t").put("messages", new JSONArray(Arrays.asList(messages)));
    }

    static List<JSONObject> sample() throws Exception {
        List<JSONObject> chats = new ArrayList<>();
        chats.add(chat(m("user", at(0, 13, 0), null, 0), m("assistant", at(0, 13, 0), "x/big", 1000)));
        chats.add(chat(m("user", at(1, 13, 0), null, 0), m("assistant", at(1, 13, 0), "x/big", 500),
                m("user", at(1, 9, 0), null, 0), m("assistant", at(1, 9, 0), "y/small", 20)));
        chats.add(chat(Agent.msg("user", "old"), Agent.msg("assistant", "old")));   // 시각 없는 옛 대화
        return chats;
    }

    @Test
    public void streaksCountFromYesterdayWhenTodayIsInactive() {
        LocalDate d = TODAY;
        int[] s = Usage.streaks(new HashSet<>(Arrays.asList(d, d.minusDays(1), d.minusDays(2),
                d.minusDays(5), d.minusDays(6), d.minusDays(7), d.minusDays(8))), d);
        assertArrayEquals(new int[]{3, 4}, s);
        assertEquals(2, Usage.streaks(new HashSet<>(Arrays.asList(d.minusDays(1), d.minusDays(2))), d)[0]);
        assertEquals(0, Usage.streaks(new HashSet<>(Collections.singletonList(d.minusDays(3))), d)[0]);
        assertArrayEquals(new int[]{0, 0}, Usage.streaks(new HashSet<>(), d));
    }

    @Test
    public void computeMatchesThePcVersion() throws Exception {
        Usage all = Usage.compute(sample(), null, TODAY, SEOUL);
        assertEquals(3, all.sessions);
        assertEquals(8, all.messages);
        assertEquals(1520, all.tokens);
        assertEquals(2, all.activeDays);
        assertEquals(2, all.streak);
        assertEquals(Integer.valueOf(13), all.peakHour);
        assertEquals("x/big", all.topModel);

        // 모델별 메시지·토큰
        assertEquals(2, all.models.size());
        assertEquals("x/big", all.models.get(0).model);
        assertEquals(2, all.models.get(0).messages);
        assertEquals(1500, all.models.get(0).tokens);
        assertEquals(20, all.models.get(1).tokens);

        // 기간을 좁히면 시각 없는 옛 대화는 빠진다
        Usage week = Usage.compute(sample(), 7, TODAY, SEOUL);
        assertEquals(2, week.sessions);
        assertEquals(6, week.messages);
    }

    @Test
    public void rangeUsesLocalDates() throws Exception {
        // 서울 0시 30분은 UTC 로는 전날이다 — 오늘로 세야 한다
        List<JSONObject> chats = Collections.singletonList(chat(
                m("user", at(0, 0, 30), null, 0),
                m("user", at(6, 12, 0), null, 0),      // 7일 안 (오늘 포함 7일의 첫날)
                m("user", at(7, 23, 59), null, 0)));   // 7일 밖, 30일 안
        Usage today = Usage.compute(chats, 1, TODAY, SEOUL);
        assertEquals(1, today.messages);
        assertTrue(today.perDay.containsKey(TODAY));
        assertEquals(2, Usage.compute(chats, 7, TODAY, SEOUL).messages);
        assertEquals(3, Usage.compute(chats, 30, TODAY, SEOUL).messages);
    }

    @Test
    public void peakHourTieGoesToTheFirstSeen() throws Exception {
        List<JSONObject> chats = Collections.singletonList(chat(m("user", at(0, 21, 0), null, 0), m("user", at(0, 8, 0), null, 0)));
        assertEquals(Integer.valueOf(21), Usage.compute(chats, null, TODAY, SEOUL).peakHour);
        assertNull(Usage.compute(new ArrayList<>(), null, TODAY, SEOUL).peakHour);
        assertEquals("—", Usage.compute(new ArrayList<>(), null, TODAY, SEOUL).topModel);
    }

    @Test
    public void formatsLikeThePcVersion() {
        assertEquals("999", Usage.compact(999));
        assertEquals("12,345", Usage.compact(12_345));
        assertEquals("99,999", Usage.compact(99_999));
        assertEquals("123K", Usage.compact(123_456));
        assertEquals("124K", Usage.compact(123_500));
        assertEquals("1M", Usage.compact(1_000_000));
        assertEquals("1.2M", Usage.compact(1_234_567));
        assertEquals("1.3M", Usage.compact(1_250_000));
        assertEquals("12M", Usage.compact(12_345_678));

        assertEquals("—", Usage.hour(null));
        assertEquals("오전 12시", Usage.hour(0));
        assertEquals("오전 9시", Usage.hour(9));
        assertEquals("오후 12시", Usage.hour(12));
        assertEquals("오후 1시", Usage.hour(13));
        assertEquals("오후 11시", Usage.hour(23));

        assertEquals("llama-3.1-nemotron-70b-instruct", Usage.shortModel("nvidia/llama-3.1-nemotron-70b-instruct"));
        assertEquals("plain", Usage.shortModel("plain"));
        assertEquals("—", Usage.shortModel("—"));
        assertEquals("1,234", Usage.n0(1234));
    }
}
