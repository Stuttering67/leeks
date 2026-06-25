package utils;

import java.util.HashMap;

/**
 * @Created by DAIE
 * @Date 2021/3/8 20:26
 * @Description leek面板TABLE工具类
 */
public class WindowUtils {
    //基金表头
    public static final String FUND_TABLE_HEADER_KEY = "fund_table_header_key2"; //移动表头时存储的key
    public static final String FUND_TABLE_HEADER_VALUE = "编码,基金名称,估算涨跌,当日净值,估算净值,持仓成本价,持有份额,收益率,收益,更新时间";
    //股票表头
    public static final String STOCK_TABLE_HEADER_KEY = "stock_table_header_key2"; //移动表头时存储的key
        public static final String STOCK_TABLE_HEADER_VALUE = "编码,股票名称,当日机会,涨跌,涨跌幅,最高价,最低价,今开,昨收,涨速,成交量,成交额,换手率,市盈率,振幅,量比,总市值(亿),流通市值/亿,外盘(亿),内盘(亿),卖一,当前价,买一,成本价,持仓,持仓成本,持仓市值,当日盈亏,仓位,账号,收益率,收益,更新时间,预警,MA5,MA10,MA20,MA30,MA60,MA120,MA240,DIFF,DEA,MACD";
    //货币表头
    public static final String COIN_TABLE_HEADER_KEY = "coin_table_header_key2"; //移动表头时存储的key
    public static final String COIN_TABLE_HEADER_VALUE = "编码,当前价,涨跌,涨跌幅,最高价,最低价,更新时间";

    private static HashMap<String, String> remapPinYinMap = new HashMap<>();

    static {
        remapPinYinMap.put(PinYinUtils.toPinYin("编码"), "编码");
        remapPinYinMap.put(PinYinUtils.toPinYin("基金名称"), "基金名称");
        remapPinYinMap.put(PinYinUtils.toPinYin("估算净值"), "估算净值");
        remapPinYinMap.put(PinYinUtils.toPinYin("估算涨跌"), "估算涨跌");
        remapPinYinMap.put(PinYinUtils.toPinYin("更新时间"), "更新时间");
        remapPinYinMap.put(PinYinUtils.toPinYin("当日净值"), "当日净值");
        remapPinYinMap.put(PinYinUtils.toPinYin("股票名称"), "股票名称");
        remapPinYinMap.put(PinYinUtils.toPinYin("当前价"), "当前价");
        remapPinYinMap.put(PinYinUtils.toPinYin("涨跌"), "涨跌");
        remapPinYinMap.put(PinYinUtils.toPinYin("涨跌幅"), "涨跌幅");
        remapPinYinMap.put(PinYinUtils.toPinYin("最高价"), "最高价");
        remapPinYinMap.put(PinYinUtils.toPinYin("最低价"), "最低价");
        remapPinYinMap.put(PinYinUtils.toPinYin("名称"), "名称");

        remapPinYinMap.put(PinYinUtils.toPinYin("成本价"), "成本价");
        remapPinYinMap.put(PinYinUtils.toPinYin("持仓"), "持仓");
        remapPinYinMap.put(PinYinUtils.toPinYin("收益率"), "收益率");
        remapPinYinMap.put(PinYinUtils.toPinYin("收益"), "收益");

        remapPinYinMap.put(PinYinUtils.toPinYin("持仓成本价"), "持仓成本价");
        remapPinYinMap.put(PinYinUtils.toPinYin("持有份额"), "持有份额");
        remapPinYinMap.put(PinYinUtils.toPinYin("持仓成本"), "持仓成本");
        remapPinYinMap.put(PinYinUtils.toPinYin("持仓市值"), "持仓市值");
        remapPinYinMap.put(PinYinUtils.toPinYin("当日盈亏"), "当日盈亏");
        remapPinYinMap.put(PinYinUtils.toPinYin("本金"), "本金");
        remapPinYinMap.put(PinYinUtils.toPinYin("预警"), "预警");
        remapPinYinMap.put(PinYinUtils.toPinYin("仓位"), "仓位");
        remapPinYinMap.put(PinYinUtils.toPinYin("今开"), "今开");
        remapPinYinMap.put(PinYinUtils.toPinYin("成交量"), "成交量");
        remapPinYinMap.put(PinYinUtils.toPinYin("成交额"), "成交额");
        remapPinYinMap.put(PinYinUtils.toPinYin("换手率"), "换手率");
            remapPinYinMap.put(PinYinUtils.toPinYin("当日机会"), "当日机会");
        remapPinYinMap.put(PinYinUtils.toPinYin("市盈率"), "市盈率");
        remapPinYinMap.put(PinYinUtils.toPinYin("振幅"), "振幅");
        remapPinYinMap.put(PinYinUtils.toPinYin("量比"), "量比");
        remapPinYinMap.put(PinYinUtils.toPinYin("昨收"), "昨收");
        remapPinYinMap.put(PinYinUtils.toPinYin("涨速"), "涨速");
        remapPinYinMap.put(PinYinUtils.toPinYin("总市值"), "总市值");
        remapPinYinMap.put(PinYinUtils.toPinYin("流通市值"), "流通市值");
        remapPinYinMap.put(PinYinUtils.toPinYin("外盘"), "外盘");
        remapPinYinMap.put(PinYinUtils.toPinYin("内盘"), "内盘");
        remapPinYinMap.put(PinYinUtils.toPinYin("总市值(亿)"), "总市值(亿)");
        remapPinYinMap.put(PinYinUtils.toPinYin("流通市值/亿"), "流通市值/亿");
        remapPinYinMap.put(PinYinUtils.toPinYin("外盘(亿)"), "外盘(亿)");
        remapPinYinMap.put(PinYinUtils.toPinYin("内盘(亿)"), "内盘(亿)");
        remapPinYinMap.put("MA5", "MA5");
        remapPinYinMap.put("MA10", "MA10");
        remapPinYinMap.put("MA20", "MA20");
        remapPinYinMap.put("MA30", "MA30");
        remapPinYinMap.put("MA60", "MA60");
        remapPinYinMap.put("MA120", "MA120");
        remapPinYinMap.put("MA240", "MA240");
        remapPinYinMap.put("DIFF", "DIFF");
        remapPinYinMap.put("DEA", "DEA");
        remapPinYinMap.put("MACD", "MACD");
    }


    /**
     * 通过列名 获取该TABLE的列的数组下标
     *
     * @param columnNames 列名数组
     * @param columnName  要获取的列名
     * @return 返回给出列名的数组下标 匹配失败返回-1
     */
    public static int getColumnIndexByName(String[] columnNames, String columnName) {
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i].equals(columnName)) {
                return i;
            }
        }
        //考虑拼音编码

        return -1;
    }

    /**
     * 规范化账号字符串为纯数字（去掉前缀"账号"、非数字字符），返回空字符串表示未设置或默认
     */
    public static String normalizeAccount(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty() || "默认".equals(t)) return "";
        String digits = t.replaceAll("[^0-9]", "");
        return digits == null ? "" : digits;
    }

    public static String remapPinYin(String pinyin) {
        return remapPinYinMap.getOrDefault(pinyin, pinyin);
    }


}
