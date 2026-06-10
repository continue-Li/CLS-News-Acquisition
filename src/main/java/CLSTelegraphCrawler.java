import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 财联社电报爬虫 v5 - 实时监听版
 *
 * <pre>
 * 策略：
 *   1. 每轮先拉最新消息（last_time = 当前时间戳）
 *   2. 如果没有新消息，则往更早的时间翻页（向前回溯当天内容）
 *   3. 当天所有消息都拉完后，才提示"暂无新的更新"
 * </pre>
 *
 * API: GET /v1/roll/get_roll_list
 * 签名: MD5(SHA1(sorted_query_string))
 *
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
    private static final int POLL_INTERVAL = 60000;

    // 已输出的消息 ID 集合（避免重复）
    private static final Set<String> seenIds = new HashSet<>();

    // 东八区
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset OFFSET_8 = ZoneOffset.ofHours(8);

    // 当天 0 点的时间戳（秒），用于判断是否跨天
    private static long todayStartEpoch;

    // 已拉取到的最早一条消息的 ctime（用于向前翻页）
    // Long.MAX_VALUE 表示尚未拉取过任何消息
    private static long oldestSeenCtime = Long.MAX_VALUE;

    public static void main(String[] args) {
        todayStartEpoch = LocalDate.now(ZONE_SHANGHAI)
                .atStartOfDay(ZONE_SHANGHAI)
                .toEpochSecond();

        System.out.println("╔" + "═".repeat(78) + "╗");
        System.out.println("║" + center("财联社电报 - 实时监听 v5", 78) + "║");
        System.out.println("║" + center("优先拉最新，无新则回溯更早内容", 78) + "║");
        System.out.println("╚" + "═".repeat(78) + "╝");
        System.out.println();
        System.out.println("API:  " + API_URL);
        System.out.println("日期: " + LocalDate.now(ZONE_SHANGHAI));
        System.out.println("间隔: " + (POLL_INTERVAL / 1000) + " 秒  |  每页 " + PAGE_SIZE + " 条");
        System.out.println("─".repeat(80));
        System.out.println();

        // ========== 第一轮：拉取最新 ==========
        long now = Instant.now().getEpochSecond();
        String json = fetchTelegraph(now);
        int newCount = parseAndPrint(json, "最新");
        if (newCount > 0) {
            System.out.println();
        }

        // ========== 循环轮询 ==========
        while (true) {
            try {
                Thread.sleep(POLL_INTERVAL);

                // 检查是否跨天，跨天则重置
                checkNewDay();

                now = Instant.now().getEpochSecond();
                newCount = 0;

                // --- 第 1 步：拉最新 ---
                json = fetchTelegraph(now);
                newCount = parseAndPrint(json, "最新");

                // --- 第 2 步：没有新的，往更早时间翻 ---
                if (newCount == 0 && oldestSeenCtime < Long.MAX_VALUE) {
                    json = fetchTelegraph(oldestSeenCtime);
                    newCount = parseAndPrint(json, "更早");
                }

                // --- 第 3 步：确实没了 ---
                if (newCount == 0) {
                    String timeStr = LocalDateTime.now(ZONE_SHANGHAI)
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    System.out.println("[" + timeStr + "] 暂无新的更新");
                }

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

    // ======================== API 调用 ========================

    /**
     * 拉取电报数据
     *
     * @param lastTime 时间锚点（秒级时间戳），返回 ctime < lastTime 的消息
     */
    private static String fetchTelegraph(long lastTime) {
        try {
            Map<String, String> params = new TreeMap<>(caseInsensitiveComparator());
            params.put("app", APP);
            params.put("last_time", String.valueOf(lastTime));
            params.put("os", OS);
            params.put("refresh_type", "1");
            params.put("rn", String.valueOf(PAGE_SIZE));
            params.put("sv", SV);

            String queryString = buildQueryString(params);
            String sign = computeSign(queryString);
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
        } catch (Exception e) {
            System.err.println("请求异常: " + e.getMessage());
            return null;
        }
    }

    // ======================== JSON 解析 ========================

    /**
     * 解析 JSON 并打印新消息。
     *
     * @param jsonResponse API 响应
     * @param tag          来源标签（"最新" 或 "更早"）
     * @return 本次打印的新消息数量
     */
    private static int parseAndPrint(String jsonResponse, String tag) {
        if (jsonResponse == null || jsonResponse.isEmpty()) {
            return 0;
        }

        try {
            JSONObject root = JSONUtil.parseObj(jsonResponse);
            int errno = root.getInt("errno", -1);
            if (errno != 0) {
                // 签名错误等不打印（太吵）
                if (!"10012".equals(String.valueOf(root.getStr("errno", "")))) {
                    System.err.println("API errno=" + errno + " msg=" + root.getStr("msg"));
                }
                return 0;
            }

            JSONObject data = root.getJSONObject("data");
            if (data == null) return 0;

            JSONArray rollData = data.getJSONArray("roll_data");
            if (rollData == null || rollData.isEmpty()) return 0;

            LocalDate today = LocalDate.now(ZONE_SHANGHAI);

            // roll_data 按时间倒序（最新在前），收集未见过 + 今天的消息
            List<JSONObject> newItems = new ArrayList<>();
            long batchOldest = Long.MAX_VALUE;

            for (int i = 0; i < rollData.size(); i++) {
                JSONObject item = rollData.getJSONObject(i);
                if (item == null) continue;

                String id = item.getStr("id");
                if (id == null || seenIds.contains(id)) continue;

                Long ctime = item.getLong("ctime");
                if (ctime == null) continue;

                // 只收今天的
                LocalDateTime msgTime = LocalDateTime.ofEpochSecond(ctime, 0, OFFSET_8);
                if (!msgTime.toLocalDate().equals(today)) continue;

                seenIds.add(id);
                newItems.add(item);

                // 跟踪本批次最早的时间
                if (ctime < batchOldest) {
                    batchOldest = ctime;
                }
            }

            if (newItems.isEmpty()) {
                return 0;
            }

            // 更新全局最早时间（用于下次往前翻页）
            if (batchOldest < oldestSeenCtime) {
                oldestSeenCtime = batchOldest;
            }

            // 按时间正序输出（最早的在前面 → 最新的在最后）
            String timeStr = LocalDateTime.now(ZONE_SHANGHAI)
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            System.out.println("── [" + timeStr + "] " + tag + "消息 (+" + newItems.size() + "条) " + "─".repeat(50));

            for (int i = newItems.size() - 1; i >= 0; i--) {
                printItem(newItems.get(i));
            }

            System.out.println("本批 " + newItems.size() + " 条 | 累计 " + seenIds.size() + " 条");
            System.out.println("─".repeat(80));
            System.out.println();

            return newItems.size();

        } catch (Exception e) {
            System.err.println("JSON 解析异常: " + e.getMessage());
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

        LocalDateTime msgTime = LocalDateTime.ofEpochSecond(
                ctime != null ? ctime : 0, 0, OFFSET_8);
        String timeStr = msgTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        // 等级标记
        String levelTag = "";
        if ("A".equals(level) || "B".equals(level)) {
            levelTag = " ★重磅";
        }

        // 标题行
        System.out.println("┌" + "─".repeat(76) + "┐");
        String displayTitle = (title != null && !title.isEmpty()) ? title : "快讯";
        System.out.println("│ " + timeStr + levelTag + "  " + truncate(displayTitle, 64) + " │");
        System.out.println("├" + "─".repeat(76) + "┤");

        // 内容
        if (content != null && !content.isEmpty()) {
            String cleanContent = content.replaceAll("<[^>]+>", "").trim();
            String[] lines = wrapText(cleanContent, 72);
            for (String line : lines) {
                System.out.println("│  " + padRight(line, 72) + " │");
            }
        }

        // 底部
        String footer = "🕐 " + timeStr + "  阅 " + formatNumber(readingNum) + "  评论 " + commentNum;
        System.out.println("├" + "─".repeat(76) + "┤");
        System.out.println("│ " + padRight(footer, 74) + " │");
        System.out.println("└" + "─".repeat(76) + "┘");
        System.out.println();
    }

    // ======================== 签名 ========================

    static String computeSign(String queryString) {
        return DigestUtil.md5Hex(DigestUtil.sha1Hex(queryString));
    }

    private static String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private static Comparator<String> caseInsensitiveComparator() {
        return (a, b) -> a.toUpperCase().compareTo(b.toUpperCase());
    }

    // ======================== 工具方法 ========================

    /**
     * 跨天重置
     */
    private static void checkNewDay() {
        long newDayStart = LocalDate.now(ZONE_SHANGHAI)
                .atStartOfDay(ZONE_SHANGHAI)
                .toEpochSecond();
        if (newDayStart != todayStartEpoch) {
            System.out.println();
            System.out.println("══════════ 新的一天！" + LocalDate.now(ZONE_SHANGHAI) + " ══════════");
            System.out.println();
            todayStartEpoch = newDayStart;
            seenIds.clear();
            oldestSeenCtime = Long.MAX_VALUE;
        }
    }

    private static String[] wrapText(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return new String[]{""};

        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curW = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int cw = (ch >= 0x4E00 && ch <= 0x9FFF)
                    || (ch >= 0x3000 && ch <= 0x303F)
                    || (ch >= 0xFF00 && ch <= 0xFFEF) ? 2 : 1;

            if (curW + cw > maxWidth) {
                lines.add(cur.toString());
                cur = new StringBuilder();
                curW = 0;
            }
            cur.append(ch);
            curW += cw;
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines.toArray(new String[0]);
    }

    private static String formatNumber(int num) {
        if (num >= 10000) return String.format("%.1f万", num / 10000.0);
        return String.valueOf(num);
    }

    private static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "…";
    }

    private static String padRight(String text, int width) {
        int dw = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            dw += (ch >= 0x4E00 && ch <= 0x9FFF) ? 2 : 1;
        }
        if (dw >= width) return text;
        return text + " ".repeat(width - dw);
    }

    private static String center(String text, int width) {
        int dw = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            dw += (ch >= 0x4E00 && ch <= 0x9FFF) ? 2 : 1;
        }
        int pad = (width - dw) / 2;
        return " ".repeat(Math.max(0, pad)) + text
                + " ".repeat(Math.max(0, width - dw - pad));
    }
}
