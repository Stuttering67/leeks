package leeks;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionToolbarPosition;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import handler.CoinRefreshHandler;
import handler.YahooCoinHandler;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import quartz.HandlerJob;
import quartz.QuartzManager;
import utils.WindowUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.HashMap;
import java.util.List;

public class CoinWindow {
    public static final String NAME = "Coin";
    private JPanel mPanel;

    static CoinRefreshHandler handler;

    static JBTable table;
    static JLabel refreshTimeLabel;

    public JPanel getmPanel() {
        return mPanel;
    }

    static {
        refreshTimeLabel = new JLabel();
        refreshTimeLabel.setToolTipText("最后刷新时间");
        refreshTimeLabel.setBorder(new EmptyBorder(0, 0, 0, 5));
        table = new JBTable();
        //记录列名的变化
        table.getTableHeader().addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                StringBuilder tableHeadChange = new StringBuilder();
                for (int i = 0; i < table.getColumnCount(); i++) {
                    tableHeadChange.append(table.getColumnName(i)).append(",");
                }
                PropertiesComponent instance = PropertiesComponent.getInstance();
                //将列名的修改放入环境中 key:coin_table_header_key
                instance.setValue(WindowUtils.COIN_TABLE_HEADER_KEY, tableHeadChange
                        .substring(0, tableHeadChange.length() > 0 ? tableHeadChange.length() - 1 : 0));

                //LogUtil.info(instance.getValue(WindowUtils.COIN_TABLE_HEADER_KEY));
            }
        });
    }

    public CoinWindow() {
        // 确保mPanel被初始化（GUI Designer表单编译依赖IDEA编译器，此处做防御）
        if (mPanel == null) {
            mPanel = new JPanel(new BorderLayout());
        }
        //切换接口
        handler = new YahooCoinHandler(table,refreshTimeLabel);

        AnActionButton refreshAction = new AnActionButton("停止刷新当前表格数据", AllIcons.Actions.Pause) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                stop();
                this.setEnabled(false);
            }
        };
        ToolbarDecorator toolbarDecorator = ToolbarDecorator.createDecorator(table)
                .addExtraAction(new AnActionButton("持续刷新当前表格数据", AllIcons.Actions.Refresh) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        refresh();
                        refreshAction.setEnabled(true);
                    }
                })
                .addExtraAction(refreshAction)
                .setToolbarPosition(ActionToolbarPosition.TOP);
        JPanel toolPanel = toolbarDecorator.createPanel();
        toolbarDecorator.getActionsPanel().add(refreshTimeLabel, BorderLayout.EAST);
        toolPanel.setBorder(new EmptyBorder(0,0,0,0));
        mPanel.add(toolPanel, BorderLayout.CENTER);
        // 非主要tab，需要创建，创建时立即应用数据
        apply();
    }



    public static void apply() {
        if (handler != null) {
            ConfigManager configManager = ConfigManager.getInstance();
            handler.setStriped(configManager.isTableStriped());
            handler.clearRow();
            handler.setupTable(loadCoins());
            refresh();
        }
    }
    public static void refresh() {
        if (handler != null) {
            ConfigManager configManager = ConfigManager.getInstance();
            handler.refreshColorful(configManager.isColorfulEnabled());
            List<String> codes = loadCoins();
            if (CollectionUtils.isEmpty(codes)) {
                stop(); //如果没有数据则不需要启动时钟任务浪费资源
            } else {
                // Run network refresh in background to avoid blocking EDT on plugin load
                utils.ThreadPools.getRefreshExecutor().execute(() -> {
                    try {
                        handler.handle(codes);
                    } catch (Exception ex) {
                        utils.LogUtil.info("Coin refresh failed: " + ex.getMessage());
                    }
                    try {
                        QuartzManager quartzManager = QuartzManager.getInstance(NAME); // 时钟任务
                        HashMap<String, Object> dataMap = new HashMap<>();
                        dataMap.put(HandlerJob.KEY_HANDLER, handler);
                        dataMap.put(HandlerJob.KEY_CODES, codes);
                        // 优先使用秒级刷新配置
                        int sec = ConfigManager.getInstance().getCoinRefreshIntervalSeconds();
                        if (sec > 0) {
                            quartzManager.runJobWithInterval(HandlerJob.class, sec, dataMap);
                        } else {
                            String userCron = PropertiesComponent.getInstance().getValue(ConfigKeys.KEY_CRON_EXPRESSION_COIN);
                            if (StringUtils.isNotBlank(userCron)) quartzManager.runJob(HandlerJob.class, userCron, dataMap);
                            else quartzManager.runJob(HandlerJob.class, configManager.getCoinCronExpression(), dataMap);
                        }
                    } catch (Throwable qex) { utils.LogUtil.info("CoinQuartz schedule failed: " + qex.getMessage()); }
                });
            }
        }
    }

    public static void stop() {
        try {
            QuartzManager.getInstance(NAME).stopJob();
        } catch (Throwable ignore) { utils.LogUtil.info("CoinWindow.stop: stopJob exception: " + (ignore == null ? "null" : ignore.getMessage())); }
        if (handler != null) {
            handler.stopHandle();
        }
    }

    private static List<String> loadCoins(){
        return SettingsWindow.getConfigList("key_coins", "[,，]");
    }

}
