package com.shimizukenta.secssim.core.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.shimizukenta.secssim.core.domain.CeidDef;
import com.shimizukenta.secssim.core.domain.EquipmentSpec;
import com.shimizukenta.secssim.core.domain.GlobalCeidDef;
import com.shimizukenta.secssim.core.domain.GlobalSvidDef;
import com.shimizukenta.secssim.core.domain.IdRange;
import com.shimizukenta.secssim.core.domain.IdSpacePolicy;
import com.shimizukenta.secssim.core.domain.ReportDef;
import com.shimizukenta.secssim.core.domain.SvidDef;
import com.shimizukenta.secssim.core.domain.UnitId;
import com.shimizukenta.secssim.core.domain.UnitSpec;
import com.shimizukenta.secssim.core.domain.ValueType;

class EquipmentCompilerTest {

    private static UnitSpec chamber(String name, int unitId) {
        return new UnitSpec(UnitId.of(unitId), name,
                List.of(
                        new SvidDef(1, "TEMP_PV", ValueType.F4, "C", 2, 0.0, 400.0),
                        new SvidDef(2, "TEMP_SV", ValueType.F4, "C", 2, 0.0, 400.0),
                        new SvidDef(90, "UNIT_ID", ValueType.U4, "", 0, 0.0, 64.0)),
                List.of(new ReportDef(1, "STATUS", List.of("UNIT_ID", "TEMP_PV"))),
                List.of(new CeidDef(1, "PROCESS_START", List.of("STATUS")),
                        CeidDef.of(2, "PROCESS_END")),
                true);
    }

    private static EquipmentSpec twoChambers() {
        return new EquipmentSpec("PRI-01", 100,
                List.of(chamber("CH1", 1), chamber("CH2", 2)),
                List.of(),
                List.of(),
                IdSpacePolicy.defaults());
    }

    private static Set<String> codes(CompilationResult result) {
        return result.issues().stream().map(Issue::code).collect(Collectors.toSet());
    }

    @Test
    void wellFormedEquipmentCompiles() {
        CompilationResult result = EquipmentCompiler.compile(twoChambers());

        assertTrue(result.ok(), () -> "unexpected issues: " + result.issues());
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }

    @Test
    void idsAreResolvedPerUnitSegment() {
        ResolvedEquipment eq = EquipmentCompiler.compile(twoChambers()).equipment();

        assertEquals(Set.of(1001, 1002, 1090, 1101, 1102, 1190), eq.svids().keySet());
        assertEquals(Set.of(2001, 2002, 2101, 2102), eq.ceids().keySet());
        assertEquals(Set.of(3001, 3101), eq.reports().keySet());

        assertEquals("CH1", eq.unitByCeid().get(2001));
        assertEquals("CH2", eq.unitByCeid().get(2101));
    }

    @Test
    void reportKeepsSvidOrder() {
        ResolvedEquipment eq = EquipmentCompiler.compile(twoChambers()).equipment();

        assertEquals(List.of(1090, 1001), eq.reports().get(3001).svidIds());
        assertEquals(List.of(1190, 1101), eq.reports().get(3101).svidIds());
    }

    /**
     * 号段溢出必须变成 Issue，而不是负数 ID。
     *
     * <p>以前只校验了 base + (position-1)*stride，没有校验 +槽位偏移：
     * svidBase 接近 Integer.MAX_VALUE 时能编译通过，模型里出现负 ID，
     * 报文按 U4 发出去就是 2147483648 之类的编号。</p>
     */
    @Test
    void idSegmentOverflowIsReportedAsIssue() {
        IdSpacePolicy policy = new IdSpacePolicy(
                Integer.MAX_VALUE - 10, 2000, 3000, 100, 100, 100,
                List.of(new IdRange(1, 999)), List.of(new IdRange(1, 999)), List.of(new IdRange(1, 999)));
        EquipmentSpec spec = new EquipmentSpec("E", 1, List.of(chamber("CH1", 1)),
                List.of(), List.of(), policy);

        CompilationResult result = EquipmentCompiler.compile(spec);

        assertFalse(result.ok(), "号段溢出必须编译失败");
        assertTrue(codes(result).contains("ID_OUT_OF_RANGE"), result.issues().toString());
    }

    @Test
    void duplicateUnitIdIsRejected() {
        EquipmentSpec spec = new EquipmentSpec("E", 1,
                List.of(chamber("CH1", 7), chamber("CH2", 7)),
                List.of(), List.of(), IdSpacePolicy.defaults());

        assertTrue(codes(EquipmentCompiler.compile(spec)).contains("DUPLICATE_UNIT_ID"));
    }

    @Test
    void duplicateSlotIsRejected() {
        UnitSpec bad = new UnitSpec(UnitId.of(1), "CH1",
                List.of(SvidDef.of(1, "TEMP", ValueType.F4), SvidDef.of(2, "TEMP", ValueType.F4)),
                List.of(), List.of(), false);

        assertTrue(codes(EquipmentCompiler.compile(
                new EquipmentSpec("E", 1, List.of(bad), List.of(), List.of(), IdSpacePolicy.defaults())))
                .contains("DUPLICATE_SLOT"));
    }

