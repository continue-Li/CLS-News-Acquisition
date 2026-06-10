import cn.hutool.core.codec.Base64;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 财联社电报爬虫 v5 - 实时监听版
 * 实时监听财联社电报更新，控制台持续输出当天最新消息
 * <p>
 * API: POST /v1/roll/get_roll_list
 * 签名算法: MD5(SHA1(sorted_query_string))
 * <p>
 * 按 Ctrl+C 停止监听
 */
public class CLSTelegraphCrawler {

    // 财联社电报 API
    private static final String API_URL = "https://www.cls.cn/v1/roll/get_roll_list";

    // 固定参数
    private static final String APP = "CailianpressWeb";
    private static final String OS = "web";
    private static final String SV = "8.7.9";

    // 每页条数
    private static final int PAGE_SIZE = 20;

    // 轮询间隔（毫秒）
    private static final int POLL_INTERVAL = 5000;

    // 已输出的消息 ID 集合（避免重复）
    private static final Set<String> seenIds = new HashSet<>();

    // 东八区时区
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    public static void main(String[] args) {
        System.out.println("╔" + "═".repeat(78) + "╗");
        System.out.println("║" + center("财联社电报 - 实时监听 v5", 78) + "║");
        System.out.println("║" + center("只输出当天最新消息，按 Ctrl+C 停止", 78) + "║");
        System.out.println("╚" + "═".repeat(78) + "╝");
        System.out.println();
        System.out.println("API: " + API_URL);
        System.out.println("日期: " + LocalDate.now(ZONE_SHANGHAI));
        System.out.println("刷新: " + (POLL_INTERVAL / 1000) + " 秒");
        System.out.println("─".repeat(80));
        System.out.println();

        // 首次拉取用当前时间戳
        long lastTime = Instant.now().getEpochSecond();

        while (true) {
            try {
                String jsonResponse = fetchTelegraph(lastTime);

                if (jsonResponse != null && !jsonResponse.isEmpty()) {
                    int newCount = parseAndPrint(jsonResponse);

                    // 更新拉取时间：如果有新消息，用最新的 ctime；否则保持当前时间
                    if (newCount > 0) {
                        // 更新 lastTime 为当前时间，确保下次拉到最新
                        lastTime = Instant.now().getEpochSecond();
                    }
                }

                Thread.sleep(POLL_INTERVAL);

            } catch (InterruptedException e) {
                System.out.println("\n监听已停止");
                break;
            } catch (Exception e) {
                System.err.println("错误: " + e.getMessage());
                e.printStackTrace();
                try {
                    Thread.sleep(POLL_INTERVAL);
                } catch (InterruptedException ex) {
                    break;
                }
            }
        }
    }

