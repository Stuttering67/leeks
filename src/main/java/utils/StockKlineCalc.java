package utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PropertiesComponent;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 日K线数据获取及技术指标计算
 * 数据源：腾讯 https://web.ifzq.gtimg.cn/appstock/app/fqkline/get
 * (前复权，支持 A股/港股/美股)
 */
public class StockKlineCalc {
    private static final String URL = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=%s,day,,,260,qfq";

    public static class KlineResult {
        public double[] ma5, ma10, ma20, ma30, ma60, ma120, ma240;
        public double[] diff, dea, macd;
    }

    private static final Map<String, Cached> cache = new HashMap<>();
    private static final long DEFAULT_TTL_MS = 300_000L; // 默认 5 分钟
    private static long cacheTtlMs = DEFAULT_TTL_MS;

    static {
        try {
            String v = PropertiesComponent.getInstance().getValue("key_kline_cache_ttl_ms");
            if (v != null) cacheTtlMs = Long.parseLong(v);
        } catch (Exception ignore) { cacheTtlMs = DEFAULT_TTL_MS; }
    }

    private static class Cached {
        KlineResult r; long t;
        Cached(KlineResult r, long t) { this.r = r; this.t = t; }
    }

    public static KlineResult compute(String rawCode) {
        Cached c = cache.get(rawCode);
        if (c != null && System.currentTimeMillis() - c.t < cacheTtlMs) return c.r;

        String klineCode = toKlineCode(rawCode);
        if (klineCode == null) { cache.put(rawCode, new Cached(null, System.currentTimeMillis())); return null; }

        try {
            String resp = HttpClientPool.getHttpClient().get(String.format(URL, klineCode));
            JsonObject root = null;
            try { root = JsonParser.parseString(resp).getAsJsonObject(); } catch (Exception ex) { root = null; }
            if (root == null) return null;
            JsonObject data = root.has("data") ? root.getAsJsonObject("data") : null;
            if (data == null) return null;
            JsonObject stockData = data.has(klineCode) ? data.getAsJsonObject(klineCode) : null;
            if (stockData == null) return null;
            JsonArray arr = stockData.has("qfqday") ? stockData.getAsJsonArray("qfqday") : null;
            if (arr == null || arr.size() == 0) arr = stockData.has("day") ? stockData.getAsJsonArray("day") : null;
            if (arr == null || arr.size() == 0) return null;

            int len = arr.size();
            double[] closes = new double[len];
            for (int i = 0; i < len; i++) {
                JsonArray row = arr.get(i).getAsJsonArray();
                closes[i] = row.get(2).getAsDouble();
            }

            KlineResult r = new KlineResult();
            r.ma5  = calcMA(closes, 5);
            r.ma10 = calcMA(closes, 10);
            r.ma20 = calcMA(closes, 20);
            r.ma30 = calcMA(closes, 30);
            r.ma60 = calcMA(closes, 60);
            r.ma120 = calcMA(closes, 120);
            r.ma240 = calcMA(closes, 240);
            double[][] macd = calcMACD(closes);
            r.diff = macd[0]; r.dea = macd[1]; r.macd = macd[2];
            c = new Cached(r, System.currentTimeMillis());
            cache.put(rawCode, c);
            return r;
        } catch (Exception e) {
            LogUtil.info("K线获取异常: " + rawCode + " " + e.getMessage());
            cache.put(rawCode, new Cached(null, System.currentTimeMillis()));
            return null;
        }
    }

    private static String toKlineCode(String c) {
        if (StringUtils.isBlank(c)) return null;
        String l = c.toLowerCase().trim();
        if (l.startsWith("sh") || l.startsWith("sz") || l.startsWith("hk")) return l;
        if (l.startsWith("us")) return "us" + l.substring(2).toUpperCase() + ".OQ";
        if (l.matches("\\d+")) return l.startsWith("6") ? "sh" + l : "sz" + l;
        return null;
    }

    private static double[] calcMA(double[] closes, int period) {
        int n = closes.length;
        double[] ma = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += closes[i];
            if (i >= period) sum -= closes[i - period];
            ma[i] = i >= period - 1 ? sum / period : Double.NaN;
        }
        return ma;
    }

    private static double[][] calcMACD(double[] closes) {
        int n = closes.length;
        double[] diff = new double[n];
        double[] dea = new double[n];
        double[] macd = new double[n];
        double ema12 = closes[0], ema26 = closes[0];
        for (int i = 0; i < n; i++) {
            ema12 = closes[i] * 2.0 / 13 + ema12 * 11.0 / 13;
            ema26 = closes[i] * 2.0 / 27 + ema26 * 25.0 / 27;
            diff[i] = ema12 - ema26;
        }
        double emaDea = diff[0];
        dea[0] = diff[0];
        macd[0] = 2 * (diff[0] - dea[0]);
        for (int i = 0; i < n; i++) {
            emaDea = diff[i] * 2.0 / 10 + emaDea * 8.0 / 10;
            dea[i] = emaDea;
            macd[i] = 2 * (diff[i] - dea[i]);
        }
        return new double[][]{diff, dea, macd};
    }
}
