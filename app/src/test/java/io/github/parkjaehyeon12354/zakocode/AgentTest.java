package io.github.parkjaehyeon12354.zakocode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 가짜 OpenAI 호환 서버(소켓 직접)로 스트리밍·명령 실행·거절 흐름을 확인한다. */
public class AgentTest {
    static ServerSocket server;
    static String root;
    static final List<String[]> seen = Collections.synchronizedList(new ArrayList<>());   // {Authorization, 본문}

    @BeforeClass
    public static void startServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        root = "http://127.0.0.1:" + server.getLocalPort() + "/v1";
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    serve(server.accept());
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @AfterClass
    public static void stopServer() throws IOException {
        server.close();
    }

    @Before
    public void clear() {
        seen.clear();
    }

    static void serve(Socket socket) throws Exception {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            String request = line(in);
            String auth = "";
            int length = 0;
            for (String h; !(h = line(in)).isEmpty(); ) {
                String lower = h.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
                if (lower.startsWith("authorization:")) auth = h.substring(14).trim();
            }
            byte[] body = new byte[length];
            for (int off = 0, n; off < length && (n = in.read(body, off, length - off)) > 0; ) off += n;

            String type, payload;
            if (request.startsWith("GET")) {
                type = "application/json";
                payload = "{\"data\":[{\"id\":\"b/model\"},{\"id\":\"nv/NV-Embed-QA\"},{\"id\":\"a/model\"},{\"id\":\"x/llama-guard\"}]}";
            } else {
                JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
                seen.add(new String[]{auth, json.toString()});
                JSONArray msgs = json.getJSONArray("messages");
                String last = msgs.getJSONObject(msgs.length() - 1).getString("content");
                String text = last.startsWith("명령 실행 결과") ? "끝났습니다."
                        : last.equals("빈 답") ? "     "
                        : "확인해 볼게요.\n```bash\necho zako-ok\n```\n```python\nprint(\"실행하면 안 됨\")\n```";
                type = "text/event-stream";
                payload = event(piece(text.substring(0, 5))) + event(piece(text.substring(5)))
                        + event(new JSONObject().put("choices", new JSONArray()).put("usage", new JSONObject().put("total_tokens", 42)))
                        + "data: [DONE]\n\n";
            }
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        }
    }

    static JSONObject piece(String content) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("delta", new JSONObject().put("content", content))));
    }

    static String event(JSONObject o) {
        return "data: " + o + "\n\n";
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) if (c != '\r') b.write(c);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static final class Screen implements Agent.Output {
        final StringBuilder text = new StringBuilder();
        final List<String> ran = new ArrayList<>();

        @Override
        public void text(String piece) {
            text.append(piece);
        }

        @Override
        public void ran(String command, Agent.Result result) {
            ran.add(command + (result == null ? " 거절" : " " + result.exitCode));
        }
    }

    static JSONArray chat() {
        return new JSONArray().put(Agent.stamped("user", "hi"));
    }

    static String lastContent(String body) throws Exception {
        JSONArray msgs = new JSONObject(body).getJSONArray("messages");
        return msgs.getJSONObject(msgs.length() - 1).getString("content");
    }

    @Test
    public void onlyBashAndShBlocksAreCommands() {
        assertEquals(Arrays.asList("ls", "echo hi"),
                Agent.commands("```bash\nls\n```\n```python\nx\n```\n```sh\necho hi\n```\n```\nplain\n```"));
    }

    @Test
    public void modelsComeSortedWithoutNonChatModels() throws Exception {
        assertEquals(Arrays.asList("a/model", "b/model"), new Agent(root, "k", "").models());
    }

    @Test
    public void defaultModelKeepsThePickThenPrefersThenFirst() {
        List<String> ids = Arrays.asList("01-ai/yi-large", "mistralai/mistral-large-2-instruct", "z/m");
        assertEquals("z/m", Agent.pickDefault(ids, "z/m"));
        assertEquals("mistralai/mistral-large-2-instruct", Agent.pickDefault(ids, "gone/model"));
        assertEquals("a", Agent.pickDefault(Arrays.asList("a", "b"), ""));
        assertEquals("", Agent.pickDefault(Collections.emptyList(), ""));
    }

    @Test
    public void fencesSplitLikeThePcVersion() {
        List<String[]> b = Agent.fences("설명입니다.\n```html\n<b>hi</b>\n```\n뒤 설명.\n```Python\nprint(1)\n```");
        assertEquals(4, b.size());
        assertEquals(null, b.get(0)[0]);
        assertEquals("설명입니다.", b.get(0)[1]);
        assertEquals("html", b.get(1)[0]);
        assertEquals("<b>hi</b>\n", b.get(1)[1]);
        assertEquals("뒤 설명.", b.get(2)[1]);
        assertEquals("python", b.get(3)[0]);
        assertEquals(1, Agent.fences("```\n```").size());   // 빈 코드 블록도 블록이다
    }

    @Test
    public void emptyReplyIsNotStored() throws Exception {
        JSONArray m = new JSONArray().put(Agent.stamped("user", "빈 답"));
        Agent.Reply r = new Agent(root, "k", "a/model").turn(m, cmd -> null, cmd -> true, new Screen());
        assertTrue(r.text.trim().isEmpty());
        assertEquals(1, m.length());
    }

    @Test
    public void approvedCommandRunsAndItsOutputGoesBack() throws Exception {
        List<String> shellRan = new ArrayList<>();
        Screen screen = new Screen();
        JSONArray m = chat();
        new Agent(root, "test-key", "a/model").turn(m, cmd -> {
            shellRan.add(cmd);
            return new Agent.Result(0, "zako-ok\n");
        }, cmd -> true, screen);

        assertEquals(Collections.singletonList("echo zako-ok"), shellRan);
        assertEquals(2, seen.size());
        assertEquals("Bearer test-key", seen.get(0)[0]);
        JSONObject first = new JSONObject(seen.get(0)[1]);
        assertTrue(first.getJSONObject("stream_options").getBoolean("include_usage"));
        assertEquals(4096, first.getInt("max_tokens"));
        String back = lastContent(seen.get(1)[1]);
        assertTrue(back, back.contains("zako-ok") && back.contains("종료 코드 0"));
        assertFalse(back, back.contains("실행하면 안 됨"));

        // 대화에는 시각·모델·토큰이 남고, 명령 결과는 user 로 남는다
        assertEquals(4, m.length());
        JSONObject reply = m.getJSONObject(1);
        assertEquals("a/model", reply.getString("model"));
        assertEquals(42, reply.getLong("tokens"));
        assertTrue(reply.getLong("at") > 0);
        assertEquals("user", m.getJSONObject(2).getString("role"));
        assertTrue(m.getJSONObject(2).getString("content").startsWith(Agent.RESULTS));
        assertEquals("끝났습니다.", m.getJSONObject(3).getString("content"));
        assertFalse(screen.text.toString(), screen.text.toString().contains("토큰"));
        assertEquals(Collections.singletonList("echo zako-ok 0"), screen.ran);
    }

    @Test
    public void requestHasSystemPromptFirstAndOnlyRoleAndContent() throws Exception {
        new Agent(root, "k", "a/model").turn(chat(), cmd -> new Agent.Result(0, ""), cmd -> true, new Screen());
        for (String[] s : seen) {
            JSONArray msgs = new JSONObject(s[1]).getJSONArray("messages");
            assertEquals("system", msgs.getJSONObject(0).getString("role"));
            for (int i = 0; i < msgs.length(); i++) assertEquals(msgs.getJSONObject(i).toString(), 2, msgs.getJSONObject(i).length());
        }
        assertEquals(1, Agent.request(new JSONArray()).length());
    }

    @Test
    public void declinedCommandDoesNotRun() throws Exception {
        List<String> shellRan = new ArrayList<>();
        Screen screen = new Screen();
        new Agent(root, "k", "a/model").turn(chat(), cmd -> {
            shellRan.add(cmd);
            return new Agent.Result(0, "");
        }, cmd -> false, screen);

        assertTrue(shellRan.isEmpty());
        assertEquals(2, seen.size());
        String back = lastContent(seen.get(1)[1]);
        assertTrue(back, back.contains("거절") && !back.contains("종료 코드"));
        assertEquals(Collections.singletonList("echo zako-ok 거절"), screen.ran);
    }

    @Test
    public void textAfterUnansweredResultsJoinsTheLastUserMessage() throws Exception {
        JSONArray m = chat();
        Agent.say(m, "계속");
        assertEquals(2, m.length());
        assertEquals("hi\n\n계속", m.getJSONObject(1).getString("content"));

        m.put(Agent.msg("assistant", "네"));
        Agent.say(m, "다음");
        assertEquals(4, m.length());
    }
}
