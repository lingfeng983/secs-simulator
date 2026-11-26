package com.shimizukenta.secssim.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.cycle.CycleClock;
import com.shimizukenta.secssim.core.curve.ConstantCurve;
import com.shimizukenta.secssim.core.domain.ValueType;
import com.shimizukenta.secssim.core.value.DataSource;

/**
 * 晶圆属性表：文本随片走、顺序即定义顺序、重名拒绝；
 * 数值型 source 现状静默空串（P2，留待 config 层校验，此处锁定现状）。
 */
class ContextTableTest {

    private static final CycleClock CLOCK = CycleClock.of(10_000L, 0L, 3, 7L);

    @Test
    void textFollowsWaferIndex() {
        ContextTable table = new ContextTable(List.of(
                new ContextTable.Def("LOTID", ValueType.ASCII,
                        new DataSource.CycleText(null, "LOT{02}", null, 1L, 1L)),
                new ContextTable.Def("RCPID", ValueType.ASCII, new DataSource.Text("RCP-A"))),
                CLOCK);

        assertEquals("LOT01", table.textAt("LOTID", 0L).orElseThrow());
        assertEquals("LOT02", table.textAt("LOTID", 10_000L).orElseThrow());
        assertEquals("LOT03", table.textAt("LOTID", 25_000L).orElseThrow());
        assertEquals("RCP-A", table.textAt("RCPID", 12_345L).orElseThrow());
        assertEquals(ValueType.ASCII, table.typeOf("LOTID").orElseThrow());
    }

    @Test
    void snapshotKeepsDefinitionOrderAndUnknownIdIsEmpty() {
        ContextTable table = new ContextTable(List.of(
                new ContextTable.Def("B", ValueType.ASCII, new DataSource.Text("b")),
                new ContextTable.Def("A", ValueType.ASCII, new DataSource.Text("a"))),
                CLOCK);

        assertEquals(List.of("B", "A"), List.copyOf(table.snapshotAt(0L).keySet()),
                "快照顺序 = 定义顺序，不按字典序");
        assertTrue(table.textAt("NOPE", 0L).isEmpty(), "未知属性名返回 empty");
        assertTrue(table.typeOf("NOPE").isEmpty());
        assertTrue(table.def("A").isPresent());
    }

    @Test
    void emptyTableAndDuplicateAndBlankIdAreRejected() {
        ContextTable empty = ContextTable.empty();
        assertTrue(empty.ids().isEmpty());
        assertTrue(empty.snapshotAt(0L).isEmpty());
        assertTrue(empty.textAt("LOTID", 0L).isEmpty());

        ContextTable.Def def = new ContextTable.Def("LOTID", ValueType.ASCII, new DataSource.Text("x"));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextTable(List.of(def, def), CLOCK), "重名属性必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new ContextTable.Def(" ", ValueType.ASCII, new DataSource.Text("x")),
                "空白 id 必须拒绝");
    }

    @Test
    void numericSourceFallsBackToEmptyTextAtCore() {
        /* core 兜底：Context 配了数值型 source 仍返回空串，但正常配置不会走到这里——
           SessionConfig.compile() 已在 config 层以 CONTEXT_SOURCE_TYPE_MISMATCH 拒绝。 */
        ContextTable table = new ContextTable(List.of(
                new ContextTable.Def("BAD_NUM", ValueType.F4,
                        new DataSource.CurveSource(new ConstantCurve(3.0), DataSource.Clock.PHASE, null)),
                new ContextTable.Def("CONST_NUM", ValueType.U2, new DataSource.Const(7.0))),
                CLOCK);

        assertEquals("", table.textAt("BAD_NUM", 0L).orElseThrow(), "数值型曲线 source 现状为空串");
        assertEquals("7", table.textAt("CONST_NUM", 0L).orElseThrow(), "Const 数值有文本表示（整数无小数点）");
    }
}