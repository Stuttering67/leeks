package handler;

import bean.StockBean;
import org.apache.commons.lang3.StringUtils;
import utils.HttpClientPool;
import utils.LogUtil;

import javax.swing.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TencentStockHandler extends StockRefreshHandler {
    private String urlPara;
    private HashMap<String, String[]> codeMap;
    private JLabel refreshTimeLabel;


    public TencentStockHandler(JTable table1, JLabel refreshTimeLabel) {
        super(table1);
        this.refreshTimeLabel = refreshTimeLabel;
    }

    @Override
    public void handle(List<String> code) {

        //LogUtil.info("Leeks 更新Stock编码数据.");
//        clearRow();
        if (code.isEmpty()) {
            return;
        }

        //股票编码，英文分号分隔（成本价和成本接在编码后用逗号分隔）
        List<String> codeList = new ArrayList<>();
        codeMap = new HashMap<>();
        for (String str : code) {
            //兼容原有设置
            String[] strArray;
            if (str.contains(",")) {
                strArray = str.split(",", -1);
            } else {
                strArray = new String[]{str};
            }
            codeList.add(strArray[0]);
            codeMap.put(strArray[0], strArray);
        }

        urlPara = String.join(",", codeList);
        stepAction();

    }

    @Override
    public void stopHandle() {
        LogUtil.info("Leeks 准备停止更新Stock编码数据.");
        try { shutdownUpdateBatchTimer(); } catch (Exception ignore) {}
    }

    private String doubleOrDash(String val) {
        if (val == null || val.isEmpty() || "--".equals(val)) return "--";
        try { return new BigDecimal(val).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException e) { return "--"; }
    }

    /** 万手转亿股 */
    private String volToYiD(String val) {
        if (val == null || val.isEmpty() || "--".equals(val)) return "--";
        try { double d = Double.parseDouble(val) / 100.0; return d >= 0.01 ? String.format("%.2f", d) : String.valueOf(d); }
        catch (NumberFormatException e) { return "--"; }
    }

    /** 亿 → 亿，去掉多余的尾零 */
    private String formatFV(String val) {
        if (val == null || val.isEmpty()) return "--";
        try { return new BigDecimal(val).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException e) { return "--"; }
    }

    private void stepAction() {
        if (StringUtils.isEmpty(urlPara)) {
            return;
        }
        // 拆分批次，每批最多 20 只股票，避免 URL 过长导致请求失败
        String[] allCodes = urlPara.split(",");
        int batchSize = 20;
        StringBuilder allResults = new StringBuilder();
        for (int i = 0; i < allCodes.length; i += batchSize) {
            int end = Math.min(i + batchSize, allCodes.length);
            String batchUrl = String.join(",", java.util.Arrays.copyOfRange(allCodes, i, end));
            try {
                String result = HttpClientPool.getHttpClient().get("https://qt.gtimg.cn/q=" + batchUrl);
                if (StringUtils.isNotBlank(result)) allResults.append(result).append("\n");
                // 批次间加短暂延迟避免请求过快
                if (end < allCodes.length) Thread.sleep(100);
            } catch (Exception e) {
                LogUtil.info("请求异常: " + e.getMessage() + " (批次" + (i / batchSize + 1) + ")");
            }
        }
        if (allResults.length() > 0) {
            parse(allResults.toString());
            updateUI();
        }
    }

    private void parse(String result) {
        String[] lines = result.split("\n");
        for (String line : lines) {
            if (StringUtils.isBlank(line)) continue;
            int eqIdx = line.indexOf("=");
            int usIdx = line.indexOf("_");
            if (usIdx < 0 || eqIdx < 0 || eqIdx <= usIdx) continue;
            try {
                String code = line.substring(usIdx + 1, eqIdx);
                int start = eqIdx + 2;
                int end = line.length() - 2;
                if (start >= end || start >= line.length()) continue;
                String dataStr = line.substring(start, end);
                String[] values = dataStr.split("~");
                if (values.length < 35) continue;
                StockBean bean = new StockBean(code, codeMap);
                bean.setName(values[1]);
                bean.setNow(values[3]);
                bean.setOpen(values[5]);        // 今开
                bean.setPreClose(values[4]);    // 昨收
                bean.setChange(values[31]);
                bean.setChangePercent(values[32]);
                bean.setSpeed(doubleOrDash(values[62]));      // 涨速
                bean.setTime(values[30]);
                bean.setMax(values[33]);
                bean.setMin(values[34]);
                bean.setVolume(values[6]);      // 成交量(手)
                // 成交额: API 返回为“万”为单位（values[37]），统一转换为元后存入 bean
                try {
                    if (values[37] == null || values[37].isEmpty() || "--".equals(values[37])) {
                        bean.setAmount("--");
                    } else {
                        java.math.BigDecimal amtWan = new java.math.BigDecimal(values[37]);
                        java.math.BigDecimal amtYuan = amtWan.multiply(new java.math.BigDecimal("10000")).setScale(2, RoundingMode.HALF_UP);
                        bean.setAmount(amtYuan.stripTrailingZeros().toPlainString());
                    }
                } catch (Exception ex) {
                    bean.setAmount(values[37]);
                }
                bean.setTurnover(doubleOrDash(values[38]));   // 换手率
                bean.setPe(doubleOrDash(values[39]));         // 市盈率
                bean.setAmplitude(doubleOrDash(values[43]));  // 振幅
                bean.setVolRatio(doubleOrDash(values[46]));   // 量比
                bean.setTotalValue(formatFV(values[44])); // 总市值(亿)
                bean.setFlowValue(formatFV(values[45]));  // 流通市值(亿)
                bean.setOuterDisc(volToYiD(values[7]));   // 外盘(万→亿)
                bean.setInnerDisc(volToYiD(values[8]));   // 内盘(万→亿)
                bean.setBuyOne(values[10]);
                bean.setSellOne(values[20]);

                BigDecimal now = new BigDecimal(values[3]);
                String costPriceStr = bean.getCostPrise();
                if (StringUtils.isNotBlank(costPriceStr)) {
                    try {
                        BigDecimal costPriceDec = new BigDecimal(costPriceStr);
                        BigDecimal incomeDiff = now.add(costPriceDec.negate());
                        if (costPriceDec.compareTo(BigDecimal.ZERO) <= 0) {
                            bean.setIncomePercent("0");
                        } else {
                            BigDecimal incomePercentDec = incomeDiff.divide(costPriceDec, 5, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.TEN).multiply(BigDecimal.TEN)
                                    .setScale(3, RoundingMode.HALF_UP);
                            bean.setIncomePercent(incomePercentDec.toString());
                        }
                        String bondStr = bean.getBonds();
                        if (StringUtils.isNotBlank(bondStr)) {
                            BigDecimal bondDec = new BigDecimal(bondStr);
                            bean.setIncome(incomeDiff.multiply(bondDec)
                                    .setScale(2, RoundingMode.HALF_UP).toString());
                        }
                    } catch (NumberFormatException ignore) {}
                }
                updateData(bean);
            } catch (Exception e) {
                LogUtil.info("解析单行异常: " + e.getMessage());
            }
        }
        // 所有数据加载完毕后，重新计算仓位占比（不再触发全表重绘，单行更新已由 updateRow/setValueAt 触发）
        recalcAllPositionRatios();
        // 通知外部（例如 StockWindow）数据已刷新，以便即时更新持仓统计
        try { notifyDataRefreshed(); } catch (Exception ignore) {}
    }

    public void updateUI() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                refreshTimeLabel.setText(LocalDateTime.now().format(TianTianFundHandler.timeFormatter));
                refreshTimeLabel.setToolTipText("最后刷新时间");
            }
        });
    }


}
