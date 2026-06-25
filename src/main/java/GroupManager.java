package leeks;

import com.intellij.ide.util.PropertiesComponent;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票分组管理器
 * 持久化数据:
 *   key_stock_groups = "默认分组,科技股"
 *   key_stock_group_<name> = "sh600519,12.451,1200;hk00700,13.412,2000"
 */
public class GroupManager {
    private static final String KEY_GROUPS = "key_stock_groups";
    private static final String KEY_GROUP_PREFIX = "key_stock_group_";
    public static final String DEFAULT_GROUP = "默认";

    private static final GroupManager INSTANCE = new GroupManager();
    private final PropertiesComponent props;

    private GroupManager() { this.props = PropertiesComponent.getInstance(); }
    public static GroupManager getInstance() { return INSTANCE; }

    public List<String> getGroupNames() {
        String val = props.getValue(KEY_GROUPS);
        if (StringUtils.isBlank(val)) {
            props.setValue(KEY_GROUPS, DEFAULT_GROUP);
            return new ArrayList<>(Collections.singletonList(DEFAULT_GROUP));
        }
        List<String> names = new ArrayList<>();
        for (String n : val.split(",")) {
            if (StringUtils.isNotBlank(n)) names.add(n.trim());
        }
        // 确保默认分组永远在第一位
        if (!names.isEmpty() && !DEFAULT_GROUP.equals(names.get(0))) {
            names.remove(DEFAULT_GROUP);
            names.add(0, DEFAULT_GROUP);
            saveGroupNames(names);
        }
        return names;
    }

    private void saveGroupNames(List<String> names) { props.setValue(KEY_GROUPS, String.join(",", names)); }

    public void addGroup(String name) {
        if (StringUtils.isBlank(name)) return;
        String n = name.trim();
        // 不允许逗号出现在分组名中
        n = n.replace(",", " ");
        List<String> ns = getGroupNames();
        if (!ns.contains(n)) { ns.add(n); saveGroupNames(ns); }
    }

    public void removeGroup(String name) {
        if (DEFAULT_GROUP.equals(name)) return;
        List<String> ns = getGroupNames(); ns.remove(name); saveGroupNames(ns);
        // 删除分组时只移除分组定义，不修改 key_stocks（默认组数据保持原样）
        props.unsetValue(KEY_GROUP_PREFIX + name);
    }

    /** 重命名分组（保留其股票数据） */
    public void renameGroup(String oldName, String newName) {
        if (DEFAULT_GROUP.equals(oldName) || StringUtils.isBlank(newName)) return;
        List<String> ns = getGroupNames();
        if (ns.contains(newName)) return;
        int idx = ns.indexOf(oldName);
        if (idx < 0) return;
        ns.set(idx, newName);
        saveGroupNames(ns);
        List<String> stocks = getStocksInGroup(oldName);
        props.setValue(KEY_GROUP_PREFIX + newName, String.join(";", stocks));
        props.unsetValue(KEY_GROUP_PREFIX + oldName);
    }

    /** 调整分组顺序 */
    public void reorderGroups(List<String> orderedNames) {
        if (orderedNames == null || orderedNames.isEmpty()) return;
        // 确保默认分组永远在第一位
        if (!DEFAULT_GROUP.equals(orderedNames.get(0))) {
            orderedNames.remove(DEFAULT_GROUP);
            orderedNames.add(0, DEFAULT_GROUP);
        }
        saveGroupNames(orderedNames);
    }

    /** 获取某分组的完整股票配置（含成本价和持仓） */
    public List<String> getStocksInGroup(String group) {
        String v = props.getValue(KEY_GROUP_PREFIX + group);
        if (StringUtils.isBlank(v)) return new ArrayList<>();
        return new ArrayList<>(Arrays.stream(v.split(";")).filter(StringUtils::isNotBlank).map(String::trim).collect(Collectors.toList()));
    }

    public void setStocksInGroup(String group, List<String> stocks) {
        // 存储为仅包含代码的清单（例如 sh600519;hk00700）
        if (stocks == null || stocks.isEmpty()) {
            props.unsetValue(KEY_GROUP_PREFIX + group);
            return;
        }
        List<String> codes = new ArrayList<>();
        for (String s : stocks) {
            if (StringUtils.isBlank(s)) continue;
            String code = s.contains(",") ? s.substring(0, s.indexOf(",")).trim() : s.trim();
            if (StringUtils.isBlank(code)) continue;
            boolean exists = codes.stream().anyMatch(c -> c.equalsIgnoreCase(code));
            if (!exists) codes.add(code);
        }
        if (codes.isEmpty()) props.unsetValue(KEY_GROUP_PREFIX + group);
        else props.setValue(KEY_GROUP_PREFIX + group, String.join(";", codes));
    }

