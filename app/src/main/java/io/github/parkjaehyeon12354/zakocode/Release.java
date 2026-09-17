package io.github.parkjaehyeon12354.zakocode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 새 버전 확인 — 깃허브 최신 릴리스. 안드로이드는 앱이 저 혼자 설치할 수 없어 받을 주소만 알려준다.
 * 레포가 공개라 인증 없이 읽는다. 안드로이드 API 를 안 써서 JVM 에서 시험한다(ReleaseTest).
 */
final class Release {
    static final String LATEST = "https://api.github.com/repos/parkjaehyeon12354/zako-code-android/releases/latest";

    /** 새 것이 없으면 null, 있으면 {버전, 받을 주소}. */
    static String[] check(String current) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) URI.create(LATEST).toURL().openConnection();
        try {
            c.setConnectTimeout(30_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", "ZakoCode");   // 없으면 깃허브가 403 을 준다
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            return pick(new JSONObject(Agent.read(c.getInputStream())), current);
        } finally {
            c.disconnect();
        }
    }

    static String[] pick(JSONObject release, String current) {
        String tag = release.optString("tag_name");
        if (!newer(tag, current)) return null;
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null || !"ZakoCode.apk".equals(a.optString("name"))) continue;
            String url = a.optString("browser_download_url");
            if (trusted(url)) return new String[]{clean(tag), url};
        }
        // APK 가 없거나 믿을 수 없는 주소면 릴리스 페이지라도 — 그것도 깃허브 https 일 때만
        String page = release.optString("html_url");
        return "github.com".equals(httpsHost(page)) ? new String[]{clean(tag), page} : null;
    }

    /** 받는 주소는 깃허브가 준 https 만 믿는다. */
    static boolean trusted(String url) {
        String host = httpsHost(url);
        return host != null && (host.equals("github.com") || host.endsWith(".githubusercontent.com"));
    }

    private static String httpsHost(String url) {
        try {
            URI u = new URI(url);
            return "https".equals(u.getScheme()) ? u.getHost() : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    static String clean(String tag) {
        return tag.replaceFirst("^[vV]+", "");
    }

    /** 숫자 칸마다 비교한다 — 글자로 비교하면 1.0.10 이 1.0.9 보다 옛것이 된다. 숫자가 아니면 새 것으로 보지 않는다. */
    static boolean newer(String tag, String current) {
        int[] a = parts(clean(tag)), b = parts(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String version) {
        String[] s = version.split("\\.", -1);
        int[] n = new int[s.length];
        try {
            for (int i = 0; i < s.length; i++) n[i] = Integer.parseInt(s[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return n;
    }
}
