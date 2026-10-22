package com.wangyu.tangdb.storage;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 值校验/隐式转换/比较的测试(规约 §4.5 / §8)。 */
class ValuesTest {

    private static Column intCol() {
        return new Column("age", DataType.INT, 0, true, false, 0);
    }

    private static Column bigintCol() {
        return new Column("id", DataType.BIGINT, 0, false, true, 0);
    }

    private static Column varcharCol(int len) {
        return new Column("name", DataType.VARCHAR, len, true, false, 0);
    }

    @Test
    void intRangeChecks() {
        assertEquals(26, Values.coerce(26, intCol()));
        assertEquals(Integer.MAX_VALUE, Values.coerce((long) Integer.MAX_VALUE, intCol()));

        StoreException e = assertThrows(StoreException.class,
                () -> Values.coerce(2147483648L, intCol()));
        assertTrue(e.getMessage().contains("超出 INT 范围"), e.getMessage());
    }

    @Test
    void intRejectsDecimal() {
        assertThrows(StoreException.class, () -> Values.coerce(1.5, intCol()));
    }

    @Test
    void varcharLengthIsMeasuredInUtf8Bytes() {
        assertEquals("北京", Values.coerce("北京", varcharCol(6)));
        StoreException e = assertThrows(StoreException.class,
                () -> Values.coerce("北京", varcharCol(5)));
        assertTrue(e.getMessage().contains("超长"), e.getMessage());
        assertTrue(e.getMessage().contains("6 字节"), e.getMessage());
    }

    @Test
    void dateMustBeIso() {
        assertEquals(LocalDate.of(2024, 1, 1), Values.coerce("2024-01-01", new Column("d", DataType.DATE, 0, true, false, 0)));
        StoreException e = assertThrows(StoreException.class,
                () -> Values.coerce("2024/01/01", new Column("d", DataType.DATE, 0, true, false, 0)));
        assertTrue(e.getMessage().contains("YYYY-MM-DD"), e.getMessage());
    }

    @Test
    void typeMismatchMessageNamesColumnAndExpectedType() {
        StoreException e = assertThrows(StoreException.class,
                () -> Values.coerce("abc", bigintCol()));
        assertEquals("列 id 类型不匹配(期望 BIGINT)", e.getMessage());
    }

    @Test
    void nullPrimaryKeyRejected() {
        StoreException e = assertThrows(StoreException.class, () -> Values.coerce(null, bigintCol()));
        assertTrue(e.getMessage().contains("主键列 id 不能为 NULL"), e.getMessage());
    }

    @Test
    void numericImplicitPromotion() {
        // BIGINT 列与字符串 '25' 比较:能转换则转换
        assertEquals(0, Values.compare(25L, bigintCol(), "25"));
        assertTrue(Values.compare(25L, bigintCol(), 25.5) < 0);
        assertTrue(Values.compare(2, intCol(), 3L) < 0);
        assertEquals(0, Values.compare(2.0, new Column("s", DataType.DOUBLE, 0, true, false, 0), 2));
    }

    @Test
    void stringComparesByUtf8Bytes() {
        assertEquals(0, Values.compare("北京", varcharCol(10), "北京"));
        assertTrue(Values.compare("一", varcharCol(10), "二") < 0);
        // 数值列与不可转换的字符串比较 → 类型不匹配
        assertThrows(StoreException.class, () -> Values.compare(1L, bigintCol(), "abc"));
    }

    @Test
    void dateComparison() {
        Column d = new Column("d", DataType.DATE, 0, true, false, 0);
        assertTrue(Values.compare(LocalDate.of(2024, 1, 1), d, "2024-06-01") < 0);
        assertThrows(StoreException.class, () -> Values.compare(LocalDate.of(2024, 1, 1), d, "2024/06/01"));
    }

    @Test
    void display() {
        assertEquals("NULL", Values.display(null));
        assertEquals("1", Values.display(1.0));
        assertEquals("1.5", Values.display(1.5));
        assertEquals("true", Values.display(true));
        assertEquals("北京", Values.display("北京"));
    }
}
