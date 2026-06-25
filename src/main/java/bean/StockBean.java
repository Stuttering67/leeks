package bean;

import leeks.ConfigManager;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import utils.PinYinUtils;

import java.util.Map;

@Data
public class StockBean {
    private String code;
    private String name;
    private String now;
    private String change;//涨跌
    private String changePercent;
    private String time;
    private String open;     // 今开
    private String volume;   // 成交量(手)
    private String amount;   // 成交额(万)
    private String turnover; // 换手率(%, 仅腾讯)
    private String pe;       // 市盈率(仅腾讯)
    private String amplitude;// 振幅(%, 仅腾讯)
    private String volRatio; // 量比(仅腾讯)
    private String preClose; // 昨收
    private String speed;    // 涨速(%, 仅腾讯)
    private String totalValue; // 总市值(亿, 仅腾讯)
    private String flowValue;  // 流通市值(亿, 仅腾讯)
    private String outerDisc;  // 外盘(仅腾讯)
    private String innerDisc;  // 内盘(仅腾讯)
    private String buyOne;
    private String sellOne;
    private String max;
    private String min;

    private String costPrise;//成本价
    //    private String cost;//成本
    private String bonds;//持仓
    private String incomePercent;//收益率
    private String income;//收益
    private String account;//账号（空/账号1/账号2/账号3）
    private String alertConfig;//预警配置
    // 计算字段: 持仓成本=持仓*成本价, 持仓市值=持仓*当前价, 当日盈亏=持仓*涨跌额, 仓位=该股持仓市值/所属账号总金额
    private String positionCost;
    private String positionValue;
    private String dayPnl;
    private String positionRatio; // 仓位占比
    // K线计算字段: 均线和MACD
    private String ma5, ma10, ma20, ma30, ma60, ma120, ma240;
    private String diff, dea, macd;

    //配置code同时配置成本价和成本值
    public StockBean(String code) {
        if (StringUtils.isNotBlank(code)) {
            applyConfigParts(code.split(",", -1), true);
        } else {
            this.code = code;
        }
        this.name = "--";
    }

    public StockBean(String code, Map<String, String[]> codeMap) {
        this.code = code;
        this.costPrise = null;
        this.bonds = null;
        if (codeMap.containsKey(code)) {
            applyConfigParts(codeMap.get(code), false);
        }
    }

    private void applyConfigParts(String[] codeStr, boolean usePlaceholderForEmpty) {
        if (codeStr == null || codeStr.length == 0) {
            return;
        }
        this.code = codeStr[0];
        if (codeStr.length >= 3) {
            this.costPrise = codeStr[1];
            this.bonds = codeStr[2];
            if (codeStr.length >= 5) this.account = codeStr[4];
            if (codeStr.length >= 7) this.alertConfig = codeStr[6];
            return;
        }
        if (codeStr.length == 2) {
            String second = StringUtils.defaultString(codeStr[1]);
            if (StringUtils.isNumeric(second)) {
                try {
                    if (Long.parseLong(second) % 100 == 0) {
                        this.costPrise = "";
                        this.bonds = second;
                        return;
                    }
                } catch (NumberFormatException ignore) {
                }
            }
            this.costPrise = second;
            this.bonds = "";
            return;
        }
        this.costPrise = usePlaceholderForEmpty ? "--" : null;
        this.bonds = usePlaceholderForEmpty ? "--" : null;
    }


