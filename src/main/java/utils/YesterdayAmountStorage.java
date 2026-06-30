package utils;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 历史成交额缓存，用于跨交易日提供"昨日成交额"数据。
 * <p>
 * 存储格式：每行 "code=amount|yyyy-MM-dd"，日期为记录（收盘）当天的日期。
 * 读取时取 newest entry whose date is strictly before today；保持昨日数据不被
 * 同日收盘覆盖。
 * </p>
 */
public class YesterdayAmountStorage {
    private static final String STORAGE_DIR_NAME = "leeks";
    private static final String STORAGE_FILE_NAME = "yesterday_amounts.txt";
    private static final String PROPERTY_KEY = "key_yesterday_amounts";

    private static volatile Map<String, String> cache;

    private YesterdayAmountStorage() {}

    public static synchronized void reload() {
        cache = loadFromFileOrProperty();
    }

    public static synchronized Map<String, String> getAll() {
        if (cache == null) {
            cache = loadFromFileOrProperty();
        }
        return cache;
    }

    public static String getForCode(String code) {
        if (StringUtils.isBlank(code)) {
            return "";
        }
        return getAll().getOrDefault(code.trim(), "");
    }

    /**
     * 保存当日成交额。同一 code 允许多条记录（不同日期），读取时取最新但&lt;今日的记录。
     *
     * @param amounts 当日 code -> amount 映射，日期自动记为本日
     */
    public static synchronized void save(Map<String, String> amounts) {
        LocalDate today = LocalDate.now();
        String todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE);

        // 1. 加载磁盘上已有原始数据（不经过日期过滤），剔除同一天已记录的条目（避免重复行）
        Map<String, String> existing = loadRaw();

        // 2. 合并新数据：同一 code 同一天的用新数据覆盖
        //    使用 LinkedHashMap 保持插入顺序
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : existing.entrySet()) {
            String code = entry.getKey();
            String value = entry.getValue();
            int pipeIdx = value.lastIndexOf('|');
            if (pipeIdx < 0) {
                // 旧格式（无日期）：保留值但标记日期未知
                merged.put(code, value);
                continue;
            }
            String existingDate = value.substring(pipeIdx + 1);
            if (!todayStr.equals(existingDate)) {
                merged.put(code, value);   // 非今天的旧记录保留
            }
            // 今天的旧记录丢弃，等待用下面的新数据写入
        }

        // 3. 写入当日新值（append 模式：存入 code=amount|today）
        if (amounts != null) {
            for (Map.Entry<String, String> entry : amounts.entrySet()) {
                String code = StringUtils.defaultString(entry.getKey()).trim();
                String value = StringUtils.defaultString(entry.getValue()).trim();
                if (StringUtils.isBlank(code) || StringUtils.isBlank(value)) {
                    continue;
                }
                merged.put(code, value + "|" + todayStr);
            }
        }

        // 4. 持久化
        try {
            Path file = getStorageFilePath();
            Files.createDirectories(file.getParent());
            List<String> lines = merged.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.toList());
            Files.write(file, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            com.intellij.ide.util.PropertiesComponent pc =
                    com.intellij.ide.util.PropertiesComponent.getInstance();
            pc.setValue(PROPERTY_KEY, String.join(";", lines));

            // 5. 更新内存缓存为 best-for-today 视图
            cache = computeBestForToday(merged);
        } catch (IOException ignore) {
            try {
                com.intellij.ide.util.PropertiesComponent pc =
                        com.intellij.ide.util.PropertiesComponent.getInstance();
                pc.setValue(PROPERTY_KEY, String.join(";",
                        merged.entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .collect(Collectors.toList())));
                cache = computeBestForToday(merged);
            } catch (Exception ignored) {
                cache = computeBestForToday(merged);
            }
        }
    }

    /**
     * 加载磁盘/属性中的原始数据（不过滤日期，供 save 合并使用）。
     */
    private static Map<String, String> loadRaw() {
        Map<String, String> raw = new LinkedHashMap<>();
        Path file = getStorageFilePath();
        if (Files.exists(file)) {
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (StringUtils.isBlank(line) || !line.contains("=")) continue;
                    int idx = line.indexOf('=');
                    String code = StringUtils.defaultString(line.substring(0, idx)).trim();
                    String value = StringUtils.defaultString(line.substring(idx + 1)).trim();
                    if (StringUtils.isBlank(code) || StringUtils.isBlank(value)) continue;
                    raw.put(code, value);
                }
                return raw;
            } catch (IOException ignore) {
                // fallback to property
            }
        }
        String rawText = com.intellij.ide.util.PropertiesComponent.getInstance()
                .getValue(PROPERTY_KEY, "");
        if (StringUtils.isNotBlank(rawText)) {
            for (String item : rawText.split(";")) {
                if (StringUtils.isBlank(item) || !item.contains("=")) continue;
                int idx = item.indexOf('=');
                String code = StringUtils.defaultString(item.substring(0, idx)).trim();
                String value = StringUtils.defaultString(item.substring(idx + 1)).trim();
                if (StringUtils.isBlank(code) || StringUtils.isBlank(value)) continue;
                raw.put(code, value);
            }
        }
        return raw;
    }

    /**
     * 加载磁盘/属性中的 raw 数据并计算"昨日"视图。
     */
    private static Map<String, String> loadFromFileOrProperty() {
        return computeBestForToday(loadRaw());
    }

    /**
     * 从原始数据中为每个 code 选择最新的日期 &lt; today 的记录。
     * 返回的 value 仅包含 amount，不包含日期后缀。
     */
    static Map<String, String> computeBestForToday(Map<String, String> raw) {
        LocalDate today = LocalDate.now();
        // 暂存 amount|date 格式以便比较
        Map<String, Entry> best = new LinkedHashMap<>();
        Map<String, String> bestWithoutDate = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String code = entry.getKey();
            String value = entry.getValue();
            int pipeIdx = value.lastIndexOf('|');
            if (pipeIdx >= 0) {
                String dateStr = value.substring(pipeIdx + 1);
                try {
                    LocalDate d = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
                    if (!d.isBefore(today)) {
                        continue;   // today or future: skip
                    }
                    String amount = value.substring(0, pipeIdx);
                    Entry exist = best.get(code);
                    if (exist != null) {
                        if (!d.isAfter(exist.date)) continue; // keep existing newer
                    }
                    best.put(code, new Entry(amount, d));
                    continue;
                } catch (Exception ignore) {
                    // fall through to legacy handling
                }
            }
            // legacy: no date suffix — keep first occurrence
            bestWithoutDate.putIfAbsent(code, value);
        }

        // Build result: amount only, no date suffix
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : best.entrySet()) {
            result.put(e.getKey(), e.getValue().amount);
        }
        // 仅在没有日期版本时才回退到旧版
        for (Map.Entry<String, String> entry : bestWithoutDate.entrySet()) {
            result.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** 内部辅助类，用于按日期比较记录 */
    private static class Entry {
        final String amount;
        final LocalDate date;
        Entry(String amount, LocalDate date) { this.amount = amount; this.date = date; }
    }

    private static Path getStorageFilePath() {
        return Paths.get(System.getProperty("user.home"), STORAGE_DIR_NAME, STORAGE_FILE_NAME);
    }
}
