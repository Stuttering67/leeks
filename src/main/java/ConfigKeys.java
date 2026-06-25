package leeks;

import utils.WindowUtils;

public class ConfigKeys {
    // 市场配置键
    public static final String KEY_FUNDS = "key_funds";
    public static final String KEY_STOCKS = "key_stocks";
    public static final String KEY_COINS = "key_coins";

    // 表格配置键
    public static final String KEY_TABLE_STRIPED = "key_table_striped";
    public static final String KEY_COLORFUL = "key_colorful";
    // 股票列可见性
    public static final String KEY_STOCK_COL_SHOW_HIGH = "key_stock_col_high";
    public static final String KEY_STOCK_COL_SHOW_LOW = "key_stock_col_low";
    public static final String KEY_STOCK_COL_SHOW_SELL1 = "key_stock_col_sell1";
    public static final String KEY_STOCK_COL_SHOW_BUY1 = "key_stock_col_buy1";
    public static final String KEY_STOCK_COL_SHOW_TIME = "key_stock_col_time";
    // 港股汇率
    public static final String KEY_HK_EXCHANGE_RATE = "key_hk_exchange_rate";

    // 代理配置键
    public static final String KEY_PROXY = "key_proxy";

    // Cron 表达式配置键
    public static final String KEY_CRON_EXPRESSION_FUND = "key_cron_expression_fund";
    public static final String KEY_CRON_EXPRESSION_STOCK = "key_cron_expression_stock";
    public static final String KEY_CRON_EXPRESSION_COIN = "key_cron_expression_coin";

    // 秒级刷新间隔配置（优先级高于 Cron 表达式），值为正整数秒，留空或不存在则不使用
    public static final String KEY_REFRESH_INTERVAL_SECONDS_FUND = "key_refresh_interval_seconds_fund";
    public static final String KEY_REFRESH_INTERVAL_SECONDS_STOCK = "key_refresh_interval_seconds_stock";
    public static final String KEY_REFRESH_INTERVAL_SECONDS_COIN = "key_refresh_interval_seconds_coin";

    // 股票接口配置键
    public static final String KEY_STOCKS_SINA = "key_stocks_sina";
    public static final String KEY_STOCKS_SOURCE = "key_stocks_source"; // tencent / sina / eastmoney

    // 定时备份设置
    public static final String KEY_BACKUP_ENABLED = "key_backup_enabled";
    public static final String KEY_BACKUP_PATH = "key_backup_path";

    // API Token 配置键
    public static final String KEY_ITICK_TOKEN = "key_itick_token";
    public static final String KEY_QOS_APIKEY = "key_qos_apikey";
    public static final String KEY_ZHITU_TOKEN = "key_zhitu_token";

    // 快捷键配置键
    public static final String KEY_CLOSE_F7 = "key_close_f7";

    // 持仓风险预警
    public static final String KEY_RISK_ALERT = "key_risk_alert";

    // 表格头配置键
    public static final String FUND_TABLE_HEADER_KEY = WindowUtils.FUND_TABLE_HEADER_KEY;
    public static final String STOCK_TABLE_HEADER_KEY = WindowUtils.STOCK_TABLE_HEADER_KEY;
    public static final String COIN_TABLE_HEADER_KEY = WindowUtils.COIN_TABLE_HEADER_KEY;
}