    /**
     * 返回列名的VALUE 用作展示
     *
     * @param colums   字段名
     * @param colorful 隐蔽模式
     * @return 对应列名的VALUE值 无法匹配返回""
     */
    public String getValueByColumn(String colums, boolean colorful) {
        switch (colums) {
            case "编码":
                return this.getCode();
            case "股票名称":
                String displayName = colorful ? this.getName() : PinYinUtils.toPinYin(this.getName());
                // 如果同时设置了持仓与成本，则在名称前加三角标记（避免重复添加）
                boolean hasBonds = StringUtils.isNotBlank(this.getBonds()) && !"--".equals(this.getBonds());
                boolean hasCost = StringUtils.isNotBlank(this.getCostPrise()) && !"--".equals(this.getCostPrise());
                if (hasBonds && hasCost) {
                    if (StringUtils.startsWith(displayName, "🔺")) return displayName;
                    return "🔺" + displayName;
                }
                return displayName;
            case "当前价":
                return this.getNow();
            case "买一":
                return this.getBuyOne();
            case "卖一":
                return this.getSellOne();
            case "涨跌":
                String changeStr = "--";
                if (this.getChange() != null) {
                    changeStr = this.getChange().startsWith("-") ? this.getChange() : "+" + this.getChange();
                }
                return changeStr;
            case "涨跌幅":
                String changePercentStr = "--";
                if (this.getChangePercent() != null) {
                    changePercentStr = this.getChangePercent().startsWith("-") ? this.getChangePercent() : "+" + this.getChangePercent();
                }
                return changePercentStr + "%";
            case "最高价":
                return this.getMax();
            case "最低价":
                return this.getMin();
            case "成本价":
                return this.getCostPrise();
            case "持仓":
                return this.getBonds();
            case "收益率":
                return StringUtils.isBlank(this.getIncomePercent()) ? "" : this.getIncomePercent() + "%";
            case "收益":
                return formatWanIfEnabled(StringUtils.defaultString(this.getIncome()));
            case "持仓成本":
                return formatWanIfEnabled(getPositionCost());
            case "持仓市值":
                return formatWanIfEnabled(getPositionValue());
            case "当日盈亏":
                String dpnl = getDayPnl();
                if (StringUtils.isNotBlank(dpnl) && !dpnl.startsWith("-") && !"--".equals(dpnl)) return formatWanIfEnabled("+" + dpnl);
                return formatWanIfEnabled(StringUtils.defaultString(getDayPnl()));
            case "账号":
                return StringUtils.defaultString(this.getAccount());
            case "预警":
                return StringUtils.isNotBlank(this.getAlertConfig()) ? "✔" : "";
            case "仓位":
                String r = getPositionRatio();
                if (StringUtils.isBlank(r)) return "";
                return r; // ratio already formatted with "%" by calcPositionRatio_inner/recalcAllPositionRatios
            case "今开":
                return StringUtils.defaultIfEmpty(getOpen(), "--");
            case "成交量":
                return formatVol(getVolume());
            case "成交额":
                return formatAmount(getAmount());
            case "换手率":
                String to = getTurnover();
                if (StringUtils.isBlank(to) || "--".equals(to)) return "--";
                return to + "%";
            case "市盈率":
                return StringUtils.defaultIfEmpty(getPe(), "--");
            case "振幅":
                String amp = getAmplitude();
                if (StringUtils.isBlank(amp) || "--".equals(amp)) return "--";
                return amp + "%";
            case "量比":
                return StringUtils.defaultIfEmpty(getVolRatio(), "--");
            case "涨速":
                String sp = getSpeed();
                if (StringUtils.isBlank(sp) || "--".equals(sp)) return "--";
                return sp;
            case "昨收":
                return StringUtils.defaultIfEmpty(getPreClose(), "--");
            case "总市值(亿)":
                return formatAmount(getTotalValue());
            case "流通市值/亿":
                return formatAmount(getFlowValue());
            case "外盘(亿)":
                return StringUtils.defaultIfEmpty(getOuterDisc(), "--");
            case "内盘(亿)":
                return StringUtils.defaultIfEmpty(getInnerDisc(), "--");
            case "MA5":  return StringUtils.defaultIfEmpty(getMa5(), "--");
            case "MA10": return StringUtils.defaultIfEmpty(getMa10(), "--");
            case "MA20": return StringUtils.defaultIfEmpty(getMa20(), "--");
            case "MA30": return StringUtils.defaultIfEmpty(getMa30(), "--");
            case "MA60": return StringUtils.defaultIfEmpty(getMa60(), "--");
            case "MA120": return StringUtils.defaultIfEmpty(getMa120(), "--");
            case "MA240": return StringUtils.defaultIfEmpty(getMa240(), "--");
            case "DIFF": return StringUtils.defaultIfEmpty(getDiff(), "--");
            case "DEA":  return StringUtils.defaultIfEmpty(getDea(), "--");
            case "MACD": return StringUtils.defaultIfEmpty(getMacd(), "--");
            case "更新时间":
                String timeStr = "--";
                if (this.getTime() != null) {
                    timeStr = this.getTime().substring(8);
                }
                return timeStr;
            default:
                return "";

        }
    }

    private static String formatVol(String v) {
        if (StringUtils.isBlank(v) || "--".equals(v)) return "--";
        try { double d = Double.parseDouble(v); return formatNumWithUnit(d); }
        catch (NumberFormatException e) { return v; }
    }
    private static String formatAmount(String v) {
        if (StringUtils.isBlank(v) || "--".equals(v)) return "--";
        try { double d = Double.parseDouble(v); return formatNumWithUnit(d); }
        catch (NumberFormatException e) { return v; }
    }
    /** <1万显示原值，>=1万 以万为单位，>=1亿 以亿为单位，最多2位小数 */
    private static String formatNumWithUnit(double v) {
        if (Math.abs(v) >= 1_0000_0000) {
            double r = v / 1_0000_0000;
            return (r == Math.floor(r) ? String.valueOf((long)r) : String.format("%.2f", r)) + "亿";
        }
        if (Math.abs(v) >= 1_0000) {
            double r = v / 1_0000;
            return (r == Math.floor(r) ? String.valueOf((long)r) : String.format("%.2f", r)) + "万";
        }
        return v == Math.floor(v) ? String.valueOf((long)v) : String.format("%.2f", v);
    }

    /** 将金额数值按"万"单位格式化：>=10000 显示为 X.XX万，否则保留原值 */
    private static String formatWan(String raw) {
        if (StringUtils.isBlank(raw) || "--".equals(raw)) return raw;
        try {
            double v = Double.parseDouble(raw);
            if (Math.abs(v) >= 10000) {
                double r = v / 10000;
                return (r == Math.floor(r) ? String.valueOf((long)r) : String.format("%.2f", r)) + "万";
            }
            return raw;
        } catch (NumberFormatException e) { return raw; }
    }

    /** 根据"金额以万为单位"设置决定是否格式化 */
    public static String formatWanIfEnabled(String raw) {
        if (StringUtils.isBlank(raw) || "--".equals(raw)) return StringUtils.defaultString(raw);
        try {
            if (ConfigManager.getInstance().isAmountInWan()) {
                return formatWan(raw);
            }
        } catch (Exception ignore) {}
        // 不启用时返回原值（含正号前缀处理由调用方负责）
        return StringUtils.defaultString(raw);
    }
}
