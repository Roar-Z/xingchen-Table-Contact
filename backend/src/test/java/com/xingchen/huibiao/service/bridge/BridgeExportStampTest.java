package com.xingchen.huibiao.service.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// 导出文件夹时间后缀的换算。
class BridgeExportStampTest {

    private static boolean digits(String value) {
        return value != null && value.matches("[0-9]+");
    }

    @Test
    void keepsSecondsWhenPresent() {
        assertEquals("20261030034912", BridgeAgentService.stamp("2026-10-30 03:49:12"));
    }

    @Test
    void keepsMinuteOnlyLegacyUnchanged() {
        // 老数据只记到分钟，保持原来的 12 位，历史交付目录不会被另起一个
        assertEquals("202610300349", BridgeAgentService.stamp("2026-10-30 03:49"));
    }

    @Test
    void padsShortValueToMinutePrecision() {
        // 只有年月（6 位）的残缺值补零到 12 位，至少名字还能区分到分钟
        assertEquals("202610000000", BridgeAgentService.stamp("2026-10"));
    }

    @Test
    void fallsBackToNowWithSeconds() {
        String stamp = BridgeAgentService.stamp(null);
        assertEquals(14, stamp.length());
        assertTrue(digits(stamp), "时间后缀应当全是数字");
    }
}
