package handler;

import bean.StockBean;
import utils.HttpClientPool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ui.JBColor;
import com.intellij.ui.table.JBTable;
import leeks.ConfigManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import utils.LogUtil;
import utils.PinYinUtils;
import utils.StockKlineCalc;
import utils.WindowUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.text.Collator;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public abstract class StockRefreshHandler extends DefaultTableModel {
    public static String[] columnNames;
    private static final String ORIGINAL_INDEX_COLUMN_NAME = "__original_index__";

    private static String[] canEditColumnNames = new String[]{"成本价", "持仓", "账号"};
    /**
     * 存放【编码】的位置，更新数据时用到
     */
    public int codeColumnIndex;

    private JTable table;
    private boolean colorful = true;
    private static final Color SEP_ROW_BG = new Color(0xFF, 0xE6, 0xC0);
    // MACD 渲染：在零轴附近且接近金叉/死叉时才染色
    private static final double MACD_NEAR_ZERO = 0.5;
    private static final double MACD_CROSS_DELTA = 0.2;
    // 本地涨速回退缓存（code -> last price / last timestamp）
    private final Map<String, Double> lastPriceMap = new HashMap<>();
    private final Map<String, Long> lastPriceTime = new HashMap<>();
    // 缓存用户自定义显示名，避免在渲染器或每行更新时重复解析 JSON
    private static volatile java.util.Map<String, String> customNamesCache = new java.util.concurrent.ConcurrentHashMap<>();
    // 批量更新队列：收集需要刷新的 model 行索引，由 EDT 定时合并调用 fireTableRowsUpdated
    private final java.util.Set<Integer> pendingRowUpdates = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    // 渲染器预计算缓存：按 code 存放计算好的数值，减少 EDT 中重复解析/计算
    private final java.util.concurrent.ConcurrentHashMap<String, PrecomputedRow> precomputedByCode = new java.util.concurrent.ConcurrentHashMap<>();
    // originalIndex 快照映射：用于恢复点击排序前的显示顺序（key: normalized code, value: model row index）
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> originalIndexMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final javax.swing.Timer updateBatchTimer;

    /** 数据刷新后的回调（用于更新持仓统计等） */
    private Runnable onDataRefreshed;

    String url = "https://proxy.finance.qq.com/cgi/cgi-bin/smartbox/search?stockFlag=1&fundFlag=1&app=official_website&c=1&query=";


    static {
        PropertiesComponent instance = PropertiesComponent.getInstance();
        String tableHeaderValue = instance.getValue(WindowUtils.STOCK_TABLE_HEADER_KEY);
        if (StringUtils.isNotBlank(tableHeaderValue)) {
            // 固定表头
            String fixedHeaderValue = WindowUtils.STOCK_TABLE_HEADER_VALUE;

            // 将表头字符串切割为列表
            List<String> memoryHeaderList = new ArrayList<>(Arrays.asList(tableHeaderValue.split(",")));
            List<String> fixedHeaderList = Arrays.asList(fixedHeaderValue.split(","));

            // 找到固定表头中新增的内容
            List<String> newHeaders = fixedHeaderList.stream().filter(header -> !memoryHeaderList.contains(header)).collect(Collectors.toList());

            // 找到固定表头中已经删除的内容
            List<String> removedHeaders = memoryHeaderList.stream().filter(header -> !fixedHeaderList.contains(header)).collect(Collectors.toList());

            // 更新内存表头：添加新增字段，移除已删除字段
            memoryHeaderList.addAll(newHeaders);
            memoryHeaderList.removeAll(removedHeaders);

            // 更新内存表头值
            String updatedHeaderValue = String.join(",", memoryHeaderList);
            instance.setValue(WindowUtils.STOCK_TABLE_HEADER_KEY, updatedHeaderValue);
            tableHeaderValue = updatedHeaderValue;
        } else {
            // 如果内存表头为空，直接使用固定表头
            instance.setValue(WindowUtils.STOCK_TABLE_HEADER_KEY, WindowUtils.STOCK_TABLE_HEADER_VALUE);
            tableHeaderValue = WindowUtils.STOCK_TABLE_HEADER_VALUE;
        }


        String[] configStr = tableHeaderValue.split(",");
        columnNames = new String[configStr.length];
        for (int i = 0; i < configStr.length; i++) {
            columnNames[i] = WindowUtils.remapPinYin(configStr[i]);
        }
    }

    /** 预计算的行数据，供渲染器快速读取 */
    private static class PrecomputedRow {
        boolean hasPosition;
        double change; // 涨跌
        double changePercent; // 涨跌幅（百分比，不含%符号）
        double nowVal;
        double turnover;
        double pe;
        double amplitude;
        double speed;
        double positionCost;
        double positionValue;
        double dayPnl;
        double costPrice;
        double bonds;
        double diff;
        double dea;
        double macd;
        double ma5, ma10, ma20, ma30, ma60, ma120, ma240;
        PrecomputedRow() {
            this.hasPosition = false;
            this.change = Double.NaN; this.changePercent = Double.NaN; this.nowVal = Double.NaN;
            this.turnover = Double.NaN; this.pe = Double.NaN; this.amplitude = Double.NaN; this.speed = Double.NaN;
            this.positionCost = Double.NaN; this.positionValue = Double.NaN; this.dayPnl = Double.NaN;
            this.costPrice = Double.NaN; this.bonds = Double.NaN; this.diff = Double.NaN; this.dea = Double.NaN; this.macd = Double.NaN;
            this.ma5 = this.ma10 = this.ma20 = this.ma30 = this.ma60 = this.ma120 = this.ma240 = Double.NaN;
        }

        double getForColumn(String colName) {
            switch (colName) {
                case "涨跌": return change;
                case "涨跌幅": return changePercent;
                case "收益率": return Double.NaN; // not used generically
                case "当日盈亏": return dayPnl;
                case "换手率": return turnover;
                case "市盈率": return pe;
                case "振幅": return amplitude;
                case "涨速": return speed;
                default: return Double.NaN;
            }
        }
    }

    /** 更新 code 对应的预计算缓存（仅包含简单的字段，避免 EDT 中复杂计算） */
    private void updatePrecomputedForCode(String code, StockBean bean) {
        if (code == null) return;
        PrecomputedRow pr = new PrecomputedRow();
        try {
            String bonds = bean.getBonds();
            if (StringUtils.isNotBlank(bonds) && !"--".equals(bonds)) {
                double bv = NumberUtils.toDouble(bonds, Double.NaN);
                pr.hasPosition = bv > 0.0;
                pr.bonds = bv;
            } else {
                pr.hasPosition = false; pr.bonds = Double.NaN;
            }
        } catch (Exception ignore) { pr.hasPosition = false; pr.bonds = Double.NaN; }
        try { pr.change = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getChange()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.change = Double.NaN; }
        try { pr.changePercent = NumberUtils.toDouble(StringUtils.remove(StringUtils.defaultString(bean.getChangePercent()), "%"), Double.NaN); } catch (Exception ignore) { pr.changePercent = Double.NaN; }
        try { pr.nowVal = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getNow()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.nowVal = Double.NaN; }
        // Fallback: if change not provided, compute as now - preClose when possible
        if (Double.isNaN(pr.change)) {
            try {
                double nowTmp = pr.nowVal;
                double preClose = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getPreClose()), "+", ""), Double.NaN);
                if (Double.isNaN(preClose)) {
                    try { preClose = parsePossiblyUnitNumber(StringUtils.defaultString(bean.getPreClose())); } catch (Exception ignore2) { preClose = Double.NaN; }
                }
                if (!Double.isNaN(nowTmp) && !Double.isNaN(preClose)) {
                    pr.change = nowTmp - preClose;
                }
            } catch (Exception ignore) { pr.change = Double.NaN; }
        }
        try { pr.turnover = NumberUtils.toDouble(StringUtils.remove(StringUtils.defaultString(bean.getTurnover()), "%"), Double.NaN); } catch (Exception ignore) { pr.turnover = Double.NaN; }
        try { pr.pe = NumberUtils.toDouble(StringUtils.defaultString(bean.getPe()), Double.NaN); } catch (Exception ignore) { pr.pe = Double.NaN; }
        try { pr.amplitude = NumberUtils.toDouble(StringUtils.remove(StringUtils.defaultString(bean.getAmplitude()), "%"), Double.NaN); } catch (Exception ignore) { pr.amplitude = Double.NaN; }
        try { pr.speed = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getSpeed()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.speed = Double.NaN; }
        try { pr.costPrice = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getCostPrise()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.costPrice = Double.NaN; }
        try { pr.positionCost = parsePossiblyUnitNumber(StringUtils.defaultString(bean.getPositionCost())); } catch (Exception ignore) { pr.positionCost = Double.NaN; }
        try { pr.positionValue = parsePossiblyUnitNumber(StringUtils.defaultString(bean.getPositionValue())); } catch (Exception ignore) { pr.positionValue = Double.NaN; }
        try { pr.dayPnl = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getDayPnl()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.dayPnl = Double.NaN; }
        try { pr.ma5 = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getMa5()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.ma5 = Double.NaN; }
        try { pr.ma10 = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getMa10()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.ma10 = Double.NaN; }
        try { pr.ma20 = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getMa20()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.ma20 = Double.NaN; }
        try { pr.ma30 = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getMa30()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.ma30 = Double.NaN; }
        try { pr.ma60 = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getMa60()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.ma60 = Double.NaN; }
        try { pr.ma120 = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getMa120()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.ma120 = Double.NaN; }
        try { pr.ma240 = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getMa240()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.ma240 = Double.NaN; }
        try { pr.diff = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getDiff()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.diff = Double.NaN; }
        try { pr.dea = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getDea()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.dea = Double.NaN; }
        try { pr.macd = NumberUtils.toDouble(StringUtils.replace(StringUtils.defaultString(bean.getMacd()), "+", ""), Double.NaN); } catch (Exception ignore) { pr.macd = Double.NaN; }
        // If dayPnl isn't provided, but change and bonds are known, compute dayPnl = change * bonds
        if (Double.isNaN(pr.dayPnl) && !Double.isNaN(pr.change) && !Double.isNaN(pr.bonds)) {
            try { pr.dayPnl = pr.change * pr.bonds; } catch (Exception ignore) { pr.dayPnl = Double.NaN; }
        }
        precomputedByCode.put(code, pr);
    }

    {
        for (int i = 0; i < columnNames.length; i++) {
            if ("编码".equals(columnNames[i])) {
                codeColumnIndex = i;
            }
        }
    }

    public StockRefreshHandler(JTable table) {
        this.table = table;
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        // Fix tree row height
        FontMetrics metrics = table.getFontMetrics(table.getFont());
        table.setRowHeight(Math.max(table.getRowHeight(), metrics.getHeight()));
        setColumnIdentifiers(getColumnIdentifiersWithOriginalIndex());
        table.setModel(this);
        hideOriginalIndexColumn();
        // 初始化 custom names 缓存
        reloadCustomNamesCache();
        // 初始化批量更新定时器（1000ms），在 EDT 上定期合并刷新请求以减少重绘开销
        updateBatchTimer = new javax.swing.Timer(1000, e -> flushPendingRowUpdates());
        updateBatchTimer.setRepeats(true);
        updateBatchTimer.start();
        refreshColorful(!colorful);
    }

    /**
     * 重新加载 PropertiesComponent 中的 key_custom_names 到内存缓存，减少渲染时的 JSON 解析开销
     */
    public static void reloadCustomNamesCache() {
        try {
            String json = com.intellij.ide.util.PropertiesComponent.getInstance().getValue("key_custom_names", "{}");
            com.google.gson.JsonObject obj = null;
            try { obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject(); } catch (Exception ex) { obj = null; }
            java.util.Map<String, String> tmp = new java.util.concurrent.ConcurrentHashMap<>();
            if (obj != null) {
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                    if (e.getValue() != null && !e.getValue().isJsonNull()) tmp.put(e.getKey(), e.getValue().getAsString());
                }
            }
            customNamesCache = tmp;
        } catch (Exception ignore) {}
    }

    public void setupAccountColumnEditor() {
        // 账号列使用 JComboBox 下拉编辑
        int accountColIdx = WindowUtils.getColumnIndexByName(columnNames, "账号");
        if (accountColIdx < 0) return;
        // 将 model 列索引转换为 view 索引，避免列可视顺序/重排导致的错误
        int viewIdx = -1;
        try { viewIdx = table.convertColumnIndexToView(accountColIdx); } catch (Exception ignore) { viewIdx = -1; }
        if (viewIdx < 0) {
            // fallback: 遍历 TableColumn 查找匹配的 modelIndex
            javax.swing.table.TableColumnModel tcm = table.getColumnModel();
            for (int i = 0; i < tcm.getColumnCount(); i++) {
                try { if (tcm.getColumn(i).getModelIndex() == accountColIdx) { viewIdx = i; break; } } catch (Exception ignore) {}
            }
        }
        if (viewIdx < 0 || viewIdx >= table.getColumnModel().getColumnCount()) return;
        javax.swing.table.TableColumn col = table.getColumnModel().getColumn(viewIdx);
        // 使用下拉 JComboBox 编辑器，单击即可弹出选择列表
        javax.swing.JComboBox<String> sample = new javax.swing.JComboBox<>();
        DefaultCellEditor comboEditor = new DefaultCellEditor(sample) {
            @Override
            public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
                JComboBox<String> combo = (JComboBox<String>) getComponent();
                combo.removeAllItems();
                int ac = 1;
                try { ac = Math.max(1, Math.min(3, Integer.parseInt(getAccountCount()))); } catch (Exception ignore) { ac = 1; }
                for (int i = 0; i < ac; i++) combo.addItem(String.valueOf(i + 1));
                String cur = value == null ? "" : value.toString();
                String curNorm = WindowUtils.normalizeAccount(cur);
                if (curNorm.trim().isEmpty()) combo.setSelectedIndex(0); else combo.setSelectedItem(curNorm);
                SwingUtilities.invokeLater(() -> {
                    try { combo.showPopup(); } catch (Exception ignore) {}
                });
                return combo;
            }

            @Override
            public Object getCellEditorValue() { return ((JComboBox) getComponent()).getSelectedItem(); }
        };
        comboEditor.setClickCountToStart(1);
        col.setCellEditor(comboEditor);
    }

    public void refreshColorful(boolean colorful) {
        if (this.colorful == colorful) {
            return;
        }
        this.colorful = colorful;
        // 刷新表头
        if (colorful) {
            setColumnIdentifiers(getColumnIdentifiersWithOriginalIndex());
        } else {
            setColumnIdentifiers(getColumnIdentifiersWithOriginalIndex(PinYinUtils.toPinYin(columnNames)));
        }
        hideOriginalIndexColumn();
        TableRowSorter<DefaultTableModel> rowSorter = new TableRowSorter<>(this);
        // 数值比较器：解析可能含单位/百分号的数值，非数字或空值视为缺失，缺失的排序放在最后
        Comparator<Object> numericComparator = (o1, o2) -> {
            Double d1 = parseToDoubleOrNull(o1);
            Double d2 = parseToDoubleOrNull(o2);
            boolean n1 = (d1 == null);
            boolean n2 = (d2 == null);
            if (n1 && n2) return 0;
            if (n1) return 1; // 缺失放最后
            if (n2) return -1;
            return d1.compareTo(d2);
        };
        // 字符串比较器：空字符串放最后，使用本地 Collator 做自然语言比较
        Collator coll = Collator.getInstance();
        Comparator<Object> stringComparator = (o1, o2) -> {
            String s1 = Objects.toString(o1, "").trim();
            String s2 = Objects.toString(o2, "").trim();
            boolean e1 = StringUtils.isBlank(s1);
            boolean e2 = StringUtils.isBlank(s2);
            if (e1 && e2) return 0;
            if (e1) return 1;
            if (e2) return -1;
            return coll.compare(s1, s2);
        };
        // 为常见的数值列设置数值比较器
        Arrays.stream("当前价,涨跌,涨跌幅,最高价,最低价,持仓成本,持仓市值,当日盈亏,成交量,成交额,换手率,市盈率,振幅,量比,涨速,总市值(亿),流通市值/亿,外盘(亿),内盘(亿)".split(","))
                .map(name -> WindowUtils.getColumnIndexByName(columnNames, name)).filter(index -> index >= 0)
                .forEach(index -> rowSorter.setComparator(index, numericComparator));
        // 为编码/名称类列设置字符串比较器（将空值放最后）
        int codeIdx = WindowUtils.getColumnIndexByName(columnNames, "编码");
        int nameIdx = WindowUtils.getColumnIndexByName(columnNames, "股票名称");
        if (codeIdx >= 0) rowSorter.setComparator(codeIdx, stringComparator);
        if (nameIdx >= 0) rowSorter.setComparator(nameIdx, stringComparator);
        table.setRowSorter(rowSorter);
        columnColors(colorful);
    }

    /**
     * 从网络更新数据
     *
     * @param code
     */
    public abstract void handle(List<String> code);

    /**
     * 设置表格条纹（斑马线）<br>
     *
     * @param striped true设置条纹
     * @throws RuntimeException 如果table不是{@link JBTable}类型，请自行实现setStriped
     */
    /**
     * 根据配置隐藏/显示指定列
     */
    public void applyColumnVisibility() {
        PropertiesComponent pc = PropertiesComponent.getInstance();
        // 统一语义：勾选=显示（visible=true），不勾选=隐藏（visible=false）
        String[][] colKeys = {
            {"最高价", "key_stock_col_high"},
            {"最低价", "key_stock_col_low"},
            {"卖一",   "key_stock_col_sell1"},
            {"买一",   "key_stock_col_buy1"},
            {"更新时间", "key_stock_col_time"},
            {"涨跌额", "key_stock_col_change"},
            {"涨跌幅", "key_stock_col_change_pct"},
            {"当前价", "key_stock_col_now"},
            {"收益率", "key_stock_col_income_pct"},
            {"收益", "key_stock_col_income"},
            {"持仓成本", "key_stock_col_position_cost"},
            {"持仓市值", "key_stock_col_position_value"},
            {"当日盈亏", "key_stock_col_day_pnl"},
            {"仓位",   "key_stock_col_position_ratio"},
            {"今开",   "key_stock_col_open"},
            {"成交量", "key_stock_col_volume"},
            {"成交额", "key_stock_col_amount"},
            {"MA5",   "key_stock_col_ma5"},
            {"MA10",  "key_stock_col_ma10"},
            {"MA20",  "key_stock_col_ma20"},
            {"MA30",  "key_stock_col_ma30"},
            {"MA60",  "key_stock_col_ma60"},
            {"MA120", "key_stock_col_ma120"},
            {"MA240", "key_stock_col_ma240"},
            {"DIFF",  "key_stock_col_diff"},
            {"DEA",   "key_stock_col_dea"},
            {"MACD",  "key_stock_col_macd"},
        };
        String source = ConfigManager.getInstance().getStockSource();
        boolean isTencent = !"sina".equals(source);
        for (String[] pair : colKeys) {
            int modelIdx = WindowUtils.getColumnIndexByName(columnNames, pair[0]);
            if (modelIdx >= 0) {
                int viewIdx = -1;
                try { viewIdx = table.convertColumnIndexToView(modelIdx); } catch (Exception ignore) { viewIdx = -1; }
                if (viewIdx < 0 || viewIdx >= table.getColumnModel().getColumnCount()) {
                    // fallback: try to find by header value
                    javax.swing.table.TableColumnModel tcm = table.getColumnModel();
                    for (int ci = 0; ci < tcm.getColumnCount(); ci++) {
                        try {
                            Object hv = tcm.getColumn(ci).getHeaderValue();
                            if (hv != null && hv.toString().equals(columnNames[modelIdx])) { viewIdx = ci; break; }
                            if (hv != null && hv.toString().equals(WindowUtils.remapPinYin(columnNames[modelIdx]))) { viewIdx = ci; break; }
                        } catch (Exception ignore) {}
                    }
                }
                if (viewIdx >= 0 && viewIdx < table.getColumnModel().getColumnCount()) {
                    boolean visible = pc.getBoolean(pair[1], true);
                    javax.swing.table.TableColumn col = table.getColumnModel().getColumn(viewIdx);
                    col.setMinWidth(visible ? 30 : 0);
                    col.setMaxWidth(visible ? Integer.MAX_VALUE : 0);
                    col.setPreferredWidth(visible ? 75 : 0);
                }
            }
        }
        // 腾讯专属列：仅腾讯接口时显示
        String[][] tencentOnlyKeys = {
            {"换手率", "key_stock_col_turnover"},
            {"市盈率", "key_stock_col_pe"},
            {"振幅",   "key_stock_col_amplitude"},
            {"量比",   "key_stock_col_volRatio"},
            {"涨速",   "key_stock_col_speed"},
            {"总市值(亿)",  "key_stock_col_totalValue"},
            {"流通市值/亿", "key_stock_col_flowValue"},
            {"外盘(亿)",    "key_stock_col_outerDisc"},
            {"内盘(亿)",    "key_stock_col_innerDisc"},
        };
        for (String[] pair : tencentOnlyKeys) {
            int modelIdx = WindowUtils.getColumnIndexByName(columnNames, pair[0]);
            if (modelIdx >= 0) {
                int viewIdx = -1;
                try { viewIdx = table.convertColumnIndexToView(modelIdx); } catch (Exception ignore) { viewIdx = -1; }
                if (viewIdx < 0 || viewIdx >= table.getColumnModel().getColumnCount()) {
                    javax.swing.table.TableColumnModel tcm = table.getColumnModel();
                    for (int ci = 0; ci < tcm.getColumnCount(); ci++) {
                        try {
                            Object hv = tcm.getColumn(ci).getHeaderValue();
                            if (hv != null && hv.toString().equals(columnNames[modelIdx])) { viewIdx = ci; break; }
                            if (hv != null && hv.toString().equals(WindowUtils.remapPinYin(columnNames[modelIdx]))) { viewIdx = ci; break; }
                        } catch (Exception ignore) {}
                    }
                }
                if (viewIdx >= 0 && viewIdx < table.getColumnModel().getColumnCount()) {
                    boolean visible = isTencent && pc.getBoolean(pair[1], true);
                    javax.swing.table.TableColumn col = table.getColumnModel().getColumn(viewIdx);
                    col.setMinWidth(visible ? 30 : 0);
                    col.setMaxWidth(visible ? Integer.MAX_VALUE : 0);
                    col.setPreferredWidth(visible ? 75 : 0);
                }
            }
        }
    }

    public void setStriped(boolean striped) {
        if (table instanceof JBTable) {
            ((JBTable) table).setStriped(striped);
        } else {
            throw new RuntimeException("table不是JBTable类型，请自行实现setStriped");
        }
    }

    /** 设置数据刷新完成后的回调 */
    public void setOnDataRefreshed(Runnable callback) { this.onDataRefreshed = callback; }

    /** 子类在完成一次批量数据刷新后应调用此方法，触发外部注册的统计/汇总回调 */
    protected void notifyDataRefreshed() {
        try {
            if (this.onDataRefreshed != null) {
                this.onDataRefreshed.run();
            }
        } catch (Exception ignore) {}
    }

    public void setupTable(List<String> code) {
        for (String s : code) {
            updateData(new StockBean(s));
        }
    }

    /**
     * 为外部统计提供的轻量快照接口：返回指定 codes 的当前价格、涨跌与当日盈亏（数组: [nowVal, change, dayPnl])。
     * 使用 precomputedByCode 缓存以避免在 EDT 上读取表格数据。
     */
    public java.util.Map<String, double[]> getSnapshotForCodes(java.util.Collection<String> codes) {
        java.util.Map<String, double[]> out = new java.util.HashMap<>();
        if (codes == null || codes.isEmpty()) return out;
        for (String code : codes) {
            if (code == null) continue;
            String c = code.trim();
            PrecomputedRow pr = precomputedByCode.get(c);
            if (pr != null) {
                out.put(c, new double[]{pr.nowVal, pr.change, pr.dayPnl});
            }
        }
        return out;
    }

    /**
     * 停止从网络更新数据
     */
    public abstract void stopHandle();

    private void columnColors(boolean colorful) {
        // 对分隔行的统一显示处理：当 code 列以 ---sep 开头时，所有列显示为空白
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                try {
                    Object codeVal = t.getValueAt(row, codeColumnIndex);
                    if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) {
                        // 显示空白并将整行背景改为橙色（非选中时）
                        Component sepComp = super.getTableCellRendererComponent(t, "", isSelected, hasFocus, row, column);
                        if (!isSelected) sepComp.setBackground(SEP_ROW_BG);
                        sepComp.setForeground(JBColor.foreground());
                        return sepComp;
                    }
                } catch (Exception ignore) {}
                return comp;
            }
        });

        // 通用正负数据渲染器: >0 红色 <0 绿色 =0 默认
        DefaultTableCellRenderer redGreenRenderer = createRedGreenRenderer(colorful);

        // 涨跌/涨跌幅/收益率/收益/当日盈亏/换手率/振幅/涨速 使用通用渲染器
        for (String cn : new String[]{"涨跌", "涨跌幅", "收益率", "收益", "当日盈亏", "换手率", "振幅", "涨速"})
            setRendererForCol(cn, redGreenRenderer);

        // 编码/股票名称列：涨跌幅>=5% 红色, <=-5% 绿色
        renderCodeNameColors(colorful);

        // 换手率专色: >10% 红色
        DefaultTableCellRenderer turnoverRenderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                try {
                    Object codeVal = t.getValueAt(r, codeColumnIndex);
                    if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) {
                        Component sepComp = super.getTableCellRendererComponent(t, "", s, f, r, c);
                        if (!s) sepComp.setBackground(SEP_ROW_BG);
                        sepComp.setForeground(JBColor.foreground());
                        return sepComp;
                    }
                } catch (Exception ignore) {}
                Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                if (colorful) {
                    double val = Double.NaN;
                    try { String code = Objects.toString(t.getValueAt(r, codeColumnIndex), ""); PrecomputedRow pre = precomputedByCode.get(code); if (pre != null) val = pre.turnover; } catch (Exception ignore) { val = Double.NaN; }
                    if (Double.isNaN(val)) {
                        val = NumberUtils.toDouble(StringUtils.remove(Objects.toString(v), "%"));
                    }
                    if (val > 10) setForeground(JBColor.RED);
                    else if (val < 0) setForeground(JBColor.GREEN);
                    else setForeground(JBColor.foreground());
                }
                return comp;
            }
        };
        setRendererForCol("换手率", turnoverRenderer);

        // 市盈率专色: <0 绿色, >500 红色
        DefaultTableCellRenderer peRenderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                try {
                    Object codeVal = t.getValueAt(r, codeColumnIndex);
                    if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) {
                        Component sepComp = super.getTableCellRendererComponent(t, "", s, f, r, c);
                        if (!s) sepComp.setBackground(SEP_ROW_BG);
                        sepComp.setForeground(JBColor.foreground());
                        return sepComp;
                    }
                } catch (Exception ignore) {}
                Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                if (colorful) {
                    double val = Double.NaN;
                    try { String code = Objects.toString(t.getValueAt(r, codeColumnIndex), ""); PrecomputedRow pre = precomputedByCode.get(code); if (pre != null) val = pre.pe; } catch (Exception ignore) { val = Double.NaN; }
                    if (Double.isNaN(val)) {
                        val = NumberUtils.toDouble(StringUtils.remove(Objects.toString(v), "%"));
                    }
                    if (val < 0) setForeground(JBColor.GREEN);
                    else if (val > 500) setForeground(JBColor.RED);
                    else setForeground(JBColor.foreground());
                }
                return comp;
            }
        };
        setRendererForCol("市盈率", peRenderer);

        // 振幅专色: >8% 红色
        DefaultTableCellRenderer ampRenderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                try {
                    Object codeVal = t.getValueAt(r, codeColumnIndex);
                    if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) {
                        Component sepComp = super.getTableCellRendererComponent(t, "", s, f, r, c);
                        if (!s) sepComp.setBackground(SEP_ROW_BG);
                        sepComp.setForeground(JBColor.foreground());
                        return sepComp;
                    }
                } catch (Exception ignore) {}
                Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                if (colorful) {
                    double val = NumberUtils.toDouble(StringUtils.remove(Objects.toString(v), "%"));
                    if (val > 8) setForeground(JBColor.RED);
                    else if (val < 0) setForeground(JBColor.GREEN);
                    else setForeground(JBColor.foreground());
                }
                return comp;
            }
        };
        setRendererForCol("振幅", ampRenderer);

        // 成本价列: > 当前价 绿色, < 当前价 红色
        int costIdx2 = WindowUtils.getColumnIndexByName(columnNames, "成本价");
        int nowIdx2 = WindowUtils.getColumnIndexByName(columnNames, "当前价");
        if (costIdx2 >= 0 && nowIdx2 >= 0) {
            DefaultTableCellRenderer costRenderer = new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                        try {
                            Object codeVal = t.getValueAt(r, codeColumnIndex);
                            if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) {
                                Component sepComp = super.getTableCellRendererComponent(t, "", s, f, r, c);
                                if (!s) sepComp.setBackground(SEP_ROW_BG);
                                sepComp.setForeground(JBColor.foreground());
                                return sepComp;
                            }
                        } catch (Exception ignore) {}
                        Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                    if (colorful) {
                        double cv = Double.NaN, nv = Double.NaN;
                        try { String code = Objects.toString(t.getValueAt(r, codeColumnIndex), ""); PrecomputedRow pre = precomputedByCode.get(code); if (pre != null) { cv = pre.costPrice; nv = pre.nowVal; } } catch (Exception ignore) { cv = Double.NaN; nv = Double.NaN; }
                        if (Double.isNaN(cv)) try { cv = NumberUtils.toDouble(StringUtils.remove(Objects.toString(v), "%")); } catch (Exception ignore) { cv = Double.NaN; }
                        if (Double.isNaN(nv)) try { String nowV = Objects.toString(t.getValueAt(r, nowIdx2), ""); nv = NumberUtils.toDouble(StringUtils.remove(nowV, "%")); } catch (Exception ignore) { nv = Double.NaN; }
                        if (!Double.isNaN(cv) && !Double.isNaN(nv)) {
                            if (cv > nv) setForeground(JBColor.GREEN);
                            else if (cv < nv) setForeground(JBColor.RED);
                            else setForeground(JBColor.foreground());
                        } else {
                            setForeground(JBColor.foreground());
                        }
                    }
                    return comp;
                }
            };
                table.getColumn(getColumnName(costIdx2)).setCellRenderer(costRenderer);
        }

        // MA5-240 天均线：高于当前价→绿色（压力）, 低于当前价→红色（支撑）
        int nowIdx3 = WindowUtils.getColumnIndexByName(columnNames, "当前价");
        for (String maName : new String[]{"MA5","MA10","MA20","MA30","MA60","MA120","MA240"}) {
            int maIdx = WindowUtils.getColumnIndexByName(columnNames, maName);
            if (maIdx >= 0 && nowIdx3 >= 0) {
                table.getColumn(getColumnName(maIdx)).setCellRenderer(new DefaultTableCellRenderer() {
                    @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                        try { Object codeVal = t.getValueAt(r, codeColumnIndex); if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) return super.getTableCellRendererComponent(t, "", s, f, r, c); } catch (Exception ignore) {}
                        Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                        if (colorful) {
                            double ma = Double.NaN, nv = Double.NaN;
                            try { String code = Objects.toString(t.getValueAt(r, codeColumnIndex), ""); PrecomputedRow pre = precomputedByCode.get(code); if (pre != null) {
                                switch (maName) {
                                    case "MA5": ma = pre.ma5; break;
                                    case "MA10": ma = pre.ma10; break;
                                    case "MA20": ma = pre.ma20; break;
                                    case "MA30": ma = pre.ma30; break;
                                    case "MA60": ma = pre.ma60; break;
                                    case "MA120": ma = pre.ma120; break;
                                    case "MA240": ma = pre.ma240; break;
                                }
                                nv = pre.nowVal;
                            } } catch (Exception ignore) { ma = Double.NaN; nv = Double.NaN; }
                            if (Double.isNaN(ma)) try { ma = NumberUtils.toDouble(StringUtils.remove(Objects.toString(v), "%")); } catch (Exception ignore) { ma = Double.NaN; }
                            if (Double.isNaN(nv)) try { String nowV = Objects.toString(t.getValueAt(r, nowIdx3), ""); nv = NumberUtils.toDouble(StringUtils.remove(nowV, "%")); } catch (Exception ignore) { nv = Double.NaN; }
                            if (!Double.isNaN(ma) && !Double.isNaN(nv)) {
                                if (ma > nv) setForeground(JBColor.GREEN);
                                else if (ma < nv) setForeground(JBColor.RED);
                                else setForeground(JBColor.foreground());
                            } else {
                                setForeground(JBColor.foreground());
                            }
                        }
                        return comp;
                    }
                });
            }
        }

        // DIFF/DEA/MACD：DIFF > DEA → 黄色（金叉）, DIFF < DEA → 蓝色（死叉）
        // 仅当 DIFF 和 DEA 都有有效数值时才染色
        int diffIdx = WindowUtils.getColumnIndexByName(columnNames, "DIFF");
        int deaIdx = WindowUtils.getColumnIndexByName(columnNames, "DEA");
        if (diffIdx >= 0 && deaIdx >= 0) {
            DefaultTableCellRenderer macdRenderer = new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                    try { Object codeVal = t.getValueAt(r, codeColumnIndex); if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) { Component sepComp = super.getTableCellRendererComponent(t, "", s, f, r, c); if (!s) sepComp.setBackground(SEP_ROW_BG); sepComp.setForeground(JBColor.foreground()); return sepComp; } } catch (Exception ignore) {}
                    Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                    if (colorful) {
                        double diff = Double.NaN, dea = Double.NaN;
                        try { String code = Objects.toString(t.getValueAt(r, codeColumnIndex), ""); PrecomputedRow pre = precomputedByCode.get(code); if (pre != null) { diff = pre.diff; dea = pre.dea; } } catch (Exception ignore) { diff = Double.NaN; dea = Double.NaN; }
                        if (Double.isNaN(diff) || Double.isNaN(dea)) {
                            String diffV = Objects.toString(t.getValueAt(r, diffIdx), "");
                            String deaV = Objects.toString(t.getValueAt(r, deaIdx), "");
                            if ("--".equals(diffV) || "--".equals(deaV) || StringUtils.isBlank(diffV) || StringUtils.isBlank(deaV)) {
                                setForeground(JBColor.foreground());
                                return comp;
                            }
                            diff = NumberUtils.toDouble(StringUtils.remove(diffV, "%"));
                            dea = NumberUtils.toDouble(StringUtils.remove(deaV, "%"));
                        }
                        // 只在 DIFF/DEA 值接近零轴并且处于金叉/死叉附近时染色
                        boolean nearZero = Math.abs(diff) <= MACD_NEAR_ZERO && Math.abs(dea) <= MACD_NEAR_ZERO;
                        double delta = Math.abs(diff - dea);
                        boolean nearCross = delta <= MACD_CROSS_DELTA;
                        if (nearZero && diff > dea && nearCross) setForeground(new Color(0xCC, 0xAA, 0x00)); // 金叉（零轴上方附近）
                        else if (nearZero && diff < dea && nearCross) setForeground(new Color(0x00, 0x66, 0xCC)); // 死叉（零轴下方附近）
                        else setForeground(JBColor.foreground());
                    }
                    return comp;
                }
            };
            table.getColumn(getColumnName(diffIdx)).setCellRenderer(macdRenderer);
            table.getColumn(getColumnName(deaIdx)).setCellRenderer(macdRenderer);
            int macdIdx = WindowUtils.getColumnIndexByName(columnNames, "MACD");
            if (macdIdx >= 0) table.getColumn(getColumnName(macdIdx)).setCellRenderer(macdRenderer);
        }
    }

    private void setRendererForCol(String colName, DefaultTableCellRenderer renderer) {
        int idx = WindowUtils.getColumnIndexByName(columnNames, colName);
        if (idx >= 0) table.getColumn(getColumnName(idx)).setCellRenderer(renderer);
    }

    private DefaultTableCellRenderer createRedGreenRenderer(boolean colorful) {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                try { Object codeVal = table.getValueAt(row, codeColumnIndex); if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) return super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column); } catch (Exception ignore) {}
                Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                double temp = Double.NaN;
                try {
                    Object codeVal = table.getValueAt(row, codeColumnIndex);
                    String code = codeVal == null ? "" : codeVal.toString();
                    PrecomputedRow pre = precomputedByCode.get(code);
                    String colName = columnNames[column];
                    if (pre != null) temp = pre.getForColumn(colName);
                } catch (Exception ignore) { temp = Double.NaN; }
                if (Double.isNaN(temp)) {
                    temp = NumberUtils.toDouble(StringUtils.remove(Objects.toString(value), "%"));
                }
                if (colorful) {
                    if (temp > 0) setForeground(JBColor.RED);
                    else if (temp < 0) setForeground(JBColor.GREEN);
                    else setForeground(JBColor.foreground());
                } else {
                    if (temp > 0) setForeground(JBColor.DARK_GRAY);
                    else if (temp < 0) setForeground(JBColor.GRAY);
                    else setForeground(JBColor.foreground());
                }
                return component;
            }
        };
    }

    private void renderCodeNameColors(boolean colorful) {

        // 编码/股票名称列：涨跌幅>=5% 红色, <=-5% 绿色
        int codeIdx = WindowUtils.getColumnIndexByName(columnNames, "编码");
        int nameIdx = WindowUtils.getColumnIndexByName(columnNames, "股票名称");
        int pctIdx = WindowUtils.getColumnIndexByName(columnNames, "涨跌幅");
        if (pctIdx >= 0) {
            DefaultTableCellRenderer codeNameRenderer = new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    try {
                        Object codeVal = table.getValueAt(row, codeIdx);
                        if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) {
                            Component sepComp = super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
                            if (!isSelected) sepComp.setBackground(SEP_ROW_BG);
                            sepComp.setForeground(JBColor.foreground());
                            return sepComp;
                        }
                    } catch (Exception ignore) {}
                    Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    if (colorful) {
                        double pct = Double.NaN;
                        try {
                            String code = Objects.toString(table.getValueAt(row, codeIdx), "");
                            PrecomputedRow pre = precomputedByCode.get(code);
                            if (pre != null) pct = pre.changePercent;
                        } catch (Exception ignore) { pct = Double.NaN; }
                        if (Double.isNaN(pct)) {
                            Object pctObj = table.getValueAt(row, pctIdx);
                            pct = NumberUtils.toDouble(StringUtils.remove(Objects.toString(pctObj, ""), "%"));
                        }
                        if (pct >= 5) setForeground(JBColor.RED);
                        else if (pct <= -5) setForeground(JBColor.GREEN);
                        else setForeground(JBColor.foreground());
                    }
                    // 名称列: 若存在自定义显示名则加粗显示；使用预计算缓存避免在 EDT 中重复计算均线匹配
                    try {
                        if (column == nameIdx) {
                            String code = Objects.toString(table.getValueAt(row, codeIdx), "");
                            String custom = customNamesCache.get(code);
                            boolean isCustom = StringUtils.isNotBlank(custom);
                            component.setFont(component.getFont().deriveFont(isCustom ? java.awt.Font.BOLD : java.awt.Font.PLAIN));

                            // 使用预计算缓存获取持仓前缀与当日机会
                            PrecomputedRow pre = precomputedByCode.get(code);
                            boolean hasPos = pre != null ? pre.hasPosition : false;
                            String safeName = Objects.toString(value, "");
                            String shown = safeName;
                            if (component instanceof JLabel) {
                                ((JLabel) component).setText(shown);
                                // 不在名称列做额外机会计算，已在模型层或异步 k 线中填充
                            } else if (component instanceof JComponent) {
                                ((JComponent) component).setToolTipText(shown);
                            }
                        }
                    } catch (Exception ignore) {}
                    return component;
                }
            };
            if (codeIdx >= 0) table.getColumn(getColumnName(codeIdx)).setCellRenderer(codeNameRenderer);
            if (nameIdx >= 0) table.getColumn(getColumnName(nameIdx)).setCellRenderer(codeNameRenderer);
            // 当前价着色：高于0%（相对于昨收/0%）为红色，低于0%为绿色
            int nowIdx = WindowUtils.getColumnIndexByName(columnNames, "当前价");
            if (nowIdx >= 0) {
                DefaultTableCellRenderer nowRenderer = new DefaultTableCellRenderer() {
                    @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                        try { Object codeVal = t.getValueAt(r, codeColumnIndex); if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) { Component sepComp = super.getTableCellRendererComponent(t, "", s, f, r, c); if (!s) sepComp.setBackground(SEP_ROW_BG); sepComp.setForeground(JBColor.foreground()); return sepComp; } } catch (Exception ignore) {}
                        Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                        if (colorful) {
                            double ch = Double.NaN;
                            try { String code = Objects.toString(t.getValueAt(r, codeColumnIndex), ""); PrecomputedRow pre = precomputedByCode.get(code); if (pre != null) ch = pre.change; } catch (Exception ignore) { ch = Double.NaN; }
                            if (Double.isNaN(ch)) {
                                int chIdx = WindowUtils.getColumnIndexByName(columnNames, "涨跌");
                                if (chIdx >= 0) {
                                    try { ch = NumberUtils.toDouble(StringUtils.remove(Objects.toString(t.getValueAt(r, chIdx), ""), "%")); } catch (Exception ex) { ch = 0; }
                                }
                            }
                            if (ch > 0) setForeground(JBColor.RED);
                            else if (ch < 0) setForeground(JBColor.GREEN);
                            else setForeground(JBColor.foreground());
                        }
                        return comp;
                    }
                };
                table.getColumn(getColumnName(nowIdx)).setCellRenderer(nowRenderer);
            }
        }

        // 持仓市值列：> 持仓成本 红色, < 持仓成本 绿色, = 默认颜色
        int posValueIdx = WindowUtils.getColumnIndexByName(columnNames, "持仓市值");
        int posCostIdx = WindowUtils.getColumnIndexByName(columnNames, "持仓成本");
        if (posValueIdx >= 0 && posCostIdx >= 0) {
            DefaultTableCellRenderer posValueRenderer = new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    try { Object codeVal = table.getValueAt(row, codeColumnIndex); if (codeVal != null && String.valueOf(codeVal).startsWith("---sep")) return super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column); } catch (Exception ignore) {}
                    Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    if (colorful) {
                        double pv = Double.NaN, pc = Double.NaN;
                        try { String code = Objects.toString(table.getValueAt(row, codeColumnIndex), ""); PrecomputedRow pre = precomputedByCode.get(code); if (pre != null) { pv = pre.positionValue; pc = pre.positionCost; } } catch (Exception ignore) { pv = Double.NaN; pc = Double.NaN; }
                        if (Double.isNaN(pv)) {
                            try { pv = parsePossiblyUnitNumber(Objects.toString(value)); } catch (Exception ex) { pv = NumberUtils.toDouble(StringUtils.remove(Objects.toString(value), "%")); }
                        }
                        if (Double.isNaN(pc)) {
                            try { Object costObj = table.getValueAt(row, posCostIdx); pc = parsePossiblyUnitNumber(Objects.toString(costObj)); } catch (Exception ex) { try { Object costObj = table.getValueAt(row, posCostIdx); pc = NumberUtils.toDouble(StringUtils.remove(Objects.toString(costObj), "%")); } catch (Exception ignore) { pc = Double.NaN; } }
                        }
                        if (pv > pc) setForeground(JBColor.RED);
                        else if (pv < pc) setForeground(JBColor.GREEN);
                        else setForeground(JBColor.foreground());
                    } else {
                        setForeground(JBColor.DARK_GRAY);
                    }
                    return component;
                }
            };
            table.getColumn(getColumnName(posValueIdx)).setCellRenderer(posValueRenderer);
        }
    }

    private void calcPositionFields(StockBean bean) {
        if (bean == null) return;
        String bondsStr = bean.getBonds();
        if (StringUtils.isBlank(bondsStr) || "--".equals(bondsStr)) {
            bean.setPositionCost("");
            bean.setPositionValue("");
            bean.setDayPnl("");
            bean.setPositionRatio("");
            return;
        }
        try {
            // 支持带单位的数值解析（例如 "1.23万"）以及可能带的 "+" 前缀
            double bonds = parsePossiblyUnitNumber(bondsStr.replaceAll("\\+", "").trim());
            String costStr = bean.getCostPrise();
            if (StringUtils.isNotBlank(costStr) && !"--".equals(costStr)) {
                double cost = parsePossiblyUnitNumber(costStr.replaceAll("\\+", "").trim());
                bean.setPositionCost(String.format("%.2f", bonds * cost));
            } else {
                bean.setPositionCost("");
            }
            String nowStr = bean.getNow();
            if (StringUtils.isNotBlank(nowStr) && !"--".equals(nowStr)) {
                double now = parsePossiblyUnitNumber(nowStr.replaceAll("\\+", "").trim());
                bean.setPositionValue(String.format("%.2f", bonds * now));
            } else {
                bean.setPositionValue("");
            }
            String changeStr = bean.getChange();
            if (StringUtils.isNotBlank(changeStr) && !"--".equals(changeStr)) {
                double change = parsePossiblyUnitNumber(changeStr.replaceAll("\\+", "").trim());
                bean.setDayPnl(String.format("%.2f", bonds * change));
            } else {
                bean.setDayPnl("");
            }
        } catch (NumberFormatException e) {
            bean.setPositionCost("");
            bean.setPositionValue("");
            bean.setDayPnl("");
            bean.setPositionRatio("");
        }
    }

    /** 计算仓位占比: 该股市值 / 所属账号的总金额 */
    private void calcPositionRatio(StockBean bean) {
        bean.setPositionRatio("");
        if (bean == null) return;
        calcPositionRatio_inner(bean);
    }

    /** 计算K线指标并写入bean */
    private void calcKlineFields(StockBean bean) {
        if (bean == null) return;
        bean.setMa5("--"); bean.setMa10("--"); bean.setMa20("--"); bean.setMa30("--");
        bean.setMa60("--"); bean.setMa120("--"); bean.setMa240("--");
        bean.setDiff("--"); bean.setDea("--"); bean.setMacd("--");
        try {
            StockKlineCalc.KlineResult kr = StockKlineCalc.compute(bean.getCode());
            if (kr == null) return;
            int n = kr.ma5 != null ? kr.ma5.length : 0;
            if (n > 0) {
                int last = n - 1;
                bean.setMa5(fmtVal2(kr.ma5[last]));
                bean.setMa10(fmtVal2(kr.ma10[last]));
                bean.setMa20(fmtVal2(kr.ma20[last]));
                bean.setMa30(fmtVal2(kr.ma30[last]));
                bean.setMa60(fmtVal2(kr.ma60[last]));
                bean.setMa120(fmtVal2(kr.ma120[last]));
                bean.setMa240(fmtVal2(kr.ma240[last]));
                bean.setDiff(fmtVal2(kr.diff[last]));
                bean.setDea(fmtVal2(kr.dea[last]));
                bean.setMacd(fmtVal2(kr.macd[last]));
            }
        } catch (Exception e) { LogUtil.info("K线计算失败: " + bean.getCode() + " " + e.getMessage()); }
    }

    private static final ExecutorService klineExecutor = Executors.newFixedThreadPool(2);

    /** 异步计算K线并刷新表格对应行 */
    private void calcKlineFieldsAsync(StockBean bean) {
        if (bean == null) return;
        bean.setMa5("--"); bean.setMa10("--"); bean.setMa20("--"); bean.setMa30("--");
        bean.setMa60("--"); bean.setMa120("--"); bean.setMa240("--");
        bean.setDiff("--"); bean.setDea("--"); bean.setMacd("--");
        final String code = bean.getCode();
        final DefaultTableModel model = this;
        klineExecutor.submit(() -> {
            try {
                StockKlineCalc.KlineResult kr = StockKlineCalc.compute(code);
                if (kr == null) return;
                int n = kr.ma5 != null ? kr.ma5.length : 0;
                if (n > 0) {
                    int last = n - 1;
                    String ma5  = fmtVal2(kr.ma5[last]);
                    String ma10 = fmtVal2(kr.ma10[last]);
                    String ma20 = fmtVal2(kr.ma20[last]);
                    String ma30 = fmtVal2(kr.ma30[last]);
                    String ma60 = fmtVal2(kr.ma60[last]);
                    String ma120 = fmtVal2(kr.ma120[last]);
                    String ma240 = fmtVal2(kr.ma240[last]);
                    String diff = fmtVal2(kr.diff[last]);
                    String dea  = fmtVal2(kr.dea[last]);
                    String macd = fmtVal2(kr.macd[last]);
                    SwingUtilities.invokeLater(() -> {
                        int row = findRowIndex(codeColumnIndex, code);
                        if (row < 0) return;
                        int mi5  = WindowUtils.getColumnIndexByName(columnNames, "MA5");
                        int mi10 = WindowUtils.getColumnIndexByName(columnNames, "MA10");
                        int mi20 = WindowUtils.getColumnIndexByName(columnNames, "MA20");
                        int mi30 = WindowUtils.getColumnIndexByName(columnNames, "MA30");
                        int mi60 = WindowUtils.getColumnIndexByName(columnNames, "MA60");
                        int mi120 = WindowUtils.getColumnIndexByName(columnNames, "MA120");
                        int mi240 = WindowUtils.getColumnIndexByName(columnNames, "MA240");
                        int di = WindowUtils.getColumnIndexByName(columnNames, "DIFF");
                        int ei = WindowUtils.getColumnIndexByName(columnNames, "DEA");
                        int mdi = WindowUtils.getColumnIndexByName(columnNames, "MACD");
                        if (mi5 >= 0)   model.setValueAt(ma5, row, mi5);
                        if (mi10 >= 0)  model.setValueAt(ma10, row, mi10);
                        if (mi20 >= 0)  model.setValueAt(ma20, row, mi20);
                        if (mi30 >= 0)  model.setValueAt(ma30, row, mi30);
                        if (mi60 >= 0)  model.setValueAt(ma60, row, mi60);
                        if (mi120 >= 0) model.setValueAt(ma120, row, mi120);
                        if (mi240 >= 0) model.setValueAt(ma240, row, mi240);
                        if (di >= 0)    model.setValueAt(diff, row, di);
                        if (ei >= 0)    model.setValueAt(dea, row, ei);
                        if (mdi >= 0)   model.setValueAt(macd, row, mdi);
                        // 更新预计算缓存（使用原始数值，避免在渲染器中再次解析）
                        try {
                            PrecomputedRow pr = precomputedByCode.get(code);
                            if (pr == null) pr = new PrecomputedRow();
                            pr.ma5 = kr.ma5[last]; pr.ma10 = kr.ma10[last]; pr.ma20 = kr.ma20[last]; pr.ma30 = kr.ma30[last];
                            pr.ma60 = kr.ma60[last]; pr.ma120 = kr.ma120[last]; pr.ma240 = kr.ma240[last];
                            pr.diff = kr.diff[last]; pr.dea = kr.dea[last]; pr.macd = kr.macd[last];
                            precomputedByCode.put(code, pr);
                        } catch (Exception ignore) {}
                        // 计算并写入当日机会列（若当前价在任意 MA ±1% 则记录 MA 编号）
                        int oppIdx = WindowUtils.getColumnIndexByName(columnNames, "当日机会");
                        if (oppIdx >= 0) {
                            int nowIdx = WindowUtils.getColumnIndexByName(columnNames, "当前价");
                            double nowV = Double.NaN;
                            if (nowIdx >= 0) {
                                try { String nowS = Objects.toString(model.getValueAt(row, nowIdx), ""); nowV = Double.parseDouble(nowS.replaceAll("\\+", "")); } catch (Exception ex) { nowV = Double.NaN; }
                            }
                            List<String> matched2 = new ArrayList<>();
                            if (!Double.isNaN(nowV)) {
                                try {
                                    Map<String, String> maMap = new LinkedHashMap<>();
                                    maMap.put("MA5", ma5); maMap.put("MA10", ma10); maMap.put("MA20", ma20); maMap.put("MA30", ma30);
                                    maMap.put("MA60", ma60); maMap.put("MA120", ma120); maMap.put("MA240", ma240);
                                    for (Map.Entry<String, String> e : maMap.entrySet()) {
                                        String ms = e.getValue();
                                        if (StringUtils.isBlank(ms) || "--".equals(ms)) continue;
                                        double mv = Double.parseDouble(ms.replaceAll("\\+", ""));
                                        if (mv == 0) continue;
                                        double diffRatio = Math.abs(nowV - mv) / mv;
                                        if (diffRatio <= 0.01) matched2.add(e.getKey().replaceAll("MA", ""));
                                    }
                                } catch (Exception ignore) {}
                            }
                            model.setValueAt(matched2.isEmpty() ? "" : String.join("/", matched2), row, oppIdx);
                        }
                    });
                        // 更新预计算缓存（异步计算完成后更新）
                        try { SwingUtilities.invokeLater(() -> {
                            int row = findRowIndex(codeColumnIndex, code);
                            if (row < 0) return;
                            // 读取最新 bean 信息并更新缓存
                            try {
                                Object codeObj = getValueAt(row, codeColumnIndex);
                                if (codeObj != null) {
                                    String c = codeObj.toString();
                                    // 尝试构造临时 bean 获取持仓
                                    StockBean tmp = new StockBean(c);
                                    // 尝试从表模型读取持仓列
                                    int bondsIdx = WindowUtils.getColumnIndexByName(columnNames, "持仓");
                                    if (bondsIdx >= 0) {
                                        Object bv = getValueAt(row, bondsIdx);
                                        if (bv != null) tmp.setBonds(bv.toString());
                                    }
                                    updatePrecomputedForCode(c, tmp);
                                }
                            } catch (Exception ignore) {}
                        }); } catch (Exception ignore) {}
                }
            } catch (Exception e) { LogUtil.info("K线异步计算失败: " + code + " " + e.getMessage()); }
        });
    }

    private String fmtVal2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "--";
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private void calcPositionRatio_inner(StockBean bean) {
        String pv = bean.getPositionValue();
        if (StringUtils.isBlank(pv)) return;
        try {
            // 可能 pv 带单位，例如包含 万/亿 后缀，先尝试解析纯数字，否则尝试去除单位
            double stockValue = parsePossiblyUnitNumber(pv);
            if (stockValue <= 0) return;
            // 按账号计算总金额
            String account = bean.getAccount();
            int ai = 0;
            if (StringUtils.isNotBlank(account) && !"默认".equals(account)) {
                String num = WindowUtils.normalizeAccount(account);
                try { int parsed = Integer.parseInt(num); if (parsed >= 1 && parsed <= 3) ai = parsed - 1; } catch (Exception ignore) {}
            }
            // 从当前股票市值开始，扫描表里其他同行，计算同一账号总持仓市值
            double accountTotal = stockValue;
            String beanCode = bean.getCode();
            int codeIdx = codeColumnIndex;
            int pvIdx = WindowUtils.getColumnIndexByName(columnNames, "持仓市值");
            int acctIdx = WindowUtils.getColumnIndexByName(columnNames, "账号");
            for (int r = 0; r < getRowCount(); r++) {
                // 跳过当前股票自身（避免重复计数）
                Object rowCode = codeIdx >= 0 ? getValueAt(r, codeIdx) : null;
                if (rowCode != null && beanCode.equalsIgnoreCase(rowCode.toString())) continue;
                Object rowAccount = acctIdx >= 0 ? getValueAt(r, acctIdx) : "";
                String ra = rowAccount != null ? rowAccount.toString() : "";
                if (StringUtils.isBlank(ra) || "默认".equals(ra)) ra = "1";
                int rai = 0;
                String rnum = WindowUtils.normalizeAccount(ra);
                try { int parsed = Integer.parseInt(rnum); if (parsed >= 1 && parsed <= 3) rai = parsed - 1; } catch (Exception ignore) {}
                if (rai != ai) continue;
                Object rowPv = pvIdx >= 0 ? getValueAt(r, pvIdx) : null;
                String rpv = rowPv != null ? rowPv.toString() : "";
                if (StringUtils.isNotBlank(rpv)) {
                    try { accountTotal += Double.parseDouble(rpv); } catch (NumberFormatException e) {}
                }
            }
                if (accountTotal > 0) {
                double ratio = stockValue / accountTotal * 100;
                bean.setPositionRatio(String.format("%.2f%%", ratio));
            }
        } catch (NumberFormatException e) {}
    }

    /**
     * 解析可能带单位的金额字符串（例如 "12345"、"1.23万"、"0.12亿"、"12345.00"等）
     */
    private double parsePossiblyUnitNumber(String s) throws NumberFormatException {
        if (StringUtils.isBlank(s)) throw new NumberFormatException("empty");
        s = s.trim();
        try { return Double.parseDouble(s); } catch (NumberFormatException ignore) {}
        // 处理带单位的表示
        if (s.endsWith("万")) {
            String core = s.substring(0, s.length() - 1).replaceAll(",", "");
            return Double.parseDouble(core) * 10000.0;
        }
        if (s.endsWith("亿")) {
            String core = s.substring(0, s.length() - 1).replaceAll(",", "");
            return Double.parseDouble(core) * 100000000.0;
        }
        // 保护性处理：去掉任何非数字字符后再解析
        String digits = s.replaceAll("[^0-9.\\-]", "");
        return Double.parseDouble(digits);
    }

    /**
     * 尝试将对象解析为 Double，解析失败或为空时返回 null（用于排序，将空值视为缺失）
     */
    private Double parseToDoubleOrNull(Object o) {
        if (o == null) return null;
        String s = Objects.toString(o, "").trim();
        if (StringUtils.isBlank(s) || "--".equals(s)) return null;
        // 移除千分位逗号
        s = s.replaceAll(",", "").trim();
        // 处理百分比后缀
        boolean percent = s.endsWith("%");
        if (percent) s = s.substring(0, s.length() - 1).trim();
        try {
            // 先尝试解析带单位的数值（如万/亿）
            try { return parsePossiblyUnitNumber(s); } catch (NumberFormatException ignore) {}
            // 尝试直接解析为 Double
            try { return NumberUtils.createDouble(s); } catch (Exception ignore) {}
        } catch (Exception ignore) {}
        return null;
    }

    /** 在所有股票行加载完毕后重新计算仓位占比（两遍扫描：先汇总账号总市值，再分配） */
    public void recalcAllPositionRatios() {
        // 第一遍：按账号汇总持仓市值
        int codeIdx = codeColumnIndex;
        int pvIdx = WindowUtils.getColumnIndexByName(columnNames, "持仓市值");
        int acctIdx = WindowUtils.getColumnIndexByName(columnNames, "账号");
        if (pvIdx < 0) return;
        java.util.Map<Integer, Double> accountTotals = new HashMap<>();
        for (int r = 0; r < getRowCount(); r++) {
            Object rowAccount = acctIdx >= 0 ? getValueAt(r, acctIdx) : "";
            String ra = rowAccount != null ? rowAccount.toString() : "";
            if (StringUtils.isBlank(ra) || "默认".equals(ra)) ra = "1";
            int ai = 0;
            String anum = WindowUtils.normalizeAccount(ra);
            try { int parsed = Integer.parseInt(anum); if (parsed >= 1 && parsed <= 3) ai = parsed - 1; } catch (Exception ignore) {}
            Object rowPv = getValueAt(r, pvIdx);
            String rpv = rowPv != null ? rowPv.toString() : "";
            if (StringUtils.isNotBlank(rpv)) {
                try {
                    double val = parsePossiblyUnitNumber(rpv);
                    accountTotals.put(ai, accountTotals.getOrDefault(ai, 0.0) + val);
                } catch (NumberFormatException e) {}
            }
        }
        // 第二遍：为每行设置仓位占比
        int ratioIdx = WindowUtils.getColumnIndexByName(columnNames, "仓位");
        for (int r = 0; r < getRowCount(); r++) {
            Object rowAccount = acctIdx >= 0 ? getValueAt(r, acctIdx) : "";
            String ra = rowAccount != null ? rowAccount.toString() : "";
            if (StringUtils.isBlank(ra) || "默认".equals(ra)) ra = "1";
            int ai = 0;
            String anum = WindowUtils.normalizeAccount(ra);
            try { int parsed = Integer.parseInt(anum); if (parsed >= 1 && parsed <= 3) ai = parsed - 1; } catch (Exception ignore) {}
            double total = accountTotals.getOrDefault(ai, 0.0);
            Object rowPv = pvIdx >= 0 ? getValueAt(r, pvIdx) : null;
            String rpv = rowPv != null ? rowPv.toString() : "";
            if (total > 0 && StringUtils.isNotBlank(rpv) && ratioIdx >= 0) {
                try {
                    double sv = parsePossiblyUnitNumber(rpv);
                    double ratio = sv / total * 100;
                    setValueAt(String.format("%.2f%%", ratio), r, ratioIdx);
                } catch (NumberFormatException e) {}
            }
        }
    }

    private String[] getColumnIdentifiersWithOriginalIndex() {
        return getColumnIdentifiersWithOriginalIndex(columnNames);
    }

    private String[] getColumnIdentifiersWithOriginalIndex(String[] visibleNames) {
        String[] ids = Arrays.copyOf(visibleNames, visibleNames.length + 1);
        ids[visibleNames.length] = ORIGINAL_INDEX_COLUMN_NAME;
        return ids;
    }

    private void hideOriginalIndexColumn() {
        if (table == null) return;
        try {
            int hiddenIndex = getOriginalIndexColumnIndex();
            if (hiddenIndex < 0) return;
            javax.swing.table.TableColumn column = table.getColumnModel().getColumn(hiddenIndex);
            column.setMinWidth(0);
            column.setMaxWidth(0);
            column.setPreferredWidth(0);
            column.setResizable(false);
        } catch (Exception ignore) {}
    }

    public int getOriginalIndexColumnIndex() {
        if (getColumnCount() <= 0) return -1;
        for (int i = 0; i < getColumnCount(); i++) {
            try {
                if (ORIGINAL_INDEX_COLUMN_NAME.equals(getColumnName(i))) return i;
            } catch (Exception ignore) {}
        }
        return -1;
    }

    protected void updateData(StockBean bean) {
        if (bean.getCode() == null) { return; }
        // 支持分隔行：如果是分隔行则在表格末尾插入占位行并设置 code/name
        if (bean.getCode().startsWith("---sep")) {
            try {
                Vector<Object> v = new Vector<>(Collections.nCopies(getColumnCount() > 0 ? getColumnCount() : columnNames.length + 1, ""));
                addRow(v);
                int last = getRowCount() - 1;
                setValueAt(bean.getCode(), last, codeColumnIndex);
                int nameIdx = WindowUtils.getColumnIndexByName(columnNames, "股票名称");
                if (nameIdx >= 0) {
                    // 若用户为分隔行设置了自定义名称则使用之（使用缓存）
                    try {
                        String custom = customNamesCache.get(bean.getCode());
                        if (StringUtils.isNotBlank(custom)) setValueAt(custom, last, nameIdx);
                        else setValueAt("━━━━━━━━━━━━", last, nameIdx);
                    } catch (Exception ignore) { setValueAt("━━━━━━━━━━━━", last, nameIdx); }
                }
            } catch (Exception ignore) {}
            return;
        }
        // 应用用户自定义显示名（如果存在），存储在 PropertiesComponent key_custom_names（JSON 对象）中
        try {
            String customName = customNamesCache.get(bean.getCode());
            if (StringUtils.isNotBlank(customName)) bean.setName(customName);
        } catch (Exception ignore) {}
        // 本地计算涨速的回退处理：若接口未提供或为空，则依据上一次价格估算每分钟涨幅（%/min）
        try {
            String code = bean.getCode();
            String speedStr = bean.getSpeed();
            String nowStr = bean.getNow();
            double nowVal = Double.NaN;
            if (StringUtils.isNotBlank(nowStr) && !"--".equals(nowStr)) {
                try { nowVal = Double.parseDouble(nowStr.replaceAll("\\+", "")); } catch (Exception e) { nowVal = Double.NaN; }
            }
            if ((StringUtils.isBlank(speedStr) || "--".equals(speedStr)) && !Double.isNaN(nowVal)) {
                Double last = lastPriceMap.get(code);
                Long lt = lastPriceTime.get(code);
                if (last != null && lt != null && last > 0) {
                    double deltaMin = (System.currentTimeMillis() - lt) / 60000.0;
                    if (deltaMin > 0) {
                        double speedPerMin = (nowVal - last) / last * 100.0 / deltaMin;
                        bean.setSpeed(String.format("%.2f", speedPerMin));
                    }
                }
            }
            if (!Double.isNaN(nowVal)) { lastPriceMap.put(code, nowVal); lastPriceTime.put(code, System.currentTimeMillis()); }
        } catch (Exception ignore) {}
        // 优先使用用户持久化的成本/持仓配置，避免后台刷新将用户刚输入的值覆盖
        try {
            String persistedCost = getCostPriseRaw(bean.getCode());
            if (persistedCost != null) bean.setCostPrise(persistedCost);
        } catch (Exception ignore) {}
        try {
            String persistedBonds = getBondsRaw(bean.getCode());
            if (persistedBonds != null) bean.setBonds(persistedBonds);
        } catch (Exception ignore) {}
        calcPositionFields(bean);
        calcPositionRatio(bean);
        calcKlineFieldsAsync(bean);
        // 更新预计算缓存以便渲染器快速访问
        try { updatePrecomputedForCode(bean.getCode(), bean); } catch (Exception ignore) {}
        Vector<Object> convertData = convertData(bean);
        if (convertData == null) {
            return;
        }
        int hiddenIndex = getOriginalIndexColumnIndex();
        if (hiddenIndex >= 0) {
            while (convertData.size() < getColumnCount()) convertData.add("");
            if (convertData.size() > hiddenIndex) {
                convertData.set(hiddenIndex, getRowCount());
            }
        }
        // 获取行
        int index = findRowIndex(codeColumnIndex, bean.getCode());
        if (index >= 0) {
            updateRow(index, convertData);
        } else {
            addRow(convertData);
        }
    }

    /**
     * 参考源码{@link DefaultTableModel#setValueAt}，此为直接更新行，提高点效率
     *
     * @param rowIndex
     * @param rowData
     */
    protected void updateRow(int rowIndex, Vector<Object> rowData) {
        Vector<Object> normalized = new Vector<>(rowData);
        int hiddenIndex = getOriginalIndexColumnIndex();
        if (hiddenIndex >= 0) {
            while (normalized.size() < getColumnCount()) normalized.add("");
            if (normalized.size() > hiddenIndex) {
                Object existingHidden = null;
                try { existingHidden = getValueAt(rowIndex, hiddenIndex); } catch (Exception ignore) { existingHidden = null; }
                if (existingHidden == null) existingHidden = rowIndex;
                normalized.set(hiddenIndex, existingHidden);
            }
        }
        dataVector.set(rowIndex, normalized);
        // 将需要刷新的行加入集合，实际刷新由定时器在 EDT 上合并执行，避免大量连续 repaint
        try { pendingRowUpdates.add(rowIndex); } catch (Exception ignore) {}
    }

    /**
     * 在 EDT 上合并并触发对 pendingRowUpdates 集合的刷新调用
     */
    private void flushPendingRowUpdates() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::flushPendingRowUpdates);
            return;
        }
        java.util.Set<Integer> copy;
        synchronized (pendingRowUpdates) {
            if (pendingRowUpdates.isEmpty()) return;
            copy = new java.util.HashSet<>(pendingRowUpdates);
            pendingRowUpdates.clear();
        }
        java.util.List<Integer> list = new java.util.ArrayList<>(copy);
        java.util.Collections.sort(list);
        int start = list.get(0);
        int end = start;
        for (int i = 1; i < list.size(); i++) {
            int v = list.get(i);
            if (v == end + 1) {
                end = v;
            } else {
                fireTableRowsUpdated(start, end);
                start = v; end = v;
            }
        }
        fireTableRowsUpdated(start, end);
    }

    /**
     * 停止并清理批量更新定时器
     */
    public void shutdownUpdateBatchTimer() {
        try {
            if (updateBatchTimer != null && updateBatchTimer.isRunning()) updateBatchTimer.stop();
        } catch (Exception ignore) {}
    }

    /**
     * 参考源码{@link DefaultTableModel#removeRow(int)}，此为直接清除全部行，提高点效率
     */
    public void clearRow() {
        int size = dataVector.size();
        if (0 < size) {
            dataVector.clear();
            // 清理原始索引快照，避免残留旧快照造成恢复顺序异常
            try { originalIndexMap.clear(); } catch (Exception ignore) {}
            // 通知listeners刷新ui
            fireTableRowsDeleted(0, size - 1);
        }
    }

    /**
     * 记录当前模型顺序为原始顺序快照（用于三态排序的“恢复原始顺序”功能）
     */
    public void recordOriginalIndexSnapshot() {
        try {
            int hiddenIndex = getOriginalIndexColumnIndex();
            if (hiddenIndex < 0) return;
            for (int r = 0; r < getRowCount(); r++) {
                try { setValueAt(r, r, hiddenIndex); } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
    }

    /**
     * 返回上一次记录的原始顺序快照（按记录的 index 升序返回 code 列表）
     */
    public java.util.List<String> getOriginalOrderSnapshot() {
        try {
            java.util.List<java.util.Map.Entry<String, Integer>> entries = new java.util.ArrayList<>(originalIndexMap.entrySet());
            entries.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));
            java.util.List<String> out = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, Integer> e : entries) out.add(e.getKey());
            return out;
        } catch (Exception ignore) { return new java.util.ArrayList<>(); }
    }

    /**
     * 返回一个比较器，依据先前记录的 originalIndexMap 对编码进行比较
     */
    public java.util.Comparator<Object> getOriginalIndexComparator() {
        return (o1, o2) -> {
            try {
                String s1 = o1 == null ? "" : String.valueOf(o1).replace("rt_", "").trim().toUpperCase();
                String s2 = o2 == null ? "" : String.valueOf(o2).replace("rt_", "").trim().toUpperCase();
                Integer i1 = originalIndexMap.getOrDefault(s1, Integer.MAX_VALUE);
                Integer i2 = originalIndexMap.getOrDefault(s2, Integer.MAX_VALUE);
                if (!i1.equals(i2)) return Integer.compare(i1, i2);
                return s1.compareTo(s2);
            } catch (Exception ignore) { return 0; }
        };
    }

    /**
     * 查找列项中的valueName所在的行
     *
     * @param columnIndex 列号
     * @param value       值
     * @return 如果不存在返回-1
     */
    protected int findRowIndex(int columnIndex, String value) {
        int rowCount = getRowCount();
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            Object valueAt = getValueAt(rowIndex, columnIndex);
            if (valueAt == null) continue;
            try {
                if (StringUtils.equalsIgnoreCase(value, valueAt.toString())) {
                    return rowIndex;
                }
            } catch (Exception ignore) {}
        }
        return -1;
    }

    /**
     * 按给定的代码列表重新排列当前模型的行顺序（就地重排，保留现有数据），未匹配的行保留在末尾。
     * 该方法在 EDT 中被调用以避免数据竞争。
     */
    public void reorderRowsByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) return;
        try {
            Vector<Vector> newData = new Vector<>();
            Set<Integer> used = new HashSet<>();
            // 规范化比较函数：去掉 rt_ 前缀并忽略大小写
            java.util.function.Function<String, String> norm = (s) -> {
                if (s == null) return "";
                String t = s.trim();
                if (t.startsWith("rt_")) t = t.substring(3);
                return t.trim().toUpperCase();
            };
            // 优先将 codes 中定义的顺序加入
            for (String code : codes) {
                if (code == null) continue;
                String want = norm.apply(code);
                for (int r = 0; r < dataVector.size(); r++) {
                    if (used.contains(r)) continue;
                    Vector row = dataVector.get(r);
                    Object cv = null;
                    try { cv = row.get(codeColumnIndex); } catch (Exception ignore) { cv = null; }
                    String actual = cv == null ? "" : cv.toString();
                    if (norm.apply(actual).equals(want)) {
                        newData.add(row);
                        used.add(r);
                        break;
                    }
                }
            }
            // 追加未被匹配的其余行
            for (int r = 0; r < dataVector.size(); r++) {
                if (!used.contains(r)) newData.add(dataVector.get(r));
            }
            // 替换数据向量并通知 UI
            dataVector = newData;
            fireTableDataChanged();
        } catch (Exception ignore) {}
    }

    private Vector<Object> convertData(StockBean stockBean) {
        if (stockBean == null) {
            return null;
        }
        // 与columnNames中的元素保持一致
        Vector<Object> v = new Vector<Object>(getColumnCount() > 0 ? getColumnCount() : columnNames.length + 1);
        for (int i = 0; i < columnNames.length; i++) {
            v.addElement(stockBean.getValueByColumn(columnNames[i], colorful));
        }
        return v;
    }

    private static String getAccountCount() {
        try {
            String v = PropertiesComponent.getInstance().getValue("key_account_count", "1");
            int count = Integer.parseInt(v);
            if (count < 1) count = 1; if (count > 3) count = 3;
            return v;
        } catch (NumberFormatException e) { return "1"; }
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        List<String> list = Arrays.asList(canEditColumnNames);
        String columnName = columnNames[column];
        // 账号列：仅在该行同时存在 成本价 和 持仓 时可编辑（否则不可点击）
        if ("账号".equals(columnName)) {
            try {
                int costIdx = WindowUtils.getColumnIndexByName(columnNames, "成本价");
                int bondsIdx = WindowUtils.getColumnIndexByName(columnNames, "持仓");
                String cost = costIdx >= 0 ? String.valueOf(getValueAt(row, costIdx)) : "";
                String bonds = bondsIdx >= 0 ? String.valueOf(getValueAt(row, bondsIdx)) : "";
                if (StringUtils.isBlank(cost) || "--".equals(cost.trim())) return false;
                if (StringUtils.isBlank(bonds) || "--".equals(bonds.trim())) return false;
                return true;
            } catch (Exception ignore) { return false; }
        }
        return list.contains(columnName);
    }

    @Override
    public Object getValueAt(int row, int column) {
        return super.getValueAt(row, column);
    }

    /**
     * 共用：根据成本价和持仓计算收益率/收益
     */
    protected void calcIncome(StockBean b) {
        String cp = b.getCostPrise(), now = b.getNow();
        if (StringUtils.isBlank(cp) || StringUtils.isBlank(now) || "--".equals(now)) return;
        try {
            BigDecimal n = new BigDecimal(now), c = new BigDecimal(cp);
            BigDecimal diff = n.subtract(c);
            if (c.compareTo(BigDecimal.ZERO) <= 0) { b.setIncomePercent("0"); return; }
            b.setIncomePercent(diff.divide(c, 5, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.TEN).multiply(BigDecimal.TEN)
                    .setScale(3, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString());
            String bonds = b.getBonds();
            if (StringUtils.isNotBlank(bonds))
                b.setIncome(diff.multiply(new BigDecimal(bonds)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        } catch (NumberFormatException ignore) {}
    }

    protected String formatDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "--";
        return BigDecimal.valueOf(v)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    protected String formatPrice(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "--";
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    protected String formatPercent(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "--";
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    protected String emptyToDash(String s) {
        return StringUtils.isBlank(s) ? "--" : s;
    }

    /**
     * 判断当前是否交易时间（周一至周五，09:15-12:00，13:00-16:15）
     */
    private boolean isTradingTime() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.DayOfWeek day = now.getDayOfWeek();
        if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) return false;
        int hm = now.getHour() * 100 + now.getMinute();
        return (hm >= 915 && hm <= 1200) || (hm >= 1300 && hm <= 1615);
    }

    // 边沿触发状态缓存：记录每个预警键上一次是否满足条件
    private static final Map<String, Boolean> alertEdgeState = new HashMap<>();
    private static final Map<String, Long> lastAlertSentTime = new HashMap<>();
    private static final long ALERT_THROTTLE_MS = 60 * 1000L;

    /** 触发预警检查（数据刷新后调用） */
    public void triggerAlertCheck() {
        if (!isTradingTime()) return;
        int cci = codeColumnIndex;
        int ni = WindowUtils.getColumnIndexByName(columnNames, "当前价");
        int chi = WindowUtils.getColumnIndexByName(columnNames, "涨跌");
        int cn = WindowUtils.getColumnIndexByName(columnNames, "股票名称");
        if (ni < 0 || chi < 0) return;
        boolean riskEnabled = ConfigManager.getInstance().isRiskAlertEnabled();
        for (int i = 0; i < getRowCount(); i++) {
            String code = Objects.toString(getValueAt(i, cci), "");
            if (StringUtils.isBlank(code)) continue;
            String ns = Objects.toString(getValueAt(i, ni), "");
            String chs = Objects.toString(getValueAt(i, chi), "");
            String name = cn >= 0 ? Objects.toString(getValueAt(i, cn), "") : code;
            String alertCfg = getAlertConfigRaw(code);
            // 持仓风险预警：边沿触发，1分钟节流
            if (riskEnabled) {
                double upThreshold = ConfigManager.getInstance().getRiskUpPercent();
                double downThreshold = ConfigManager.getInstance().getRiskDownPercent();
                String costStr = getCostPriseRaw(code);
                String bondsStr = getBondsRaw(code);
                if (StringUtils.isNotBlank(costStr) && StringUtils.isNotBlank(bondsStr)
                        && !"--".equals(costStr) && !"--".equals(bondsStr)) {
                    try {
                        double cost = Double.parseDouble(costStr);
                        double bonds = Double.parseDouble(bondsStr);
                        double now = Double.parseDouble(ns);
                        if (cost > 0 && bonds > 0) {
                            double dayPct = 0;
                            if (StringUtils.isNotBlank(chs) && !"--".equals(chs)) {
                                double change = Double.parseDouble(chs);
                                double prevClose = now - change;
                                if (prevClose > 0) dayPct = (now - prevClose) / prevClose * 100;
                            }
                            final double dPct = dayPct;
                            final double dNow = now;
                            edgeCheck(code + "_riskUp", dPct >= upThreshold,
                                () -> showAlertBubble(name, "持仓风险预警", "上涨+" + formatPercent(dPct) + "%，报" + formatPrice(dNow) + "元"));
                            edgeCheck(code + "_riskDown", dPct <= -downThreshold,
                                () -> showAlertBubble(name, "持仓风险预警", "下跌" + formatPercent(dPct) + "%，报" + formatPrice(dNow) + "元"));
                        }
                    } catch (NumberFormatException e) {}
                }
            }
            // 自定义预警：边沿触发，1分钟节流
            if (StringUtils.isNotBlank(alertCfg)) {
                checkCustomAlerts(code, name, alertCfg, ns, chs);
            }
        }
    }

    /** false→true 边沿触发 + 节流 */
    private void edgeCheck(String key, boolean nowMet, Runnable action) {
        boolean wasMet = Boolean.TRUE.equals(alertEdgeState.get(key));
        if (nowMet) {
            if (!wasMet && !isThrottled(key)) {
                markThrottled(key);
                action.run();
            }
            alertEdgeState.put(key, true);
        } else {
            alertEdgeState.put(key, false);
        }
    }

    private void checkCustomAlerts(String code, String name, String alertCfg, String ns, String chs) {
        try {
            double now = Double.parseDouble(ns);
            for (String p : alertCfg.split(",")) {
                if (StringUtils.isBlank(p)) continue;
                String[] kv = p.split("_");
                if (kv.length < 2) continue;
                double v = Double.parseDouble(kv[1]);
                String k = code + "_" + kv[0] + "_" + v;
                switch (kv[0]) {
                    case "upPrice":
                        edgeCheck(k, now >= v, () -> showAlertBubble(name, "上涨价格预警", "当前价 " + formatPrice(now) + " 元 ≥ 触发价 " + formatPrice(v) + " 元"));
                        break;
                    case "downPrice":
                        edgeCheck(k, now <= v, () -> showAlertBubble(name, "下跌价格预警", "当前价 " + formatPrice(now) + " 元 ≤ 触发价 " + formatPrice(v) + " 元"));
                        break;
                    case "upPct":
                        if (StringUtils.isNotBlank(chs) && !"--".equals(chs)) {
                            double change = Double.parseDouble(chs);
                            double prevClose = now - change;
                            if (prevClose > 0) {
                                double pct = (now - prevClose) / prevClose * 100;
                                edgeCheck(k, pct >= v, () -> showAlertBubble(name, "上涨幅度预警", "上涨+" + formatPercent(pct) + "%，报" + formatPrice(now) + "元"));
                            }
                        }
                        break;
                    case "downPct":
                        if (StringUtils.isNotBlank(chs) && !"--".equals(chs)) {
                            double change = Double.parseDouble(chs);
                            double prevClose = now - change;
                            if (prevClose > 0) {
                                double pct = (now - prevClose) / prevClose * 100;
                                edgeCheck(k, pct <= -v, () -> showAlertBubble(name, "下跌幅度预警", "下跌" + formatPercent(pct) + "%，报" + formatPrice(now) + "元"));
                            }
                        }
                        break;
                }
            }
        } catch (NumberFormatException e) {}
    }

    private boolean isThrottled(String key) {
        Long last = lastAlertSentTime.get(key);
        return last != null && System.currentTimeMillis() - last < ALERT_THROTTLE_MS;
    }

    private void markThrottled(String key) {
        lastAlertSentTime.put(key, System.currentTimeMillis());
    }

    private void showAlertBubble(String name, String type, String msg) {
        SwingUtilities.invokeLater(() ->
            LogUtil.notify("【" + name + "】" + type + ": " + msg, true));
    }

    private String getAlertConfigRaw(String code) {
        String cfg = PropertiesComponent.getInstance().getValue("key_stocks");
        if (StringUtils.isBlank(cfg)) return null;
        for (String p : cfg.split(";")) {
            String[] arr = p.trim().split(",");
            if (arr.length >= 7 && arr[0].equalsIgnoreCase(code)) return arr[6];
        }
        return null;
    }

    private String getCostPriseRaw(String code) {
        String cfg = PropertiesComponent.getInstance().getValue("key_stocks");
        if (StringUtils.isBlank(cfg)) return null;
        for (String p : cfg.split(";")) {
            String[] arr = p.trim().split(",");
            if (arr.length >= 2 && arr[0].equalsIgnoreCase(code)) return arr[1];
        }
        return null;
    }

    private String getBondsRaw(String code) {
        String cfg = PropertiesComponent.getInstance().getValue("key_stocks");
        if (StringUtils.isBlank(cfg)) return null;
        for (String p : cfg.split(";")) {
            String[] arr = p.trim().split(",");
            if (arr.length >= 3 && arr[0].equalsIgnoreCase(code)) return arr[2];
        }
        return null;
    }

    public List<String> search(String query) {
        List<String> results = new ArrayList<>();
        String text = null;
        try {
            text = HttpClientPool.getHttpClient().get(url + query);
        } catch (Exception e) {
            LogUtil.info("搜索接口调用失败: " + e.getMessage());
            return results;
        }
        if (StringUtils.isBlank(text)) return results;
        JsonObject jsonObject;
        try { jsonObject = JsonParser.parseString(text).getAsJsonObject(); } catch (Exception ex) { return results; }
        // 处理 "stock" 节点
        if (jsonObject.has("stock")) {
            JsonArray stockArray = jsonObject.getAsJsonArray("stock");
            for (int i = 0; i < stockArray.size(); i++) {
                JsonObject stockItem = stockArray.get(i).getAsJsonObject();
                String code = stockItem.has("code") ? stockItem.get("code").getAsString() : "";
                String name = stockItem.has("name") ? stockItem.get("name").getAsString() : "";
                results.add("股票-" + code + "-" + name);
            }
        }

        // 处理 "fund" 节点
        if (jsonObject.has("fund")) {
            JsonArray fundArray = jsonObject.getAsJsonArray("fund");
            for (int i = 0; i < fundArray.size(); i++) {
                JsonObject fundItem = fundArray.get(i).getAsJsonObject();
                String code = fundItem.has("code") ? fundItem.get("code").getAsString() : "";
                String name = fundItem.has("name") ? fundItem.get("name").getAsString() : "";
                results.add("基金-" + code + "-" + name);
            }
        }
        return results;
    }
}
