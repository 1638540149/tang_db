package com.wangyu.tangdb.planner;

import com.wangyu.tangdb.catalog.CatalogException;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.parser.Parser;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.TableMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 访问路径选择测试(FR-142 ~ FR-144)。 */
class PlannerTest {

    private static final TableMeta USERS = new TableMeta(16, "users", TableMeta.STATE_ACTIVE, "", List.of(
            new Column("id", DataType.BIGINT, 0, false, true, 0),
            new Column("name", DataType.VARCHAR, 32, true, false, 1),
            new Column("city", DataType.VARCHAR, 32, true, false, 2),
            new Column("age", DataType.INT, 0, true, false, 3)
    ), false);

    private static Ast.Select select(String where) {
        return (Ast.Select) Parser.parse("SELECT * FROM users" + where);
    }

    @Test
    void primaryKeyEqualityUsesPointGet() {
        Plan plan = Planner.access(USERS, select(" WHERE id = 7").where());
        assertEquals(Plan.Kind.POINT, plan.kind());
        assertArrayEquals(KeyCodec.rowKey(16, DataType.BIGINT, 7L), plan.pointKeys().get(0));
    }

    @Test
    void primaryKeyRangeUsesScanBounds() {
        Plan plan = Planner.access(USERS, select(" WHERE id > 5 AND id <= 10").where());
        assertEquals(Plan.Kind.RANGE, plan.kind());
        assertFalse(plan.fullScan());
        assertArrayEquals(KeyCodec.lowerBound(16, DataType.BIGINT, 5L, false), plan.start());
        assertArrayEquals(KeyCodec.upperBound(16, DataType.BIGINT, 10L, true), plan.end());
    }

    @Test
    void primaryKeyInUsesMultiplePointLookups() {
        Plan plan = Planner.access(USERS, select(" WHERE id IN (1, 2, 3)").where());
        assertEquals(Plan.Kind.POINTS, plan.kind());
        assertEquals(3, plan.pointKeys().size());
    }

    @Test
    void nonPrimaryKeyFallsBackToFullScan() {
        Plan plan = Planner.access(USERS, select(" WHERE city = '北京'").where());
        assertEquals(Plan.Kind.RANGE, plan.kind());
        assertTrue(plan.fullScan());
        assertArrayEquals(KeyCodec.tableRangeStart(16), plan.start());
        assertArrayEquals(KeyCodec.tableRangeEnd(16), plan.end());
    }

    @Test
    void primaryKeyPlusOtherConditionStillUsesPointGet() {
        Plan plan = Planner.access(USERS, select(" WHERE id = 3 AND age > 20").where());
        assertEquals(Plan.Kind.POINT, plan.kind());
    }

    @Test
    void orPreventsIndexPath() {
        Plan plan = Planner.access(USERS, select(" WHERE id = 1 OR age = 20").where());
        assertEquals(Plan.Kind.RANGE, plan.kind());
        assertTrue(plan.fullScan());
    }

    @Test
    void stringRangeOnVarcharPrimaryKey() {
        TableMeta codes = new TableMeta(17, "codes", TableMeta.STATE_ACTIVE, "", List.of(
                new Column("code", DataType.VARCHAR, 16, false, true, 0)), false);
        Ast.Select sel = (Ast.Select) Parser.parse(
                "SELECT * FROM codes WHERE code >= 'a' AND code < 'c'");
        Plan plan = Planner.access(codes, sel.where());
        assertEquals(Plan.Kind.RANGE, plan.kind());
        assertFalse(plan.fullScan());
        assertArrayEquals(KeyCodec.lowerBound(17, DataType.VARCHAR, "a", true), plan.start());
        assertArrayEquals(KeyCodec.upperBound(17, DataType.VARCHAR, "c", false), plan.end());
    }

    @Test
    void projectionWithAlias() {
        Ast.Select sel = (Ast.Select) Parser.parse("SELECT id AS 编号, name FROM users");
        Planner.Projection p = Planner.projection(USERS, sel);
        assertArrayEquals(new int[]{0, 1}, p.indexes());
        assertEquals(List.of("编号", "name"), p.names());
    }

    @Test
    void unknownColumnReportsTable() {
        CatalogException e = assertThrows(CatalogException.class,
                () -> Planner.access(USERS, select(" WHERE nope = 1").where()));
        assertEquals("列 nope 不存在(表 users)", e.getMessage());
    }

    @Test
    void incompatibleLiteralReportsType() {
        assertThrows(RuntimeException.class,
                () -> Planner.access(USERS, select(" WHERE age = 'abc'").where()));
    }
}
