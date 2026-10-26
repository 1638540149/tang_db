package com.wangyu.tangdb.protocol.mysql;

import com.wangyu.tangdb.execute.Result;
import com.wangyu.tangdb.execute.SqlError;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** OK / ERR / EOF / 文本结果集编码(FR-439)。 */
class ResultSetEncoderTest {

    @Test
    void okPacketHasZeroHeaderAndAffectedRows() {
        byte[] ok = ResultSetEncoder.okPacket(3, 0, Protocol.SERVER_STATUS_AUTOCOMMIT, 0);
        assertEquals(0x00, ok[0] & 0xFF);
        assertEquals(3, ok[1] & 0xFF, "3 行受影响(lenenc 3 单字节)");
    }

    @Test
    void okPacketWithInfoAppendsSessionState() {
        byte[] ok = ResultSetEncoder.okPacket(1, 0, Protocol.SERVER_STATUS_AUTOCOMMIT, 0, "OK, 已提交");
        assertEquals(0x00, ok[0] & 0xFF);
        // 载荷长度大于无 info 的版本(末尾有 info 字符串 + 空 session state)
        assertTrue(ok.length > 7);
    }

    @Test
    void errPacketCarriesCodeAndSqlState() {
        byte[] err = ResultSetEncoder.errPacket(SqlError.ER_DUP_ENTRY, "23000", "主键冲突");
        assertEquals(0xFF, err[0] & 0xFF);
        int code = (err[1] & 0xFF) | ((err[2] & 0xFF) << 8);
        assertEquals(SqlError.ER_DUP_ENTRY, code);
        assertArrayEquals("#23000".getBytes(), new byte[]{err[3], err[4], err[5], err[6], err[7], err[8]});
    }

    @Test
    void eofPacketHasFeHeader() {
        byte[] eof = ResultSetEncoder.eofPacket(0, Protocol.SERVER_STATUS_AUTOCOMMIT);
        assertEquals(0xFE, eof[0] & 0xFF);
    }

    @Test
    void messageEncodesToSingleOkPacket() {
        List<byte[]> packets = ResultSetEncoder.forResult(
                new Result.Message("OK, 1 row affected", 1),
                Protocol.SERVER_STATUS_AUTOCOMMIT, true);
        assertEquals(1, packets.size());
        assertEquals(0x00, packets.get(0)[0] & 0xFF);
    }

    @Test
    void resultSetWithNullAndLongText() {
        String longText = "长".repeat(5000); // 15000 字节(UTF-8)
        Result.Table table = new Result.Table(
                List.of("id", "name", "day"),
                List.of(
                        new Object[]{1L, "wangyu", LocalDate.of(2024, 1, 2)},
                        new Object[]{2, null, null},
                        new Object[]{3L, longText, null}));
        List<byte[]> packets = ResultSetEncoder.forResult(table, Protocol.SERVER_STATUS_AUTOCOMMIT, false);

        // 列数 + 3 列定义 + EOF + 3 行 + EOF = 9 包
        assertEquals(9, packets.size());
        // 列数包
        assertEquals(3, packets.get(0)[0] & 0xFF);
        // NULL 值在行包里编码为 0xFB;第 2 行(id=2,name=NULL,day=NULL)在 packets[6]
        byte[] secondRow = packets.get(6);
        // 第 2 行:lenenc(1)="2"(id) + 0xFB(name null) + 0xFB(day null)
        assertTrue(secondRow.length >= 3);
        assertEquals(0xFB, secondRow[2] & 0xFF, "NULL 用 0xFB 表示");
    }
}
