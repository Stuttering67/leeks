package utils;

import com.intellij.ide.util.PropertiesComponent;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public static synchronized void save(Map<String, String> amounts) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (amounts != null) {
            for (Map.Entry<String, String> entry : amounts.entrySet()) {
                String code = StringUtils.defaultString(entry.getKey()).trim();
                String value = StringUtils.defaultString(entry.getValue()).trim();
                if (StringUtils.isBlank(code) || StringUtils.isBlank(value)) {
                    continue;
                }
                normalized.put(code, value);
            }
        }
        try {
            Path file = getStorageFilePath();
            Files.createDirectories(file.getParent());
            List<String> lines = new java.util.ArrayList<>();
            for (Map.Entry<String, String> entry : normalized.entrySet()) {
                lines.add(entry.getKey() + "=" + entry.getValue());
            }
            Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            PropertiesComponent.getInstance().setValue(PROPERTY_KEY, String.join(";", lines));
            cache = new LinkedHashMap<>(normalized);
        } catch (IOException ignore) {
            try {
                PropertiesComponent.getInstance().setValue(PROPERTY_KEY, joinAsPropertyString(normalized));
                cache = new LinkedHashMap<>(normalized);
            } catch (Exception ignored) {
                cache = new LinkedHashMap<>(normalized);
            }
        }
    }

    private static Map<String, String> loadFromFileOrProperty() {
        Map<String, String> result = new LinkedHashMap<>();
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
                    result.put(code, value);
                }
                return result;
            } catch (IOException ignore) {
                // fallback to property
            }
        }
        String raw = PropertiesComponent.getInstance().getValue(PROPERTY_KEY, "");
        return parsePropertyString(raw);
    }

    private static Map<String, String> parsePropertyString(String raw) {
        if (StringUtils.isBlank(raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : raw.split(";")) {
            if (StringUtils.isBlank(item) || !item.contains("=")) continue;
            int idx = item.indexOf('=');
            String code = StringUtils.defaultString(item.substring(0, idx)).trim();
            String value = StringUtils.defaultString(item.substring(idx + 1)).trim();
            if (StringUtils.isBlank(code) || StringUtils.isBlank(value)) continue;
            result.put(code, value);
        }
        return result;
    }

    private static String joinAsPropertyString(Map<String, String> values) {
        if (values == null || values.isEmpty()) return "";
        List<String> items = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            items.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join(";", items);
    }

    private static Path getStorageFilePath() {
        return Paths.get(System.getProperty("user.home"), STORAGE_DIR_NAME, STORAGE_FILE_NAME);
    }
}
