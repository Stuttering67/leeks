package utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.TabsListener;
import com.intellij.ui.tabs.impl.JBTabsImpl;
import org.apache.commons.lang3.StringUtils;

import javax.imageio.ImageIO;
import utils.ImageCache;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * intellij ui 弹窗展示工具类 <br>
 * https://plugins.jetbrains.com/docs/intellij/popups.html#popups
 */
public class PopupsUiUtil {
    /**
     * 弹窗展示图片
     *
     * @param fundCode    基金编码
     * @param showByPoint 窗口显示位置
     */
    public static void showImageByFundCode(String fundCode, FundShowType type, Point showByPoint) throws MalformedURLException {
        //------试图解决个BUG，项目销毁的问题-------
        Project project = LogUtil.getProject();
        if (project.isDisposed()) {
            return;
        }
        // 图片接口
        // 带水印  http://j4.dfcfw.com/charts/pic6/590008.png
        // 无水印  http://j4.dfcfw.com/charts/pic7/590008.png
        // 暂时先硬编码，后续再优化调整
        // 使用异步加载避免在 EDT 上进行网络请求
        JLabel lbl = new JLabel("加载中...", SwingConstants.CENTER);
        lbl.setOpaque(true); lbl.setBackground(Color.WHITE);
        TabInfo tabInfo = new TabInfo(lbl);
        tabInfo.setText(type.getDesc());
        JBTabsImpl tabs = new JBTabsImpl(LogUtil.getProject());
        tabs.addTab(tabInfo);
        List<String> cands = new ArrayList<>();
        cands.add(String.format("https://j4.dfcfw.com/charts/pic7/%s.png?%s", fundCode, System.currentTimeMillis()));
        loadImageAsync(lbl, cands, StockShowType.min);
        JBPopupFactory.getInstance().createBalloonBuilder(tabs)
            .setBorderInsets(new Insets(0, 0, 0, 0))
            .createBalloon().show(RelativePoint.fromScreen(showByPoint), Balloon.Position.atRight);
    }

    /**
     * 获取图片链接
     *
     * @param stockCode 股票编码
     * @param type      枚举类型
     * @return 可能为null
     */
    public static String getImageUrlByStock(String stockCode, StockShowType type) throws MalformedURLException {
        // 仍然保留兼容签名，但实际在加载时会尝试多个候选 URL。
        List<String> cands = buildCandidateUrls(stockCode, type);
        for (String u : cands) {
            // 立刻返回第一个看起来合法的 URL（不做网络探测，实际加载时会尝试多个地址）
            if (StringUtils.isNotBlank(u)) return u;
        }
        return "";
    }

    private static List<String> buildCandidateUrls(String stockCode, StockShowType type) {
        String base = "https://image.sinajs.cn/newchart";
        String prefix = StringUtils.substring(stockCode, 0, 2);
        String suffix = stockCode.length() > 2 ? StringUtils.substring(stockCode, 2) : stockCode;
        long ts = System.currentTimeMillis();
        List<String> list = new ArrayList<>();
        // 常见路径组合，按可能性排序
        if ("sh".equals(prefix) || "sz".equals(prefix)) {
            list.add(String.format("%s/%s/n/%s.gif?%s", base, type.getType(), stockCode, ts));
            list.add(String.format("%s/%s/%s.gif?%s", base, type.getType(), stockCode, ts));
            list.add(String.format("%s/png/%s/%s.png?%s", base, type.getType(), stockCode, ts));
            list.add(String.format("%s/%s/n/%s.png?%s", base, type.getType(), stockCode, ts));
        } else if ("us".equals(prefix)) {
            list.add(String.format("%s/png/%s/us/%s.png?%s", base, type.getType(), suffix, ts));
            list.add(String.format("%s/us/%s/%s.gif?%s", base, type.getType(), suffix, ts));
            list.add(String.format("%s/%s/us/%s.gif?%s", base, type.getType(), suffix, ts));
            list.add(String.format("%s/png/%s/%s/%s.png?%s", base, type.getType(), prefix, suffix, ts));
        } else if ("hk".equals(prefix)) {
            list.add(String.format("%s/png/%s/hk/%s.png?%s", base, type.getType(), suffix, ts));
            list.add(String.format("%s/hk/%s/%s.gif?%s", base, type.getType(), suffix, ts));
            list.add(String.format("%s/%s_stock/%s/%s.gif?%s", base, prefix, type.getType(), suffix, ts));
            list.add(String.format("%s/png/%s/%s/%s.png?%s", base, type.getType(), prefix, suffix, ts));
        } else {
            // 回退：尝试使用原始 stockCode
            list.add(String.format("%s/%s/n/%s.gif?%s", base, type.getType(), stockCode, ts));
            list.add(String.format("%s/png/%s/%s.png?%s", base, type.getType(), stockCode, ts));
        }
        return list;
    }

    // 跟踪已打开的 chart dialog，防止同一个股票 code 重复弹窗
    private static final Map<String, JDialog> openChartDialogs = new HashMap<>();

    /**
     * 弹窗展示 K线图（使用 JDialog 替代 Balloon，自带关闭按钮）
     *
     * @param stockCode   编码
     * @param selectType  展示的类型
     * @param showByPoint 窗口显示位置（未使用，由 JDialog 自行定位）
     */
    public static void showImageByStockCode(String stockCode, StockShowType selectType, Point showByPoint) throws MalformedURLException {
        if (selectType == StockShowType.delete || selectType == StockShowType.group) {
            return;
        }

        // 已存在该 code 的弹窗则复用
        JDialog existing = openChartDialogs.get(stockCode);
        if (existing != null && existing.isVisible()) {
            existing.toFront();
            return;
        }

        JDialog dialog = new JDialog((JFrame) null, stockCode + " K线图", false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { openChartDialogs.remove(stockCode); }
        });
        openChartDialogs.put(stockCode, dialog);

