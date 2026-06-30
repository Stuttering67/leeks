package leeks;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionToolbarPosition;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.CommonActionsPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.UIUtil;
import handler.*;
import bean.StockBean;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import quartz.HandlerJob;
import quartz.QuartzManager;
import utils.LogUtil;
import utils.PopupsUiUtil;
import utils.WindowUtils;
import utils.YesterdayAmountStorage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.Border;
import javax.swing.event.ChangeListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
import utils.ThreadPools;
import utils.HttpClientPool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StockWindow {
    public static final String NAME = "Stock";

    private static final String COST_COLUMN_NAME = "成本价";
    private static final String BONDS_COLUMN_NAME = "持仓";
    private static final String NOW_COLUMN_NAME = "当前价";
    private static final String INCOME_PERCENT_COLUMN_NAME = "收益率";
    private static final String INCOME_COLUMN_NAME = "收益";
    private static final String ACCOUNT_COLUMN_NAME = "账号";
    private static JLabel[] accountSummaryLabels = new JLabel[0];
    private static JPanel summaryPanel;

    private JPanel mPanel;

    static StockRefreshHandler handler;
    static JBTable table;
    static JLabel refreshTimeLabel;
    static TableModelListener editPersistenceListener;
    // 当表格数据更新时，用于刷新重要股票区域的监听器
    private static TableModelListener importantPanelModelListener;
    private static StockWindow currentInstance;
    // 记录最近一次开始编辑的表格视图索引（用于在编辑停止时确定要持久化的单元格）
    private static int lastEditingViewRow = -1;
    private static int lastEditingViewCol = -1;
    private static boolean editingListenerAdded = false;
    // 用于在程序性更新表格模型时抑制 editPersistenceListener 的触发，避免递归
    private static java.util.concurrent.atomic.AtomicBoolean suppressTableEditListener = new java.util.concurrent.atomic.AtomicBoolean(false);

    // 表头三态排序状态：0=未排序 1=升序 2=降序
    private static final java.util.Map<Integer, Integer> headerSortState = new java.util.HashMap<>();
    // 表头排序：记录第一次点击时的当前行顺序快照（用于第三次点击还原）
    private static java.util.List<String> headerRecordedOrder = null;
    private static int headerRecordedColumn = -1;
    // 当为 true 时，刷新操作不会重新注册/创建 Quartz 调度任务（用于仅更新分组显示的场景）
    private static volatile boolean suppressQuartzReschedule = false;

    private JComboBox<String> groupComboBox;
    private JPanel groupTabPanel;
    private JBScrollPane groupScroll;
    private JDialog searchDialog;
    private JTextField searchField;
    private JList<String> resultList;
    private DefaultListModel<String> listModel;
    private Point initialClick;
    private javax.swing.Timer summaryRefreshTimer;
    private javax.swing.Timer importantPanelRefreshTimer;
    private static javax.swing.Timer staticSummaryTimer;
    static PropertiesComponent instance = PropertiesComponent.getInstance();

    // 重要股票 UI 元素
    private JPanel importantPanel;
    private JButton addImportantBtn;
    // 新增重要股票后高亮/定位的 code（用于添加后居中显示）
    private String lastAddedImportantCode = null;
    private JPanel topPanel;

    private transient ScheduledFuture<?> dailyOpportunityFuture;

    private enum SearchMode { NORMAL, IMPORTANT_ADD }
    private SearchMode currentSearchMode = SearchMode.NORMAL;

    // 重要股票轮播相关
    private transient ScheduledFuture<?> importantRotateFuture;
    private transient int importantRotateIndex = 0;
    private transient java.util.List<String> importantCodesCache = new java.util.ArrayList<>();
    // 每日保存昨日成交额的调度任务，避免与每日机会弹窗任务冲突
    private transient ScheduledFuture<?> dailySaveYesterdayFuture;
    // 每页显示数量（2 列 x 2 行）
    private static final int IMPORTANT_PAGE_SIZE = 4;
    private static final long IMPORTANT_ROTATE_INTERVAL_MS = 5000L;

    public JPanel getmPanel() { return mPanel; }

    // 搜索后台任务引用（用于取消过期请求）
    private final AtomicReference<Future<?>> pendingSearch = new AtomicReference<>();

    private static void moveRow(int direction) {
        int sel = table.getSelectedRow();
        if (sel < 0) return;
        int target = sel + direction;
        if (target < 0 || target >= table.getRowCount()) return;
        String curGroup = GroupManager.DEFAULT_GROUP;
        try { if (currentInstance != null && currentInstance.groupComboBox != null && currentInstance.groupComboBox.getSelectedItem() != null) curGroup = currentInstance.groupComboBox.getSelectedItem().toString(); } catch (Exception ignore) {}
        // 如果当前是默认分组，直接在模型中交换（保持向后兼容）
        if (GroupManager.DEFAULT_GROUP.equals(curGroup)) {
            DefaultTableModel model = (DefaultTableModel) table.getModel();
            int m1 = table.convertRowIndexToModel(sel);
            int m2 = table.convertRowIndexToModel(target);
            Vector r1 = (Vector) model.getDataVector().elementAt(m1);
            Vector r2 = (Vector) model.getDataVector().elementAt(m2);
            model.getDataVector().set(m1, r2);
            model.getDataVector().set(m2, r1);
            model.fireTableDataChanged();
            javax.swing.ListSelectionModel sm0 = table.getSelectionModel();
            boolean prev0 = sm0.getValueIsAdjusting();
            try {
                sm0.setValueIsAdjusting(true);
                table.setRowSelectionInterval(target, target);
            } finally {
                sm0.setValueIsAdjusting(prev0);
            }
            saveTableConfigFromModel();
            return;
        }
        // 非默认分组：在分组内部调整顺序，不修改全局模型
        try {
            Object o1 = table.getValueAt(sel, handler.codeColumnIndex);
            Object o2 = table.getValueAt(target, handler.codeColumnIndex);
            if (o1 == null || o2 == null) return;
            String code1 = o1.toString().replace("rt_", "").trim();
            String code2 = o2.toString().replace("rt_", "").trim();
            List<String> list = GroupManager.getInstance().getStocksInGroup(curGroup);
            int idx1 = -1, idx2 = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).equalsIgnoreCase(code1)) idx1 = i;
                if (list.get(i).equalsIgnoreCase(code2)) idx2 = i;
            }
            if (idx1 < 0 || idx2 < 0) return;
            // 将 code1 移动到 idx2 的位置
            list.remove(idx1);
            if (idx2 >= list.size()) list.add(code1); else list.add(idx2, code1);
            GroupManager.getInstance().setStocksInGroup(curGroup, list);
            // 重新应用过滤与分组排序
            if (currentInstance != null) currentInstance.filterTableByGroup(curGroup);
            // 选中移动后的行
            for (int r = 0; r < table.getRowCount(); r++) {
                Object v = table.getValueAt(r, handler.codeColumnIndex);
                if (v != null && code1.equalsIgnoreCase(v.toString().replace("rt_", "").trim())) {
                    javax.swing.ListSelectionModel sm = table.getSelectionModel();
                    boolean prev = sm.getValueIsAdjusting();
                    try {
                        sm.setValueIsAdjusting(true);
                        table.setRowSelectionInterval(r, r);
                    } finally { sm.setValueIsAdjusting(prev); }
                    break;
                }
            }
        } catch (Exception ignore) {}
    }

    private void clearSortAndReload() {
        SwingUtilities.invokeLater(() -> {
            headerSortState.clear();
            headerRecordedOrder = null;
            headerRecordedColumn = -1;
            try {
                javax.swing.RowSorter<?> sorter = table.getRowSorter();
                if (sorter != null) sorter.setSortKeys(null);
                if (table != null && table.getTableHeader() != null) table.getTableHeader().repaint();
            } catch (Exception ignore) {}
            try { commitTableEdits(); } catch (Exception ignore) {}
            try { apply(); } catch (Exception ex) { LogUtil.info("apply 重载失败: " + ex.getMessage()); }
            try { if (currentInstance != null) currentInstance.refreshImportantPanel(); } catch (Exception ignore) {}
            try { updateSummaryLabels(); } catch (Exception ignore) {}
            try { if (currentInstance != null) { currentInstance.topPanel.revalidate(); currentInstance.topPanel.repaint(); } } catch (Exception ignore) {}
        });
    }

    private static void editCellValue() {
        if (table == null || table.getModel() == null || table.getSelectedRow() < 0 || handler == null) return;
        String code = String.valueOf(table.getModel().getValueAt(
                table.convertRowIndexToModel(table.getSelectedRow()), handler.codeColumnIndex));
        int row = table.getSelectedRow();
        int col = table.getSelectedColumn();
        if (row < 0 || col < 0) return;
        String colName = table.getColumnName(col);
        String costVal = "-1", bondsVal = "-1";
        String oldVal = table.getValueAt(row, col).toString();
        if (colName.equals(COST_COLUMN_NAME)) costVal = oldVal;
        else if (colName.equals(BONDS_COLUMN_NAME)) bondsVal = oldVal;

        String key = getKeyForName("Stock");
        String configStr = instance.getValue(key);
        if (StringUtils.isNotBlank(configStr)) {
            String[] parts = configStr.split(";");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part.contains(code)) {
                    String[] arr = part.split(",");
                    if (arr.length == 1) {
                        if (!costVal.equals("-1") && StringUtils.isNotBlank(costVal)) part = part + "," + costVal + ",";
                        else if (!bondsVal.equals("-1") && StringUtils.isNotBlank(bondsVal)) part = part + ",," + bondsVal;
                    } else if (arr.length == 2) {
                        String s1 = arr[1];
                        if (StringUtils.isNumeric(s1) && Integer.parseInt(s1) % 100 == 0) {
                            if (!bondsVal.equals("-1")) bondsVal = s1;
                            part = arr[0] + "," + costVal + "," + bondsVal;
                        } else {
                            if (!costVal.equals("-1")) costVal = s1;
                            part = arr[0] + "," + costVal + "," + bondsVal;
                        }
                    } else if (arr.length == 3) {
                        if (!bondsVal.equals("-1")) bondsVal = arr[2];
                        if (!costVal.equals("-1")) costVal = arr[1];
                        part = arr[0] + "," + costVal + "," + bondsVal;
                    }
                }
                if (part.split(",").length == 1) part = part.replaceAll(",", "");
                sb.append(part).append(";");
            }
            instance.setValue(key, sb.toString());
        }
        // 如果只是编辑成本价或持仓，则不触发完整的 apply()（避免重新注册刷新任务）
        if (Objects.equals(colName, COST_COLUMN_NAME) || Objects.equals(colName, BONDS_COLUMN_NAME)) {
            try {
                DefaultTableModel model = (DefaultTableModel) table.getModel();
                int modelRow = table.convertRowIndexToModel(row);
                refreshIncomeColumns(model, modelRow);
                SwingUtilities.invokeLater(() -> { try { updateSummaryLabels(); } catch (Exception ignore) {} });
            } catch (Exception ignore) {}
        } else if (Objects.equals(colName, ACCOUNT_COLUMN_NAME)) {
            SwingUtilities.invokeLater(() -> { try { updateSummaryLabels(); } catch (Exception ignore) {} });
        } else {
            apply();
        }
    }

    private static void bindEditPersistenceListener() {
        if (handler == null) return;
        // 移除旧的编辑持久化监听器
        if (editPersistenceListener != null) handler.removeTableModelListener(editPersistenceListener);
        editPersistenceListener = (TableModelEvent e) -> {
            if (e.getType() == TableModelEvent.UPDATE) {
                if (suppressTableEditListener.get()) return;
                persistEditedCell(e.getFirstRow(), e.getColumn());
            }
        };
        handler.addTableModelListener(editPersistenceListener);

        // 移除旧的重要面板刷新监听器（如果存在），并注册新的监听器以便在数据更新时刷新重要区域
        try {
            if (importantPanelModelListener != null) handler.removeTableModelListener(importantPanelModelListener);
        } catch (Exception ignore) {}
        importantPanelModelListener = (TableModelEvent e) -> {
            if (e.getType() == TableModelEvent.UPDATE) {
                if (currentInstance != null && currentInstance.importantPanel != null) {
                    // Debounce frequent updates to avoid UI flicker: restart a short single-shot timer
                    try {
                        if (currentInstance.importantPanelRefreshTimer == null) {
                            currentInstance.importantPanelRefreshTimer = new javax.swing.Timer(300, ev -> {
                                try { currentInstance.refreshImportantPanel(); } catch (Exception ignore) {}
                            });
                            currentInstance.importantPanelRefreshTimer.setRepeats(false);
                        } else {
                            currentInstance.importantPanelRefreshTimer.stop();
                        }
                        currentInstance.importantPanelRefreshTimer.start();
                    } catch (Exception ignore) {
                        SwingUtilities.invokeLater(() -> { try { currentInstance.refreshImportantPanel(); } catch (Exception ex) {} });
                    }
                }
            }
        };
        try { handler.addTableModelListener(importantPanelModelListener); } catch (Exception ignore) {}
        // 注册 tableCellEditor 属性监听器，以便在编辑停止时可靠地持久化编辑内容（回车/失焦等场景）
        try {
            if (!editingListenerAdded && table != null) {
                table.addPropertyChangeListener("tableCellEditor", evt -> {
                    try {
                        if (table.isEditing()) {
                            lastEditingViewRow = table.getEditingRow();
                            lastEditingViewCol = table.getEditingColumn();
                        } else {
                            int viewRow = lastEditingViewRow; int viewCol = lastEditingViewCol;
                            lastEditingViewRow = -1; lastEditingViewCol = -1;
                            if (viewRow >= 0 && viewCol >= 0) {
                                int modelRow = table.convertRowIndexToModel(viewRow);
                                int modelCol = table.convertColumnIndexToModel(viewCol);
                                SwingUtilities.invokeLater(() -> { try { persistEditedCell(modelRow, modelCol); } catch (Exception ignore) {} });
                            } else {
                                int selViewRow = table.getSelectedRow(); int selViewCol = table.getSelectedColumn();
                                if (selViewRow >= 0 && selViewCol >= 0) {
                                    int mr = table.convertRowIndexToModel(selViewRow);
                                    int mc = table.convertColumnIndexToModel(selViewCol);
                                    SwingUtilities.invokeLater(() -> { try { persistEditedCell(mr, mc); } catch (Exception ignore) {} });
                                }
                            }
                        }
                    } catch (Exception ignore) {}
                });
                editingListenerAdded = true;
            }
        } catch (Exception ignore) {}
    }

    private static void persistEditedCell(int row, int col) {
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        if (row < 0 || col < 0 || row >= model.getRowCount() || col >= model.getColumnCount()) return;
        String colName = WindowUtils.remapPinYin(model.getColumnName(col));
        if (!Objects.equals(colName, COST_COLUMN_NAME) && !Objects.equals(colName, BONDS_COLUMN_NAME)
                && !Objects.equals(colName, ACCOUNT_COLUMN_NAME)) return;
        // 如果当前正在由程序性更新驱动（例如我们在内部调用 setValueAt），则避免再次进入持久化逻辑
        if (suppressTableEditListener.get()) return;

        // 进入持久化处理时抑制表格事件监听，防止 setValueAt 导致的递归触发
        suppressTableEditListener.set(true);
        try {
            String code = Objects.toString(model.getValueAt(row, handler.codeColumnIndex), "");
            // 跳过分隔线
            if (StringUtils.isBlank(code) || code.startsWith("---sep")) return;
            // 统一归一化 code（去除 rt_ 前缀等），以便在 key_stocks 中匹配
            code = normalizeCode(code);
            int costIdx = findColumnIndex(COST_COLUMN_NAME);
            int bondsIdx = findColumnIndex(BONDS_COLUMN_NAME);
            String costVal = costIdx >= 0 ? normalizeStockValue(Objects.toString(model.getValueAt(row, costIdx), "")) : "";
            String bondsVal = bondsIdx >= 0 ? normalizeStockValue(Objects.toString(model.getValueAt(row, bondsIdx), "")) : "";
            int accountIdx = findColumnIndex(ACCOUNT_COLUMN_NAME);
            String accountRaw = accountIdx >= 0 ? Objects.toString(model.getValueAt(row, accountIdx), "") : "";
            String accountVal = WindowUtils.normalizeAccount(normalizeStockValue(accountRaw));
            // 如果成本价和持仓都被清空，则同步清空账号（变为不可编辑状态）
            if ((StringUtils.isBlank(costVal) || "--".equals(costVal)) && (StringUtils.isBlank(bondsVal) || "--".equals(bondsVal))) {
                accountVal = "";
                if (accountIdx >= 0) model.setValueAt(accountVal, row, accountIdx);
            } else {
                // 如果编辑了成本价或持仓且账号为空，则默认分配为账号1，便于统计聚合
                if (StringUtils.isBlank(accountVal) && (Objects.equals(colName, COST_COLUMN_NAME) || Objects.equals(colName, BONDS_COLUMN_NAME))) {
                    if (StringUtils.isNotBlank(costVal) || StringUtils.isNotBlank(bondsVal)) {
                        accountVal = "1"; // default to numeric account 1
                        if (accountIdx >= 0) model.setValueAt(accountVal, row, accountIdx);
                    }
                }
            }
            String key = getKeyForName("Stock");
            String configStr = instance.getValue(key);
            String[] parts = StringUtils.isBlank(configStr) ? new String[0] : configStr.split(";");
            StringBuilder sb = new StringBuilder();
            boolean found = false;
            for (String part : parts) {
                if (StringUtils.isBlank(part)) continue;
                if (!found && Objects.equals(normalizeCode(extractStockCode(part)), code)) {
                    part = mergeStockConfig(code, costVal, bondsVal, accountVal);
                    found = true;
                }
                sb.append(part).append(";");
            }
            if (!found) sb.append(mergeStockConfig(code, costVal, bondsVal, accountVal)).append(";");
            instance.setValue(key, sb.toString());
            // 尝试在运行时更新 Quartz 任务的 JobDataMap，使定时任务立刻使用更新后的 key_stocks（避免重启后才生效）
            try {
                QuartzManager qm = QuartzManager.getInstance("Stock");
                java.util.HashMap<String, Object> dm = new java.util.HashMap<>();
                dm.put(HandlerJob.KEY_HANDLER, handler);
                dm.put(HandlerJob.KEY_CODES, loadStocks());
                qm.updateJobData(dm);
            } catch (Exception ignore) {}
            refreshIncomeColumns(model, row);
            if (Objects.equals(colName, ACCOUNT_COLUMN_NAME)) {
                // 仅更新统计显示，不触发重新注册刷新定时任务
                SwingUtilities.invokeLater(() -> { try { updateSummaryLabels(); } catch (Exception ignore) {} });
            } else if (Objects.equals(colName, COST_COLUMN_NAME) || Objects.equals(colName, BONDS_COLUMN_NAME)) {
                // 编辑成本或持仓时只更新相关列与统计，避免触发完整的 apply()/任务重建
                SwingUtilities.invokeLater(() -> { try { updateSummaryLabels(); } catch (Exception ignore) {} });
            } else {
                SwingUtilities.invokeLater(StockWindow::apply);
            }
        } finally {
            // 确保在任何情况下都释放抑制标志
            suppressTableEditListener.set(false);
        }
    }

    private static int findColumnIndex(String name) {
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        for (int i = 0; i < model.getColumnCount(); i++) {
            if (Objects.equals(WindowUtils.remapPinYin(model.getColumnName(i)), name)) return i;
        }
        return -1;
    }

    private static String normalizeCode(String code) {
        if (code == null) return "";
        String c = code.trim();
        if (c.startsWith("rt_")) c = c.substring(3);
        return c.trim();
    }

    private static boolean isCodeOrNameColumn(int col) {
        if (col < 0 || table == null) return false;
        String name = WindowUtils.remapPinYin(table.getColumnName(col));
        return "编码".equals(name) || "股票名称".equals(name);
    }

    private static void saveTableConfigFromModel() {
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        int costIdx = findColumnIndex(COST_COLUMN_NAME);
        int bondsIdx = findColumnIndex(BONDS_COLUMN_NAME);
        int accountIdx = findColumnIndex(ACCOUNT_COLUMN_NAME);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < model.getRowCount(); i++) {
            String code = Objects.toString(model.getValueAt(i, handler.codeColumnIndex), "");
            // 保留分隔线（以特殊 code 标记），仅跳过空白行
            if (StringUtils.isBlank(code)) continue;
            String cost = costIdx >= 0 ? normalizeStockValue(Objects.toString(model.getValueAt(i, costIdx), "")) : "";
            String bonds = bondsIdx >= 0 ? normalizeStockValue(Objects.toString(model.getValueAt(i, bondsIdx), "")) : "";
            String acctRaw = accountIdx >= 0 ? Objects.toString(model.getValueAt(i, accountIdx), "") : "";
            String acct = WindowUtils.normalizeAccount(normalizeStockValue(acctRaw));
            sb.append(mergeStockConfig(code, cost, bonds, acct)).append(";");
        }
        instance.setValue(getKeyForName("Stock"), sb.toString());
    }

    private static void refreshIncomeColumns(DefaultTableModel model, int row) {
        if (model == null || row < 0 || row >= model.getRowCount()) return;
        int nowIdx = findColumnIndex(NOW_COLUMN_NAME);
        int costIdx = findColumnIndex(COST_COLUMN_NAME);
        int bondsIdx = findColumnIndex(BONDS_COLUMN_NAME);
        int ipIdx = findColumnIndex(INCOME_PERCENT_COLUMN_NAME);
        int iIdx = findColumnIndex(INCOME_COLUMN_NAME);
        if (nowIdx < 0 || costIdx < 0 || bondsIdx < 0 || ipIdx < 0 || iIdx < 0) return;
        String ns = normalizeStockValue(Objects.toString(model.getValueAt(row, nowIdx), ""));
        String cs = normalizeStockValue(Objects.toString(model.getValueAt(row, costIdx), ""));
        String bs = normalizeStockValue(Objects.toString(model.getValueAt(row, bondsIdx), ""));
        String ip = "", inc = "";
        if (StringUtils.isNotBlank(ns) && StringUtils.isNotBlank(cs)) {
            try {
                BigDecimal n = new BigDecimal(ns), c = new BigDecimal(cs), d = n.subtract(c);
                ip = c.compareTo(BigDecimal.ZERO) > 0 ? d.divide(c, 5, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.TEN).multiply(BigDecimal.TEN).setScale(3, RoundingMode.HALF_UP)
                        .toPlainString() + "%" : "0.000%";
                if (StringUtils.isNotBlank(bs)) inc = d.multiply(new BigDecimal(bs)).setScale(2, RoundingMode.HALF_UP).toPlainString();
            } catch (NumberFormatException e) { ip = ""; inc = ""; }
        }
        model.setValueAt(ip, row, ipIdx);
        // Ensure consistent formatting (respect "以万为单位" setting) to avoid UI flicker
        model.setValueAt(StockBean.formatWanIfEnabled(StringUtils.defaultString(inc)), row, iIdx);
    }

    private static String extractStockCode(String config) { return StringUtils.substringBefore(config, ","); }

    private static String mergeStockConfig(String code, String cost, String bonds) {
        return mergeStockConfig(code, cost, bonds, "");
    }

    private static String mergeStockConfig(String code, String cost, String bonds, String account) {
        String c = normalizeStockValue(cost), b = normalizeStockValue(bonds);
        String acct = normalizeStockValue(account);
        // normalize account to numeric-only (strip any leading '账号' or non-digits)
        acct = WindowUtils.normalizeAccount(acct);

        // Preserve alertConfig from existing config (field 6+, may contain commas)
        String existingAlertCfg = getAlertConfigForCode(code);

        StringBuilder sb = new StringBuilder(code);
        sb.append(",").append(c);    // field 1: costPrice
        sb.append(",").append(b);    // field 2: bonds
        sb.append(",,");             // field 3: reserved, field 4: account
        sb.append(acct);
        sb.append(",,");             // field 5: reserved, field 6: alertConfig
        if (StringUtils.isNotBlank(existingAlertCfg)) sb.append(existingAlertCfg);

        // trim trailing commas (but not ones inside alertConfig)
        String result = sb.toString();
        if (StringUtils.isBlank(existingAlertCfg)) {
            while (result.endsWith(",")) result = result.substring(0, result.length() - 1);
        }
        if (StringUtils.isBlank(result.replaceAll("[,]+", ""))) return code;
        return result;
    }

    private static String normalizeStockValue(String val) {
        String t = StringUtils.trimToEmpty(val);
        if (Objects.equals(t, "--")) return "";
        // 尝试解析带单位的数字（例如 1.23万、0.12亿、1,234.56），并以纯数字字符串形式存储
        try {
            double parsed = parseNumberWithUnits(t);
            if (!Double.isNaN(parsed)) {
                java.math.BigDecimal bd = java.math.BigDecimal.valueOf(parsed).stripTrailingZeros();
                return bd.toPlainString();
            }
        } catch (Exception ignore) {}
        // 回退：返回原始修剪字符串
        return t;
    }

    private static double parseNumberWithUnits(String s) {
        if (StringUtils.isBlank(s)) return Double.NaN;
        String t = s.replaceAll(",", "").trim();
        try { return Double.parseDouble(t); } catch (Exception ignore) {}
        try {
            if (t.endsWith("万亿")) {
                String core = t.substring(0, t.length() - 2).trim();
                return new java.math.BigDecimal(core).multiply(new java.math.BigDecimal("1000000000000")).setScale(6, RoundingMode.HALF_UP).doubleValue();
            }
            if (t.endsWith("亿")) {
                String core = t.substring(0, t.length() - 1).trim();
                return new java.math.BigDecimal(core).multiply(new java.math.BigDecimal("100000000")).setScale(6, RoundingMode.HALF_UP).doubleValue();
            }
            if (t.endsWith("万")) {
                String core = t.substring(0, t.length() - 1).trim();
                return new java.math.BigDecimal(core).multiply(new java.math.BigDecimal("10000")).setScale(6, RoundingMode.HALF_UP).doubleValue();
            }
        } catch (Exception ignore) {}
        String digits = t.replaceAll("[^0-9.\\-]", "");
        try { return Double.parseDouble(digits); } catch (Exception ignore) { return Double.NaN; }
    }

    private static String formatAmountForImportant(String raw) {
        if (StringUtils.isBlank(raw) || "--".equals(raw)) return "--";
        try {
            String t = raw.replaceAll(",", "").trim();
            // Convert any existing unit to value expressed in 万 (ten-thousands)
            java.math.BigDecimal valWan;
            if (t.endsWith("万亿")) {
                String core = t.substring(0, t.length() - 2).trim();
                valWan = new java.math.BigDecimal(core).multiply(new java.math.BigDecimal("100000000")); // 1 万亿 = 1e8 万
            } else if (t.endsWith("亿")) {
                String core = t.substring(0, t.length() - 1).trim();
                valWan = new java.math.BigDecimal(core).multiply(new java.math.BigDecimal("10000")); // 1 亿 = 10000 万
            } else if (t.endsWith("万")) {
                String core = t.substring(0, t.length() - 1).trim();
                valWan = new java.math.BigDecimal(core);
            } else {
                // treat plain numeric as value in 元 (raw), convert to 万 units
                valWan = new java.math.BigDecimal(t).divide(new java.math.BigDecimal("10000"), 6, RoundingMode.HALF_UP);
            }

            java.math.BigDecimal abs = valWan.abs();
            java.math.BigDecimal TEN_THOUSAND = new java.math.BigDecimal("10000"); // 10000 万 = 1 亿
            java.math.BigDecimal HUNDRED_MILLION = new java.math.BigDecimal("100000000"); // 1e8 万 = 1 万亿

            // Rule: if valWan > 1 and < 10000 -> show in 万; if >=10000 and <100000000 -> show in 亿; >=100000000 -> show in 万亿
            if (abs.compareTo(TEN_THOUSAND) < 0) {
                // show as 万
                return valWan.setScale(2, RoundingMode.HALF_UP).toPlainString() + "万";
            } else if (abs.compareTo(HUNDRED_MILLION) < 0) {
                java.math.BigDecimal out = valWan.divide(TEN_THOUSAND, 2, RoundingMode.HALF_UP);
                return out.toPlainString() + "亿";
            } else {
                java.math.BigDecimal out = valWan.divide(HUNDRED_MILLION, 2, RoundingMode.HALF_UP);
                return out.toPlainString() + "万亿";
            }
        } catch (Exception e) {
            return StringUtils.defaultString(raw);
        }
    }

    public StockWindow() {
        currentInstance = this;
        if (mPanel == null) mPanel = new JPanel(new BorderLayout());
        handler = factoryHandler();
        try { handler.setOnDataRefreshed(() -> updateSummaryLabels()); } catch (Exception ignore) {}
        initSearchDialog();

        AnActionButton refreshAction = new AnActionButton("停止刷新当前表格数据", AllIcons.Actions.Pause) {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { stop(); this.setEnabled(false); }
        };
        ToolbarDecorator toolbarDecorator = ToolbarDecorator.createDecorator(table)
                .addExtraAction(new AnActionButton("持续刷新当前表格数据", AllIcons.Actions.Refresh) {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) { refresh(); refreshAction.setEnabled(true); }
                })
                .addExtraAction(refreshAction)
                .addExtraAction(new AnActionButton("添加分隔线", AllIcons.General.Add) {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) {
                        handler.addRow(new Vector<>(Collections.nCopies(StockRefreshHandler.columnNames.length, "")));
                        // 在分隔行的 code 列打上标记
                        int lastRow = handler.getRowCount() - 1;
                        String sepCode = "---sep" + System.currentTimeMillis() + "---";
                        int codeIdx = handler.codeColumnIndex;
                        if (codeIdx >= 0) handler.setValueAt(sepCode, lastRow, codeIdx);
                        int nameIdx = findColumnIndex("股票名称");
                        if (nameIdx >= 0) handler.setValueAt("━━━━━━━━━━━━", lastRow, nameIdx);
                        saveTableConfigFromModel();
                    }
                })
                .addExtraAction(new AnActionButton("清除排序", AllIcons.Actions.Rollback) {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) {
                        if (currentInstance != null) currentInstance.clearSortAndReload();
                    }
                })
                .setToolbarPosition(ActionToolbarPosition.TOP);
        JPanel toolPanel = toolbarDecorator.createPanel();
        toolbarDecorator.getActionsPanel().add(refreshTimeLabel, "East");
        toolPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
        mPanel.add(toolPanel, BorderLayout.CENTER);

        topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        // 动态创建统计标签: 标题(粗体) + 5列数值(非粗体)
        int accountCount = 1;
        try { accountCount = Integer.parseInt(PropertiesComponent.getInstance().getValue("key_account_count", "1")); }
        catch (NumberFormatException e) { accountCount = 1; }
        if (accountCount < 1) accountCount = 1; if (accountCount > 3) accountCount = 3;
        summaryPanel = new JPanel();
        summaryPanel.setLayout(new BoxLayout(summaryPanel, BoxLayout.Y_AXIS));
        int totalRows = accountCount > 1 ? accountCount + 1 : 1;
        accountSummaryLabels = new JLabel[totalRows * 6];
        String[] colNames = {"总金额", "当日收益", "当日收益率", "累计收益", "本金"};
        int[] colWidths = {115, 115, 115, 115, 105};
        Font boldFont = new Font(summaryPanel.getFont().getName(), Font.BOLD, 11);
        Font plainFont = new Font(summaryPanel.getFont().getName(), Font.PLAIN, 11);
        for (int r = 0; r < totalRows; r++) {
            boolean isTotalRow = (accountCount > 1 && r == accountCount);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 2));
            int titleIdx = r * 6;
            String title;
            if (isTotalRow) title = "总  计";
            else if (accountCount > 1) title = "账号" + (r + 1);
            else title = "";
            accountSummaryLabels[titleIdx] = new JLabel(title);
            accountSummaryLabels[titleIdx].setFont(boldFont);
            accountSummaryLabels[titleIdx].setHorizontalAlignment(SwingConstants.LEFT);
            row.add(accountSummaryLabels[titleIdx]);
            for (int c2 = 0; c2 < 5; c2++) {
                int idx = titleIdx + 1 + c2;
                accountSummaryLabels[idx] = new JLabel(colNames[c2] + ": --");
                accountSummaryLabels[idx].setFont(plainFont);
                accountSummaryLabels[idx].setPreferredSize(new Dimension(colWidths[c2], 16));
                row.add(accountSummaryLabels[idx]);
            }
            summaryPanel.add(row);
        }
        // 已移除 summary 中的当日机会显示（改为独立列展示）
        topPanel.add(summaryPanel);
        // 重要股票区域：显示在股票列表下方（界面从上到下为：持仓信息、股票列表、重要股票信息）
        // 在此处不直接初始化重要面板，稍后将其添加到 mPanel 的 SOUTH

        JPanel groupPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        groupPanel.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showGroupOrderDialog(groupPanel);
                }
            }
        });
        // keep a reference so settings apply() can rebuild tabs immediately
        this.groupTabPanel = groupPanel;
        groupComboBox = new JComboBox<>();
        rebuildGroupTabs(groupPanel);
        groupComboBox.addItemListener(e -> { if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) filterTableByGroup((String) e.getItem()); });
        groupComboBox.setVisible(false);

        JButton addBtn = new JButton("+");
        addBtn.setToolTipText("添加新分组");
        addBtn.setFont(addBtn.getFont().deriveFont(11.0f));
        addBtn.setMargin(new Insets(1, 6, 1, 6));
        addBtn.setFocusPainted(false); addBtn.setRolloverEnabled(false);
        addBtn.setContentAreaFilled(false); addBtn.setBorderPainted(false); addBtn.setOpaque(false);
        addBtn.addActionListener(e -> {
            String nn = JOptionPane.showInputDialog(groupPanel, "输入新分组名称:");
            if (StringUtils.isNotBlank(nn)) { GroupManager.getInstance().addGroup(nn); rebuildGroupTabs(groupPanel); }
        });

        // 将 groupPanel 的大小变化绑定为重建按钮布局的触发器，便于在窗口宽度变化时自适应分组尺寸
        groupPanel.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { rebuildGroupTabs(groupPanel); }
        });

        // 表头点击处理：三态循环为 初始(未排序) -> 降序 -> 升序 -> 恢复初始顺序
        try {
            javax.swing.table.JTableHeader header = table.getTableHeader();
            for (java.awt.event.MouseListener listener : header.getMouseListeners()) {
                String className = listener.getClass().getName();
                if (className.contains("BasicTableHeaderUI") || className.contains("MouseInputHandler")) {
                    header.removeMouseListener(listener);
                }
            }
            for (java.awt.event.MouseMotionListener listener : header.getMouseMotionListeners()) {
                String className = listener.getClass().getName();
                if (className.contains("BasicTableHeaderUI") || className.contains("MouseInputHandler")) {
                    header.removeMouseMotionListener(listener);
                }
            }
        } catch (Exception ignore) {}

        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int viewCol = table.getTableHeader().columnAtPoint(e.getPoint());
                if (viewCol < 0) return;
                int modelCol = table.convertColumnIndexToModel(viewCol);
                try {
                    javax.swing.table.TableRowSorter<?> sorter;
                    if (table.getRowSorter() instanceof javax.swing.table.TableRowSorter) {
                        sorter = (javax.swing.table.TableRowSorter<?>) table.getRowSorter();
                    } else {
                        sorter = new javax.swing.table.TableRowSorter<>((DefaultTableModel) table.getModel());
                        table.setRowSorter(sorter);
                    }
                    java.util.List<? extends javax.swing.RowSorter.SortKey> keys = sorter.getSortKeys();
                    // 点击提示：展示列/排序/状态/快照信息，便于用户反馈复现步骤
                    try {
                        String headerName = null;
                        try { headerName = table.getColumnModel().getColumn(viewCol).getHeaderValue().toString(); } catch (Exception ignore) { try { headerName = table.getColumnName(viewCol); } catch (Exception ignore2) { headerName = String.valueOf(modelCol); } }
                        int headerStateVal = headerSortState.getOrDefault(modelCol, -999);
                        int snapshotSize = -1;
                        try { if (handler != null) { java.util.List<String> tmp = handler.getOriginalOrderSnapshot(); snapshotSize = tmp == null ? -1 : tmp.size(); } } catch (Exception ignore) {}
                        String keysStr = keys == null ? "null" : keys.toString();
                    } catch (Exception ignore) {}

                    int currentHeaderState = headerSortState.getOrDefault(modelCol, 0);
                    if (currentHeaderState == 0) {
                        try {
                            DefaultTableModel model = (DefaultTableModel) table.getModel();
                            if (model != null && handler != null) {
                                try { handler.recordOriginalIndexSnapshot(); } catch (Exception ignore) {}
                                headerRecordedColumn = modelCol;
                                try {
                                    int snapshotSize2 = -1;
                                    try { java.util.List<String> tmp = handler.getOriginalOrderSnapshot(); snapshotSize2 = tmp == null ? -1 : tmp.size(); } catch (Exception ignore) {}
                                    String headerName1 = null;
                                    try { headerName1 = table.getColumnModel().getColumn(viewCol).getHeaderValue().toString(); } catch (Exception ignore) { headerName1 = table.getColumnName(viewCol); }
                                } catch (Exception ignore) {}
                            } else {
                                headerRecordedColumn = -1;
                            }
                        } catch (Exception ignore) { headerRecordedColumn = -1; }

                        javax.swing.RowSorter.SortKey k = new javax.swing.RowSorter.SortKey(modelCol, javax.swing.SortOrder.DESCENDING);
                        sorter.setSortKeys(java.util.Arrays.asList(k));
                        headerSortState.put(modelCol, 1);
                        try { sorter.sort(); } catch (Exception ignore) {}
                        try { table.getTableHeader().repaint(); table.repaint(); } catch (Exception ignore) {}
                        return;
                    }

                    if (currentHeaderState == 1) {
                        javax.swing.RowSorter.SortKey newKey = new javax.swing.RowSorter.SortKey(modelCol, javax.swing.SortOrder.ASCENDING);
                        sorter.setSortKeys(java.util.Arrays.asList(newKey));
                        headerSortState.put(modelCol, 2);
                        try { sorter.sort(); } catch (Exception ignore) {}
                        try { table.getTableHeader().repaint(); table.repaint(); } catch (Exception ignore) {}
                        return;
                    }

                    try {
                        String headerName3 = null;
                        try { headerName3 = table.getColumnModel().getColumn(viewCol).getHeaderValue().toString(); } catch (Exception ignore) { headerName3 = table.getColumnName(viewCol); }
                    } catch (Exception ignore) {}
                    try {
                        headerSortState.remove(modelCol);
                        headerRecordedOrder = null; headerRecordedColumn = -1;
                        if (sorter != null) {
                            try { sorter.setSortKeys(null); } catch (Exception ignore) {}
                            try { sorter.sort(); } catch (Exception ignore) {}
                        }
                        try { table.getTableHeader().repaint(); table.repaint(); } catch (Exception ignore) {}
                    } catch (Exception ignore) {}
                } catch (Exception ex) { LogUtil.info("表头排序失败: " + ex.getMessage()); }
            }
        });
        groupPanel.add(addBtn);
        // 包裹 groupPanel 以支持横向滚动，避免过多分组挤出可见区域
        this.groupScroll = new JBScrollPane(groupPanel);
        this.groupScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // 允许垂直滚动或根据 wrapCount 动态调整高度
        this.groupScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        this.groupScroll.setBorder(BorderFactory.createEmptyBorder());
        topPanel.add(this.groupScroll);
        mPanel.add(topPanel, BorderLayout.NORTH);

        apply();
        GroupManager.getInstance().initFromExisting();
        rebuildGroupTabs(groupPanel);
        // 修复 Bug 3：默认选中第一个分组(通过comboBox触发listener+过滤)
        if (groupComboBox.getItemCount() > 0) {
            groupComboBox.setSelectedIndex(0);
        }
        bindGlobalKeyListener();
        implementRowDragAndDrop();
    }

    private void initSearchDialog() {
        // 获取屏幕的宽度和高度
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        // 创建搜索弹窗
        searchDialog = new JDialog((JFrame) null, false);
        searchDialog.setUndecorated(true); // 无标题栏
        searchDialog.setSize(600, 50);    // 设置大小
        searchDialog.setLayout(new BorderLayout());
        searchDialog.setLocationRelativeTo(null); // 居中显示
        searchDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); // 默认隐藏
        // 设置透明度
        searchDialog.setOpacity(0.85f);  // 设置透明度，范围从0.0 (完全透明) 到 1.0 (完全不透明)

        // 背景面板
        JPanel cp = new JPanel(new BorderLayout());
        cp.setBackground(new Color(0, 0, 0, 0)); // 确保背景是透明的
        searchDialog.add(cp, BorderLayout.CENTER);  // 添加自定义面板到弹窗

        // 搜索输入框
        searchField = new JTextField(50);
        Font lf = UIUtil.getLabelFont();
        searchField.setFont(lf);
        searchField.setBackground(JBColor.background()); // 搜索框背景颜色跟随系统主题
        searchField.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        searchField.setPreferredSize(new Dimension(0, 50)); // 控制高度

        // 添加鼠标事件监听器以实现拖动功能
        searchField.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { initialClick = e.getPoint(); }
        });
        searchField.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - initialClick.x, dy = e.getY() - initialClick.y;
                Point loc = searchDialog.getLocation(); searchDialog.setLocation(loc.x + dx, loc.y + dy);
            }
        });

        int x = (screenSize.width - searchDialog.getWidth()) / 2;
        int y = (int) (screenSize.height * 0.2); // 距离顶部20%
        searchDialog.setLocation(x, y);
        cp.add(searchField, BorderLayout.NORTH);

        // 搜索结果列表
        listModel = new DefaultListModel<>();
        resultList = new JBList<>(listModel);
        resultList.setFont(lf);
        resultList.setBackground(JBColor.background()); // 列表背景颜色
        resultList.setForeground(JBColor.foreground()); // 列表字体颜色
        resultList.setSelectionBackground(UIUtil.getListSelectionBackground(true)); // 选中背景
        resultList.setSelectionForeground(UIUtil.getListSelectionForeground(true)); // 选中字体颜色
        resultList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setForeground(JBColor.foreground());
                if (value != null && value.toString().endsWith("-已添加")) {
                    label.setForeground(JBColor.GREEN); // 已添加时字体颜色为绿色
                }
                label.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 20)); // 设置内边距
                return label;
            }
        });

        // 滚动面板包装结果列表
        JBScrollPane sp = new JBScrollPane(resultList);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setVisible(false);// 初始时隐藏列表

        // 搜索框键盘事件监听
        searchField.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) {
                String text = searchField.getText().trim();
                if (text.isEmpty()) {
                    listModel.clear();
                    if (sp.isVisible()) {
                        cp.remove(sp); sp.setVisible(false);
                        searchDialog.setSize(600, 50); searchDialog.revalidate();
                    }
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_ENTER) { handleSelection(); return; }
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { searchDialog.setVisible(false); return; }
                try {
                    // 在后台线程执行搜索，避免阻塞 EDT；取消之前未完成的请求
                    Future<?> prev = pendingSearch.getAndSet(ThreadPools.getRefreshExecutor().submit(() -> {
                        try {
                            List<String> results = handler.search(text);
                            SwingUtilities.invokeLater(() -> {
                                try {
                                    updateSearchResults(results);
                                    if (!listModel.isEmpty()) {
                                        if (!sp.isVisible()) {
                                            cp.add(sp, BorderLayout.CENTER); sp.setVisible(true);
                                            searchDialog.setSize(600, 600); searchDialog.revalidate();
                                        }
                                    } else if (sp.isVisible()) {
                                        cp.remove(sp); sp.setVisible(false);
                                        searchDialog.setSize(600, 50); searchDialog.revalidate();
                                    }
                                } catch (Exception ignore) {}
                            });
                        } catch (Exception ex) { LogUtil.info("Search error: " + ex.getMessage()); }
                    }));
                    if (prev != null) prev.cancel(true);
                } catch (Exception ex) { LogUtil.info("Search submit error: " + ex.getMessage()); }
                // 支持上下键导航（当结果刷新后可以按下箭头）
                if (e.getKeyCode() == KeyEvent.VK_DOWN && !listModel.isEmpty()) {
                    resultList.requestFocus(); resultList.setSelectedIndex(0);
                }
            }
        });

        // 鼠标选择建议
        resultList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && resultList.getSelectedValue() != null) handleSelection();
            }
        });
        // 结果列表键盘事件监听
        resultList.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && resultList.getSelectedValue() != null) handleSelection();
            }
        });
        // 监听焦点丢失，关闭弹窗
        searchDialog.addWindowFocusListener(new WindowFocusListener() {
            @Override public void windowLostFocus(WindowEvent e) { searchDialog.setVisible(false); }
            @Override public void windowGainedFocus(WindowEvent e) {}
        });
        // 监听 Esc 键关闭对话框
        searchDialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog");
        searchDialog.getRootPane().getActionMap().put("closeDialog", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { searchDialog.setVisible(false); }
        });
    }

    private void handleSelection() {
        int idx = resultList.getSelectedIndex();
        String sel = listModel.getElementAt(idx).toString();
        // 处理重要股票添加模式
        if (currentSearchMode == SearchMode.IMPORTANT_ADD) {
            try {
                String[] parts = sel.split("-");
                if (parts.length >= 2) {
                    String code = parts[1];
                    String k = "key_important_stocks";
                    String raw = instance.getValue(k, "");
                    Set<String> set = new LinkedHashSet<>();
                    if (StringUtils.isNotBlank(raw)) {
                        for (String s : raw.split(";")) if (StringUtils.isNotBlank(s)) set.add(s.trim());
                    }
                    if (!set.contains(code)) set.add(code);
                    StringBuilder sb = new StringBuilder();
                    for (String s : set) { if (sb.length() > 0) sb.append(";"); sb.append(s); }
                    instance.setValue(k, sb.toString());
                    // 记录以便刷新时定位并高亮新添加项
                    lastAddedImportantCode = code;
                    java.util.List<String> codesList = new java.util.ArrayList<>();
                    for (String s : sb.toString().split(";")) if (StringUtils.isNotBlank(s)) codesList.add(s.trim());
                    int newIndex = codesList.indexOf(code);
                    if (newIndex >= 0) importantRotateIndex = Math.max(0, newIndex / IMPORTANT_PAGE_SIZE);
                    // 如果表格中已经存在该股票，则直接刷新面板使用表格数据；否则后台拉取数据后再刷新
                    DefaultTableModel model = null;
                    try { model = (DefaultTableModel) table.getModel(); } catch (Exception ignore) {}
                    boolean existsInTable = false;
                    if (model != null) {
                        int codeIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "编码");
                        if (codeIdx >= 0) {
                            for (int r = 0; r < model.getRowCount(); r++) {
                                Object oc = model.getValueAt(r, codeIdx);
                                if (oc != null && code.equals(String.valueOf(oc))) { existsInTable = true; break; }
                            }
                        }
                    }
                    if (existsInTable) {
                        refreshImportantPanel();
                    } else {
                        // 后台拉取数据，避免阻塞 EDT
                        ThreadPools.getRefreshExecutor().execute(() -> {
                            try {
                                if (handler != null) handler.handle(Collections.singletonList(code));
                            } catch (Exception ex) { LogUtil.info("后台获取重要股票数据失败: " + ex.getMessage()); }
                            SwingUtilities.invokeLater(() -> refreshImportantPanel());
                        });
                    }
                }
            } catch (Exception ex) { LogUtil.info("添加重要股票失败: " + ex.getMessage()); }
            currentSearchMode = SearchMode.NORMAL;
            searchDialog.setVisible(false);
            return;
        }
        String key = getKeyForResult(sel);
        if (sel.endsWith("-已添加")) {
            String pure = sel.substring(0, sel.length() - 4);
            listModel.setElementAt(pure, idx); resultList.repaint();
            String config = instance.getValue(key);
            if (config != null) {
                String[] parts = pure.split("-");
                if (parts.length == 3) {
                    String[] cps = config.split(";"); StringBuilder sb = new StringBuilder();
                    for (String p : cps) { if (!p.contains(parts[1])) sb.append(p).append(";"); }
                    instance.setValue(key, sb.toString());
                }
            }
        } else {
            listModel.setElementAt(sel + "-已添加", idx); resultList.repaint();
            String[] parts = sel.split("-");
            if (parts.length == 3) {
                String ne = parts[1] + ",,";
                String config = instance.getValue(key);
                config = (config == null ? "" : config) + ne + ";";
                instance.setValue(key, config);
            }
        }
        apply();
    }

    private String getKeyForResult(String r) {
        if (r.startsWith("股票")) return "key_stocks";
        if (r.startsWith("基金")) return "key_funds";
        if (r.startsWith("债券")) return "key_coins";
        return "";
    }

    private static String getKeyForName(String name) {
        if (name.equals("Stock")) return "key_stocks";
        if (name.startsWith("Fund")) return "key_funds";
        if (name.startsWith("Coin")) return "key_coins";
        return "";
    }

    private void updateSearchResults(List<String> results) {
        listModel.clear();
        PropertiesComponent pc = PropertiesComponent.getInstance();
        for (String r : results) {
            String display = r;
            String key = getKeyForResult(r);
            String config = pc.getValue(key);
            if (currentSearchMode == SearchMode.IMPORTANT_ADD) {
                // 在重要股票添加模式下，根据 key_important_stocks 标记已添加
                String imp = pc.getValue("key_important_stocks");
                if (imp != null && r.split("-").length > 1 && imp.contains(r.split("-")[1])) display = display + "-已添加";
            } else {
                if (config != null && r.split("-").length > 1 && config.contains(r.split("-")[1])) display = display + "-已添加";
            }
            listModel.addElement(display);
        }
    }

    private void initImportantPanel(java.awt.Container parent) {
        JPanel wrapper = new JPanel(new BorderLayout());
        // 用外层边框作为卡片样式，内部行不再单独画边框
        wrapper.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY), new EmptyBorder(6, 6, 6, 6)));
        wrapper.setBackground(UIUtil.getPanelBackground());
        importantPanel = new JPanel();
        importantPanel.setLayout(new BoxLayout(importantPanel, BoxLayout.Y_AXIS));
        importantPanel.setOpaque(true);
        // 移除白色背景，重要股票区域使用透明/默认背景以便在深色主题下可读性更好
        importantPanel.setBackground(UIUtil.getPanelBackground());
        wrapper.add(importantPanel, BorderLayout.CENTER);
        // 将添加按钮放到右侧（同一行），避免额外占用一行高度
        JPanel sideBtnPanel = new JPanel(new GridBagLayout());
        sideBtnPanel.setOpaque(false);
        addImportantBtn = new JButton(AllIcons.General.Add);
        addImportantBtn.setToolTipText("添加重要股票");
        addImportantBtn.setPreferredSize(new Dimension(18, 18));
        addImportantBtn.addActionListener(e -> {
            currentSearchMode = SearchMode.IMPORTANT_ADD;
            listModel.clear(); searchField.setText("");
            if (searchDialog != null) { searchDialog.setVisible(true); searchField.requestFocus(); }
        });
        sideBtnPanel.add(addImportantBtn);
        wrapper.add(sideBtnPanel, BorderLayout.EAST);
        // 将重要面板添加到父容器的 SOUTH（通常为 mPanel）
        try {
            if (parent.getLayout() instanceof BorderLayout) parent.add(wrapper, BorderLayout.SOUTH);
            else parent.add(wrapper);
        } catch (Exception ignore) { parent.add(wrapper); }
        // 内容在 apply() 或首次显示时刷新
        refreshImportantPanel();
    }

    private void refreshImportantPanel() {
        if (importantPanel == null) return;
        importantPanel.removeAll();
        String raw = PropertiesComponent.getInstance().getValue("key_important_stocks", "");
        java.util.List<String> codesList = new java.util.ArrayList<>();
        if (StringUtils.isNotBlank(raw)) {
            for (String s : raw.split(";")) {
                if (StringUtils.isNotBlank(s)) codesList.add(s.trim());
            }
        }

        // 缓存并计算分页
        importantCodesCache = codesList;
        int total = codesList.size();
        if (total == 0) {
            stopImportantRotateTimer();
            importantPanel.add(new JLabel("未设置重要股票"));
            importantPanel.revalidate(); importantPanel.repaint();
            try { if (this.topPanel != null) this.topPanel.revalidate(); if (this.mPanel != null) this.mPanel.revalidate(); } catch (Exception ignore) {}
            return;
        }

        int pages = (total + IMPORTANT_PAGE_SIZE - 1) / IMPORTANT_PAGE_SIZE;
        if (importantRotateIndex >= pages) importantRotateIndex = 0;
        int from = importantRotateIndex * IMPORTANT_PAGE_SIZE;
        int to = Math.min(from + IMPORTANT_PAGE_SIZE, total);
        java.util.List<String> visible = codesList.subList(from, to);

        // 使用 2 列网格布局，每项为一个 card，信息间距更小
        JPanel grid = new JPanel(new GridLayout(0, 2, 6, 4));
        grid.setOpaque(false);

        DefaultTableModel model = null;
        try { model = (DefaultTableModel) table.getModel(); } catch (Exception ignore) {}

        int codeIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "编码");
        int nameIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "股票名称");
        int nowIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "当前价");
        int changeIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "涨跌");
        int pctIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "涨跌幅");
        int amountIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "成交额");

        Color separatorColor = new JBColor(new Color(220, 220, 220), new Color(70, 70, 70));
        Component highlightCard = null;
        for (int i = 0; i < visible.size(); i++) {
            String code = visible.get(i);
            boolean leftColumn = (i % 2 == 0);
            JPanel card = new JPanel(new BorderLayout());
            card.setOpaque(false);

            Border baseBorder = leftColumn ? BorderFactory.createMatteBorder(0, 0, 0, 1, separatorColor) : BorderFactory.createEmptyBorder(0, 0, 0, 0);
            card.setBorder(baseBorder);

            JPanel left = new JPanel();
            left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
            left.setOpaque(false);

            JLabel codeLbl = new JLabel(code);
            codeLbl.setFont(codeLbl.getFont().deriveFont(12f));
            codeLbl.setPreferredSize(new Dimension(70, 16)); codeLbl.setMaximumSize(new Dimension(70, 16));

            // 从表格读取最新数据
            String name = code;
            String now = "--", change = "--", pct = "--", amount = "--";
            try {
                if (model != null) {
                    int rows = model.getRowCount();
                    for (int r = 0; r < rows; r++) {
                        Object oc = codeIdx >= 0 ? model.getValueAt(r, codeIdx) : null;
                        if (oc != null && code.equals(String.valueOf(oc))) {
                            if (nameIdx >= 0) name = String.valueOf(model.getValueAt(r, nameIdx));
                            if (nowIdx >= 0) now = String.valueOf(model.getValueAt(r, nowIdx));
                            if (changeIdx >= 0) change = String.valueOf(model.getValueAt(r, changeIdx));
                            if (pctIdx >= 0) pct = String.valueOf(model.getValueAt(r, pctIdx));
                            if (amountIdx >= 0) amount = String.valueOf(model.getValueAt(r, amountIdx));
                            break;
                        }
                    }
                }
            } catch (Exception ex) { LogUtil.info("读取重要股票数据失败: " + ex.getMessage()); }
            // 构建新的显示顺序：名称、当前价、涨跌幅、今日成交额、昨日成交额
            JLabel nameLbl = new JLabel(name);
            nameLbl.setPreferredSize(new Dimension(120, 16)); nameLbl.setMaximumSize(new Dimension(120, 16));
            JLabel nowLbl = new JLabel(now);
            nowLbl.setPreferredSize(new Dimension(60, 16)); nowLbl.setMaximumSize(new Dimension(60, 16));
            JLabel pctLbl = new JLabel(pct);
            pctLbl.setPreferredSize(new Dimension(60, 16)); pctLbl.setMaximumSize(new Dimension(60, 16));
            JLabel amountLbl = new JLabel(formatAmountForImportant(amount));
            amountLbl.setPreferredSize(new Dimension(80, 16)); amountLbl.setMaximumSize(new Dimension(80, 16));
            // 尝试获取昨日成交额（优先从 handler 的 snapshot 或接口获取）
            String yesterdayAmount = "--";
            try {
                // 1) 尝试从 handler 的 snapshot（precomputed 缓存或表格回退）读取：
                if (handler != null) {
                    java.util.Map<String, double[]> snap = handler.getSnapshotForCodes(Collections.singleton(code));
                    if (snap != null && snap.containsKey(code)) {
                        double[] arr = snap.get(code);
                        // handler snapshot 不包含昨日成交额，跳过
                    }
                }
                // 2) 直接从本地缓存读取昨日成交额，避免误用当前行情接口返回的当日成交额
                try {
                    yesterdayAmount = formatAmountForImportant(YesterdayAmountStorage.getForCode(code));
                } catch (Exception ignore) {}
            } catch (Exception ignore) {}
            JLabel yesterdayLbl = new JLabel(yesterdayAmount);
            yesterdayLbl.setPreferredSize(new Dimension(80, 16)); yesterdayLbl.setMaximumSize(new Dimension(80, 16));

            // 颜色规则：名称/当前价/涨跌幅 随涨跌幅变色；今日成交额与昨日做比较（+5% 红， -5% 绿，否则默认）；昨日成交额默认色
            Color fgColor = JBColor.foreground();
            try {
                double pv = Double.NaN;
                if (pct != null && pct.contains("%")) {
                    String p = pct.replace("%", "").replace("+", "").trim(); pv = Double.parseDouble(p);
                } else if (change != null) {
                    String ch = change.replace("+", "").trim(); pv = Double.parseDouble(ch);
                }
                if (!Double.isNaN(pv)) {
                    if (pv > 0) fgColor = JBColor.RED;
                    else if (pv < 0) fgColor = JBColor.GREEN;
                }
            } catch (Exception ignore) {}

            Color amountColor = JBColor.foreground();
            try {
                double todayVal = parseNumberWithUnits(amount);
                double yVal = parseNumberWithUnits(yesterdayAmount);
                if (!Double.isNaN(todayVal) && !Double.isNaN(yVal) && yVal > 0) {
                    if (todayVal >= yVal * 1.05) amountColor = JBColor.RED;
                    else if (todayVal <= yVal * 0.95) amountColor = JBColor.GREEN;
                }
            } catch (Exception ignore) {}

            codeLbl.setForeground(fgColor);
            nameLbl.setForeground(fgColor);
            nowLbl.setForeground(fgColor);
            pctLbl.setForeground(fgColor);
            amountLbl.setForeground(amountColor);
            yesterdayLbl.setForeground(JBColor.foreground());

            left.add(nameLbl);
            left.add(Box.createHorizontalStrut(4));
            left.add(nowLbl);
            left.add(Box.createHorizontalStrut(4));
            left.add(pctLbl);
            left.add(Box.createHorizontalStrut(4));
            left.add(amountLbl);
            left.add(Box.createHorizontalStrut(4));
            left.add(yesterdayLbl);

            card.add(left, BorderLayout.CENTER);

            JButton del = new JButton("x");
            del.setToolTipText("删除重要股票");
            del.setPreferredSize(new Dimension(18, 18));
            del.setMargin(new Insets(1, 1, 1, 1));
            del.setBorderPainted(false); del.setContentAreaFilled(false); del.setFocusPainted(false);
            del.addActionListener(ev -> {
                try {
                    String key = "key_important_stocks";
                    String raw2 = PropertiesComponent.getInstance().getValue(key, "");
                    java.util.List<String> list = new java.util.ArrayList<>();
                    if (StringUtils.isNotBlank(raw2)) for (String s2 : raw2.split(";")) if (StringUtils.isNotBlank(s2) && !s2.trim().equals(code)) list.add(s2.trim());
                    StringBuilder sb2 = new StringBuilder();
                    for (int ii = 0; ii < list.size(); ii++) { if (ii > 0) sb2.append(";"); sb2.append(list.get(ii)); }
                    PropertiesComponent.getInstance().setValue(key, sb2.toString());
                    // reset index to ensure deleted item not shown
                    importantRotateIndex = 0;
                    refreshImportantPanel();
                } catch (Exception ex) { LogUtil.info("删除重要股票失败: " + ex.getMessage()); }
            });
            card.add(del, BorderLayout.EAST);

            // 新添加项高亮并在短时后移除高亮（恢复为 baseBorder）
            if (lastAddedImportantCode != null && lastAddedImportantCode.equals(code)) {
                Border highlight = BorderFactory.createLineBorder(JBColor.YELLOW, 1);
                card.setBorder(BorderFactory.createCompoundBorder(highlight, baseBorder));
                highlightCard = card;
                final Border restore = baseBorder;
                ThreadPools.getScheduledExecutor().schedule(() -> SwingUtilities.invokeLater(() -> {
                    try { card.setBorder(restore); } catch (Exception ignore) {}
                    lastAddedImportantCode = null;
                }), 1600, TimeUnit.MILLISECONDS);
            }

            grid.add(card);
        }

        importantPanel.add(grid);

        // 若存在需要居中的高亮卡片，尝试将其在可滚动容器中居中显示
        if (highlightCard != null) {
            final Component hc = highlightCard;
            SwingUtilities.invokeLater(() -> {
                try {
                    JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, importantPanel);
                    if (sp != null) {
                        JViewport vp = sp.getViewport();
                        Rectangle r = hc.getBounds();
                        Rectangle view = vp.getViewRect();
                        int cx = r.x + r.width / 2 - view.width / 2;
                        int cy = r.y + r.height / 2 - view.height / 2;
                        cx = Math.max(0, cx); cy = Math.max(0, cy);
                        vp.setViewPosition(new Point(cx, cy));
                    } else {
                        // fallback: make visible if it's a JComponent
                        if (hc instanceof JComponent) {
                            ((JComponent) hc).scrollRectToVisible(hc.getBounds());
                        }
                    }
                } catch (Exception ignore) {}
            });
        }

        importantPanel.revalidate(); importantPanel.repaint();
        // 根据数量决定是否启动轮播
        if (total > IMPORTANT_PAGE_SIZE) startImportantRotateTimer(); else stopImportantRotateTimer();
        try {
            if (this.topPanel != null) this.topPanel.revalidate();
            if (this.mPanel != null) this.mPanel.revalidate();
        } catch (Exception ignore) {}
    }

    private void startImportantRotateTimer() {
        stopImportantRotateTimer();
        if (importantCodesCache == null || importantCodesCache.size() <= IMPORTANT_PAGE_SIZE) return;
        importantRotateFuture = ThreadPools.getScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (importantCodesCache == null || importantCodesCache.isEmpty()) { stopImportantRotateTimer(); return; }
                        int pages = (importantCodesCache.size() + IMPORTANT_PAGE_SIZE - 1) / IMPORTANT_PAGE_SIZE;
                        importantRotateIndex = (importantRotateIndex + 1) % Math.max(1, pages);
                        refreshImportantPanel();
                    } catch (Exception ignore) {}
                });
            } catch (Exception ignore) {}
        }, IMPORTANT_ROTATE_INTERVAL_MS, IMPORTANT_ROTATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopImportantRotateTimer() {
        if (importantRotateFuture != null) {
            try { importantRotateFuture.cancel(true); } catch (Exception ignore) {}
            importantRotateFuture = null;
            importantRotateIndex = 0;
        }
    }

    private boolean isMarketOpenToday() {
        try {
            String api = PropertiesComponent.getInstance().getValue("key_trade_check_url", "");
            if (StringUtils.isNotBlank(api)) {
                try {
                    String body = HttpClientPool.getHttpClient().get(api);
                    if (StringUtils.isBlank(body)) return false;
                    if (body.contains("\"isOpen\":true") || body.contains("\"open\":true") || body.contains("\"trade\":true") || body.contains("\"marketOpen\":true")) return true;
                    if (body.trim().equalsIgnoreCase("true")) return true;
                    return false;
                } catch (Exception ex) { LogUtil.info("market check API failed: " + ex.getMessage()); return false; }
            }
            String whitelist = PropertiesComponent.getInstance().getValue("key_trade_day_whitelist", "");
            if (StringUtils.isNotBlank(whitelist)) {
                String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date());
                for (String d : whitelist.split(",")) if (today.equals(d.trim())) return true;
                return false;
            }
            Calendar c = Calendar.getInstance();
            int dow = c.get(Calendar.DAY_OF_WEEK);
            return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
        } catch (Exception e) { LogUtil.info("检查开市日异常: " + e.getMessage()); return false; }
    }

    private void scheduleDailyOpportunityPopup() {
        try {
            if (dailyOpportunityFuture != null) { dailyOpportunityFuture.cancel(true); dailyOpportunityFuture = null; }
            java.util.Calendar now = java.util.Calendar.getInstance();
            java.util.Calendar next = (java.util.Calendar) now.clone();
            next.set(java.util.Calendar.HOUR_OF_DAY, 14);
            next.set(java.util.Calendar.MINUTE, 45);
            next.set(java.util.Calendar.SECOND, 0);
            next.set(java.util.Calendar.MILLISECOND, 0);
            if (!next.after(now)) next.add(java.util.Calendar.DAY_OF_MONTH, 1);
            // 跳到下一个工作日
            while (next.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SATURDAY || next.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY) {
                next.add(java.util.Calendar.DAY_OF_MONTH, 1);
            }
            long delay = next.getTimeInMillis() - now.getTimeInMillis();
            dailyOpportunityFuture = ThreadPools.getScheduledExecutor().schedule(() -> {
                try { showDailyOpportunityDialog(); } catch (Exception ex) { LogUtil.info("每日机会弹窗异常: " + ex.getMessage()); }
                // 任务执行完后重新调度下一次
                scheduleDailyOpportunityPopup();
            }, delay, TimeUnit.MILLISECONDS);
        } catch (Exception e) { LogUtil.info("调度每日机会失败: " + e.getMessage()); }
    }

    private void scheduleDailySaveYesterdayAmounts() {
        try {
            // cancel previous save task only
            if (dailySaveYesterdayFuture != null) { dailySaveYesterdayFuture.cancel(true); dailySaveYesterdayFuture = null; }
            java.util.Calendar now = java.util.Calendar.getInstance();
            java.util.Calendar next = (java.util.Calendar) now.clone();
            // schedule at 16:30 local time
            next.set(java.util.Calendar.HOUR_OF_DAY, 16);
            next.set(java.util.Calendar.MINUTE, 30);
            next.set(java.util.Calendar.SECOND, 0);
            next.set(java.util.Calendar.MILLISECOND, 0);
            if (!next.after(now)) next.add(java.util.Calendar.DAY_OF_MONTH, 1);
            // skip weekends by advancing to next weekday
            while (next.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SATURDAY || next.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY) {
                next.add(java.util.Calendar.DAY_OF_MONTH, 1);
            }
            long delay = next.getTimeInMillis() - now.getTimeInMillis();
            dailySaveYesterdayFuture = ThreadPools.getScheduledExecutor().schedule(() -> {
                try {
                    // Only save when today is a trading day
                    if (!isMarketOpenToday()) return;
                    StringBuilder sb = new StringBuilder();
                    DefaultTableModel model = null;
                    try { model = (DefaultTableModel) table.getModel(); } catch (Exception ignore) {}
                    int codeIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "编码");
                    int amountIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "成交额");
                    for (int r = 0; model != null && codeIdx >= 0 && amountIdx >= 0 && r < model.getRowCount(); r++) {
                        try {
                            Object oc = model.getValueAt(r, codeIdx);
                            Object oa = model.getValueAt(r, amountIdx);
                            if (oc == null || oa == null) continue;
                            String code = String.valueOf(oc).trim();
                            String amountStr = String.valueOf(oa).trim();
                            if (StringUtils.isBlank(code) || StringUtils.isBlank(amountStr) || "--".equals(amountStr)) continue;
                            if (sb.length() > 0) sb.append(";");
                            sb.append(code).append("=").append(amountStr);
                        } catch (Exception ignore) {
                        }
                    }
                    if (sb.length() > 0) {
                        YesterdayAmountStorage.save(parseKeyValueMap(sb.toString()));
                    }
                } catch (Exception ignore) { LogUtil.info("保存昨日成交额失败: " + ignore.getMessage()); }
                // reschedule next day
                scheduleDailySaveYesterdayAmounts();
            }, delay, TimeUnit.MILLISECONDS);
        } catch (Exception e) { LogUtil.info("调度保存昨日成交额失败: " + e.getMessage()); }
    }

    private static Map<String, String> parseKeyValueMap(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (StringUtils.isBlank(raw)) return result;
        for (String item : raw.split(";")) {
            if (StringUtils.isBlank(item) || !item.contains("=")) continue;
            int idx = item.indexOf('=');
            String code = StringUtils.defaultString(item.substring(0, idx)).trim();
            String value = StringUtils.defaultString(item.substring(idx + 1)).trim();
            if (StringUtils.isBlank(code) || StringUtils.isBlank(value)) continue;
            result.put(code, value);
        }
        return result;
    }

    private void bindGlobalKeyListener() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            boolean f7d = PropertiesComponent.getInstance().getBoolean("key_close_f7");
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_F7 && !f7d) {
                listModel.clear();
                if (!searchDialog.isVisible()) { searchField.setText(""); searchDialog.setVisible(true); searchField.requestFocus(); }
                return true;
            }
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_DELETE) {
                if (handler == null || table.getSelectedRow() < 0) return false;
                String code = String.valueOf(table.getModel().getValueAt(table.convertRowIndexToModel(table.getSelectedRow()), handler.codeColumnIndex));
                String key = getKeyForName("Stock");
                String cs = instance.getValue(key);
                if (StringUtils.isNotBlank(cs)) {
                    String[] parts = cs.split(";"); StringBuilder sb = new StringBuilder();
                    for (String p : parts) { if (!p.contains(code)) sb.append(p).append(";"); }
                    instance.setValue(key, sb.toString());
                }
                apply(); return true;
            }
            return false;
        });
    }

    private void showDailyOpportunityDialog() {
        // Placeholder for summary dialog; kept minimal to avoid heavy UI during restore
    }
    private static StockRefreshHandler factoryHandler() {
        String src = ConfigManager.getInstance().getStockSource();
        if ("sina".equals(src)) {
            if (handler instanceof SinaStockHandler) return handler;
            return new SinaStockHandler(table, refreshTimeLabel);
        } else {
            if (handler instanceof TencentStockHandler) return handler;
            return new TencentStockHandler(table, refreshTimeLabel);
        }
    }

    public static void apply() {
        if (handler == null) return;
        commitTableEdits();
        handler = factoryHandler();
        try { handler.setOnDataRefreshed(() -> updateSummaryLabels()); } catch (Exception ignore) {}
        ConfigManager cm = ConfigManager.getInstance();
        handler.setStriped(cm.isTableStriped());
        handler.clearRow();
        List<String> codes = loadStocks();
        handler.setupTable(codes);
        handler.applyColumnVisibility();
        // setupTable 后 ColumnModel 才初始化完成，此时注册账号编辑器
        handler.setupAccountColumnEditor();
        bindEditPersistenceListener();
        // If the Stock tab is hidden in settings, stop any scheduled jobs and do not refresh
        try {
            boolean tabHidden = PropertiesComponent.getInstance().getBoolean("key_tab_hide_stock", false);
            if (tabHidden) {
                try { quartz.QuartzManager.getInstance("Stock").stopJob(); } catch (Throwable ignore) {}
                if (handler != null) try { handler.stopHandle(); } catch (Throwable ignore) {}
                return;
            }
        } catch (Throwable ignore) {}
        refresh();
        // 根据设置显示/隐藏持仓统计和重要股票区域
        PropertiesComponent pc = PropertiesComponent.getInstance();
        boolean showImportant = pc.getBoolean("key_show_important_area", true);
        boolean showPositions = pc.getBoolean("key_show_positions", true);
        if (currentInstance != null) {
            // summaryPanel 显示控制
            try {
                boolean hasSummary = false;
                for (Component c : currentInstance.topPanel.getComponents()) if (c == summaryPanel) { hasSummary = true; break; }
                if (showPositions && !hasSummary) currentInstance.topPanel.add(summaryPanel, 0);
                if (!showPositions && hasSummary) currentInstance.topPanel.remove(summaryPanel);
            } catch (Exception ignore) {}
                // importantPanel 显示控制（保留原有 wrapper 移除逻辑，panel 挂在 mPanel 的 SOUTH）
                if (showImportant) {
                    if (currentInstance.importantPanel == null) currentInstance.initImportantPanel(currentInstance.mPanel);
                    else currentInstance.refreshImportantPanel();
                } else {
                    if (currentInstance.importantPanel != null) {
                        for (Component c : currentInstance.mPanel.getComponents()) {
                            if (c instanceof JPanel) {
                                JPanel p = (JPanel) c;
                                for (Component cc : p.getComponents()) { if (cc == currentInstance.importantPanel) { currentInstance.mPanel.remove(p); break; } }
                            }
                        }
                        currentInstance.importantPanel = null;
                    }
                }
            // 根据是否显示持仓控制 summary 更新定时器
            if (showPositions) currentInstance.startSummaryTimer(); else currentInstance.stopSummaryTimer();
            currentInstance.scheduleDailyOpportunityPopup();
            try { currentInstance.scheduleDailySaveYesterdayAmounts(); } catch (Exception ignore) {}
            try { if (currentInstance.groupTabPanel != null) currentInstance.rebuildGroupTabs(currentInstance.groupTabPanel); } catch (Exception ignore) {}
            // 保持当前分组视图（避免在删除/分组切换后回到默认分组）
            if (currentInstance.groupComboBox != null && currentInstance.groupComboBox.getSelectedItem() != null) {
                String cur = currentInstance.groupComboBox.getSelectedItem().toString();
                if (!GroupManager.DEFAULT_GROUP.equals(cur)) currentInstance.filterTableByGroup(cur);
            }
            try { currentInstance.topPanel.revalidate(); currentInstance.topPanel.repaint(); } catch (Exception ignore) {}
        }
    }

    private void startSummaryTimer() {
        stopSummaryTimer();
        int interval = ConfigManager.getInstance().getSummaryRefreshInterval();
        summaryRefreshTimer = new javax.swing.Timer(interval, e -> {
            if (handler != null && table != null && table.getModel() != null && table.getModel().getRowCount() > 0)
                updateSummaryLabels();
        });
        summaryRefreshTimer.setRepeats(true);
        summaryRefreshTimer.start();
        staticSummaryTimer = summaryRefreshTimer;
    }

    private void stopSummaryTimer() {
        if (summaryRefreshTimer != null) { summaryRefreshTimer.stop(); summaryRefreshTimer = null; }
        staticSummaryTimer = null;
    }

    public static void refresh() {
        if (handler == null) return;
        commitTableEdits();
        // If the Stock tab is hidden, avoid executing refresh and stop any scheduled jobs
        try {
            if (PropertiesComponent.getInstance().getBoolean("key_tab_hide_stock", false)) {
                try { quartz.QuartzManager.getInstance("Stock").stopJob(); } catch (Throwable ignore) {}
                try { handler.stopHandle(); } catch (Throwable ignore) {}
                return;
            }
        } catch (Throwable ignore) {}
        ConfigManager cm = ConfigManager.getInstance();
        handler.refreshColorful(cm.isColorfulEnabled());
        List<String> codes = loadStocks();
        if (CollectionUtils.isEmpty(codes)) { stop(); return; }
        // 将刷新操作放到后台线程，避免阻塞 EDT（handler 内部会更新 UI via invokeLater）
        ThreadPools.getRefreshExecutor().execute(() -> {
            try {
                handler.handle(codes);
                handler.triggerAlertCheck();
                if (!suppressQuartzReschedule) {
                    QuartzManager qm = QuartzManager.getInstance("Stock");
                    HashMap<String, Object> dm = new HashMap<>();
                    dm.put("handler", handler); dm.put("codes", codes);
                    try {
                        int sec = cm.getStockRefreshIntervalSeconds();
                        if (sec > 0) qm.runJobWithInterval(HandlerJob.class, sec, dm);
                        else {
                            String userCron = PropertiesComponent.getInstance().getValue(ConfigKeys.KEY_CRON_EXPRESSION_STOCK);
                            if (StringUtils.isNotBlank(userCron)) qm.runJob(HandlerJob.class, userCron, dm);
                            else qm.runJob(HandlerJob.class, cm.getStockCronExpression(), dm);
                        }
                    } catch (Throwable qex) { utils.LogUtil.info("StockQuartz schedule failed: " + qex.getMessage()); }
                }
            } catch (Exception ex) { LogUtil.info("后台刷新失败: " + ex.getMessage()); }
        });
    }

    public static void stop() {
        try { QuartzManager.getInstance("Stock").stopJob(); } catch (Throwable ignore) { utils.LogUtil.info("StockWindow.stop: stopJob error: " + (ignore == null ? "null" : ignore.getMessage())); }
        if (handler != null) handler.stopHandle();
        if (staticSummaryTimer != null) { staticSummaryTimer.stop(); staticSummaryTimer = null; }
    }

    private static List<String> loadStocks() { return GroupManager.getInstance().loadAllStocks(); }

    private static void commitTableEdits() {
        if (table == null || !table.isEditing()) return;
        TableCellEditor ed = table.getCellEditor();
        if (ed != null) ed.stopCellEditing();
        // 仅在当前为默认分组时保存到 key_stocks，避免在过滤/分组视图下修改导致全局顺序被覆盖
        try {
            if (table.getModel() != null && table.getRowCount() > 0 && currentInstance != null
                    && currentInstance.groupComboBox != null && currentInstance.groupComboBox.getSelectedItem() != null
                    && GroupManager.DEFAULT_GROUP.equals(currentInstance.groupComboBox.getSelectedItem().toString())) {
                saveTableConfigFromModel();
            }
        } catch (Exception ignore) {}
    }

    private void implementRowDragAndDrop() {
        final int[] draggedRow = {-1};
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    draggedRow[0] = row;
                    table.setRowSelectionInterval(row, row);
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                draggedRow[0] = -1;
            }
        });
        table.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (draggedRow[0] == -1) return;
                int targetRow = table.rowAtPoint(e.getPoint());
                if (targetRow < 0 || targetRow == draggedRow[0]) return;
                try {
                    swapRows(draggedRow[0], targetRow);
                    table.setRowSelectionInterval(targetRow, targetRow);
                } catch (Exception ignore) {}
                draggedRow[0] = targetRow;
            }
        });
    }

    private void swapRows(int r1, int r2) {
        String curGroup = GroupManager.DEFAULT_GROUP;
        try { if (currentInstance != null && currentInstance.groupComboBox != null && currentInstance.groupComboBox.getSelectedItem() != null) curGroup = currentInstance.groupComboBox.getSelectedItem().toString(); } catch (Exception ignore) {}
        // 默认分组：修改模型行（向后兼容）
        if (GroupManager.DEFAULT_GROUP.equals(curGroup)) {
            DefaultTableModel m = (DefaultTableModel) table.getModel();
            int modelRowCount = m.getRowCount();
            int mm1 = table.convertRowIndexToModel(r1), mm2 = table.convertRowIndexToModel(r2);
            if (mm1 < 0 || mm1 >= modelRowCount || mm2 < 0 || mm2 >= modelRowCount) return;
            Vector v1 = (Vector) m.getDataVector().elementAt(mm1);
            Vector v2 = (Vector) m.getDataVector().elementAt(mm2);
            m.getDataVector().set(mm1, v2); m.getDataVector().set(mm2, v1);
            m.fireTableDataChanged();
            // 由调用方负责设置选择，避免在拖拽中频繁触发选择更新
            updateConfigFile();
        }
        // 非默认分组：仅调整该分组内的顺序，不修改全局模型
        try {
            Object o1 = table.getValueAt(r1, handler.codeColumnIndex);
            Object o2 = table.getValueAt(r2, handler.codeColumnIndex);
            if (o1 == null || o2 == null) return;
            String code1 = o1.toString().replace("rt_", "").trim();
            String code2 = o2.toString().replace("rt_", "").trim();
            List<String> list = GroupManager.getInstance().getStocksInGroup(curGroup);
            int idx1 = -1, idx2 = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).equalsIgnoreCase(code1)) idx1 = i;
                if (list.get(i).equalsIgnoreCase(code2)) idx2 = i;
            }
            if (idx1 < 0 || idx2 < 0) return;
            list.remove(idx1);
            if (idx2 >= list.size()) list.add(code1); else list.add(idx2, code1);
            GroupManager.getInstance().setStocksInGroup(curGroup, list);
            if (currentInstance != null) currentInstance.filterTableByGroup(curGroup);
            for (int r = 0; r < table.getRowCount(); r++) {
                Object v = table.getValueAt(r, handler.codeColumnIndex);
                if (v != null && code1.equalsIgnoreCase(v.toString().replace("rt_", "").trim())) {
                    javax.swing.ListSelectionModel sm = table.getSelectionModel();
                    boolean prev = sm.getValueIsAdjusting();
                    try {
                        sm.setValueIsAdjusting(true);
                        table.setRowSelectionInterval(r, r);
                    } finally { sm.setValueIsAdjusting(prev); }
                    break;
                }
            }
        } catch (Exception ignore) {}
    }

    private void updateConfigFile() {
        // 仅在默认分组下更新全局 key_stocks（避免在非默认分组的局部排序修改影响全局顺序）
        if (groupComboBox != null && groupComboBox.getSelectedItem() != null &&
                GroupManager.DEFAULT_GROUP.equals(groupComboBox.getSelectedItem().toString())) {
            saveTableConfigFromModel();
        }
        // 同步当前分组的股票顺序（仅保存 code 部分，保持各分组顺序独立）
        if (groupComboBox != null && groupComboBox.getSelectedItem() != null) {
            String curGroup = groupComboBox.getSelectedItem().toString();
            DefaultTableModel m = (DefaultTableModel) table.getModel();
            List<String> ordered = new ArrayList<>();
            for (int i = 0; i < m.getRowCount(); i++) {
                String code = Objects.toString(m.getValueAt(i, handler.codeColumnIndex), "");
                if (StringUtils.isNotBlank(code)) {
                    // 仅保存 code，setStocksInGroup 内会做规范化
                    ordered.add(code.trim());
                }
            }
            GroupManager.getInstance().setStocksInGroup(curGroup, ordered);
        }
    }

    // 统计标签数组
    // summaryPanel 和 accountSummaryLabels 已在类顶部声明
    private static void updateSummaryLabels() {
        if (summaryPanel == null || accountSummaryLabels.length == 0) return;
        try {
            // Ensure summary panel matches configured account count
            int cfgAccountCount = 1;
            try { cfgAccountCount = Integer.parseInt(PropertiesComponent.getInstance().getValue("key_account_count", "1")); } catch (Exception ignore) { cfgAccountCount = 1; }
            if (cfgAccountCount < 1) cfgAccountCount = 1; if (cfgAccountCount > 3) cfgAccountCount = 3;
            int expectedRows = cfgAccountCount > 1 ? cfgAccountCount + 1 : 1;
            if (accountSummaryLabels.length != expectedRows * 6) {
                // Rebuild panel to match new account count (will be run on EDT)
                rebuildSummaryPanel();
                return;
            }

            // Collect current table values on EDT to use for accurate, immediate statistics
            final java.util.List<String> codes = new java.util.ArrayList<>();
            final java.util.Map<String, String> bondsMap = new java.util.HashMap<>();
            final java.util.Map<String, String> costMap = new java.util.HashMap<>();
            final java.util.Map<String, String> acctMap = new java.util.HashMap<>();
            // 持仓市值回退（当行情快照缺失时使用）
            final java.util.Map<String, String> pvMap = new java.util.HashMap<>();
            // 每行的当日盈亏列值（用于顶部按账号汇总时优先直接相加）
            final java.util.Map<String, String> dayPnlMap = new java.util.HashMap<>();
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    try {
                        DefaultTableModel model = (DefaultTableModel) table.getModel();
                        int codeIdx = handler.codeColumnIndex;
                        int bondsIdx = findColumnIndex(BONDS_COLUMN_NAME);
                        int costIdx = findColumnIndex(COST_COLUMN_NAME);
                        int acctIdx = findColumnIndex(ACCOUNT_COLUMN_NAME);
                        int pvIdx = findColumnIndex("持仓市值");
                        int dayPnlIdx = findColumnIndex("当日盈亏");
                        for (int r = 0; r < model.getRowCount(); r++) {
                            Object codeObj = model.getValueAt(r, codeIdx);
                            if (codeObj == null) continue;
                            String code = codeObj.toString().trim();
                            if (StringUtils.isBlank(code) || code.startsWith("---sep")) continue;
                            String ncode = normalizeCode(code);
                            codes.add(ncode);
                            if (bondsIdx >= 0) bondsMap.put(ncode, Objects.toString(model.getValueAt(r, bondsIdx), ""));
                            if (costIdx >= 0) costMap.put(ncode, Objects.toString(model.getValueAt(r, costIdx), ""));
                            if (acctIdx >= 0) acctMap.put(ncode, WindowUtils.normalizeAccount(normalizeStockValue(Objects.toString(model.getValueAt(r, acctIdx), ""))));
                            if (pvIdx >= 0) pvMap.put(ncode, Objects.toString(model.getValueAt(r, pvIdx), ""));
                            if (dayPnlIdx >= 0) dayPnlMap.put(ncode, Objects.toString(model.getValueAt(r, dayPnlIdx), ""));
                        }
                    } catch (Exception ex) { }
                } else {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            DefaultTableModel model = (DefaultTableModel) table.getModel();
                            int codeIdx = handler.codeColumnIndex;
                            int bondsIdx = findColumnIndex(BONDS_COLUMN_NAME);
                            int costIdx = findColumnIndex(COST_COLUMN_NAME);
                            int acctIdx = findColumnIndex(ACCOUNT_COLUMN_NAME);
                            int pvIdx = findColumnIndex("持仓市值");
                            int dayPnlIdx = findColumnIndex("当日盈亏");
                            for (int r = 0; r < model.getRowCount(); r++) {
                                Object codeObj = model.getValueAt(r, codeIdx);
                                if (codeObj == null) continue;
                                String code = codeObj.toString().trim();
                                if (StringUtils.isBlank(code) || code.startsWith("---sep")) continue;
                                String ncode = normalizeCode(code);
                                codes.add(ncode);
                                if (bondsIdx >= 0) bondsMap.put(ncode, Objects.toString(model.getValueAt(r, bondsIdx), ""));
                                if (costIdx >= 0) costMap.put(ncode, Objects.toString(model.getValueAt(r, costIdx), ""));
                                if (acctIdx >= 0) acctMap.put(ncode, WindowUtils.normalizeAccount(normalizeStockValue(Objects.toString(model.getValueAt(r, acctIdx), ""))));
                                if (pvIdx >= 0) pvMap.put(ncode, Objects.toString(model.getValueAt(r, pvIdx), ""));
                                if (dayPnlIdx >= 0) dayPnlMap.put(ncode, Objects.toString(model.getValueAt(r, dayPnlIdx), ""));
                            }
                        } catch (Exception ex) { }
                    });
                }
            } catch (Exception e) { /* ignore EDT read errors */ }

            if (handler == null) { resetSummaryLabels(); return; }
            ThreadPools.getRefreshExecutor().execute(() -> {
                try {
                    int totalRows = accountSummaryLabels.length / 6;
                    boolean colorful = ConfigManager.getInstance().isColorfulEnabled();
                    int accountCount = totalRows > 1 ? totalRows - 1 : 1;
                    double er = Double.parseDouble(ConfigManager.getInstance().getHkExchangeRate());

                    double[] totalAmount = new double[accountCount];
                    double[] dayGainArr = new double[accountCount];
                    double[] totalGainArr = new double[accountCount];

                    java.util.Map<String, double[]> snap = handler.getSnapshotForCodes(codes);
                    for (String code : codes) {
                        String bondsStr = bondsMap.getOrDefault(code, "");
                        String costStr = costMap.getOrDefault(code, "");
                        String accCfg = acctMap.getOrDefault(code, "");
                        double cost = Double.NaN, bonds = Double.NaN;
                        if (StringUtils.isNotBlank(bondsStr)) {
                            try { bonds = parseNumberWithUnits(bondsStr); } catch (Exception ignore) { bonds = Double.NaN; }
                        }
                        // 必须有持仓 > 0 才参与统计
                        if (Double.isNaN(bonds) || bonds <= 0) continue;
                        if (StringUtils.isNotBlank(costStr)) {
                            try { cost = parseNumberWithUnits(costStr); } catch (Exception ignore) { cost = Double.NaN; }
                        }
                        int ai = 0;
                        if (accountCount > 1 && StringUtils.isNotBlank(accCfg)) {
                            try {
                                int parsed = Integer.parseInt(accCfg);
                                if (parsed >= 1 && parsed <= accountCount) ai = parsed - 1;
                            } catch (Exception ignore) {}
                        }
                        // 港股：表格中的 "持仓市值"、"当日盈亏" 已换算为人民币，汇总时不再重复乘汇率
                        // 但 now/change 快照值为原始港币价格，计算 totalAmount/totalGain 时需乘汇率
                        boolean isHk = code.toLowerCase().startsWith("hk");
                        double rate = isHk ? er : 1.0;
                        double now = Double.NaN, ch = Double.NaN, dayPnlVal = Double.NaN;
                        double[] arr = snap.get(code);
                        if (arr != null) {
                            if (arr.length > 0) now = arr[0];
                            if (arr.length > 1) ch = arr[1];
                            if (arr.length > 2) dayPnlVal = arr[2];
                        }
                        // 如果快照缺失 current price，则使用表格中的持股数量和持仓市值反推（港股时该值已为RMB）
                        if (Double.isNaN(now)) {
                            String pvStr = pvMap.getOrDefault(code, "");
                            if (StringUtils.isNotBlank(pvStr)) {
                                try {
                                    double pv = parseNumberWithUnits(pvStr);
                                    if (!Double.isNaN(pv) && bonds > 0) {
                                        now = pv / bonds;
                                        // pv 来自表格时已为RMB（港股），不再乘汇率；成本也需同步转为RMB
                                        if (isHk) {
                                            rate = 1.0;
                                            if (!Double.isNaN(cost)) cost = cost * er;
                                        }
                                    }
                                } catch (Exception ignore) { now = Double.NaN; }
                            }
                        }
                        if (Double.isNaN(now)) continue;
                        totalAmount[ai] += bonds * now * rate;
                        // 优先使用表格中的"当日盈亏"列直接相加（确保顶部汇总与表格一致，港股表格值已为RMB）
                        String dayPnlStrFromTable = dayPnlMap.getOrDefault(code, "");
                        double dayPnlFromTable = Double.NaN;
                        if (StringUtils.isNotBlank(dayPnlStrFromTable)) {
                            try { dayPnlFromTable = parseNumberWithUnits(dayPnlStrFromTable.replaceAll("\\+", "").trim()); } catch (Exception ignore) { dayPnlFromTable = Double.NaN; }
                        }
                        // 表格当日盈亏已根据 calcPositionFields 换算过汇率，不再重复乘 rate
                        if (!Double.isNaN(dayPnlFromTable)) {
                            dayGainArr[ai] += dayPnlFromTable;
                        } else if (!Double.isNaN(dayPnlVal)) {
                            // dayPnlVal 来自快照，可能已换算 -- 稳妥处理：港股快照值也经过了 PrecomputedRow
                            // 它读取的是 bean.dayPnl（已换算），直接累加；备份用 change（HKD原始值需乘rate）
                            dayGainArr[ai] += dayPnlVal;
                        } else if (!Double.isNaN(ch)) {
                            dayGainArr[ai] += bonds * ch * rate;
                        }
                        // 仅当成本存在时计算累计收益
                        if (!Double.isNaN(cost)) totalGainArr[ai] += bonds * (now - cost) * rate;
                    }

                    final int fTotalRows = totalRows;
                    final int fAccountCount = accountCount;
                    final double[] fTotalAmount = totalAmount;
                    final double[] fDayGainArr = dayGainArr;
                    final double[] fTotalGainArr = totalGainArr;
                    final boolean fColorful = colorful;
                    SwingUtilities.invokeLater(() -> {
                        try {
                            Color rd = JBColor.RED, gr = JBColor.GREEN;
                            for (int r = 0; r < fTotalRows; r++) {
                                int titleIdx = r * 6; int base = titleIdx + 1;
                                boolean isTotal = (fAccountCount > 1 && r == fAccountCount);
                                double ta, td, tt;
                                if (isTotal) { ta = 0; td = 0; tt = 0; for (int a2 = 0; a2 < fAccountCount; a2++) { ta += fTotalAmount[a2]; td += fDayGainArr[a2]; tt += fTotalGainArr[a2]; } }
                                else { int a2 = r; ta = fTotalAmount[a2]; td = fDayGainArr[a2]; tt = fTotalGainArr[a2]; }
                                Color fg = JBColor.foreground(); Color titleClr = isTotal ? JBColor.BLUE : fg;
                                double dgPct = (ta - td > 0) ? td / (ta - td) * 100.0 : 0;
                                Color amtC, dgC, dpC, tgC;
                                if (fColorful) { amtC = fg; dgC = td >= 0 ? rd : gr; dpC = dgPct >= 0 ? rd : gr; tgC = tt >= 0 ? rd : gr; }
                                else { amtC = fg; dgC = fg; dpC = fg; tgC = fg; }
                                // Only update label text/foreground if changed to reduce unnecessary repaint
                                String t0 = htmlFmt("总金额", ta, "%.2f", amtC);
                                if (!Objects.equals(accountSummaryLabels[titleIdx].getForeground(), titleClr)) accountSummaryLabels[titleIdx].setForeground(titleClr);
                                if (!Objects.equals(accountSummaryLabels[base].getForeground(), titleClr)) accountSummaryLabels[base].setForeground(titleClr);
                                if (!Objects.equals(accountSummaryLabels[base].getText(), t0)) accountSummaryLabels[base].setText(t0);

                                String t1 = htmlFmt("当日收益", td, "%+.2f", dgC);
                                if (!Objects.equals(accountSummaryLabels[base+1].getForeground(), titleClr)) accountSummaryLabels[base+1].setForeground(titleClr);
                                if (!Objects.equals(accountSummaryLabels[base+1].getText(), t1)) accountSummaryLabels[base+1].setText(t1);

                                String t2 = htmlFmt("当日收益率", dgPct, "%+.2f%%", dpC);
                                if (!Objects.equals(accountSummaryLabels[base+2].getForeground(), titleClr)) accountSummaryLabels[base+2].setForeground(titleClr);
                                if (!Objects.equals(accountSummaryLabels[base+2].getText(), t2)) accountSummaryLabels[base+2].setText(t2);

                                String t3 = htmlFmt("累计收益", tt, "%+.2f", tgC);
                                if (!Objects.equals(accountSummaryLabels[base+3].getForeground(), titleClr)) accountSummaryLabels[base+3].setForeground(titleClr);
                                if (!Objects.equals(accountSummaryLabels[base+3].getText(), t3)) accountSummaryLabels[base+3].setText(t3);

                                if (!Objects.equals(accountSummaryLabels[base+4].getForeground(), titleClr)) accountSummaryLabels[base+4].setForeground(titleClr);
                                if (!Objects.equals(accountSummaryLabels[base+4].getText(), "")) accountSummaryLabels[base+4].setText("");
                            }
                        } catch (Exception ex) { LogUtil.info("updateSummaryLabels UI update error: " + ex.getMessage()); }
                    });
                } catch (Exception e) { LogUtil.info("updateSummaryLabels background error: " + e.getMessage()); }
            });
        } catch (Exception e) { LogUtil.info("updateSummaryLabels schedule error: " + e.getMessage()); }
    }

    public static void rebuildSummaryPanel() {
        SwingUtilities.invokeLater(() -> {
            try {
                int accountCount = 1;
                try { accountCount = Integer.parseInt(PropertiesComponent.getInstance().getValue("key_account_count", "1")); } catch (Exception ignore) { accountCount = 1; }
                if (accountCount < 1) accountCount = 1; if (accountCount > 3) accountCount = 3;
                if (summaryPanel == null) summaryPanel = new JPanel();
                summaryPanel.removeAll();
                summaryPanel.setLayout(new BoxLayout(summaryPanel, BoxLayout.Y_AXIS));
                int totalRows = accountCount > 1 ? accountCount + 1 : 1;
                accountSummaryLabels = new JLabel[totalRows * 6];
                String[] colNames = {"总金额", "当日收益", "当日收益率", "累计收益", "本金"};
                int[] colWidths = {115, 115, 115, 115, 105};
                Font boldFont = new Font(summaryPanel.getFont().getName(), Font.BOLD, 11);
                Font plainFont = new Font(summaryPanel.getFont().getName(), Font.PLAIN, 11);
                for (int r = 0; r < totalRows; r++) {
                    boolean isTotalRow = (accountCount > 1 && r == accountCount);
                    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 2));
                    int titleIdx = r * 6;
                    String title;
                    if (isTotalRow) title = "总  计";
                    else if (accountCount > 1) title = "账号" + (r + 1);
                    else title = "";
                    accountSummaryLabels[titleIdx] = new JLabel(title);
                    accountSummaryLabels[titleIdx].setFont(boldFont);
                    accountSummaryLabels[titleIdx].setHorizontalAlignment(SwingConstants.LEFT);
                    row.add(accountSummaryLabels[titleIdx]);
                    for (int c2 = 0; c2 < 5; c2++) {
                        int idx = titleIdx + 1 + c2;
                        accountSummaryLabels[idx] = new JLabel(colNames[c2] + ": --");
                        accountSummaryLabels[idx].setFont(plainFont);
                        accountSummaryLabels[idx].setPreferredSize(new Dimension(colWidths[c2], 16));
                        row.add(accountSummaryLabels[idx]);
                    }
                    summaryPanel.add(row);
                }
                if (currentInstance != null) {
                    try { currentInstance.topPanel.revalidate(); currentInstance.topPanel.repaint(); } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}
        });
    }

    private static String htmlFmt(String name, double v, String fmt, Color c) {
        return "<html>" + name + ": <font color=#" + String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()) + ">" + String.format(fmt, v) + "</font></html>";
    }

    private static void resetSummaryLabels() {
        String[] colNames = {"总金额", "当日收益", "当日收益率", "累计收益", "本金"};
        for (int r = 0; r < accountSummaryLabels.length / 6; r++) {
            int base = r * 6 + 1;
            for (int c2 = 0; c2 < 5; c2++) {
                accountSummaryLabels[base + c2].setText(colNames[c2] + ": --");
                accountSummaryLabels[base + c2].setForeground(JBColor.foreground());
            }
        }
    }


    // 跟踪当前打开的预警弹窗（同一 code 只显示一个）
    private static final Map<String, JDialog> openAlertDialogs = new HashMap<>();

    private static void showAlertDialog(int row, int screenX, int screenY) {
        if (handler == null || row < 0) return;
        String code = Objects.toString(table.getValueAt(row, handler.codeColumnIndex), "");
        if (StringUtils.isBlank(code)) return;

        // 已存在该 code 的弹窗则复用
        JDialog existing = openAlertDialogs.get(code);
        if (existing != null && existing.isVisible()) {
            existing.toFront();
            return;
        }

        // 读取当前预警配置
        String curCfg = getAlertConfigForCode(code);
        double upPrice = 0, downPrice = 0, upPct = 0, downPct = 0;
        if (StringUtils.isNotBlank(curCfg)) {
            for (String part : curCfg.split(",")) {
                if (StringUtils.isBlank(part)) continue;
                String[] kv = part.split("_");
                if (kv.length < 2) continue;
                try {
                    double v = Double.parseDouble(kv[1]);
                    if ("upPrice".equals(kv[0])) upPrice = v;
                    else if ("downPrice".equals(kv[0])) downPrice = v;
                    else if ("upPct".equals(kv[0])) upPct = v;
                    else if ("downPct".equals(kv[0])) downPct = v;
                } catch (NumberFormatException e) {}
            }
        }

        JDialog dialog = new JDialog((JFrame) null, code + " 预警设置", false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { openAlertDialogs.remove(code); }
        });
        openAlertDialogs.put(code, dialog);

        JPanel panel = new JPanel(new GridLayout(4, 2, 5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JTextField upPriceField = new JTextField(upPrice > 0 ? formatAlertNum(upPrice) : "", 8);
        JTextField downPriceField = new JTextField(downPrice > 0 ? formatAlertNum(downPrice) : "", 8);
        JTextField upPctField = new JTextField(upPct > 0 ? formatAlertNum(upPct) : "", 8);
        JTextField downPctField = new JTextField(downPct > 0 ? formatAlertNum(downPct) : "", 8);
        panel.add(new JLabel("上涨价格 >= (元):")); panel.add(upPriceField);
        panel.add(new JLabel("下跌价格 <= (元):")); panel.add(downPriceField);
        panel.add(new JLabel("上涨幅度 >= (%):")); panel.add(upPctField);
        panel.add(new JLabel("下跌幅度 >= (%):")); panel.add(downPctField);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveBtn = new JButton("保存");
        JButton clearBtn = new JButton("清空并保存");
        JButton cancelBtn = new JButton("取消");

        saveBtn.addActionListener(ev -> {
            saveAlertFromDialog(code, row, upPriceField, downPriceField, upPctField, downPctField);
            dialog.dispose();
        });
            clearBtn.addActionListener(ev -> {
            int warnIdx = findColumnIndex("预警");
            if (warnIdx >= 0) table.setValueAt("", row, warnIdx);
            dialog.dispose();
            ThreadPools.getRefreshExecutor().execute(() -> saveAlertConfigForCode(code, ""));
        });
        cancelBtn.addActionListener(ev -> {
            dialog.dispose();
            openAlertDialogs.remove(code);
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { openAlertDialogs.remove(code); }
        });

        btnPanel.add(clearBtn);
        btnPanel.add(saveBtn);
        btnPanel.add(cancelBtn);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(panel, BorderLayout.CENTER);
        dialog.getContentPane().add(btnPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    private static void saveAlertFromDialog(String code, int row, JTextField upPriceField, JTextField downPriceField,
                                             JTextField upPctField, JTextField downPctField) {
        StringBuilder sb = new StringBuilder();
        appendIfNum(sb, "upPrice", upPriceField.getText());
        appendIfNum(sb, "downPrice", downPriceField.getText());
        appendIfNum(sb, "upPct", upPctField.getText());
        appendIfNum(sb, "downPct", downPctField.getText());
        String newCfg = sb.toString();

        // 在后台线程做持久化保存，避免阻塞 EDT，同时更新表格 UI
        final int warnIdx = findColumnIndex("预警");
        if (warnIdx >= 0) {
            table.setValueAt(StringUtils.isNotBlank(newCfg) ? "✔" : "", row, warnIdx);
        }
        // 将持久化放到后台线程池执行，避免频繁创建线程
        ThreadPools.getRefreshExecutor().execute(() -> saveAlertConfigForCode(code, newCfg));
    }

    private static void appendIfNum(StringBuilder sb, String key, String val) {
        if (StringUtils.isBlank(val)) return;
        try { Double.parseDouble(val); } catch (NumberFormatException e) { return; }
        if (sb.length() > 0) sb.append(",");
        sb.append(key).append("_").append(val);
    }

    private static String formatAlertNum(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private static String getAlertConfigForCode(String code) {
        String key = getKeyForName("Stock");
        String cfg = instance.getValue(key);
        if (StringUtils.isBlank(cfg)) return null;
        for (String p : cfg.split(";")) {
            p = p.trim();
            if (StringUtils.isBlank(p)) continue;
            if (!normalizeCode(extractStockCode(p)).equalsIgnoreCase(normalizeCode(code))) continue;
            // alertConfig 从字段6开始（索引6），内部可含逗号
            int idx = findNthComma(p, 6);
            if (idx > 0) return p.substring(idx + 1);
        }
        return null;
    }

    private static int findNthComma(String s, int n) {
        int pos = -1;
        for (int i = 0; i < n; i++) {
            pos = s.indexOf(",", pos + 1);
            if (pos < 0) return -1;
        }
        return pos;
    }

    private static void saveAlertConfigForCode(String code, String alertCfg) {
        String key = getKeyForName("Stock");
        String cfg = instance.getValue(key);
        if (StringUtils.isBlank(cfg)) return;
        String[] parts = cfg.split(";");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String p = part.trim();
            if (StringUtils.isBlank(p)) continue;
            if (normalizeCode(extractStockCode(p)).equalsIgnoreCase(normalizeCode(code))) {
                // 保留字段0-5（code,cost,bonds,,account,），替换后续为 alertCfg
                int idx6 = findNthComma(p, 6);
                if (idx6 > 0) {
                    p = p.substring(0, idx6 + 1) + alertCfg;
                } else {
                    // 不足6个逗号，补齐
                    while (countCommas(p) < 6) p += ",";
                    p += alertCfg;
                }
            }
            sb.append(p).append(";");
        }
        instance.setValue(key, sb.toString());
    }

    private static int countCommas(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == ',') n++;
        return n;
    }


    private void showGroupOrderDialog(JPanel panel) {
        List<String> names = new ArrayList<>(GroupManager.getInstance().getGroupNames());
        if (names.isEmpty()) return;

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(panel), "调整分组顺序", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(360, 420);

        JLabel tipLabel = new JLabel("拖动分组行调整顺序，保存后同步到分组页签。");
        tipLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));

        DefaultListModel<String> model = new DefaultListModel<>();
        for (String name : names) model.addElement(name);
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(Math.min(12, Math.max(6, names.size())));
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new TransferHandler() {
            @Override
            protected Transferable createTransferable(JComponent c) {
                JList<?> source = (JList<?>) c;
                Object selected = source.getSelectedValue();
                return new StringSelection(selected == null ? "" : String.valueOf(selected));
            }

            @Override
            public int getSourceActions(JComponent c) {
                return MOVE;
            }

            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && support.getComponent() instanceof JList;
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                JList<String> target = (JList<String>) support.getComponent();
                DefaultListModel<String> targetModel = (DefaultListModel<String>) target.getModel();
                JList.DropLocation dropLocation = (JList.DropLocation) support.getDropLocation();
                int index = dropLocation.getIndex();
                if (index < 0) index = targetModel.getSize();

                Transferable transferable = support.getTransferable();
                String value;
                try {
                    value = (String) transferable.getTransferData(DataFlavor.stringFlavor);
                } catch (Exception ex) {
                    return false;
                }
                int sourceIndex = targetModel.indexOf(value);
                if (sourceIndex < 0) return false;
                if (sourceIndex != index) {
                    targetModel.remove(sourceIndex);
                    if (sourceIndex < index) index--;
                    if (index < 0) index = 0;
                    targetModel.add(index, value);
                }
                target.setSelectedIndex(index);
                return true;
            }
        });
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setText((index + 1) + ". " + value);
                return label;
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancelBtn = new JButton("取消");
        cancelBtn.addActionListener(ev -> dialog.dispose());
        JButton saveBtn = new JButton("保存");
        saveBtn.addActionListener(ev -> {
            List<String> ordered = new ArrayList<>();
            for (int i = 0; i < model.getSize(); i++) ordered.add(model.getElementAt(i));
            if (!ordered.isEmpty()) {
                String current = groupComboBox != null && groupComboBox.getSelectedItem() != null ? groupComboBox.getSelectedItem().toString() : null;
                GroupManager.getInstance().reorderGroups(ordered);
                rebuildGroupTabs(panel);
                if (StringUtils.isNotBlank(current)) {
                    if (groupComboBox != null) {
                        groupComboBox.setSelectedItem(current);
                        if (groupComboBox.getSelectedItem() == null && groupComboBox.getItemCount() > 0) {
                            groupComboBox.setSelectedIndex(0);
                        }
                    }
                    if (StringUtils.isNotBlank(current)) filterTableByGroup(current);
                }
            }
            dialog.dispose();
        });
        buttons.add(cancelBtn);
        buttons.add(saveBtn);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(tipLabel, BorderLayout.NORTH);
        content.add(new JBScrollPane(list), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setLocationRelativeTo(panel);
        dialog.setVisible(true);
    }

    private void rebuildGroupTabs(JPanel panel) {
        Component[] comps = panel.getComponents();
        for (int i = comps.length - 1; i >= 0; i--) {
            if (comps[i] instanceof JComboBox) continue;
            if (comps[i] instanceof JButton && "+".equals(((JButton) comps[i]).getText())) continue;
            panel.remove(i);
        }
        String sel = groupComboBox.getSelectedItem() != null ? groupComboBox.getSelectedItem().toString() : "默认";
        groupComboBox.removeAllItems();
        Color selBg = new Color(0.3f, 0.3f, 0.3f, 0.3f);
        Color btnFg = UIManager.getColor("Button.foreground");
        Color btnBg = UIManager.getColor("Button.background");
        Color fg = UIManager.getColor("Button.foreground");
        List<String> names = GroupManager.getInstance().getGroupNames();

        // 计算可用宽度并确定按钮目标宽度，以便在窗口变宽时每行能容纳更多分组
        int panelWidth = panel.getWidth();
        if (panelWidth <= 0 && panel.getParent() != null) panelWidth = panel.getParent().getWidth();
        if (panelWidth <= 0 && this.topPanel != null) panelWidth = this.topPanel.getWidth();
        int reservedAddWidth = 28; // 为 "+" 按钮预留空间
        int hgap = 2;
        int minBtnWidth = 60; // 最小按钮宽度
        int maxBtnWidth = 160; // 最大按钮宽度
        int truncateLen = 12;
        try {
            PropertiesComponent pc = PropertiesComponent.getInstance();
            minBtnWidth = Integer.parseInt(pc.getValue("key_group_tab_min_width", "60"));
            maxBtnWidth = Integer.parseInt(pc.getValue("key_group_tab_max_width", "160"));
            truncateLen = Integer.parseInt(pc.getValue("key_group_tab_truncate_len", "12"));
            if (minBtnWidth < 10) minBtnWidth = 10;
            if (maxBtnWidth < minBtnWidth) maxBtnWidth = minBtnWidth;
            if (truncateLen < 4) truncateLen = 4;
        } catch (Exception ignore) {}
        int namesCount = names.size() > 0 ? names.size() : 1;
        int columns = 1;
        if (panelWidth > 0) {
            columns = Math.max(1, Math.min(namesCount, Math.max(1, (panelWidth - reservedAddWidth) / minBtnWidth)));
        }
        int totalGap = (columns - 1) * hgap;
        int btnWidth = panelWidth > 0 ? Math.max(minBtnWidth, (panelWidth - reservedAddWidth - totalGap) / columns) : minBtnWidth;
        if (btnWidth > maxBtnWidth) btnWidth = maxBtnWidth;

        // 支持按行自动换行数量配置（0 表示按宽度自适应）
        int wrapCount = 0;
        try { wrapCount = Integer.parseInt(PropertiesComponent.getInstance().getValue("key_group_tab_wrap_count", "0")); if (wrapCount < 0) wrapCount = 0; } catch (Exception ignore) { wrapCount = 0; }

        if (wrapCount <= 0) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                groupComboBox.addItem(name);
                boolean isSel = name.equals(sel);
                // 显示文本可能过长，截断显示并在鼠标悬停时显示完整名称
                String displayName = name;
                if (displayName.length() > truncateLen) displayName = displayName.substring(0, Math.max(0, truncateLen - 1)) + "…";
                JButton btn = new JButton(displayName);
                btn.setToolTipText(name);
                btn.setFont(btn.getFont().deriveFont(10.0f));
                btn.setMargin(new Insets(0, 4, 0, 4));
                btn.setFocusPainted(false); btn.setContentAreaFilled(false); btn.setBorderPainted(false);
                if (isSel) { btn.setBackground(selBg); btn.setForeground(btnFg); btn.setOpaque(true); btn.setContentAreaFilled(true); btn.setBorderPainted(true); }
                else { btn.setBackground(btnBg); btn.setForeground(fg); btn.setOpaque(false); btn.setContentAreaFilled(false); btn.setBorderPainted(false); }
                btn.setRolloverEnabled(false);
                // 设置按钮固定宽度，方便在宽屏上每行显示更多分组
                try {
                    btn.setPreferredSize(new Dimension(btnWidth, 20));
                    btn.setMaximumSize(new Dimension(btnWidth, 20));
                    btn.setMinimumSize(new Dimension(minBtnWidth, 18));
                } catch (Exception ignore) {}
                btn.getModel().addChangeListener((ChangeListener) e1 -> {
                    if (!name.equals(groupComboBox.getSelectedItem())) { btn.setBackground(btnBg); btn.setForeground(fg); btn.setOpaque(false); btn.setContentAreaFilled(false); btn.setBorderPainted(false); }
                });
                final String gn = name; final int gi = i;
                btn.addActionListener(e -> { groupComboBox.setSelectedItem(gn); filterTableByGroup(gn); rebuildGroupTabs(panel); });
                btn.addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        if (SwingUtilities.isRightMouseButton(e)) {
                            JPopupMenu menu = new JPopupMenu();
                            JMenuItem reorder = new JMenuItem("调整顺序");
                            reorder.addActionListener(ev -> showGroupOrderDialog(panel));
                            menu.add(reorder);
                            JMenuItem ri = new JMenuItem("重命名");
                            ri.addActionListener(ev -> {
                                String nn = JOptionPane.showInputDialog(panel, "输入新名称:", gn);
                                if (StringUtils.isNotBlank(nn) && !nn.equals(gn)) { GroupManager.getInstance().renameGroup(gn, nn); rebuildGroupTabs(panel); }
                            });
                            menu.add(ri);
                            if (!"默认".equals(gn)) {
                                JMenuItem di = new JMenuItem("删除");
                                di.addActionListener(ev -> { GroupManager.getInstance().removeGroup(gn); rebuildGroupTabs(panel); filterTableByGroup("默认"); });
                                menu.add(di);
                            }
                            menu.show(btn, e.getX(), e.getY());
                        }
                    }
                });
                panel.add(btn, panel.getComponentCount() > 0 ? panel.getComponentCount() - 1 : 0);
            }
        } else {
            // 以固定每行 wrapCount 个的方式布局，便于控制行数与面板高度
            int idx = 0;
            int rows = (names.size() + wrapCount - 1) / wrapCount;
            // 重新计算 btnWidth 以保证每行能容纳 wrapCount 个按钮
            if (panelWidth > 0) {
                int tc = Math.max(1, wrapCount);
                int totalGapPerRow = (tc - 1) * hgap;
                int calcWidth = (panelWidth - reservedAddWidth - totalGapPerRow) / tc;
                btnWidth = Math.max(minBtnWidth, calcWidth);
                if (btnWidth > maxBtnWidth) btnWidth = maxBtnWidth;
            }
            // 将每行的 rowPanel 放入一个垂直容器中，确保多行时真正垂直堆叠而不是横向排列
            JPanel rowsContainer = new JPanel();
            rowsContainer.setLayout(new BoxLayout(rowsContainer, BoxLayout.Y_AXIS));
            rowsContainer.setOpaque(false);
            for (int r = 0; r < rows; r++) {
                JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
                rowPanel.setOpaque(false);
                for (int c = 0; c < wrapCount && idx < names.size(); c++) {
                    String name = names.get(idx++);
                    groupComboBox.addItem(name);
                    boolean isSel = name.equals(sel);
                    String displayName = name;
                    if (displayName.length() > truncateLen) displayName = displayName.substring(0, Math.max(0, truncateLen - 1)) + "…";
                    JButton btn = new JButton(displayName);
                    btn.setToolTipText(name);
                    btn.setFont(btn.getFont().deriveFont(10.0f));
                    btn.setMargin(new Insets(0, 4, 0, 4));
                    btn.setFocusPainted(false); btn.setContentAreaFilled(false); btn.setBorderPainted(false);
                    if (isSel) { btn.setBackground(selBg); btn.setForeground(btnFg); btn.setOpaque(true); btn.setContentAreaFilled(true); btn.setBorderPainted(true); }
                    else { btn.setBackground(btnBg); btn.setForeground(fg); btn.setOpaque(false); btn.setContentAreaFilled(false); btn.setBorderPainted(false); }
                    btn.setRolloverEnabled(false);
                    try { btn.setPreferredSize(new Dimension(btnWidth, 20)); btn.setMaximumSize(new Dimension(btnWidth, 20)); btn.setMinimumSize(new Dimension(minBtnWidth, 18)); } catch (Exception ignore) {}
                    btn.getModel().addChangeListener((ChangeListener) e1 -> { if (!name.equals(groupComboBox.getSelectedItem())) { btn.setBackground(btnBg); btn.setForeground(fg); btn.setOpaque(false); btn.setContentAreaFilled(false); btn.setBorderPainted(false); } });
                    final String gn = name;
                    btn.addActionListener(e -> { groupComboBox.setSelectedItem(gn); filterTableByGroup(gn); rebuildGroupTabs(panel); });
                    btn.addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { if (SwingUtilities.isRightMouseButton(e)) { JPopupMenu menu = new JPopupMenu(); JMenuItem reorder = new JMenuItem("调整顺序"); reorder.addActionListener(ev -> showGroupOrderDialog(panel)); menu.add(reorder); JMenuItem ri = new JMenuItem("重命名"); ri.addActionListener(ev -> { String nn = JOptionPane.showInputDialog(panel, "输入新名称:", gn); if (StringUtils.isNotBlank(nn) && !nn.equals(gn)) { GroupManager.getInstance().renameGroup(gn, nn); rebuildGroupTabs(panel); } }); menu.add(ri); if (!"默认".equals(gn)) { JMenuItem di = new JMenuItem("删除"); di.addActionListener(ev -> { GroupManager.getInstance().removeGroup(gn); rebuildGroupTabs(panel); filterTableByGroup("默认"); }); menu.add(di); } menu.show(btn, e.getX(), e.getY()); } } });
                    rowPanel.add(btn);
                }
                rowsContainer.add(rowPanel);
            }
            panel.add(rowsContainer, panel.getComponentCount() > 0 ? panel.getComponentCount() - 1 : 0);
        }
        // 根据 wrapCount 动态调整外部滚动面板的首选高度，便于显示多行分组
        try {
            if (wrapCount > 0 && this.groupScroll != null) {
                int btnH = 20; // 与 btn.setPreferredSize 中的高度对应
                int vgapRow = 2; // FlowLayout 垂直间距
                int rowHeight = btnH + Math.max(2, vgapRow) + 2; // 精简的垂直行高估算，避免过高
                int rows = (names.size() + wrapCount - 1) / wrapCount;
                int maxRows = 3; // 限制最大行数以避免占用过高空间
                int rowsToShow = Math.min(rows, maxRows);
                int neededHeight = rowsToShow * rowHeight + 6;
                int prefWidth = panelWidth > 0 ? panelWidth : this.groupScroll.getPreferredSize().width;
                this.groupScroll.setPreferredSize(new Dimension(Math.max(100, prefWidth), Math.max(24, neededHeight)));
            } else if (this.groupScroll != null) {
                // 恢复为单行高度（默认）
                int singleH = 26;
                int prefWidth = panelWidth > 0 ? panelWidth : this.groupScroll.getPreferredSize().width;
                this.groupScroll.setPreferredSize(new Dimension(Math.max(100, prefWidth), singleH));
            }
        } catch (Exception ignore) {}

        groupComboBox.setSelectedItem(sel);
        if (this.groupScroll != null) this.groupScroll.revalidate();
        panel.revalidate(); panel.repaint();
    }

    private void filterTableByGroup(String group) {
        if (handler == null || group == null) return;
        // 使用 RowFilter 进行显示层面的过滤，保留后台模型以继续更新所有股票数据
        try {
            DefaultTableModel model = (DefaultTableModel) table.getModel();
            if (model == null) return;
            if (GroupManager.DEFAULT_GROUP.equals(group)) {
                javax.swing.RowSorter<?> rs = table.getRowSorter();
                if (rs instanceof javax.swing.table.TableRowSorter) {
                    ((javax.swing.table.TableRowSorter) rs).setRowFilter(null);
                    try { ((javax.swing.table.TableRowSorter) rs).setSortKeys(null); } catch (Exception ignore) {}
                } else {
                    javax.swing.table.TableRowSorter<DefaultTableModel> sorter = new javax.swing.table.TableRowSorter<>(model);
                    table.setRowSorter(sorter);
                    sorter.setRowFilter(null);
                    try { sorter.setSortKeys(null); } catch (Exception ignore) {}
                }
            } else {
                List<String> codes = GroupManager.getInstance().getStocksInGroup(group);
                final Set<String> codeSet = new HashSet<>();
                for (String c : codes) if (c != null) codeSet.add(c.trim().toUpperCase());
                final int codeCol = handler.codeColumnIndex;
                javax.swing.table.TableRowSorter<DefaultTableModel> sorter;
                if (table.getRowSorter() instanceof javax.swing.table.TableRowSorter) sorter = (javax.swing.table.TableRowSorter<DefaultTableModel>) table.getRowSorter();
                else { sorter = new javax.swing.table.TableRowSorter<>(model); table.setRowSorter(sorter); }
                try { sorter.setSortKeys(null); } catch (Exception ignore) {}
                try { sorter.setComparator(codeCol, null); } catch (Exception ignore) {}
                sorter.setRowFilter(new javax.swing.RowFilter<DefaultTableModel, Integer>() {
                    @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                        try {
                            Object v = entry.getValue(codeCol);
                            if (v == null) return false;
                            String code = String.valueOf(v).replace("rt_", "").trim().toUpperCase();
                            return codeSet.contains(code);
                        } catch (Exception e) { return false; }
                    }
                });
            }
            SwingUtilities.invokeLater(() -> { table.revalidate(); table.repaint(); });
        } catch (Exception ex) { LogUtil.info("filterTableByGroup failed: " + ex.getMessage()); }
    }

    static {
        refreshTimeLabel = new JLabel();
        refreshTimeLabel.setToolTipText("最后刷新时间");
        refreshTimeLabel.setBorder(new EmptyBorder(0, 0, 0, 5));
        table = new JBTable();
        // 使用单选避免拖拽时出现临时多行高亮闪烁
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(false);
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "moveUp");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "moveDown");
        table.getActionMap().put("moveUp", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { moveRow(-1); }
        });
        table.getActionMap().put("moveDown", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { moveRow(1); }
        });
        table.getTableHeader().addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                StringBuilder sb = new StringBuilder();
                int cc = table.getColumnCount();
                for (int i = 0; i < cc; i++) {
                    String name = table.getColumnName(i);
                    if (name != null) sb.append(name).append(",");
                }
                if (sb.length() > 0) instance.setValue(WindowUtils.STOCK_TABLE_HEADER_KEY, sb.substring(0, sb.length() - 1));
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (table.getSelectedRow() < 0) return;
                // 单击账号列时直接进入编辑并弹出下拉（恢复单击弹出行为）
                if (SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    int viewCol = table.columnAtPoint(e.getPoint());
                    if (viewRow >= 0 && viewCol >= 0) {
                        int modelCol = table.convertColumnIndexToModel(viewCol);
                        int accountModelIdx = findColumnIndex(ACCOUNT_COLUMN_NAME);
                        if (accountModelIdx >= 0 && modelCol == accountModelIdx) {
                            if (table.editCellAt(viewRow, viewCol)) {
                                Component editorComp = table.getEditorComponent();
                                if (editorComp instanceof JComboBox) {
                                    try { ((JComboBox) editorComp).showPopup(); } catch (Exception ignore) {}
                                } else if (editorComp != null) {
                                    editorComp.requestFocusInWindow();
                                }
                            }
                            return;
                        }
                    }
                }
                if (SwingUtilities.isRightMouseButton(e)) {
                    JPopupMenu menu = new JPopupMenu();
                    JMenuItem up = new JMenuItem("上移"); up.addActionListener(ev -> moveRow(-1)); menu.add(up);
                    JMenuItem dn = new JMenuItem("下移"); dn.addActionListener(ev -> moveRow(1)); menu.add(dn);
                    JMenuItem del = new JMenuItem("删除");
                    del.addActionListener(ev -> {
                        String code = String.valueOf(table.getModel().getValueAt(table.convertRowIndexToModel(table.getSelectedRow()), handler.codeColumnIndex));
                        // 如果当前选择的分组不是默认分组，仅从该分组移除，不影响其他分组
                        String curGroup = GroupManager.DEFAULT_GROUP;
                        if (currentInstance != null && currentInstance.groupComboBox != null && currentInstance.groupComboBox.getSelectedItem() != null) {
                            curGroup = currentInstance.groupComboBox.getSelectedItem().toString();
                        }
                        try {
                            if (!GroupManager.DEFAULT_GROUP.equals(curGroup)) {
                                GroupManager.getInstance().removeStockFromGroup(code, curGroup);
                            } else {
                                String key = getKeyForName("Stock");
                                String cs = instance.getValue(key);
                                if (StringUtils.isNotBlank(cs)) {
                                    String[] parts = cs.split(";"); StringBuilder sb = new StringBuilder();
                                    for (String p : parts) { if (!p.contains(code)) sb.append(p).append(";"); }
                                    instance.setValue(key, sb.toString());
                                }
                            }
                        } catch (Exception ex) { LogUtil.info("删除股票失败: " + ex.getMessage()); }
                        apply();
                    });
                    menu.add(del);

                    // 添加到分组（多选）——改为弹出复选面板：点击复选框不自动关闭，点击空白处关闭
                    String codeForGroup = String.valueOf(table.getModel().getValueAt(table.convertRowIndexToModel(table.getSelectedRow()), handler.codeColumnIndex));
                    java.util.List<String> groups = GroupManager.getInstance().getGroupNames();
                    final JPopupMenu groupPopup = new JPopupMenu();
                    JPanel groupPanel = new JPanel(new GridLayout(0, 5, 6, 4));
                    groupPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
                    for (String gname : groups) {
                        boolean exists = GroupManager.getInstance().stockExistsInGroup(codeForGroup, gname);
                        final JCheckBox cb = new JCheckBox(gname, exists);
                        cb.setPreferredSize(new Dimension(96, 24));
                        cb.addActionListener(ae -> {
                            try {
                                boolean sel = cb.isSelected();
                                if (sel) GroupManager.getInstance().addStockToGroup(codeForGroup, gname);
                                else GroupManager.getInstance().removeStockFromGroup(codeForGroup, gname);
                                // 刷新当前分组显示
                                try {
                                    String cur = GroupManager.DEFAULT_GROUP;
                                    if (currentInstance != null && currentInstance.groupComboBox != null && currentInstance.groupComboBox.getSelectedItem() != null) {
                                        cur = currentInstance.groupComboBox.getSelectedItem().toString();
                                    }
                                    final String targetGroup = cur;
                                    SwingUtilities.invokeLater(() -> {
                                        try { if (currentInstance != null) currentInstance.filterTableByGroup(targetGroup); }
                                        catch (Exception ex) { LogUtil.info("刷新分组显示失败: " + ex.getMessage()); }
                                    });
                                } catch (Exception ex) { LogUtil.info("分组选择后刷新失败: " + ex.getMessage()); }
                            } catch (Exception ex) { LogUtil.info("设置分组失败: " + ex.getMessage()); }
                        });
                        groupPanel.add(cb);
                    }
                    JScrollPane groupScrollPane = new JScrollPane(groupPanel);
                    groupScrollPane.setBorder(BorderFactory.createEmptyBorder());
                    groupScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                    groupScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
                    groupScrollPane.setPreferredSize(new Dimension(520, 260));
                    groupPopup.setPopupSize(new Dimension(520, 260));
                    groupPopup.add(groupScrollPane);
                    // 菜单中放置一个触发项，点击时在表格位置弹出复选面板
                    JMenuItem showGroups = new JMenuItem("添加到分组...");
                    showGroups.addActionListener(ev -> {
                        groupPopup.show(table, e.getX(), e.getY());
                    });
                    menu.add(showGroups);
                    // 测试预警弹窗按钮
                    int selRow = table.getSelectedRow();
                    if (selRow >= 0) {
                        String testCode = String.valueOf(table.getModel().getValueAt(
                                table.convertRowIndexToModel(selRow), handler.codeColumnIndex));
                        JMenuItem testAlert = new JMenuItem("测试预警弹窗");
                        testAlert.addActionListener(ev -> {
                            // 模拟一次预警弹窗
                            int ni = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "当前价");
                            String now = ni >= 0 ? Objects.toString(table.getValueAt(selRow, ni), "--") : "--";
                            LogUtil.notify("【测试预警】当前价: " + now + "，如果满足预警条件则会弹出气泡提示", true);
                        });
                        menu.add(testAlert);
                        // 自定义名称：设置/恢复
                        JMenuItem setCustom = new JMenuItem("设置自定义名称");
                        setCustom.addActionListener(ev -> {
                            int sr = table.getSelectedRow(); if (sr < 0) return;
                            int mr = table.convertRowIndexToModel(sr);
                            String code = Objects.toString(table.getModel().getValueAt(mr, handler.codeColumnIndex), "");
                            if (StringUtils.isBlank(code)) return;
                            try {
                                String key = "key_custom_names";
                                String json = instance.getValue(key, "{}");
                                com.google.gson.JsonObject obj;
                                try {
                                    obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                                } catch (Exception ex) { obj = new com.google.gson.JsonObject(); }
                                int nameIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "股票名称");
                                String current = obj.has(code) ? obj.get(code).getAsString() : null;
                                String defaultName = StringUtils.isNotBlank(current) ? current : (nameIdx >= 0 ? Objects.toString(table.getValueAt(sr, nameIdx), "") : "");
                                String input = JOptionPane.showInputDialog(table, "请输入自定义显示名（留空恢复默认）:", defaultName);
                                if (input != null) {
                                    input = StringUtils.trimToEmpty(input);
                                    if (StringUtils.isBlank(input)) obj.remove(code); else obj.addProperty(code, input);
                                    instance.setValue(key, obj.toString());
                                    // 刷新 StockRefreshHandler 中的 custom names 缓存
                                    try { handler.reloadCustomNamesCache(); } catch (Exception ignore) {}
                                    if (nameIdx >= 0) table.getModel().setValueAt(input, mr, nameIdx);
                                }
                            } catch (Exception ex) { LogUtil.info("保存自定义名称失败: " + ex.getMessage()); }
                        });
                        menu.add(setCustom);
                        JMenuItem resetName = new JMenuItem("恢复默认名称");
                        resetName.addActionListener(ev -> {
                            int sr = table.getSelectedRow(); if (sr < 0) return;
                            int mr = table.convertRowIndexToModel(sr);
                            String code = Objects.toString(table.getModel().getValueAt(mr, handler.codeColumnIndex), "");
                            if (StringUtils.isBlank(code)) return;
                            try {
                                String key = "key_custom_names";
                                String json = instance.getValue(key, "{}");
                                com.google.gson.JsonObject obj;
                                try { obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject(); } catch (Exception ex) { obj = new com.google.gson.JsonObject(); }
                                if (obj.has(code)) { obj.remove(code); instance.setValue(key, obj.toString()); }
                                // 刷新 StockRefreshHandler 中的 custom names 缓存
                                try { handler.reloadCustomNamesCache(); } catch (Exception ignore) {}
                                int nameIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "股票名称");
                                if (nameIdx >= 0) table.getModel().setValueAt("", mr, nameIdx);
                                // 触发刷新以取回数据源名称
                                if (handler != null) {
                                    try { handler.handle(Collections.singletonList(code)); } catch (Exception ex) { LogUtil.info("刷新名称失败: " + ex.getMessage()); }
                                }
                            } catch (Exception ex) { LogUtil.info("恢复名称失败: " + ex.getMessage()); }
                        });
                        menu.add(resetName);
                    }
                    menu.show(table, e.getX(), e.getY());
                }
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int col = table.getSelectedColumn();
                    int row = table.getSelectedRow();
                    if (row < 0) return;
                    if (col >= 0 && "预警".equals(table.getColumnName(col))) {
                        showAlertDialog(row, e.getXOnScreen(), e.getYOnScreen());
                    } else if (handler != null && isCodeOrNameColumn(col)) {
                        int codeIdx = handler.codeColumnIndex;
                        String code = Objects.toString(table.getModel().getValueAt(table.convertRowIndexToModel(row), codeIdx), "");
                        // 空白行被用作分隔/名称行，双击允许编辑名称；其他情况双击显示分时弹窗
                        try {
                            if (StringUtils.isBlank(code) || code.startsWith("---sep")) {
                                int nameIdx = WindowUtils.getColumnIndexByName(StockRefreshHandler.columnNames, "股票名称");
                                String current = nameIdx >= 0 ? Objects.toString(table.getValueAt(row, nameIdx), "") : "";
                                String input = JOptionPane.showInputDialog(table, "请输入分组/分隔行名称（留空表示无）:", current);
                                if (input != null && nameIdx >= 0) {
                                    DefaultTableModel model = (DefaultTableModel) table.getModel();
                                    model.setValueAt(StringUtils.trimToEmpty(input), table.convertRowIndexToModel(row), nameIdx);
                                }
                            } else {
                                PopupsUiUtil.showImageByStockCode(code, PopupsUiUtil.StockShowType.min, new Point(e.getXOnScreen(), e.getYOnScreen()));
                            }
                        } catch (java.net.MalformedURLException ex) { LogUtil.info("Chart URL error: " + ex.getMessage()); }
                    } else {
                        // 仅当模型声明该单元格可编辑时才进入编辑模式，避免对只读列触发刷新/闪烁
                        int viewCol2 = col;
                        int viewRow2 = row;
                        if (viewCol2 >= 0 && viewRow2 >= 0) {
                            int modelCol2 = table.convertColumnIndexToModel(viewCol2);
                            int modelRow2 = table.convertRowIndexToModel(viewRow2);
                            try {
                                if (table.getModel().isCellEditable(modelRow2, modelCol2)) {
                                    if (table.editCellAt(viewRow2, viewCol2)) {
                                        Component editorComp = table.getEditorComponent();
                                        if (editorComp instanceof JComboBox) {
                                            try { ((JComboBox) editorComp).showPopup(); } catch (Exception ignore) {}
                                        } else if (editorComp != null) editorComp.requestFocusInWindow();
                                    }
                                }
                            } catch (Exception ignore) {}
                        }
                    }
                }
            }
        });
    }
}
