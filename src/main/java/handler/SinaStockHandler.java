package handler;

import bean.StockBean;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import org.apache.commons.lang3.StringUtils;
import utils.HttpClientPool;
import utils.LogUtil;

import javax.swing.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SinaStockHandler extends StockRefreshHandler {
    private final String URL = "https://hq.sinajs.cn/list=";
    private final Pattern DEFAULT_STOCK_PATTERN = Pattern.compile("var hq_str_(\\w+?)=\"(.*?)\";");
    private final JLabel refreshTimeLabel;

    public SinaStockHandler(JTable table, JLabel label) {
        super(table);
        this.refreshTimeLabel = label;
    }

    @Override
    public void handle(List<String> code) {
        if (code.isEmpty()) {
            return;
        }

        pollStock(code);
    }

    private void pollStock(List<String> code) {
        //股票编码，英文分号分隔（成本价和成本接在编码后用逗号分隔）
        List<String> codeList = new ArrayList<>();
        Map<String, String[]> codeMap = new HashMap<>();
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

        String params = Joiner.on(",").join(codeList);
        try {
            String res = HttpClientPool.getHttpClient().get(URL + params);
//            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"));
//            System.out.printf("%s,%s%n", time, res);
            handleResponse(res, codeMap);
        } catch (Exception e) {
            LogUtil.info(e.getMessage());
        }
    }

    public void handleResponse(String response, Map<String, String[]> codeMap) {
        List<String> refreshTimeList = new ArrayList<>();
        for (String line : response.split("\n")) {
            Matcher matcher = DEFAULT_STOCK_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String code = matcher.group(1);
            String[] split = matcher.group(2).split(",");
            if (split.length < 32) {
                continue;
            }
            StockBean bean = new StockBean(code, codeMap);
            bean.setName(split[0]);
            BigDecimal now = new BigDecimal(split[3]);
            BigDecimal yesterday = new BigDecimal(split[2]);
            BigDecimal diff = now.add(yesterday.negate());

            bean.setNow(now.toString());
            bean.setChange(diff.toString());
            BigDecimal percent = diff.divide(yesterday, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.TEN)
                    .multiply(BigDecimal.TEN)
                    .setScale(2, RoundingMode.HALF_UP);
            bean.setChangePercent(percent.toString());
            bean.setTime(Strings.repeat("0", 8) + split[31]);
            bean.setMax(split[4]);
            bean.setMin(split[5]);
            bean.setOpen(split[1]);       // 今开
            bean.setPreClose(split[2]);   // 昨收
            bean.setVolume(formatSinaVolume(split[8]));   // 成交量
            bean.setAmount(formatSinaAmount(split[9]));   // 成交额
            bean.setBuyOne(split[10]);
            bean.setSellOne(split[20]);

            String costPriceStr = bean.getCostPrise();
            if (StringUtils.isNotBlank(costPriceStr)) {
                try {
                    BigDecimal costPriceDec = new BigDecimal(costPriceStr);
                    BigDecimal incomeDiff = now.add(costPriceDec.negate());
                    if (costPriceDec.compareTo(BigDecimal.ZERO) <= 0) {
                        bean.setIncomePercent("0");
                    } else {
                        BigDecimal incomePercentDec = incomeDiff.divide(costPriceDec, 5, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.TEN)
                                .multiply(BigDecimal.TEN)
                                .setScale(3, RoundingMode.HALF_UP);
                        bean.setIncomePercent(incomePercentDec.toString());
                    }

                    String bondStr = bean.getBonds();
                    if (StringUtils.isNotBlank(bondStr)) {
                        BigDecimal bondDec = new BigDecimal(bondStr);
                        BigDecimal incomeDec = incomeDiff.multiply(bondDec)
                                .setScale(2, RoundingMode.HALF_UP);
                        bean.setIncome(incomeDec.toString());
                    }
                } catch (NumberFormatException ignore) {
                }
            }

            updateData(bean);
            refreshTimeList.add(split[31]);
        }
        // 所有数据加载完毕后，重新计算仓位占比（不再触发全表重绘，单行更新已由 updateRow/setValueAt 触发）
        recalcAllPositionRatios();

        // 通知外部（例如 StockWindow）数据已刷新，以便即时更新持仓统计
        try { notifyDataRefreshed(); } catch (Exception ignore) {}

        String text = refreshTimeList.stream().sorted().findFirst().orElse("--");
        SwingUtilities.invokeLater(() -> refreshTimeLabel.setText(text));
    }

    private String formatSinaVolume(String v) {
        if (v == null || v.isEmpty() || "--".equals(v)) return "--";
        try { return new BigDecimal(v).movePointLeft(2).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException e) { return v; }
    }
    private String formatSinaAmount(String v) {
        if (v == null || v.isEmpty() || "--".equals(v)) return "--";
        try { return new BigDecimal(v).movePointLeft(6).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException e) { return v; }
    }

    @Override
    public void stopHandle() {
        LogUtil.info("leeks stock 自动刷新关闭!");
        try { shutdownUpdateBatchTimer(); } catch (Exception ignore) {}
    }
}
