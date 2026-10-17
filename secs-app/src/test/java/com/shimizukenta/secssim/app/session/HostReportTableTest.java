package com.shimizukenta.secssim.app.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class HostReportTableTest {

    @Test
    void defineAndReadReport() {
        HostReportTable table = new HostReportTable();
        table.defineReport(3001, List.of(1001, 1002));

        assertEquals(List.of(1001, 1002), table.svidsOf(3001).orElseThrow());
        assertEquals(1, table.reportCount());
        assertTrue(table.svidsOf(9999).isEmpty(), "未定义时返回 empty，让调用方回落到本地配置");
    }

    @Test
    void emptyVidListRemovesReport() {
        HostReportTable table = new HostReportTable();
        table.defineReport(3001, List.of(1001));
        table.defineReport(3001, List.of());

        assertTrue(table.svidsOf(3001).isEmpty());
        assertEquals(0, table.reportCount());
    }

    @Test
    void linkOverridesLocalReports() {
        HostReportTable table = new HostReportTable();
        table.link(2001, List.of(3100));

        assertEquals(List.of(3100), table.reportsFor(2001).orElseThrow());
        assertEquals(1, table.linkCount());

        table.link(2001, List.of());
        assertTrue(table.reportsFor(2001).isEmpty(), "解除后回落到本地配置");
    }

    @Test
    void clearingRemovesEverything() {
        HostReportTable table = new HostReportTable();
        table.defineReport(3001, List.of(1001));
        table.link(2001, List.of(3001));

        table.clearReports();
        table.clearLinks();

        assertEquals(0, table.reportCount());
        assertEquals(0, table.linkCount());
    }

    @Test
    void disablingSpecificCeidLeavesOthersEnabled() {
        HostReportTable table = new HostReportTable();

        table.setEnabled(false, List.of(2001));

        assertFalse(table.isEnabled(2001));
        assertTrue(table.isEnabled(2002), "未禁用的事件不受影响");
        assertFalse(table.isAllDisabled());

        table.setEnabled(true, List.of(2001));
        assertTrue(table.isEnabled(2001));
    }

    @Test
    void disablingAllAndReenablingAll() {
        HostReportTable table = new HostReportTable();

        table.setEnabled(false, List.of());
        assertTrue(table.isAllDisabled());
        assertFalse(table.isEnabled(2001));
        assertFalse(table.isEnabled(9999), "整体禁用后任何事件都不发");

        table.setEnabled(true, List.of());
        assertTrue(table.isEnabled(2001));
    }
}
