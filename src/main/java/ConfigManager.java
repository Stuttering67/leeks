package leeks;

import com.intellij.ide.util.PropertiesComponent;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    private static final ConfigManager INSTANCE = new ConfigManager();
    private final PropertiesComponent properties;

    private ConfigManager() {
        this.properties = PropertiesComponent.getInstance();
    }

    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    // 获取基金代码列表
    public List<String> getFundCodes() {
        return getConfigList(ConfigKeys.KEY_FUNDS);
    }

    // 获取股票代码列表
    public List<String> getStockCodes() {
        return getConfigList(ConfigKeys.KEY_STOCKS);
    }

    // 获取加密货币代码列表
    public List<String> getCoinCodes() {
        return getConfigList(ConfigKeys.KEY_COINS);
    }

    // 获取表格条纹显示设置
    public boolean isTableStriped() {
        return properties.getBoolean(ConfigKeys.KEY_TABLE_STRIPED, false);
    }

    // 获取彩色显示设置
    public boolean isColorfulEnabled() {
        return properties.getBoolean(ConfigKeys.KEY_COLORFUL, false);
    }

    // 获取代理设置
    public String getProxySetting() {
        return properties.getValue(ConfigKeys.KEY_PROXY, "");
    }

    // 获取基金 Cron 表达式
    public String getFundCronExpression() {
        return properties.getValue(ConfigKeys.KEY_CRON_EXPRESSION_FUND, "0 * * * * ?");
    }

    // 获取股票 Cron 表达式
    public String getStockCronExpression() {
        return properties.getValue(ConfigKeys.KEY_CRON_EXPRESSION_STOCK, "*/10 * * * * ?");
    }

    // 获取加密货币 Cron 表达式
    public String getCoinCronExpression() {
        return properties.getValue(ConfigKeys.KEY_CRON_EXPRESSION_COIN, "*/10 * * * * ?");
    }

    // 获取基金/股票/加密货币的秒级刷新间隔（优先级高于 Cron 表达式），返回 -1 表示未配置或无效
    public int getFundRefreshIntervalSeconds() {
        String v = properties.getValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_FUND, "");
        if (v == null || v.trim().isEmpty()) return -1;
        try { int s = Integer.parseInt(v.trim()); return s > 0 ? s : -1; } catch (Exception e) { return -1; }
    }

    public int getStockRefreshIntervalSeconds() {
        String v = properties.getValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_STOCK, "");
        if (v == null || v.trim().isEmpty()) return -1;
        try { int s = Integer.parseInt(v.trim()); return s > 0 ? s : -1; } catch (Exception e) { return -1; }
    }

    public int getCoinRefreshIntervalSeconds() {
        String v = properties.getValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_COIN, "");
        if (v == null || v.trim().isEmpty()) return -1;
        try { int s = Integer.parseInt(v.trim()); return s > 0 ? s : -1; } catch (Exception e) { return -1; }
    }

    // 获取股票接口设置（旧版兼容）
    public boolean useSinaStockApi() {
        return properties.getBoolean(ConfigKeys.KEY_STOCKS_SINA, false);
    }

    // 获取股票数据源类型：tencent / sina
    public String getStockSource() {
        String source = properties.getValue(ConfigKeys.KEY_STOCKS_SOURCE);
        if (StringUtils.isNotBlank(source)) {
            return source;
        }
        // 向后兼容：如果旧配置勾选了新浪
        if (properties.getBoolean(ConfigKeys.KEY_STOCKS_SINA, false)) {
            return "sina";
        }
        return "tencent"; // 默认腾讯
    }

    // 设置股票数据源类型
    public void setStockSource(String source) {
        properties.setValue(ConfigKeys.KEY_STOCKS_SOURCE, source);
        // 同时更新旧配置以保持兼容
        properties.setValue(ConfigKeys.KEY_STOCKS_SINA, "sina".equals(source));
    }

    // iTick token (免费注册: https://itick.org)
    public String getItickToken() {
        return properties.getValue("key_itick_token", "");
    }

    // QOS API key (免费注册: https://qos.hk)
    public String getQosApiKey() {
        return properties.getValue("key_qos_apikey", "");
    }

    // 智兔数服 token (免费注册: https://www.zhituapi.com)
    public String getZhituToken() {
        return properties.getValue("key_zhitu_token", "");
    }

    // 获取 F7 快捷键设置
    // 列可见性（勾选=显示，默认全部显示）
    public boolean isStockColVisible(String key) { return properties.getBoolean(key, true); }
    // 持仓统计刷新间隔（默认1000ms）
    public int getSummaryRefreshInterval() {
        String v = properties.getValue("key_summary_refresh_interval", "1000");
        try { int ms = Integer.parseInt(v); return Math.max(200, ms); }
        catch (NumberFormatException e) { return 1000; }
    }

    // 港股汇率
    public String getHkExchangeRate() { return properties.getValue(ConfigKeys.KEY_HK_EXCHANGE_RATE, "0.87"); }

    public boolean isF7Enabled() {
        return !properties.getBoolean(ConfigKeys.KEY_CLOSE_F7, false);
    }

    // 持仓风险预警
    public boolean isRiskAlertEnabled() {
        return properties.getBoolean(ConfigKeys.KEY_RISK_ALERT, false);
    }

    public double getRiskUpPercent() {
        String v = properties.getValue("key_risk_up_percent", "10");
        try { return Math.abs(Double.parseDouble(v)); } catch (NumberFormatException e) { return 10; }
    }

    public double getRiskDownPercent() {
        String v = properties.getValue("key_risk_down_percent", "10");
        try { return Math.abs(Double.parseDouble(v)); } catch (NumberFormatException e) { return 10; }
    }

    public boolean isTabHidden(String tab) { return properties.getBoolean("key_tab_hide_" + tab, false); }

    // 金额以万为单位显示
    public boolean isAmountInWan() {
        return properties.getBoolean("key_amount_wan", false);
    }

    // 通用配置列表加载方法
    public List<String> getConfigList(String key) {
        return getConfigList(key, "[;]");
    }

    // 带分隔符的配置列表加载方法
    public List<String> getConfigList(String key, String separator) {
        List<String> list = new ArrayList<>();
        String value = properties.getValue(key);
        if (StringUtils.isNotBlank(value)) {
            String[] split = value.split(separator);
            for (String s : split) {
                if (StringUtils.isNotBlank(s)) {
                    list.add(s.trim());
                }
            }
        }
        return list;
    }

    // 保存配置列表
    public void saveConfigList(String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            properties.setValue(key, "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            sb.append(value).append("; ");
        }
        properties.setValue(key, sb.toString().trim());
    }

    // 保存单个配置
    public void saveValue(String key, String value) {
        properties.setValue(key, value);
    }

    // 保存布尔配置
    public void saveBoolean(String key, boolean value) {
        properties.setValue(key, String.valueOf(value));
    }
}
