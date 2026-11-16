package com.folderspan.navigation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ScreenNavigationDeviceTest {
    @Test
    public void screenDestinationsRemainDistinctAfterReleaseOptimization() throws Exception {
        String[] names = {
            "settings.SettingsScreen", "settings.AppearanceSettingsScreen", "settings.PageSettingsScreen",
            "settings.FileShareSettingsScreen", "settings.EasyFileShareSettingsScreen", "settings.DirectoriesSettingsScreen",
            "settings.PermissionSettingsScreen", "settings.DeviceInfoScreen", "settings.LaboratorySettingsScreen",
            "settings.AboutSoftwareScreen", "main.ToolboxScreen", "device.DeviceScreen",
            "webrtc.WebRtcRoomManageScreen", "file.share.FileShareManageScreen", "network.NetworkManageScreen",
            "sync.SyncManageScreen", "file.filter.FileFilterScreen", "mcp.McpManageScreen"
        };
        Set<String> keys = new HashSet<>();
        for (String name : names) {
            // 反射读取实际产物，避免测试直接引用页面而掩盖 R8 类合并问题。
            Class<?> screenClass = Class.forName("com.folderspan.ui.screen." + name);
            Constructor<?> constructor = screenClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object screen = constructor.newInstance();
            Method routeKey = null;
            for (Method method : screen.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == String.class
                    && method.getDeclaringClass() != Object.class) {
                    routeKey = method;
                }
            }
            assertTrue("缺少页面路由 key: " + name, routeKey != null);
            String key = (String) routeKey.invoke(screen);
            assertEquals(screenClass.getSimpleName(), key);
            assertTrue("页面路由 key 重复: " + key, keys.add(key));
        }
    }
}
