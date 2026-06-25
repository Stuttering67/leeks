package leeks;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import quartz.QuartzManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import utils.HttpClientPool;
import utils.LogUtil;
import utils.ThreadPools;
import utils.ImageCache;
import utils.StockUtils;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SettingsWindow implements Configurable {
    private JPanel panel1; // form 生成的 panel，包含 tabbedPane + textArea（tab 内的编码输入区）
    private JTextArea textAreaFund;
    private JTextArea textAreaStock;
    private JCheckBox checkbox;             // 超隐蔽模式 — form 注入
    private JTabbedPane tabbedPane1;        // form 注入
    private JCheckBox checkBoxTableStriped; // form 注入
    private JTextField cronExpressionFund;  // 现在由 buildAllSettingsPanel 创建，form 不再注入
    private JTextField cronExpressionStock; // 同上
    private JTextField cronExpressionCoin;  // 同上
    private JTextField tfRefreshSecondsFund;
    private JTextField tfRefreshSecondsStock;
    private JTextField tfRefreshSecondsCoin;
    private JComboBox<String> stockSourceComboBox; // form 注入
    private JCheckBox cbColHigh, cbColLow, cbColSell1, cbColBuy1, cbColTime;
    private JCheckBox cbColChange, cbColChangePct, cbColNow, cbColIncomePct, cbColIncome;
    private JCheckBox cbColPosCost, cbColPosValue, cbColDayPnl, cbColPosRatio;    private JCheckBox cbRiskAlert;
    private JCheckBox cbAmountWan;
    private JTextField tfBackupPath;
    private JButton btnBackupPathBrowse;
    private JCheckBox cbBackupEnabled;
    private JCheckBox cbColOpen, cbColVolume, cbColAmount, cbColTurnover, cbColPE, cbColAmplitude, cbColVolRatio;
    private JCheckBox cbTabFund, cbTabStock, cbTabCoin;
    private JCheckBox cbShowPositions, cbShowImportant;
    private JTextField riskUpPercentField;
    private JTextField riskDownPercentField;
    private JTextField hkExchangeRateField;
    private JTextField summaryRefreshIntervalField;
    private JCheckBox checkboxLog;   // form 注入
    private JCheckBox checkboxF7;    // form 注入
    private JTextArea textAreaCoin;
    private JTextField inputProxy;       // form 注入的 proxy field
    private JButton proxyTestButton;     // form 注入
    private JTextField accountCountField; // 新字段，buildAllSettingsPanel 创建
    private JCheckBox cbImageCacheEnable;
    private JTextField tfImageCacheDir;
    private JTextField tfImageCacheTTL;
    private JTextField tfImageCacheCleanupInterval;
    private JTextField tfImageCacheMaxMem;
    private JTextField tfHttpMaxRetries;
    private JTextField tfHttpRetryBackoff;
    private JCheckBox cbHttpMetricsEnable;
    private JTextField tfHttpMetricsInterval;
    private JTextField tfGroupTabMinWidth;
    private JTextField tfGroupTabMaxWidth;
    private JTextField tfGroupTabTruncateLen;
    private JTextField tfGroupTabWrapCount;
    private JLabel lblStockTaskStatus;
    private JLabel lblFundTaskStatus;
    private JLabel lblCoinTaskStatus;
    private JLabel lblHttpMetrics;
    private ScheduledFuture<?> metricsUpdaterFuture;

    @Override public @Nls String getDisplayName() { return "Leeks"; }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    public static java.util.List<String> getExportedSettingBooleanKeys() {
        return java.util.Arrays.asList(
                "key_table_striped",
                "key_colorful",
                "key_close_log",
                "key_close_f7",
                "key_amount_wan",
                "key_tab_hide_fund",
                "key_tab_hide_stock",
                "key_tab_hide_coin",
                "key_show_positions",
                "key_show_important_area",
                "key_imagecache_enabled",
                "key_http_metrics_enabled",
                "key_risk_alert",
                "key_backup_enabled"
        );
    }

    public static java.util.List<String> getExportedSettingStringKeys() {
        return java.util.Arrays.asList(
                "key_group_tab_min_width",
                "key_group_tab_max_width",
                "key_group_tab_truncate_len",
                "key_group_tab_wrap_count",
                "key_backup_path",
                "key_proxy",
                "key_imagecache_dir",
                "key_imagecache_ttl_hours",
                "key_imagecache_cleanup_interval_minutes",
                "key_imagecache_max_mem",
                "key_http_max_retries",
                "key_http_retry_backoff_millis",
                "key_http_metrics_interval_seconds",
                "key_hk_exchange_rate",
                "key_summary_refresh_interval",
                "key_account_count",
                "key_risk_up_percent",
                "key_risk_down_percent",
                "key_stocks_source",
                "key_cron_expression_fund",
                "key_cron_expression_stock",
                "key_cron_expression_coin",
                "key_refresh_interval_seconds_fund",
                "key_refresh_interval_seconds_stock",
                "key_refresh_interval_seconds_coin"
        );
    }

    @Override public @Nullable JComponent createComponent() {
        PropertiesComponent pc = PropertiesComponent.getInstance();
        // 防御：如果 form 注入的控件为 null，这里创建默认值
        if (checkbox == null) checkbox = new JCheckBox("超隐蔽模式");
        if (checkBoxTableStriped == null) checkBoxTableStriped = new JCheckBox("表格条纹");
        if (checkboxLog == null) checkboxLog = new JCheckBox("关闭日志");
        if (checkboxF7 == null) checkboxF7 = new JCheckBox("关闭F7");
        if (stockSourceComboBox == null) stockSourceComboBox = new JComboBox<>();
        if (inputProxy == null) inputProxy = new JTextField(15);
        if (proxyTestButton == null) proxyTestButton = new JButton("test");
        if (cbImageCacheEnable == null) cbImageCacheEnable = new JCheckBox("启用图片缓存", PropertiesComponent.getInstance().getBoolean("key_imagecache_enabled", true));
        if (tfImageCacheDir == null) tfImageCacheDir = new JTextField(PropertiesComponent.getInstance().getValue("key_imagecache_dir", ""), 20);
        if (tfImageCacheTTL == null) tfImageCacheTTL = new JTextField(PropertiesComponent.getInstance().getValue("key_imagecache_ttl_hours", "24"), 4);
        if (tfImageCacheCleanupInterval == null) tfImageCacheCleanupInterval = new JTextField(PropertiesComponent.getInstance().getValue("key_imagecache_cleanup_interval_minutes", String.valueOf(TimeUnit.DAYS.toMinutes(1))), 6);
        if (tfImageCacheMaxMem == null) tfImageCacheMaxMem = new JTextField(PropertiesComponent.getInstance().getValue("key_imagecache_max_mem", "50"), 3);
        if (tfHttpMaxRetries == null) tfHttpMaxRetries = new JTextField(PropertiesComponent.getInstance().getValue("key_http_max_retries", "2"), 3);
        if (tfHttpRetryBackoff == null) tfHttpRetryBackoff = new JTextField(PropertiesComponent.getInstance().getValue("key_http_retry_backoff_millis", "300"), 6);
        if (cbHttpMetricsEnable == null) cbHttpMetricsEnable = new JCheckBox("启用HTTP请求指标日志", PropertiesComponent.getInstance().getBoolean("key_http_metrics_enabled", false));
        if (tfHttpMetricsInterval == null) tfHttpMetricsInterval = new JTextField(PropertiesComponent.getInstance().getValue("key_http_metrics_interval_seconds", "300"), 6);
        if (tfGroupTabMinWidth == null) tfGroupTabMinWidth = new JTextField(PropertiesComponent.getInstance().getValue("key_group_tab_min_width", "60"), 4);
        if (tfGroupTabMaxWidth == null) tfGroupTabMaxWidth = new JTextField(PropertiesComponent.getInstance().getValue("key_group_tab_max_width", "160"), 4);
        if (tfGroupTabTruncateLen == null) tfGroupTabTruncateLen = new JTextField(PropertiesComponent.getInstance().getValue("key_group_tab_truncate_len", "12"), 3);
        if (tfGroupTabWrapCount == null) tfGroupTabWrapCount = new JTextField(PropertiesComponent.getInstance().getValue("key_group_tab_wrap_count", "0"), 4);
        if (cbBackupEnabled == null) cbBackupEnabled = new JCheckBox("每天09:00自动备份设置", PropertiesComponent.getInstance().getBoolean(ConfigKeys.KEY_BACKUP_ENABLED, false));
        if (tfBackupPath == null) tfBackupPath = new JTextField(PropertiesComponent.getInstance().getValue(ConfigKeys.KEY_BACKUP_PATH, getDefaultBackupPath()), 30);
        if (btnBackupPathBrowse == null) btnBackupPathBrowse = new JButton("浏览");
        if (cronExpressionFund == null) cronExpressionFund = new JTextField(18);
        if (cronExpressionStock == null) cronExpressionStock = new JTextField(18);
        if (cronExpressionCoin == null) cronExpressionCoin = new JTextField(18);
        if (tfRefreshSecondsFund == null) tfRefreshSecondsFund = new JTextField(PropertiesComponent.getInstance().getValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_FUND, ""), 6);
        if (tfRefreshSecondsStock == null) tfRefreshSecondsStock = new JTextField(PropertiesComponent.getInstance().getValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_STOCK, ""), 6);
        if (tfRefreshSecondsCoin == null) tfRefreshSecondsCoin = new JTextField(PropertiesComponent.getInstance().getValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_COIN, ""), 6);
        if (panel1 == null) panel1 = new JPanel();

        if (textAreaFund != null) textAreaFund.setText(String.join(System.lineSeparator(), parseInputText(pc.getValue("key_funds", ""))));
        if (textAreaStock != null) textAreaStock.setText(String.join(System.lineSeparator(), parseInputStockText(pc.getValue("key_stocks", ""))));
        if (textAreaCoin != null) textAreaCoin.setText(String.join(System.lineSeparator(), parseInputText(pc.getValue("key_coins", ""))));
        checkbox.setSelected(!pc.getBoolean("key_colorful"));
        checkBoxTableStriped.setSelected(pc.getBoolean("key_table_striped"));
        stockSourceComboBox.removeAllItems();
        stockSourceComboBox.addItem("腾讯接口"); stockSourceComboBox.addItem("新浪接口");
        String src = pc.getValue("key_stocks_source");
        if ("sina".equals(src)) stockSourceComboBox.setSelectedItem("新浪接口");
        else stockSourceComboBox.setSelectedItem("腾讯接口");
        checkboxLog.setSelected(pc.getBoolean("key_close_log"));
        checkboxF7.setSelected(pc.getBoolean("key_close_f7"));
        cronExpressionFund.setText(pc.getValue("key_cron_expression_fund", "0 * * * * ?"));
        cronExpressionStock.setText(pc.getValue("key_cron_expression_stock", "*/10 * * * * ?"));
        cronExpressionCoin.setText(pc.getValue("key_cron_expression_coin", "*/10 * * * * ?"));
        inputProxy.setText(nullToEmpty(pc.getValue("key_proxy")));
        proxyTestButton.addActionListener(e -> testProxy(inputProxy.getText().trim()));
        if (accountCountField == null) accountCountField = new JTextField("1", 3);
        accountCountField.setText(pc.getValue("key_account_count", "1"));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(buildAllSettingsPanel(pc), BorderLayout.NORTH);
        panel1.setBorder(new TitledBorder("编码设置"));
        wrapper.add(panel1, BorderLayout.CENTER);
        // 启动周期性指标更新（避免重复调度）
        try {
            if (metricsUpdaterFuture != null) metricsUpdaterFuture.cancel(false);
            metricsUpdaterFuture = ThreadPools.getScheduledExecutor().scheduleAtFixedRate(() -> updateMetricsOnce(), 0, 10, TimeUnit.SECONDS);
        } catch (Throwable ignore) {}
        return wrapper;
    }

    /**
     * 将用户在 TextArea 中输入的多行/分隔内容解析为去重且按输入顺序保留的列表
     */
    private static java.util.List<String> parseInputText(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (StringUtils.isBlank(raw)) return out;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        // 只按分号或换行切分，避免把项内的逗号拆开（基金/币种条目也可能含逗号字段）
        String[] parts = raw.split("[;\\n\\r]+");
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (StringUtils.isNotBlank(t)) seen.add(t);
        }
        out.addAll(seen);
        return out;
    }

    /**
     * 解析 stock 文本域：仅按分号或换行分割，保留每项内的逗号（因为 key_stocks 每项可能包含逗号分隔的成本/持仓等字段）
     */
    private static java.util.List<String> parseInputStockText(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (StringUtils.isBlank(raw)) return out;
        // 规范化行结束符
        String norm = raw.replace("\r", "");
        // 如果用户使用分号明确分割，则按分号为主，且把段内的换行替换为逗号
        if (norm.contains(";")) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            String[] parts = norm.split(";");
            for (String p : parts) {
                if (p == null) continue;
                String t = p.trim();
                if (StringUtils.isBlank(t)) continue;
                // 把内部的换行替换为逗号，并合并多余空白
                String merged = t.replaceAll("[\\n\\r]+", ",").replaceAll("\\s*,\\s*", ",").trim();
                if (StringUtils.isNotBlank(merged)) seen.add(merged);
            }
            out.addAll(seen);
            return out;
        }

        // 否则按行解析，并尝试把多行表示的一条记录合并为逗号分隔的一项
        String[] lines = norm.split("\\n");
        java.util.List<String> cleaned = new java.util.ArrayList<>();
        for (String L : lines) {
            if (L == null) continue;
            String t = L.trim();
            if (StringUtils.isNotBlank(t)) cleaned.add(t);
        }
        // 如果任何一行已经包含逗号，则视为每行一条完整记录
        boolean anyComma = false;
        for (String l : cleaned) if (l.indexOf(',') >= 0 || l.indexOf('，') >= 0) { anyComma = true; break; }
        if (anyComma) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (String l : cleaned) seen.add(l.replace('，', ','));
            out.addAll(seen);
            return out;
        }

        // 辅助正则
        java.util.regex.Pattern codePat = java.util.regex.Pattern.compile("^[0-9A-Za-z]{3,20}$");
        java.util.regex.Pattern numPat = java.util.regex.Pattern.compile("^-?\\d+(\\\\.\\d+)?$");

        for (int i = 0; i < cleaned.size(); ) {
            String cur = cleaned.get(i);
            String next = (i + 1 < cleaned.size()) ? cleaned.get(i + 1) : null;
            String next2 = (i + 2 < cleaned.size()) ? cleaned.get(i + 2) : null;
            // 若模式为: code \n number \n number -> 合并为 code,number,number
            if (next2 != null && codePat.matcher(cur).matches() && numPat.matcher(next).matches() && numPat.matcher(next2).matches()) {
                out.add(cur + "," + next + "," + next2);
                i += 3; continue;
            }
            // 若模式为: code \n number -> 合并为 code,number
            if (next != null && codePat.matcher(cur).matches() && numPat.matcher(next).matches()) {
                out.add(cur + "," + next);
                i += 2; continue;
            }
            // 否则，把当前行与后续非 code 行合并，直到遇到下一个像 code 的行或者结束
            StringBuilder sb = new StringBuilder(cur);
            int j = i + 1;
            while (j < cleaned.size() && !codePat.matcher(cleaned.get(j)).matches()) {
                sb.append(",").append(cleaned.get(j)); j++; }
            out.add(sb.toString());
            i = j;
        }

        // 去重并保持顺序
        java.util.Set<String> uniq = new java.util.LinkedHashSet<>(out);
        out.clear(); out.addAll(uniq);
        return out;
    }

    private static String joinWithTrailingSemicolon(java.util.List<String> items) {
        if (items == null || items.isEmpty()) return "";
        String s = String.join(";", items);
        if (!s.endsWith(";")) s = s + ";";
        return s;
    }

    /**
     * 当账户数量从 oldCount 减少到 newCount 时，将所有属于已移除账号的股票重新分配到最后一个账号（账号newCount）
     */
    private static void adjustAccountsInKeyStocks(int oldCount, int newCount) {
        if (oldCount <= newCount) return;
        PropertiesComponent pc = PropertiesComponent.getInstance();
        String ks = pc.getValue("key_stocks", "");
        if (StringUtils.isBlank(ks)) return;
        StringBuilder sb = new StringBuilder();
        String[] items = ks.split(";");
        for (String item : items) {
            if (StringUtils.isBlank(item)) continue;
            String s = item.trim();
            // 保留 alertConfig（可能包含逗号），使用 limit 参数分割为最多7段
            String[] parts = s.split(",", 7);
            String code = parts.length > 0 ? parts[0].trim() : "";
            String cost = parts.length > 1 ? parts[1].trim() : "";
            String bonds = parts.length > 2 ? parts[2].trim() : "";
            String account = parts.length > 4 ? parts[4].trim() : "";
            String rest = parts.length > 6 ? parts[6] : "";
            int acctNum = -1;
            if (StringUtils.isNotBlank(account)) {
                String norm = utils.WindowUtils.normalizeAccount(account);
                try { acctNum = Integer.parseInt(norm); } catch (Exception ignore) { acctNum = -1; }
            }
            if (acctNum > newCount) account = String.valueOf(newCount);
            String outItem;
            if (StringUtils.isNotBlank(rest)) outItem = code + "," + cost + "," + bonds + ",," + account + "," + rest;
            else outItem = code + "," + cost + "," + bonds + ",," + account;
            // trim trailing commas
            while (outItem.endsWith(",")) outItem = outItem.substring(0, outItem.length() - 1);
            sb.append(outItem).append(";");
        }
        pc.setValue("key_stocks", sb.toString());
    }

    @Override public boolean isModified() { return true; }

    @Override public void apply() throws ConfigurationException {
        String errorMsg = checkConfig();
        if (StringUtils.isNotEmpty(errorMsg)) {
            // 不再因为 Cron 表达式校验失败而阻止其余设置生效（避免列/显示设置无法保存）
            LogUtil.info("配置校验失败: " + errorMsg + "，继续保存其它设置。");
        }
        PropertiesComponent pc = PropertiesComponent.getInstance();
        if (textAreaFund != null) pc.setValue("key_funds", joinWithTrailingSemicolon(parseInputText(textAreaFund.getText())));
        if (textAreaStock != null) pc.setValue("key_stocks", joinWithTrailingSemicolon(parseInputStockText(textAreaStock.getText())));
        if (textAreaCoin != null) pc.setValue("key_coins", joinWithTrailingSemicolon(parseInputText(textAreaCoin.getText())));
        if (checkbox != null) pc.setValue("key_colorful", !checkbox.isSelected());
        if (cronExpressionFund != null) pc.setValue("key_cron_expression_fund", cronExpressionFund.getText());
        if (cronExpressionStock != null) pc.setValue("key_cron_expression_stock", cronExpressionStock.getText());
        if (cronExpressionCoin != null) pc.setValue("key_cron_expression_coin", cronExpressionCoin.getText());
        // 秒级刷新设置：优先级最高，留空或无效则不启用（写入空值以便后续逻辑区分）
        if (tfRefreshSecondsFund != null) {
            String s = tfRefreshSecondsFund.getText().trim();
            try { int sec = Integer.parseInt(s); if (sec > 0) pc.setValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_FUND, String.valueOf(sec)); else pc.setValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_FUND, ""); } catch (Exception e) { pc.setValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_FUND, ""); }
        }
        if (tfRefreshSecondsStock != null) {
            String s = tfRefreshSecondsStock.getText().trim();
            try { int sec = Integer.parseInt(s); if (sec > 0) pc.setValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_STOCK, String.valueOf(sec)); else pc.setValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_STOCK, ""); } catch (Exception e) { pc.setValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_STOCK, ""); }
        }
        if (tfRefreshSecondsCoin != null) {
            String s = tfRefreshSecondsCoin.getText().trim();
            try { int sec = Integer.parseInt(s); if (sec > 0) pc.setValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_COIN, String.valueOf(sec)); else pc.setValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_COIN, ""); } catch (Exception e) { pc.setValue(ConfigKeys.KEY_REFRESH_INTERVAL_SECONDS_COIN, ""); }
        }
        if (checkBoxTableStriped != null) ConfigManager.getInstance().saveBoolean("key_table_striped", checkBoxTableStriped.isSelected());
        if (stockSourceComboBox != null) {
            String sel = (String) stockSourceComboBox.getSelectedItem();
            if ("新浪接口".equals(sel)) pc.setValue("key_stocks_source", "sina");
            else pc.setValue("key_stocks_source", "tencent");
            ConfigManager.getInstance().saveBoolean("key_stocks_sina", "sina".equals(sel));
        }
        if (checkboxLog != null) ConfigManager.getInstance().saveBoolean("key_close_log", checkboxLog.isSelected());
        if (checkboxF7 != null) ConfigManager.getInstance().saveBoolean("key_close_f7", checkboxF7.isSelected());
        if (cbColHigh != null) ConfigManager.getInstance().saveBoolean("key_stock_col_high", cbColHigh.isSelected());
        if (cbColLow != null) ConfigManager.getInstance().saveBoolean("key_stock_col_low", cbColLow.isSelected());
        if (cbColSell1 != null) ConfigManager.getInstance().saveBoolean("key_stock_col_sell1", cbColSell1.isSelected());
        if (cbColBuy1 != null) ConfigManager.getInstance().saveBoolean("key_stock_col_buy1", cbColBuy1.isSelected());
        if (cbColTime != null) ConfigManager.getInstance().saveBoolean("key_stock_col_time", cbColTime.isSelected());
        if (cbColChange != null) ConfigManager.getInstance().saveBoolean("key_stock_col_change", cbColChange.isSelected());
        if (cbColChangePct != null) ConfigManager.getInstance().saveBoolean("key_stock_col_change_pct", cbColChangePct.isSelected());
        if (cbColNow != null) ConfigManager.getInstance().saveBoolean("key_stock_col_now", cbColNow.isSelected());
        if (cbColIncomePct != null) ConfigManager.getInstance().saveBoolean("key_stock_col_income_pct", cbColIncomePct.isSelected());
        if (cbColIncome != null) ConfigManager.getInstance().saveBoolean("key_stock_col_income", cbColIncome.isSelected());
        if (cbColPosCost != null) ConfigManager.getInstance().saveBoolean("key_stock_col_position_cost", cbColPosCost.isSelected());
        if (cbColPosValue != null) ConfigManager.getInstance().saveBoolean("key_stock_col_position_value", cbColPosValue.isSelected());
        if (cbColDayPnl != null) ConfigManager.getInstance().saveBoolean("key_stock_col_day_pnl", cbColDayPnl.isSelected());
        if (cbColPosRatio != null) ConfigManager.getInstance().saveBoolean("key_stock_col_position_ratio", cbColPosRatio.isSelected());
        if (cbColOpen != null) ConfigManager.getInstance().saveBoolean("key_stock_col_open", cbColOpen.isSelected());
        if (cbColVolume != null) ConfigManager.getInstance().saveBoolean("key_stock_col_volume", cbColVolume.isSelected());
        if (cbColAmount != null) ConfigManager.getInstance().saveBoolean("key_stock_col_amount", cbColAmount.isSelected());
        if (cbColTurnover != null) ConfigManager.getInstance().saveBoolean("key_stock_col_turnover", cbColTurnover.isSelected());
        if (cbColPE != null) ConfigManager.getInstance().saveBoolean("key_stock_col_pe", cbColPE.isSelected());
        if (cbColAmplitude != null) ConfigManager.getInstance().saveBoolean("key_stock_col_amplitude", cbColAmplitude.isSelected());
        if (cbColVolRatio != null) ConfigManager.getInstance().saveBoolean("key_stock_col_volRatio", cbColVolRatio.isSelected());
        if (hkExchangeRateField != null) pc.setValue("key_hk_exchange_rate", hkExchangeRateField.getText().trim());
        if (summaryRefreshIntervalField != null) pc.setValue("key_summary_refresh_interval", summaryRefreshIntervalField.getText().trim());
        if (inputProxy != null) {
            String proxy = inputProxy.getText().trim();
            pc.setValue("key_proxy", proxy);
            HttpClientPool.getHttpClient().buildHttpClient(proxy);
        }
        // HTTP retry & metrics settings
        if (tfHttpMaxRetries != null) {
            String v = tfHttpMaxRetries.getText().trim();
            try { int mr = Integer.parseInt(v); if (mr < 0) mr = 0; pc.setValue("key_http_max_retries", String.valueOf(mr)); HttpClientPool.getHttpClient().setMaxRetries(mr); } catch (Exception e) { pc.setValue("key_http_max_retries", "2"); HttpClientPool.getHttpClient().setMaxRetries(2); }
        }
        if (tfHttpRetryBackoff != null) {
            String v = tfHttpRetryBackoff.getText().trim();
            try { long ms = Long.parseLong(v); if (ms < 0) ms = 0; pc.setValue("key_http_retry_backoff_millis", String.valueOf(ms)); HttpClientPool.getHttpClient().setRetryBackoffMillis(ms); } catch (Exception e) { pc.setValue("key_http_retry_backoff_millis", "300"); HttpClientPool.getHttpClient().setRetryBackoffMillis(300); }
        }
        if (cbHttpMetricsEnable != null) pc.setValue("key_http_metrics_enabled", cbHttpMetricsEnable.isSelected());
        if (tfHttpMetricsInterval != null) pc.setValue("key_http_metrics_interval_seconds", tfHttpMetricsInterval.getText().trim());
        // 启动或停止 metrics reporter
        try {
            boolean enabled = PropertiesComponent.getInstance().getBoolean("key_http_metrics_enabled", false);
            long interval = 300;
            try { interval = Long.parseLong(PropertiesComponent.getInstance().getValue("key_http_metrics_interval_seconds", "300")); } catch (Exception ignore) {}
            if (enabled) HttpClientPool.getHttpClient().startMetricsReporter(0, Math.max(5, interval)); else HttpClientPool.getHttpClient().stopMetricsReporter();
        } catch (Throwable ignore) {}
        if (accountCountField != null) {
            String oldCountStr = pc.getValue("key_account_count", "1");
            int oldCount = 1; try { oldCount = Integer.parseInt(oldCountStr); } catch (Exception ignore) {}
            try {
                int count = Integer.parseInt(accountCountField.getText().trim());
                if (count < 1) count = 1; if (count > 3) count = 3;
                pc.setValue("key_account_count", String.valueOf(count));
                // If account count decreased, reassign stocks belonging to removed accounts to the last existing account
                if (count < oldCount) adjustAccountsInKeyStocks(oldCount, count);
                // If account count changed, rebuild StockWindow summary panel immediately
                if (count != oldCount) {
                    try { StockWindow.rebuildSummaryPanel(); } catch (Exception ignore) {}
                }
            } catch (NumberFormatException e) { pc.setValue("key_account_count", "1"); }
        }
        if (cbRiskAlert != null) pc.setValue("key_risk_alert", cbRiskAlert.isSelected());
        if (cbAmountWan != null) pc.setValue("key_amount_wan", cbAmountWan.isSelected());
        if (riskUpPercentField != null) {
            String v = riskUpPercentField.getText().trim();
            if (StringUtils.isBlank(v)) v = "10";
            pc.setValue("key_risk_up_percent", v);
        }
        if (riskDownPercentField != null) {
            String v = riskDownPercentField.getText().trim();
            if (StringUtils.isBlank(v)) v = "10";
            pc.setValue("key_risk_down_percent", v);
        }
        if (cbBackupEnabled != null) ConfigManager.getInstance().saveBoolean(ConfigKeys.KEY_BACKUP_ENABLED, cbBackupEnabled.isSelected());
        if (tfBackupPath != null) pc.setValue(ConfigKeys.KEY_BACKUP_PATH, tfBackupPath.getText().trim());
        scheduleDailyBackup();
        // 图片缓存设置
        if (cbImageCacheEnable != null) pc.setValue("key_imagecache_enabled", cbImageCacheEnable.isSelected());
        if (tfImageCacheTTL != null) pc.setValue("key_imagecache_ttl_hours", tfImageCacheTTL.getText().trim());
        if (tfImageCacheCleanupInterval != null) pc.setValue("key_imagecache_cleanup_interval_minutes", tfImageCacheCleanupInterval.getText().trim());
        if (tfImageCacheMaxMem != null) pc.setValue("key_imagecache_max_mem", tfImageCacheMaxMem.getText().trim());
        if (tfImageCacheDir != null) pc.setValue("key_imagecache_dir", tfImageCacheDir.getText().trim());
        // 分组页签可配置项
        try {
            int minW = 60; int maxW = 160; int trunc = 12;
            try { minW = Math.max(10, Integer.parseInt(tfGroupTabMinWidth.getText().trim())); } catch (Exception ignore) {}
            try { maxW = Math.max(minW, Integer.parseInt(tfGroupTabMaxWidth.getText().trim())); } catch (Exception ignore) { maxW = Math.max(minW, 160); }
            try { trunc = Math.max(4, Integer.parseInt(tfGroupTabTruncateLen.getText().trim())); } catch (Exception ignore) {}
            int wrapCount = 0; try { wrapCount = Math.max(0, Integer.parseInt(tfGroupTabWrapCount.getText().trim())); } catch (Exception ignore) { wrapCount = 0; }
            pc.setValue("key_group_tab_min_width", String.valueOf(minW));
            pc.setValue("key_group_tab_max_width", String.valueOf(maxW));
            pc.setValue("key_group_tab_truncate_len", String.valueOf(trunc));
            pc.setValue("key_group_tab_wrap_count", String.valueOf(wrapCount));
        } catch (Exception ignore) {}
        // 异步刷新数据，避免阻塞设置面板
        // 重新加载 ImageCache 配置（在调用 apply 前保存到 PropertiesComponent）
        try { ImageCache.getInstance().reloadConfig(); } catch (Throwable ignore) {}

        // 停止现有调度器，确保后续的 apply() 能根据最新配置重新创建/更新任务
        try { QuartzManager.getInstance("Stock").stopJob(); } catch (Throwable ignore) {}
        try { QuartzManager.getInstance("Fund").stopJob(); } catch (Throwable ignore) {}
        try { QuartzManager.getInstance("Coin").stopJob(); } catch (Throwable ignore) {}
        SwingUtilities.invokeLater(() -> {
            StockWindow.apply();
            FundWindow.apply();
            CoinWindow.apply();
        });
        if (cbTabFund != null) ConfigManager.getInstance().saveBoolean("key_tab_hide_fund", !cbTabFund.isSelected());
        if (cbTabStock != null) ConfigManager.getInstance().saveBoolean("key_tab_hide_stock", !cbTabStock.isSelected());
        if (cbTabCoin != null) ConfigManager.getInstance().saveBoolean("key_tab_hide_coin", !cbTabCoin.isSelected());
        // 显示/隐藏持仓统计和重要股票区域
        if (cbShowPositions != null) ConfigManager.getInstance().saveBoolean("key_show_positions", cbShowPositions.isSelected());
        if (cbShowImportant != null) ConfigManager.getInstance().saveBoolean("key_show_important_area", cbShowImportant.isSelected());
    }

    private JPanel buildAllSettingsPanel(PropertiesComponent pc) {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));

        // 显示设置
        JPanel displayGroup = new JPanel();
        displayGroup.setLayout(new BoxLayout(displayGroup, BoxLayout.Y_AXIS));
        displayGroup.setBorder(new TitledBorder("显示设置"));
        JPanel dsRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        dsRow1.add(checkBoxTableStriped); dsRow1.add(checkboxLog); dsRow1.add(checkboxF7);
        displayGroup.add(dsRow1);
        JPanel dsRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        dsRow2.add(checkbox);
        displayGroup.add(dsRow2);
        JPanel dsRow1b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cbShowPositions = new JCheckBox("显示持仓信息", pc.getBoolean("key_show_positions", true));
        cbShowImportant = new JCheckBox("显示重要股票区域", pc.getBoolean("key_show_important_area", true));
        // 当用户在设置中切换显示持仓/重要区域时，立即保存并应用到主界面
        cbShowPositions.addActionListener(e -> {
            try { ConfigManager.getInstance().saveBoolean("key_show_positions", cbShowPositions.isSelected()); } catch (Throwable ignore) {}
            SwingUtilities.invokeLater(() -> { try { StockWindow.apply(); } catch (Throwable ignore) {} });
        });
        cbShowImportant.addActionListener(e -> {
            try { ConfigManager.getInstance().saveBoolean("key_show_important_area", cbShowImportant.isSelected()); } catch (Throwable ignore) {}
            SwingUtilities.invokeLater(() -> { try { StockWindow.apply(); } catch (Throwable ignore) {} });
        });
        dsRow1b.add(cbShowPositions); dsRow1b.add(cbShowImportant);
        displayGroup.add(dsRow1b);
        JPanel dsRow3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        dsRow3.add(new JLabel("数据源:")); dsRow3.add(stockSourceComboBox);
        cbAmountWan = new JCheckBox("金额以万为单位", pc.getBoolean("key_amount_wan", false));
        dsRow3.add(cbAmountWan);
        displayGroup.add(dsRow3);
        outer.add(displayGroup);

        // 股票列显示（5个一行）
        JPanel colGroup = new JPanel(new GridLayout(0, 5, 4, 2));
        colGroup.setBorder(new TitledBorder("股票列显示（勾选=显示）"));
        cbColHigh  = mkCb("最高价", pc); cbColLow  = mkCb("最低价", pc);
        cbColSell1 = mkCb("卖一", pc);   cbColBuy1 = mkCb("买一", pc);
        cbColTime  = mkCb("更新时间", pc);
        cbColChange = mkCb("涨跌额", pc); cbColChangePct = mkCb("涨跌幅", pc);
        cbColNow  = mkCb("当前价", pc);   cbColIncomePct = mkCb("收益率", pc);
        cbColIncome = mkCb("收益", pc);
        cbColPosCost = mkCb("持仓成本", pc);
        cbColPosValue = mkCb("持仓市值", pc);
        cbColDayPnl = mkCb("当日盈亏", pc);
        cbColPosRatio = mkCb("仓位", pc);
        cbColOpen = mkCb("今开", pc);     cbColVolume = mkCb("成交量", pc);
        cbColAmount = mkCb("成交额", pc);
        cbColTurnover = mkCb("换手率", pc);  cbColPE = mkCb("市盈率", pc);
        cbColAmplitude = mkCb("振幅", pc);   cbColVolRatio = mkCb("量比", pc);
        JCheckBox cbTotal = mkCb("总市值(亿)", pc); JCheckBox cbFlow = mkCb("流通市值/亿", pc);
        JCheckBox cbOuter = mkCb("外盘(亿)", pc); JCheckBox cbInner = mkCb("内盘(亿)", pc);
        // 为每个列显示复选框添加即时保存与应用的监听器
        java.util.List<JCheckBox> colCbs = java.util.Arrays.asList(cbColHigh, cbColLow, cbColSell1, cbColBuy1, cbColTime,
                cbColChange, cbColChangePct, cbColNow, cbColIncomePct, cbColIncome,
                cbColPosCost, cbColPosValue, cbColDayPnl, cbColPosRatio,
                cbColOpen, cbColVolume, cbColAmount, cbColTurnover, cbColPE, cbColAmplitude, cbColVolRatio,
                cbTotal, cbFlow, cbOuter, cbInner);
        for (JCheckBox c : colCbs) {
            String cfg = c.getClientProperty("configKey") == null ? null : c.getClientProperty("configKey").toString();
            if (cfg != null) {
                c.addActionListener(ev -> {
                    try { ConfigManager.getInstance().saveBoolean(cfg, c.isSelected()); } catch (Throwable ignore) {}
                    SwingUtilities.invokeLater(() -> { try { StockWindow.apply(); } catch (Throwable ignore) {} });
                });
            }
            colGroup.add(c);
        }
        outer.add(colGroup);

        // 港股汇率
        JPanel rateGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        rateGroup.setBorder(new TitledBorder("港股汇率"));
        hkExchangeRateField = new JTextField(pc.getValue("key_hk_exchange_rate", "0.87"), 5);
        rateGroup.add(new JLabel("汇率:"));
        rateGroup.add(hkExchangeRateField);
        outer.add(rateGroup);

        // 持仓统计刷新间隔
        JPanel summaryGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        summaryGroup.setBorder(new TitledBorder("持仓统计刷新间隔"));
        summaryRefreshIntervalField = new JTextField(pc.getValue("key_summary_refresh_interval", "1000"), 5);
        summaryGroup.add(new JLabel("间隔（毫秒）:"));
        summaryGroup.add(summaryRefreshIntervalField);
        outer.add(summaryGroup);

        // 刷新频率（每行一个）
        JPanel cronGroup = new JPanel();
        cronGroup.setLayout(new BoxLayout(cronGroup, BoxLayout.Y_AXIS));
        cronGroup.setBorder(new TitledBorder("刷新频率（Cron表达式）"));
        JPanel cr1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cr1.add(new JLabel("基金:")); cr1.add(cronExpressionFund); cronGroup.add(cr1);
        JPanel cr2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cr2.add(new JLabel("股票:")); cr2.add(cronExpressionStock); cronGroup.add(cr2);
        JPanel cr3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cr3.add(new JLabel("加密货币:")); cr3.add(cronExpressionCoin); cronGroup.add(cr3);
        JPanel cr1b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cr1b.add(new JLabel("基金 秒刷新(优先):")); cr1b.add(tfRefreshSecondsFund); cronGroup.add(cr1b);
        JPanel cr2b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cr2b.add(new JLabel("股票 秒刷新(优先):")); cr2b.add(tfRefreshSecondsStock); cronGroup.add(cr2b);
        JPanel cr3b = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cr3b.add(new JLabel("加密货币 秒刷新(优先):")); cr3b.add(tfRefreshSecondsCoin); cronGroup.add(cr3b);
        outer.add(cronGroup);

        // 代理设置
        JPanel proxyGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        proxyGroup.setBorder(new TitledBorder("代理设置"));
        proxyGroup.add(new JLabel("代理地址:"));
        proxyGroup.add(inputProxy); proxyGroup.add(proxyTestButton);
        outer.add(proxyGroup);

        // HTTP 设置
        JPanel httpGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        httpGroup.setBorder(new TitledBorder("HTTP 设置"));
        httpGroup.add(new JLabel("重试次数:")); httpGroup.add(tfHttpMaxRetries);
        httpGroup.add(new JLabel("退避(ms):")); httpGroup.add(tfHttpRetryBackoff);
        httpGroup.add(cbHttpMetricsEnable);
        httpGroup.add(new JLabel("指标间隔(s):")); httpGroup.add(tfHttpMetricsInterval);
        outer.add(httpGroup);

        // 图片缓存设置
        JPanel cacheGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cacheGroup.setBorder(new TitledBorder("图片缓存"));
        cacheGroup.add(cbImageCacheEnable);
        cacheGroup.add(new JLabel("TTL(小时):")); cacheGroup.add(tfImageCacheTTL);
        cacheGroup.add(new JLabel("清理间隔(分):")); cacheGroup.add(tfImageCacheCleanupInterval);
        cacheGroup.add(new JLabel("内存条目:")); cacheGroup.add(tfImageCacheMaxMem);
        cacheGroup.add(new JLabel("目录:")); cacheGroup.add(tfImageCacheDir);
        outer.add(cacheGroup);

        // 运行时诊断面板（任务状态与 HTTP metrics）
        JPanel diagGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        diagGroup.setBorder(new TitledBorder("运行时指标"));
        lblStockTaskStatus = new JLabel("Stock: --");
        lblFundTaskStatus = new JLabel("Fund: --");
        lblCoinTaskStatus = new JLabel("Coin: --");
        lblHttpMetrics = new JLabel("HTTP: --");
        diagGroup.add(lblStockTaskStatus); diagGroup.add(lblFundTaskStatus); diagGroup.add(lblCoinTaskStatus); diagGroup.add(lblHttpMetrics);
        JButton btnRefreshMetrics = new JButton("刷新指标");
        btnRefreshMetrics.addActionListener(e -> updateMetricsOnce());
        diagGroup.add(btnRefreshMetrics);
        outer.add(diagGroup);

        // 账号数量
        JPanel accountGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        accountGroup.setBorder(new TitledBorder("账号数量（1-3）"));
        accountCountField = new JTextField(pc.getValue("key_account_count", "1"), 3);
        accountGroup.add(new JLabel("账号数量:"));
        accountGroup.add(accountCountField);
        JLabel accountTips = new JLabel("（股票列新增账号选择列，统计信息按账号分组显示）");
        accountTips.setFont(accountTips.getFont().deriveFont(11.0f));
        accountGroup.add(accountTips);
        outer.add(accountGroup);

        // 持仓风险预警
        JPanel riskGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        riskGroup.setBorder(new TitledBorder("持仓风险预警"));
        cbRiskAlert = new JCheckBox("开启持仓风险预警", pc.getBoolean("key_risk_alert", false));
        riskGroup.add(cbRiskAlert);
        riskUpPercentField = new JTextField(pc.getValue("key_risk_up_percent", "10"), 4);
        riskDownPercentField = new JTextField(pc.getValue("key_risk_down_percent", "10"), 4);
        riskGroup.add(new JLabel(" 涨幅超过:"));
        riskGroup.add(riskUpPercentField);
        riskGroup.add(new JLabel("%  "));
        riskGroup.add(new JLabel("跌幅超过:"));
        riskGroup.add(riskDownPercentField);
        riskGroup.add(new JLabel("%"));
        outer.add(riskGroup);

        // 自动备份设置
        JPanel backupGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        backupGroup.setBorder(new TitledBorder("自动备份设置"));
        backupGroup.add(cbBackupEnabled);
        backupGroup.add(new JLabel("备份目录:"));
        backupGroup.add(tfBackupPath);
        backupGroup.add(btnBackupPathBrowse);
        btnBackupPathBrowse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(tfBackupPath.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择备份目录");
            int result = chooser.showOpenDialog(outer);
            if (result == JFileChooser.APPROVE_OPTION) {
                tfBackupPath.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        outer.add(backupGroup);

        // 导入/导出设置
        JPanel ioGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        ioGroup.setBorder(new TitledBorder("导入/导出设置"));
        JButton exportBtn = new JButton("导出设置");
        JButton importBtn = new JButton("导入设置");
        exportBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            int ret = chooser.showSaveDialog(outer);
            if (ret == JFileChooser.APPROVE_OPTION) {
                java.io.File f = chooser.getSelectedFile();
                // 在后台执行文件写入，避免阻塞 EDT
                ThreadPools.getRefreshExecutor().execute(() -> {
                    try {
                        JsonObject out = new JsonObject();
                        // basic lists
                        out.addProperty("stocks", pc.getValue("key_stocks", ""));
                        out.addProperty("funds", pc.getValue("key_funds", ""));
                        out.addProperty("coins", pc.getValue("key_coins", ""));
                        out.addProperty("important", pc.getValue("key_important_stocks", ""));

                        // groups
                        com.google.gson.JsonObject groups = new com.google.gson.JsonObject();
                        com.google.gson.JsonArray gnames = new com.google.gson.JsonArray();
                        java.util.List<String> names = GroupManager.getInstance().getGroupNames();
                        for (String n : names) gnames.add(n);
                        groups.add("names", gnames);
                        com.google.gson.JsonObject items = new com.google.gson.JsonObject();
                        for (String n : names) {
                            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                            java.util.List<String> codes = GroupManager.getInstance().getStocksInGroup(n);
                            for (String c : codes) arr.add(c);
                            items.add(n, arr);
                        }
                        groups.add("items", items);
                        out.add("groups", groups);

                        // columns
                        JsonObject cols = new JsonObject();
                        String[] colKeys = {"key_stock_col_high","key_stock_col_low","key_stock_col_sell1","key_stock_col_buy1","key_stock_col_time","key_stock_col_change","key_stock_col_change_pct","key_stock_col_now","key_stock_col_income_pct","key_stock_col_income","key_stock_col_position_cost","key_stock_col_position_value","key_stock_col_day_pnl","key_stock_col_position_ratio","key_stock_col_open","key_stock_col_volume","key_stock_col_amount","key_stock_col_turnover","key_stock_col_pe","key_stock_col_amplitude","key_stock_col_volRatio","totalValue","flowValue","outerDisc","innerDisc"};
                        for (String k : colKeys) cols.addProperty(k, pc.getBoolean(k, true));
                        out.add("columns", cols);

                        // settings (booleans + strings)
                        JsonObject settings = buildExportJson().getAsJsonObject("settings");
                        out.add("settings", settings);

                        java.nio.file.Files.write(f.toPath(), out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        SwingUtilities.invokeLater(() -> LogUtil.notify("导出成功: " + f.getAbsolutePath(), true));
                    } catch (Exception ex) { SwingUtilities.invokeLater(() -> LogUtil.notify("导出失败: " + ex.getMessage(), false)); }
                });
            }
        });
        importBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            int ret = chooser.showOpenDialog(outer);
            if (ret == JFileChooser.APPROVE_OPTION) {
                java.io.File f = chooser.getSelectedFile();
                // 在后台读取文件并解析，解析后在 EDT 更新 UI 与应用配置
                ThreadPools.getRefreshExecutor().execute(() -> {
                    try {
                        String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                        JsonObject in = JsonParser.parseString(txt).getAsJsonObject();
                        SwingUtilities.invokeLater(() -> {
                            try {
                                        if (in.has("stocks")) PropertiesComponent.getInstance().setValue("key_stocks", in.get("stocks").getAsString());
                                        if (in.has("funds")) PropertiesComponent.getInstance().setValue("key_funds", in.get("funds").getAsString());
                                        if (in.has("coins")) PropertiesComponent.getInstance().setValue("key_coins", in.get("coins").getAsString());
                                        if (in.has("important")) PropertiesComponent.getInstance().setValue("key_important_stocks", in.get("important").getAsString());
                                        if (in.has("groups")) {
                                            try {
                                                com.google.gson.JsonObject g = in.getAsJsonObject("groups");
                                                if (g.has("names")) {
                                                    com.google.gson.JsonArray arr = g.getAsJsonArray("names");
                                                    java.util.List<String> names = new java.util.ArrayList<>();
                                                    for (com.google.gson.JsonElement e2 : arr) names.add(e2.getAsString());
                                                    // overwrite group names
                                                    GroupManager.getInstance().reorderGroups(names);
                                                }
                                                if (g.has("items")) {
                                                    com.google.gson.JsonObject items = g.getAsJsonObject("items");
                                                    for (java.util.Map.Entry<String, com.google.gson.JsonElement> en : items.entrySet()) {
                                                        String gname = en.getKey();
                                                        com.google.gson.JsonArray codes = en.getValue().getAsJsonArray();
                                                        java.util.List<String> codesList = new java.util.ArrayList<>();
                                                        for (com.google.gson.JsonElement c : codes) codesList.add(c.getAsString());
                                                        GroupManager.getInstance().setStocksInGroup(gname, codesList);
                                                    }
                                                }
                                            } catch (Exception ignore) {}
                                        }
                                        if (in.has("columns")) {
                                            JsonObject cols = in.getAsJsonObject("columns");
                                            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : cols.entrySet()) {
                                                try { boolean v = entry.getValue().getAsBoolean(); PropertiesComponent.getInstance().setValue(entry.getKey(), v); } catch (Exception ignore) {}
                                            }
                                        }
                                        if (in.has("settings")) {
                                            try {
                                                JsonObject sets = in.getAsJsonObject("settings");
                                                for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : sets.entrySet()) {
                                                    try { PropertiesComponent.getInstance().setValue(entry.getKey(), entry.getValue().getAsString()); } catch (Exception ignore) {}
                                                }
                                            } catch (Exception ignore) {}
                                        }
                                // 更新界面上的文本内容、勾选框和分组页签设置
                                if (textAreaFund != null) textAreaFund.setText(String.join(System.lineSeparator(), parseInputText(PropertiesComponent.getInstance().getValue("key_funds", ""))));
                                if (textAreaStock != null) textAreaStock.setText(String.join(System.lineSeparator(), parseInputStockText(PropertiesComponent.getInstance().getValue("key_stocks", ""))));
                                if (textAreaCoin != null) textAreaCoin.setText(String.join(System.lineSeparator(), parseInputText(PropertiesComponent.getInstance().getValue("key_coins", ""))));
                                if (cbColHigh != null) cbColHigh.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_high", true));
                                if (cbColLow != null) cbColLow.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_low", true));
                                if (cbColSell1 != null) cbColSell1.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_sell1", true));
                                if (cbColBuy1 != null) cbColBuy1.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_buy1", true));
                                if (cbColTime != null) cbColTime.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_time", true));
                                if (cbColChange != null) cbColChange.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_change", true));
                                if (cbColChangePct != null) cbColChangePct.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_change_pct", true));
                                if (cbColNow != null) cbColNow.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_now", true));
                                if (cbColIncomePct != null) cbColIncomePct.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_income_pct", true));
                                if (cbColIncome != null) cbColIncome.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_income", true));
                                if (cbColPosCost != null) cbColPosCost.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_position_cost", true));
                                if (cbColPosValue != null) cbColPosValue.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_position_value", true));
                                if (cbColDayPnl != null) cbColDayPnl.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_day_pnl", true));
                                if (cbColPosRatio != null) cbColPosRatio.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_position_ratio", true));
                                if (cbColOpen != null) cbColOpen.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_open", true));
                                if (cbColVolume != null) cbColVolume.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_volume", true));
                                if (cbColAmount != null) cbColAmount.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_amount", true));
                                if (cbColTurnover != null) cbColTurnover.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_turnover", true));
                                if (cbColPE != null) cbColPE.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_pe", true));
                                if (cbColAmplitude != null) cbColAmplitude.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_amplitude", true));
                                if (cbColVolRatio != null) cbColVolRatio.setSelected(PropertiesComponent.getInstance().getBoolean("key_stock_col_volRatio", true));
                                if (cbAmountWan != null) cbAmountWan.setSelected(PropertiesComponent.getInstance().getBoolean("key_amount_wan", false));
                                if (cbShowPositions != null) cbShowPositions.setSelected(PropertiesComponent.getInstance().getBoolean("key_show_positions", true));
                                if (cbShowImportant != null) cbShowImportant.setSelected(PropertiesComponent.getInstance().getBoolean("key_show_important_area", true));
                                if (accountCountField != null) accountCountField.setText(PropertiesComponent.getInstance().getValue("key_account_count", "1"));
                                if (riskUpPercentField != null) riskUpPercentField.setText(PropertiesComponent.getInstance().getValue("key_risk_up_percent", "10"));
                                if (riskDownPercentField != null) riskDownPercentField.setText(PropertiesComponent.getInstance().getValue("key_risk_down_percent", "10"));
                                if (tfGroupTabMinWidth != null) tfGroupTabMinWidth.setText(PropertiesComponent.getInstance().getValue("key_group_tab_min_width", "60"));
                                if (tfGroupTabMaxWidth != null) tfGroupTabMaxWidth.setText(PropertiesComponent.getInstance().getValue("key_group_tab_max_width", "160"));
                                if (tfGroupTabTruncateLen != null) tfGroupTabTruncateLen.setText(PropertiesComponent.getInstance().getValue("key_group_tab_truncate_len", "12"));
                                if (tfGroupTabWrapCount != null) tfGroupTabWrapCount.setText(PropertiesComponent.getInstance().getValue("key_group_tab_wrap_count", "0"));
                                if (cbBackupEnabled != null) cbBackupEnabled.setSelected(PropertiesComponent.getInstance().getBoolean(ConfigKeys.KEY_BACKUP_ENABLED, false));
                                if (tfBackupPath != null) tfBackupPath.setText(PropertiesComponent.getInstance().getValue(ConfigKeys.KEY_BACKUP_PATH, getDefaultBackupPath()));
                                if (inputProxy != null) inputProxy.setText(PropertiesComponent.getInstance().getValue("key_proxy", ""));
                                if (tfImageCacheDir != null) tfImageCacheDir.setText(PropertiesComponent.getInstance().getValue("key_imagecache_dir", ""));
                                if (tfImageCacheTTL != null) tfImageCacheTTL.setText(PropertiesComponent.getInstance().getValue("key_imagecache_ttl_hours", "24"));
                                if (tfImageCacheCleanupInterval != null) tfImageCacheCleanupInterval.setText(PropertiesComponent.getInstance().getValue("key_imagecache_cleanup_interval_minutes", String.valueOf(TimeUnit.DAYS.toMinutes(1))));
                                if (tfImageCacheMaxMem != null) tfImageCacheMaxMem.setText(PropertiesComponent.getInstance().getValue("key_imagecache_max_mem", "50"));
                                if (tfHttpMaxRetries != null) tfHttpMaxRetries.setText(PropertiesComponent.getInstance().getValue("key_http_max_retries", "2"));
                                if (tfHttpRetryBackoff != null) tfHttpRetryBackoff.setText(PropertiesComponent.getInstance().getValue("key_http_retry_backoff_millis", "300"));
                                if (cbHttpMetricsEnable != null) cbHttpMetricsEnable.setSelected(PropertiesComponent.getInstance().getBoolean("key_http_metrics_enabled", false));
                                if (tfHttpMetricsInterval != null) tfHttpMetricsInterval.setText(PropertiesComponent.getInstance().getValue("key_http_metrics_interval_seconds", "300"));
                                if (cbImageCacheEnable != null) cbImageCacheEnable.setSelected(PropertiesComponent.getInstance().getBoolean("key_imagecache_enabled", true));
                                if (checkboxLog != null) checkboxLog.setSelected(PropertiesComponent.getInstance().getBoolean("key_close_log", false));
                                if (checkboxF7 != null) checkboxF7.setSelected(PropertiesComponent.getInstance().getBoolean("key_close_f7", false));
                                if (checkBoxTableStriped != null) checkBoxTableStriped.setSelected(PropertiesComponent.getInstance().getBoolean("key_table_striped", false));
                                if (checkbox != null) checkbox.setSelected(!PropertiesComponent.getInstance().getBoolean("key_colorful", false));
                                if (stockSourceComboBox != null) {
                                    String source = PropertiesComponent.getInstance().getValue("key_stocks_source", "tencent");
                                    if ("sina".equals(source)) stockSourceComboBox.setSelectedItem("新浪接口"); else stockSourceComboBox.setSelectedItem("腾讯接口");
                                }
                                if (cbTabFund != null) cbTabFund.setSelected(!PropertiesComponent.getInstance().getBoolean("key_tab_hide_fund", false));
                                if (cbTabStock != null) cbTabStock.setSelected(!PropertiesComponent.getInstance().getBoolean("key_tab_hide_stock", false));
                                if (cbTabCoin != null) cbTabCoin.setSelected(!PropertiesComponent.getInstance().getBoolean("key_tab_hide_coin", false));
                                if (cbRiskAlert != null) cbRiskAlert.setSelected(PropertiesComponent.getInstance().getBoolean("key_risk_alert", false));
                                // 应用并刷新
                                try { apply(); LogUtil.notify("导入并应用成功", true); } catch (Exception ex) { LogUtil.notify("导入失败: " + ex.getMessage(), false); }
                            } catch (Exception ex) { LogUtil.notify("导入处理失败: " + ex.getMessage(), false); }
                        });
                    } catch (Exception ex) { SwingUtilities.invokeLater(() -> LogUtil.notify("读取导入文件失败: " + ex.getMessage(), false)); }
                });
            }
        });
        ioGroup.add(exportBtn); ioGroup.add(importBtn);
        outer.add(ioGroup);

        // 标签页显示
        JPanel tabGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        tabGroup.setBorder(new TitledBorder("标签页显示（勾选=显示）"));
        cbTabFund = new JCheckBox("基金", !pc.getBoolean("key_tab_hide_fund", false));
        cbTabStock = new JCheckBox("股票", !pc.getBoolean("key_tab_hide_stock", false));
        cbTabCoin = new JCheckBox("加密货币", !pc.getBoolean("key_tab_hide_coin", false));
        tabGroup.add(cbTabFund); tabGroup.add(cbTabStock); tabGroup.add(cbTabCoin);
        // 为标签页复选框添加即时保存并应用（避免用户不点 Apply 时设置不生效）
        cbTabFund.addActionListener(e -> {
            try { ConfigManager.getInstance().saveBoolean("key_tab_hide_fund", !cbTabFund.isSelected()); } catch (Throwable ignore) {}
            SwingUtilities.invokeLater(() -> { try { StockWindow.apply(); } catch (Throwable ignore) {} });
        });
        cbTabStock.addActionListener(e -> {
            try { ConfigManager.getInstance().saveBoolean("key_tab_hide_stock", !cbTabStock.isSelected()); } catch (Throwable ignore) {}
            SwingUtilities.invokeLater(() -> { try { StockWindow.apply(); } catch (Throwable ignore) {} });
        });
        cbTabCoin.addActionListener(e -> {
            try { ConfigManager.getInstance().saveBoolean("key_tab_hide_coin", !cbTabCoin.isSelected()); } catch (Throwable ignore) {}
            SwingUtilities.invokeLater(() -> { try { StockWindow.apply(); } catch (Throwable ignore) {} });
        });
        outer.add(tabGroup);

        // 分组页签显示设置（最小宽度/最大宽度/截断长度）
        JPanel groupTabSettings = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        groupTabSettings.setBorder(new TitledBorder("分组页签显示设置"));
        groupTabSettings.add(new JLabel("最小宽度:"));
        groupTabSettings.add(tfGroupTabMinWidth);
        groupTabSettings.add(new JLabel(" 最大宽度:"));
        groupTabSettings.add(tfGroupTabMaxWidth);
        groupTabSettings.add(new JLabel(" 截断长度:"));
        groupTabSettings.add(tfGroupTabTruncateLen);
        groupTabSettings.add(new JLabel(" 自动换行数量:"));
        groupTabSettings.add(tfGroupTabWrapCount);
        outer.add(groupTabSettings);

        return outer;
    }

    private void updateMetricsOnce() {
        try {
            boolean s = quartz.QuartzManager.isJobScheduledFor("Stock");
            boolean f = quartz.QuartzManager.isJobScheduledFor("Fund");
            boolean c = quartz.QuartzManager.isJobScheduledFor("Coin");
            String http = "";
            try { http = utils.HttpClientPool.getHttpClient().getMetricsAsString(); } catch (Throwable ignore) { http = "error"; }
            final String httpTxt = http;
            SwingUtilities.invokeLater(() -> {
                try {
                    lblStockTaskStatus.setText("Stock: " + (s ? "已调度" : "未调度"));
                    lblFundTaskStatus.setText("Fund: " + (f ? "已调度" : "未调度"));
                    lblCoinTaskStatus.setText("Coin: " + (c ? "已调度" : "未调度"));
                    lblHttpMetrics.setText("HTTP: " + httpTxt);
                } catch (Throwable ignore) {}
            });
        } catch (Throwable ignore) {}
    }

    @Override public void disposeUIResources() {
        try { if (metricsUpdaterFuture != null) { metricsUpdaterFuture.cancel(false); metricsUpdaterFuture = null; } } catch (Throwable ignore) {}
    }

    private static JCheckBox mkCb(String text, PropertiesComponent pc) {
        String key = "key_stock_col_" + (text.equals("涨跌额") ? "change" :
                text.equals("涨跌幅") ? "change_pct" : text.equals("当前价") ? "now" :
                text.equals("收益率") ? "income_pct" : text.equals("收益") ? "income" :
                text.equals("最高价") ? "high" : text.equals("最低价") ? "low" :
                text.equals("卖一") ? "sell1" : text.equals("买一") ? "buy1" :
                text.equals("持仓成本") ? "position_cost" : text.equals("持仓市值") ? "position_value" :
                text.equals("当日盈亏") ? "day_pnl" : text.equals("仓位") ? "position_ratio" :
                text.equals("今开") ? "open" : text.equals("成交量") ? "volume" :
                text.equals("成交额") ? "amount" : text.equals("换手率") ? "turnover" :
                text.equals("市盈率") ? "pe" : text.equals("振幅") ? "amplitude" :
            text.equals("量比") ? "volRatio" :
            text.equals("总市值(亿)") ? "totalValue" : text.equals("流通市值/亿") ? "flowValue" :
            text.equals("外盘(亿)") ? "outerDisc" : text.equals("内盘(亿)") ? "innerDisc" : "time");
        JCheckBox cb = new JCheckBox(text, pc.getBoolean(key, true));
        // 保存对应的配置 key 供外部监听器使用
        cb.putClientProperty("configKey", key);
        return cb;
    }

    private void testProxy(String proxy) {
        if (proxy.indexOf('：') > 0) { LogUtil.notify("别用中文分割符啊!", false); return; }
        ThreadPools.getRefreshExecutor().execute(() -> {
            HttpClientPool.getHttpClient().buildHttpClient(proxy);
            try {
                HttpClientPool.getHttpClient().get("https://www.baidu.com");
                SwingUtilities.invokeLater(() -> LogUtil.notify("代理测试成功!请保存", true));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> LogUtil.notify("测试代理异常!", false));
            }
        });
    }

    public static List<String> getConfigList(String key, String split) {
        String v = PropertiesComponent.getInstance().getValue(key);
        if (StringUtils.isEmpty(v)) return new ArrayList<>();
        Set<String> set = new LinkedHashSet<>();
        for (String c : v.split(split)) if (!c.isEmpty()) set.add(c.trim());
        return new ArrayList<>(set);
    }

    public static List<String> getConfigList(String key) {
        String v = PropertiesComponent.getInstance().getValue(key);
        if (StringUtils.isEmpty(v)) return new ArrayList<>();
        Set<String> set = new LinkedHashSet<>();
        String[] codes = v.contains(";") ? v.split("[;]") : v.split("[,，]");
        for (String c : codes) {
            if (!c.isEmpty()) {
                if ("key_stocks".equals(key)) set.add(StockUtils.autoCompleteCode(c));
                else set.add(c);
            }
        }
        return new ArrayList<>(set);
    }

    private String checkConfig() {
        StringBuilder sb = new StringBuilder();
        if (cronExpressionFund != null)
            sb.append(getConfigList(cronExpressionFund.getText(), ";").stream().map(s ->
                QuartzManager.checkCronExpression(s) ? "" : "Fund Cron[" + s + "]无效; ").collect(Collectors.joining()));
        if (cronExpressionStock != null)
            sb.append(getConfigList(cronExpressionStock.getText(), ";").stream().map(s ->
                QuartzManager.checkCronExpression(s) ? "" : "Stock Cron[" + s + "]无效; ").collect(Collectors.joining()));
        if (cronExpressionCoin != null)
            sb.append(getConfigList(cronExpressionCoin.getText(), ";").stream().map(s ->
                QuartzManager.checkCronExpression(s) ? "" : "Coin Cron[" + s + "]无效; ").collect(Collectors.joining()));
        return sb.toString();
    }

    private String getDefaultBackupPath() {
        String desktop = System.getProperty("user.home");
        if (desktop == null) desktop = ".";
        java.io.File d = new java.io.File(desktop, "Desktop");
        if (d.exists() && d.isDirectory()) return d.getAbsolutePath();
        return desktop;
    }

    private void scheduleDailyBackup() {
        try {
            if (metricsUpdaterFuture != null) {
                metricsUpdaterFuture.cancel(false);
                metricsUpdaterFuture = null;
            }
            if (!PropertiesComponent.getInstance().getBoolean(ConfigKeys.KEY_BACKUP_ENABLED, false)) return;
            String backupPath = PropertiesComponent.getInstance().getValue(ConfigKeys.KEY_BACKUP_PATH, getDefaultBackupPath());
            if (StringUtils.isBlank(backupPath)) return;
            java.io.File folder = new java.io.File(backupPath);
            if (!folder.exists() || !folder.isDirectory()) return;
            java.util.Calendar now = java.util.Calendar.getInstance();
            java.util.Calendar next = (java.util.Calendar) now.clone();
            next.set(java.util.Calendar.HOUR_OF_DAY, 9);
            next.set(java.util.Calendar.MINUTE, 0);
            next.set(java.util.Calendar.SECOND, 0);
            next.set(java.util.Calendar.MILLISECOND, 0);
            if (!next.after(now)) next.add(java.util.Calendar.DAY_OF_MONTH, 1);
            long delay = next.getTimeInMillis() - now.getTimeInMillis();
            metricsUpdaterFuture = ThreadPools.getScheduledExecutor().schedule(() -> {
                try {
                    java.io.File file = new java.io.File(folder, "leeks-settings-backup-" +
                            new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".json");
                    java.nio.file.Files.write(file.toPath(), buildExportJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    LogUtil.notify("自动备份已保存: " + file.getAbsolutePath(), true);
                } catch (Exception ignore) {
                    LogUtil.info("自动备份失败: " + ignore.getMessage());
                } finally {
                    scheduleDailyBackup();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {}
    }

    private JsonObject buildExportJson() {
        JsonObject out = new JsonObject();
        PropertiesComponent pc = PropertiesComponent.getInstance();
        out.addProperty("key_stocks", pc.getValue("key_stocks", ""));
        out.addProperty("key_funds", pc.getValue("key_funds", ""));
        out.addProperty("key_coins", pc.getValue("key_coins", ""));
        out.addProperty("key_important_stocks", pc.getValue("key_important_stocks", ""));
        out.addProperty("key_backup_enabled", String.valueOf(pc.getBoolean(ConfigKeys.KEY_BACKUP_ENABLED, false)));
        out.addProperty("key_backup_path", pc.getValue(ConfigKeys.KEY_BACKUP_PATH, getDefaultBackupPath()));
        return out;
    }
}