    /**
     * 拉取电报数据
     *
     * @param lastTime 上次拉取时间（秒级时间戳），用于增量拉取
     */
    private static String fetchTelegraph(long lastTime) throws Exception {
        // 构建参数（按 key 字母序排序，忽略大小写）
        Map<String, String> params = new TreeMap<>(caseInsensitiveComparator());
        params.put("app", APP);
        params.put("last_time", String.valueOf(lastTime));
        params.put("os", OS);
        params.put("refresh_type", "1");
        params.put("rn", String.valueOf(PAGE_SIZE));
        params.put("sv", SV);

        // 生成签名
        String queryString = buildQueryString(params);
        String sign = computeSign(queryString);

        // 构建完整 URL
        String fullUrl = API_URL + "?" + queryString + "&sign=" + sign;

        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Referer", "https://www.cls.cn/telegraph");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int status = conn.getResponseCode();
        if (status == 200) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } else {
            System.err.println("HTTP " + status + " " + conn.getResponseMessage());
            return null;
        }
    }

    /**
     * 解析 JSON 并打印新消息
     *
     * @return 新消息数量
     */
    private static int parseAndPrint(String jsonResponse) {
        try {
            JSONObject root = JSONUtil.parseObj(jsonResponse);

            int errno = root.getInt("errno", -1);
            if (errno != 0) {
                System.err.println("API 返回错误: errno=" + errno + " msg=" + root.getStr("msg"));
                return 0;
            }

            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                return 0;
            }

            JSONArray rollData = data.getJSONArray("roll_data");
            if (rollData == null || rollData.isEmpty()) {
                return 0;
            }

            LocalDate today = LocalDate.now(ZONE_SHANGHAI);
            int newCount = 0;

            // roll_data 按时间倒序排列（最新的在前），遍历后逆序输出
            List<JSONObject> newItems = new ArrayList<>();

            for (int i = 0; i < rollData.size(); i++) {
                JSONObject item = rollData.getJSONObject(i);
                if (item == null) continue;

                String id = item.getStr("id");
                if (id == null || seenIds.contains(id)) {
                    continue;
                }

                Long ctime = item.getLong("ctime");
                if (ctime == null) continue;

                // 检查是否是今天的消息
                LocalDateTime msgTime = LocalDateTime.ofEpochSecond(ctime, 0, ZoneOffset.ofHours(8));
                if (!msgTime.toLocalDate().equals(today)) {
                    continue;
                }

                seenIds.add(id);
                newItems.add(item);
            }

            // 按时间正序输出（最早的在前面）
            for (int i = newItems.size() - 1; i >= 0; i--) {
                JSONObject item = newItems.get(i);
                printItem(item);
                newCount++;
            }

            if (newCount > 0) {
                System.out.println(newCount + " 条新消息 | 累计 " + seenIds.size() + " 条");
                System.out.println("─".repeat(80));
                System.out.println();
            }

            return newCount;
        } catch (Exception e) {
            System.err.println("JSON 解析错误: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 打印单条电报
     */
    private static void printItem(JSONObject item) {
        Long ctime = item.getLong("ctime");
        String title = item.getStr("title", "");
        String content = item.getStr("content", "");
        String level = item.getStr("level", "C");
        int readingNum = item.getInt("reading_num", 0);
        int commentNum = item.getInt("comment_num", 0);

        LocalDateTime msgTime = LocalDateTime.ofEpochSecond(ctime != null ? ctime : 0, 0, ZoneOffset.ofHours(8));
        String timeStr = msgTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        // 等级标记
        String levelTag = "";
        if ("A".equals(level) || "B".equals(level)) {
            levelTag = " [重磅]";
        }

        // 打印标题行
        System.out.println("┌" + "─".repeat(76) + "┐");
        String displayTitle = (title != null && !title.isEmpty()) ? title : "快讯";
        System.out.println("│ " + timeStr + levelTag + " " + truncate(displayTitle, 68) + " │");
        System.out.println("├" + "─".repeat(76) + "┤");

        // 打印内容
        if (content != null && !content.isEmpty()) {
            // 清理内容中的 HTML 标签
            String cleanContent = content.replaceAll("<[^>]+>", "").trim();
            String[] lines = wrapText(cleanContent, 72);
            for (String line : lines) {
                System.out.println("│  " + padRight(line, 72) + " │");
            }
        }

        // 底部信息
        String footer = "阅 " + formatNumber(readingNum) + " | 评论 " + commentNum;
        System.out.println("├" + "─".repeat(76) + "┤");
        System.out.println("│ " + padRight(footer, 74) + " │");
        System.out.println("└" + "─".repeat(76) + "┘");
        System.out.println();
    }

    // ======================== 签名算法 ========================

    /**
     * 计算签名: MD5(SHA1(sorted_query_string))
     */
    static String computeSign(String queryString) {
        // Step 1: SHA1 of query string
        String sha1Hex = DigestUtil.sha1Hex(queryString);
        // Step 2: MD5 of SHA1 hex string
        return DigestUtil.md5Hex(sha1Hex);
    }

    /**
     * 构建 query string: key1=value1&key2=value2...
     * 注意：这里不进行 URL 编码，因为 JS 端也没有编码（直接用原始值拼接）
     */
    private static String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    /**
     * 大小写不敏感的排序比较器
     * 与 JS 端行为一致：转为大写后比较
     */
    private static Comparator<String> caseInsensitiveComparator() {
        return (a, b) -> {
            String au = a.toUpperCase();
            String bu = b.toUpperCase();
            return au.compareTo(bu);
        };
    }

    // ======================== 工具方法 ========================

    /**
     * 文本换行
     */
    private static String[] wrapText(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return new String[]{""};
        }
        // 按 Unicode 码点分割，正确处理中文
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        int currentWidth = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // 中文字符、全角符号等占 2 个宽度
            int charWidth = (ch >= '一' && ch <= '鿿')
                    || (ch >= '　' && ch <= '〿')
                    || (ch >= '＀' && ch <= '￯') ? 2 : 1;

            if (currentWidth + charWidth > maxWidth) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder();
                currentWidth = 0;
            }

            currentLine.append(ch);
            currentWidth += charWidth;
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines.toArray(new String[0]);
    }

    /**
     * 数字格式化（超过 10000 显示为 x.x万）
     */
    private static String formatNumber(int num) {
        if (num >= 10000) {
            return String.format("%.1f万", num / 10000.0);
        }
        return String.valueOf(num);
    }

    /**
     * 截断文本
     */
    private static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen - 1) + "…";
    }

    private static String padRight(String text, int width) {
        // 计算显示宽度
        int displayWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            displayWidth += (ch >= '一' && ch <= '鿿') ? 2 : 1;
        }
        if (displayWidth >= width) {
            return text;
        }
        int padCount = width - displayWidth;
        return text + " ".repeat(padCount);
    }

    private static String center(String text, int width) {
        int displayWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            displayWidth += (ch >= '一' && ch <= '鿿') ? 2 : 1;
        }
        int padding = (width - displayWidth) / 2;
        String left = padding > 0 ? " ".repeat(padding) : "";
        String right = (width - displayWidth - padding) > 0
                ? " ".repeat(width - displayWidth - padding) : "";
        return left + text + right;
    }
}
