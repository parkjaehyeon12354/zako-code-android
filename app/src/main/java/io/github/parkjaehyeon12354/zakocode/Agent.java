package io.github.parkjaehyeon12354.zakocode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 대화 + 명령 실행 반복. 안드로이드 API 를 쓰지 않아서 PC 의 JVM 에서 그대로 시험한다(AgentTest).
 * AI 가 ```bash 블록을 내면 허락을 받아 실행하고, 결과를 AI 에게 돌려준다. 명령 없는 답이 올 때까지.
 */
final class Agent {
    static final int MAX_STEPS = 20;      // 한 질문에 명령을 연달아 돌리는 상한 — 같은 명령을 끝없이 되풀이하는 모델이 있다
    static final int OUT_LIMIT = 4000;    // AI 에게 돌려줄 출력 길이. 넘으면 뒤쪽만 (에러는 보통 끝에 있다)
    static final Pattern FENCE = Pattern.compile("```(?:bash|sh|shell)[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);
    static final String RESULTS = "명령 실행 결과:";
    static final String STEPS_NOTE = "명령을 " + MAX_STEPS + "번 연달아 돌려서 멈췄습니다. 이어가려면 \"계속\"이라고 보내세요.";

    // 목록에는 채팅이 안 되는 모델이 섞여 있다. /models 가 용도를 알려주지 않아 이름으로 거른다 (PC 판과 같은 패턴)
    static final Pattern NOT_CHAT = Pattern.compile("(embed|retriev|rerank|reward|guard|content-safety|nvclip|deplot|parse|detector)",
            Pattern.CASE_INSENSITIVE);
    // 알파벳순 첫 항목은 기본값으로 나쁘다. 사고 토큰 문제가 없는 평범한 instruct 를 먼저 본다
    static final String[] PREFER = {
            "nvidia/llama-3.1-nemotron-70b-instruct",
            "mistralai/mistral-large-2-instruct",
            "mistralai/codestral-22b-instruct-v0.1",
    };

    interface Shell {
        Result run(String command) throws IOException, InterruptedException;
    }

    interface Approver {
        boolean approve(String command) throws InterruptedException;
    }

    interface Output {
        void text(String piece);

        void ran(String command, Result result);   // 거절했으면 result 는 null
    }

    static final class Result {
        final Integer exitCode;   // 시간이 지나 못 받았으면 null
        final String output;

        Result(Integer exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    static final class Reply {
        final String text;
        final long tokens;   // 서버가 알려준 usage.total_tokens — 모르면 0

        Reply(String text, long tokens) {
            this.text = text;
            this.tokens = tokens;
        }
    }

    final String root, key, model;
    private volatile HttpURLConnection current;

    Agent(String root, String key, String model) {
        this.root = root;
        this.key = key;
        this.model = model;
    }

    static JSONObject msg(String role, String content) {
        try {
            return new JSONObject().put("role", role).put("content", content);
        } catch (JSONException e) {
            throw new IllegalStateException(e);   // 이름이 null 일 때만 난다
        }
    }

    /** 대화에 남기는 메시지 — 보낸 시각을 붙인다. 서버로 갈 때는 role·content 만 추린다(request). */
    static JSONObject stamped(String role, String content) {
        try {
            return msg(role, content).put("at", System.currentTimeMillis());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // 시스템 프롬프트는 대화에 저장하지 않고 보낼 때만 앞에 붙인다(PC 판과 같음).
    // 모르는 필드(at·model·tokens)를 거부하는 깐깐한 OpenAI 호환 서버가 있어 role·content 만 보낸다.
    static JSONArray request(JSONArray messages) {
        JSONArray wire = new JSONArray().put(msg("system", systemPrompt()));
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null) wire.put(msg(m.optString("role"), m.optString("content")));
        }
        return wire;
    }

    static String systemPrompt() {
        return "너는 숙련된 개발자다. 코드는 반드시 언어를 명시한 코드펜스로 감싸고, 설명은 간결하게 한국어로 한다.\n"
                + "너는 Zako Code 다. 사용자의 안드로이드 폰 Termux 에서 명령을 직접 실행하며 일을 대신한다.\n"
                + "- 명령이 필요하면 ```bash 코드 블록에 넣어라. 사용자가 허락하면 실제로 실행되고, 결과가 다음 메시지로 온다.\n"
                + "- 실행하면 안 되는 예시 코드는 bash 로 표시하지 마라. python, text 처럼 다른 이름을 붙여라.\n"
                + "- 블록마다 새 셸에서 실행된다. cd 는 다음 블록에 이어지지 않으니 `cd 폴더 && 명령` 처럼 한 블록에 써라.\n"
                + "- 한 번에 한 단계씩 실행하고 결과를 보고 다음 명령을 정해라.\n"
                + "- 입력을 기다리는 명령(vim, nano, 확인 질문)은 쓰지 마라. 설치 명령에는 -y 를 붙여라.\n"
                + "- 일이 끝나면 bash 블록 없이 한국어로 짧게 결과를 알려라.\n"
                + "- 시작 폴더: Termux 홈(~)";
    }

    static List<String> commands(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = FENCE.matcher(text);
        while (m.find()) {
            String c = m.group(1).trim();
            if (!c.isEmpty()) found.add(c);
        }
        return found;
    }

    /** ``` 로만 나눈다(PC 판 Fences.Parse). 덩이마다 {언어, 글} — 설명 글이면 언어가 null. */
    static List<String[]> fences(String text) {
        String[] parts = text.split("```", -1);
        List<String[]> blocks = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (i % 2 == 0) {
                String prose = p.replaceAll("\\A\n+|\n+\\z", "");
                if (!prose.trim().isEmpty()) blocks.add(new String[]{null, prose});
                continue;
            }
            int nl = p.indexOf('\n');
            blocks.add(nl < 0 ? new String[]{"", p}
                    : new String[]{p.substring(0, nl).trim().toLowerCase(Locale.ROOT), p.substring(nl + 1)});
        }
        return blocks;
    }

    /** 사용자 말을 붙인다. 마지막이 명령 결과(user)면 거기에 이어 쓴다 — user 가 두 번 연달아 가면 거부하는 모델이 있다. */
    static void say(JSONArray messages, String text) {
        JSONObject last = messages.optJSONObject(messages.length() - 1);
        if (last != null && "user".equals(last.optString("role"))) {
            try {
                last.put("content", last.optString("content") + "\n\n" + text);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        } else {
            messages.put(stamped("user", text));
        }
    }

    /** 고른 게 목록에 아직 있으면 그대로, 아니면 PREFER 순, 그래도 없으면 첫 항목. */
    static String pickDefault(List<String> ids, String saved) {
        if (ids.contains(saved)) return saved;
        for (String p : PREFER) if (ids.contains(p)) return p;
        return ids.isEmpty() ? "" : ids.get(0);
    }

    void cancel() {
        HttpURLConnection c = current;
        if (c != null) c.disconnect();
    }

    List<String> models() throws IOException, JSONException {
        HttpURLConnection c = open("/models", null);
        JSONArray data = new JSONObject(read(c.getInputStream())).optJSONArray("data");
        List<String> names = new ArrayList<>();
        for (int i = 0; data != null && i < data.length(); i++) {
            String id = data.getJSONObject(i).optString("id");
            if (!id.isEmpty() && !NOT_CHAT.matcher(id).find()) names.add(id);
        }
        Collections.sort(names);   // String 비교는 글자 코드순 — PC 판 Ordinal 과 같다
        return names;
    }

    Reply chat(JSONArray messages, Output out) throws IOException, JSONException {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", request(messages))
                .put("max_tokens", 4096)
                .put("temperature", 0.4)
                .put("stream", true)
                .put("stream_options", new JSONObject().put("include_usage", true));
        HttpURLConnection c = open("/chat/completions", body);
        StringBuilder reply = new StringBuilder();
        long tokens = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                JSONObject chunk = new JSONObject(data);
                JSONObject usage = chunk.optJSONObject("usage");
                if (usage != null && usage.has("total_tokens")) tokens = usage.optLong("total_tokens");
                JSONArray choices = chunk.optJSONArray("choices");   // 마지막 사용량 조각은 choices 가 비어 있다
                if (choices == null || choices.length() == 0) continue;
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                if (delta == null || delta.isNull("content")) continue;
                String piece = delta.optString("content");
                reply.append(piece);
                out.text(piece);
            }
        }
        return new Reply(reply.toString(), tokens);
    }

    /**
     * 명령 없는 답이 올 때까지 묻고 실행한다. 마지막 답을 돌려준다.
     * 빈 답은 대화에 남기지 않는다 — 추론 모델이 max_tokens 를 사고에 다 쓰면 온다.
     */
    Reply turn(JSONArray messages, Shell shell, Approver approver, Output out)
            throws IOException, JSONException, InterruptedException {
        Reply reply = null;
        for (int step = 0; step < MAX_STEPS; step++) {
            reply = chat(messages, out);
            if (reply.text.trim().isEmpty()) return reply;
            messages.put(stamped("assistant", reply.text).put("model", model).put("tokens", reply.tokens));
            List<String> cmds = commands(reply.text);
            if (cmds.isEmpty()) return reply;
            StringBuilder results = new StringBuilder(RESULTS);
            for (String cmd : cmds) {
                if (Thread.interrupted()) throw new InterruptedException();
                results.append("\n\n$ ").append(cmd).append('\n');
                if (!approver.approve(cmd)) {
                    out.ran(cmd, null);
                    results.append("(사용자가 실행을 거절했다)");
                    continue;
                }
                Result r = shell.run(cmd);
                out.ran(cmd, r);
                String o = r.output;
                results.append("(종료 코드 ").append(r.exitCode == null ? "없음" : r.exitCode).append(")\n")
                        .append(o.length() > OUT_LIMIT ? o.substring(o.length() - OUT_LIMIT) : o);
            }
            // 명령 결과도 user 로 남긴다 — 다시 열어도 대화가 그대로 이어진다
            messages.put(stamped("user", results.toString()));
        }
        out.text("\n" + STEPS_NOTE);
        return reply;
    }

    private HttpURLConnection open(String path, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(root + path).toURL().openConnection();
        current = c;
        c.setConnectTimeout(30_000);
        c.setReadTimeout(300_000);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept-Encoding", "identity");   // 압축하면 스트리밍 조각이 뭉쳐서 온다
        c.setRequestProperty("User-Agent", "ZakoCode");
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            String err = read(c.getErrorStream());
            throw new IOException("서버 오류 " + code + ": " + err.substring(0, Math.min(500, err.length())));
        }
        return c;
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