    @Test
    void strideTooSmallIsRejected() {
        UnitSpec wide = new UnitSpec(UnitId.of(1), "CH1",
                List.of(SvidDef.of(100, "TEMP", ValueType.F4)),
                List.of(), List.of(), false);

        assertTrue(codes(EquipmentCompiler.compile(
                new EquipmentSpec("E", 1, List.of(wide), List.of(), List.of(), IdSpacePolicy.defaults())))
                .contains("STRIDE_TOO_SMALL_SVID"));
    }

    @Test
    void reservedRangeIsRejected() {
        IdSpacePolicy cramped = new IdSpacePolicy(900, 2000, 3000, 100, 100, 100,
                List.of(new IdRange(1, 999)), List.of(new IdRange(1, 999)), List.of(new IdRange(1, 999)));

        assertTrue(codes(EquipmentCompiler.compile(new EquipmentSpec("E", 1,
                List.of(chamber("CH1", 1)), List.of(), List.of(), cramped))).contains("RESERVED_ID"));
    }

    @Test
    void unknownSvidInReportIsRejected() {
        UnitSpec bad = new UnitSpec(UnitId.of(1), "CH1",
                List.of(SvidDef.of(1, "TEMP", ValueType.F4)),
                List.of(new ReportDef(1, "STATUS", List.of("PRESSURE"))),
                List.of(), false);

        assertTrue(codes(EquipmentCompiler.compile(
                new EquipmentSpec("E", 1, List.of(bad), List.of(), List.of(), IdSpacePolicy.defaults())))
                .contains("UNKNOWN_SVID_REF"));
    }

    @Test
    void unknownReportInCeidIsRejected() {
        UnitSpec bad = new UnitSpec(UnitId.of(1), "CH1",
                List.of(SvidDef.of(1, "TEMP", ValueType.F4)),
                List.of(),
                List.of(new CeidDef(1, "PROCESS_START", List.of("NOPE"))),
                false);

        assertTrue(codes(EquipmentCompiler.compile(
                new EquipmentSpec("E", 1, List.of(bad), List.of(), List.of(), IdSpacePolicy.defaults())))
                .contains("UNKNOWN_REPORT_REF"));
    }

    @Test
    void injectUnitIdWithoutSlotIsRejected() {
        UnitSpec bad = new UnitSpec(UnitId.of(1), "CH1",
                List.of(SvidDef.of(1, "TEMP", ValueType.F4)),
                List.of(), List.of(), true);

        assertTrue(codes(EquipmentCompiler.compile(
                new EquipmentSpec("E", 1, List.of(bad), List.of(), List.of(), IdSpacePolicy.defaults())))
                .contains("MISSING_UNIT_ID_SVID"));
    }

    @Test
    void globalSvidClashingWithUnitSvidIsRejected() {
        EquipmentSpec spec = new EquipmentSpec("E", 1,
                List.of(chamber("CH1", 1)),
                List.of(new GlobalSvidDef(1001, "FACILITY_TEMP", ValueType.F4)),
                List.of(), IdSpacePolicy.defaults());

        assertTrue(codes(EquipmentCompiler.compile(spec)).contains("DUPLICATE_SVID"));
    }

    @Test
    void globalCeidCanReferenceCrossUnitReportByQualifiedName() {
        EquipmentSpec spec = new EquipmentSpec("E", 1,
                List.of(chamber("CH1", 1), chamber("CH2", 2)),
                List.of(),
                List.of(new GlobalCeidDef(1001, "CONTROL_STATE_CHANGE", List.of("CH2.STATUS"))),
                IdSpacePolicy.defaults());

        CompilationResult result = EquipmentCompiler.compile(spec);

        assertTrue(result.ok(), () -> "unexpected issues: " + result.issues());
        assertEquals(List.of(3101), result.equipment().ceids().get(1001).reportIds());
        assertEquals("GLOBAL", result.equipment().unitByCeid().get(1001));
    }

    @Test
    void ambiguousShortReportNameIsRejected() {
        EquipmentSpec spec = new EquipmentSpec("E", 1,
                List.of(chamber("CH1", 1), chamber("CH2", 2)),
                List.of(),
                List.of(new GlobalCeidDef(1001, "CONTROL_STATE_CHANGE", List.of("STATUS"))),
                IdSpacePolicy.defaults());

        assertTrue(codes(EquipmentCompiler.compile(spec)).contains("AMBIGUOUS_REPORT_REF"));
    }

    @Test
    void equipmentWithoutUnitsIsRejected() {
        CompilationResult result = EquipmentCompiler.compile(
                new EquipmentSpec("E", 1, List.of(), List.of(), List.of(), IdSpacePolicy.defaults()));

        assertFalse(result.ok());
        assertTrue(codes(result).contains("NO_UNITS"));
    }
}
