package io.github.parkjaehyeon12354.zakocode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ReleaseTest {
    @Test
    public void onlyAHigherVersionIsNewer() {
        assertTrue(Release.newer("v1.0.3", "1.0.1"));
        assertTrue(Release.newer("v1.0.10", "1.0.9"));   // 글자 비교면 틀린다
        assertTrue(Release.newer("1.1", "1.0.3"));
        assertFalse(Release.newer("v1.0.1", "1.0.1"));
        assertFalse(Release.newer("v1.0.0", "1.0.1"));
        assertFalse(Release.newer("최신", "1.0.1"));
        assertFalse(Release.newer("", "1.0.1"));
    }

    @Test
    public void onlyGithubHttpsIsTrusted() {
        assertTrue(Release.trusted("https://github.com/a/b/releases/download/v1/ZakoCode.apk"));
        assertTrue(Release.trusted("https://objects.githubusercontent.com/x"));
        assertFalse(Release.trusted("http://github.com/a/b/x.apk"));
        assertFalse(Release.trusted("https://github.com.evil.test/x.apk"));
        assertFalse(Release.trusted("https://evilgithubusercontent.com/x.apk"));
        assertFalse(Release.trusted("아무 글"));
        assertFalse(Release.trusted(""));
    }

    static JSONObject release(String tag, String assetUrl, String page) throws Exception {
        return new JSONObject().put("tag_name", tag).put("html_url", page).put("assets", new JSONArray()
                .put(new JSONObject().put("name", "other.apk").put("browser_download_url", "https://github.com/o"))
                .put(new JSONObject().put("name", "ZakoCode.apk").put("browser_download_url", assetUrl)));
    }

    @Test
    public void pickPrefersTheApkThenTheReleasePage() throws Exception {
        String apk = "https://github.com/parkjaehyeon12354/zako-code-android/releases/download/v9.9.9/ZakoCode.apk";
        String page = "https://github.com/parkjaehyeon12354/zako-code-android/releases/tag/v9.9.9";
        assertArrayEquals(new String[]{"9.9.9", apk}, Release.pick(release("v9.9.9", apk, page), "1.0.3"));
        assertArrayEquals(new String[]{"9.9.9", page}, Release.pick(release("v9.9.9", "http://github.com/x.apk", page), "1.0.3"));
        assertNull(Release.pick(release("v9.9.9", "http://github.com/x.apk", "https://evil.test/r"), "1.0.3"));
        assertNull(Release.pick(release("v1.0.3", apk, page), "1.0.3"));
    }
}