        // 选项卡：分时/日K/周K/月K（排除 delete 和 group)
        StockShowType[] chartTypes = {StockShowType.min, StockShowType.daily, StockShowType.weekly, StockShowType.monthly};

        // 给每个 tab 记录对应的候选 url 列表和对应的 label
        Map<Integer, List<String>> tabUrlCandidates = new HashMap<>();
        Map<Integer, JLabel> tabLabels = new HashMap<>();

        JBTabsImpl tabs = new JBTabsImpl(LogUtil.getProject());
        int idx = 0;
        for (StockShowType type : chartTypes) {
            List<String> candidates = buildCandidateUrls(stockCode, type);
            JPanel panelForTab = new JPanel(new BorderLayout());
            JLabel lbl = new JLabel("加载中...", SwingConstants.CENTER);
            // 所有图表都使用白色底，便于在深色主题中显示
            lbl.setOpaque(true);
            lbl.setBackground(Color.WHITE);
            panelForTab.add(lbl, BorderLayout.CENTER);
            TabInfo tabInfo = new TabInfo(panelForTab);
            tabInfo.setText(type.getDesc());
            tabs.addTab(tabInfo);
            tabUrlCandidates.put(idx, candidates);
            tabLabels.put(idx, lbl);
            if (type.equals(selectType)) {
                tabs.select(tabInfo, true);
                loadImageAsync(lbl, candidates, type);
            }
            idx++;
        }

        // tab 切换时异步加载对应图片（在后台依次尝试候选 URL，直到成功）
        tabs.addListener(new TabsListener() {
            // 兼容到2017，请勿修改
            @Override
            public void selectionChanged(TabInfo oldSelection, TabInfo newSelection) {
                int selectedIdx = tabs.getIndexOf(newSelection);
                List<String> urls = tabUrlCandidates.get(selectedIdx);
                JLabel lbl = tabLabels.get(selectedIdx);
                if (urls != null && lbl != null) {
                    StockShowType t = chartTypes[selectedIdx];
                    loadImageAsync(lbl, urls, t);
                }
            }
        });

        // 关闭按钮
        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(closeBtn);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(tabs.getComponent(), BorderLayout.CENTER);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
        mainPanel.setPreferredSize(new Dimension(620, 520));

        dialog.getContentPane().add(mainPanel);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    /** 在后台线程加载图片，加载完后更新 label */
    private static void loadImageAsync(JLabel label, List<String> candidateUrls, StockShowType type) {
        label.setText("加载中...");
        label.setIcon(null);
        // ensure white background for all chart types
        label.setOpaque(true);
        label.setBackground(Color.WHITE);
        ThreadPools.getRefreshExecutor().execute(() -> {
            boolean loaded = false;
            for (String imageUrl : candidateUrls) {
                if (StringUtils.isBlank(imageUrl)) continue;
                try {
                    byte[] data = ImageCache.getInstance().get(imageUrl);
                    if (data == null) {
                        data = HttpClientPool.getHttpClient().getBytes(imageUrl);
                        if (data != null && data.length > 0) ImageCache.getInstance().put(imageUrl, data);
                    }
                    if (data != null && data.length > 0) {
                        ImageIcon icon = new ImageIcon(data);
                        if (icon.getIconWidth() > 0) {
                            SwingUtilities.invokeLater(() -> {
                                label.setIcon(icon);
                                label.setText(null);
                                label.setPreferredSize(null);
                                label.setMinimumSize(null);
                                Container parent = label.getParent();
                                if (parent != null) { parent.revalidate(); parent.repaint(); }
                                Window win = SwingUtilities.getWindowAncestor(label);
                                if (win instanceof JDialog) {
                                    JDialog dlg = (JDialog) win; dlg.pack(); dlg.setLocationRelativeTo(null);
                                }
                            });
                            loaded = true; break;
                        }
                    }
                } catch (Exception ex) {
                    LogUtil.info("尝试图表URL异常: " + imageUrl + " -> " + ex.getMessage());
                }
            }
            if (!loaded) SwingUtilities.invokeLater(() -> label.setText("图片加载失败"));
        });
    }

    public enum FundShowType {
        /**
         * 净值估算图
         */
        gsz("净值估算图");
        private String desc;

        FundShowType(String desc) {
            this.desc = desc;
        }

        public String getDesc() {
            return desc;
        }
    }

    public enum StockShowType {
        /**
         * 删除
         */
        delete("delete", "删除"),
        /**
         * 设置分组
         */
        group("group", "设置分组"),
        /**
         * 分时线图
         */
        min("min", "分时线图"),
        /**
         * 日K线图
         */
        daily("daily", "日K线图"),
        /**
         * 周K线图
         */
        weekly("weekly", "周K线图"),
        /**
         * 月K线图
         */
        monthly("monthly", "月K线图");

        private String type;
        private String desc;

        StockShowType(String type, String desc) {
            this.type = type;
            this.desc = desc;
        }

        public String getType() {
            return type;
        }

        public String getDesc() {
            return desc;
        }
    }
}
