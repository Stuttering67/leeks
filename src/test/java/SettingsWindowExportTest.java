package leeks;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

public class SettingsWindowExportTest {
    @Test
    public void exportedStringKeysIncludeGroupTabWrapCount() {
        List<String> keys = SettingsWindow.getExportedSettingStringKeys();
        assertTrue(keys.contains("key_group_tab_wrap_count"));
    }
}