    /**
     * 将指定的股票配置添加到分组（若已存在则不重复添加）
     */
    public void addStockToGroup(String stockConfig, String group) {
        if (StringUtils.isBlank(stockConfig) || StringUtils.isBlank(group)) return;
        // 接受 full config 或直接 code，统一保存为 code
        String code = stockConfig.contains(",") ? stockConfig.substring(0, stockConfig.indexOf(",")).trim() : stockConfig.trim();
        if (StringUtils.isBlank(code)) return;
        List<String> stocks = getStocksInGroup(group);
        boolean exists = stocks.stream().anyMatch(s -> s.equalsIgnoreCase(code));
        if (!exists) {
            stocks.add(code);
            setStocksInGroup(group, stocks);
        }
    }

    /**
     * 从指定分组移除指定股票（按 code 匹配）
     */
    public void removeStockFromGroup(String stockCode, String group) {
        if (StringUtils.isBlank(stockCode) || StringUtils.isBlank(group)) return;
        String code = stockCode.contains(",") ? stockCode.substring(0, stockCode.indexOf(",")).trim() : stockCode.trim();
        List<String> stocks = getStocksInGroup(group);
        boolean changed = stocks.removeIf(s -> s.equalsIgnoreCase(code));
        if (changed) setStocksInGroup(group, stocks);
    }

    /**
     * 检查某股票是否在指定分组中
     */
    public boolean stockExistsInGroup(String stockCode, String group) {
        if (StringUtils.isBlank(stockCode) || StringUtils.isBlank(group)) return false;
        String code = stockCode.contains(",") ? stockCode.substring(0, stockCode.indexOf(",")).trim() : stockCode.trim();
        List<String> stocks = getStocksInGroup(group);
        return stocks.stream().anyMatch(s -> s.equalsIgnoreCase(code));
    }

    /** 提取股票代码（配置字符串的第一段） */
    private String codeOf(String config) {
        int idx = config.indexOf(',');
        return idx < 0 ? config.trim() : config.substring(0, idx).trim();
    }

    /**
     * 移动一只股票到目标分组（保留成本价和持仓信息）
     */
    public void moveStockToGroup(String stockConfig, String target) {
        String stockCode = stockConfig.contains(",") ? stockConfig.substring(0, stockConfig.indexOf(",")).trim() : stockConfig.trim();
        if (StringUtils.isBlank(stockCode) || StringUtils.isBlank(target)) return;
        // 从所有分组中移除匹配的 code
        for (String g : getGroupNames()) {
            List<String> stocks = getStocksInGroup(g);
            boolean removed = stocks.removeIf(s -> s.equalsIgnoreCase(stockCode));
            if (removed) setStocksInGroup(g, stocks);
        }
        // 添加到目标分组（以 code 形式存储）
        List<String> t = getStocksInGroup(target);
        boolean exists = t.stream().anyMatch(s -> s.equalsIgnoreCase(stockCode));
        if (!exists) {
            t.add(stockCode);
            setStocksInGroup(target, t);
        }
    }

    /** 获取某股票当前所属分组名 */
    public String getGroupForStock(String stockCode) {
        String code = stockCode.contains(",") ? stockCode.substring(0, stockCode.indexOf(",")).trim() : stockCode.trim();
        for (String g : getGroupNames())
            for (String s : getStocksInGroup(g))
                if (s.equalsIgnoreCase(code)) return g;
        return DEFAULT_GROUP;
    }

    /**
     * 根据 raw 配置列表重建所有分组数据
     * raw 列表来自 key_stocks，格式: "sh600519,12.45,1200;hk00700,13.41,2000"
     */
    public List<String> loadAllStocks() {
        // 不改变 key_stocks 的原始顺序——分组仅作为显示过滤控制
        return SettingsWindow.getConfigList("key_stocks");
    }

        public void initFromExisting() {
        List<String> all = SettingsWindow.getConfigList("key_stocks");
        if (all.isEmpty()) return;
        addGroup(DEFAULT_GROUP);
            // 将 key_stocks 中的 codes 初始化为默认分组（以 code 形式存储），但不改变 key_stocks 本身
            List<String> def = getStocksInGroup(DEFAULT_GROUP);
            boolean ch = false;
            for (String s : all) {
                String code = codeOf(s);
                if (!def.stream().anyMatch(d -> d.equalsIgnoreCase(code))) { def.add(code); ch = true; }
            }
            if (ch) setStocksInGroup(DEFAULT_GROUP, def);
    }
}
