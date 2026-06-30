package utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PropertiesComponent;
import leeks.ConfigManager;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;

/**
 * 港币汇率工具：每日自动从公开API获取HKD→CNY汇率并缓存，查询失败时回退到用户设置
 */
public class ExchangeRateUtil {
    private static final String HKD_CNY_API = "https://api.exchangerate-api.com/v4/latest/HKD";
    private static final String CACHE_DATE_KEY = "key_hk_rate_date";
    private static final String CACHE_RATE_KEY = "key_hk_rate_cached";

    /** 内存缓存，避免每次调用都读取 PropertiesComponent */
    private static volatile double cachedRate = Double.NaN;
    private static volatile String cachedDate = null;

    private ExchangeRateUtil() {}

    /** 判断股票代码是否为港股 */
    public static boolean isHkStock(String code) {
        return code != null && code.toLowerCase().startsWith("hk");
    }

    /** 获取当前 HKD→CNY 汇率：优先当日缓存，其次API查询，最后回退到用户设置 */
    public static double getHkRate() {
        String today = LocalDate.now().toString();
        // 内存缓存命中（当日已获取过）
        if (today.equals(cachedDate) && !Double.isNaN(cachedRate)) {
            return cachedRate;
        }
        // 尝试从 PropertiesComponent 持久化缓存读取
        PropertiesComponent pc = PropertiesComponent.getInstance();
        String savedDate = pc.getValue(CACHE_DATE_KEY);
        String savedRateStr = pc.getValue(CACHE_RATE_KEY);
        if (today.equals(savedDate) && StringUtils.isNotBlank(savedRateStr)) {
            try {
                double v = Double.parseDouble(savedRateStr);
                cachedRate = v;
                cachedDate = today;
                return v;
            } catch (NumberFormatException ignored) {}
        }
        // 异步尝试从公开API获取实时汇率（不在调用线程阻塞，由首次调用触发一次）
        synchronized (ExchangeRateUtil.class) {
            if (today.equals(cachedDate) && !Double.isNaN(cachedRate)) {
                return cachedRate;
            }
            Double liveRate = fetchLiveRate();
            if (liveRate != null && liveRate > 0) {
                pc.setValue(CACHE_DATE_KEY, today);
                pc.setValue(CACHE_RATE_KEY, String.valueOf(liveRate));
                cachedRate = liveRate;
                cachedDate = today;
                return liveRate;
            }
            // 回退到用户在设置中配置的汇率
            double fallback = Double.parseDouble(ConfigManager.getInstance().getHkExchangeRate());
            cachedRate = fallback;
            cachedDate = today;
            return fallback;
        }
    }

    private static Double fetchLiveRate() {
        try {
            String response = HttpClientPool.getHttpClient().get(HKD_CNY_API);
            if (StringUtils.isBlank(response)) return null;
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            if (!json.has("rates")) return null;
            JsonObject rates = json.getAsJsonObject("rates");
            if (!rates.has("CNY")) return null;
            return rates.get("CNY").getAsDouble();
        } catch (Exception e) {
            LogUtil.info("获取港币汇率失败: " + e.getMessage());
            return null;
        }
    }
}
