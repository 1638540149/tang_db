package com.wangyu.tangdb.protocol.mysql;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 协议分帧:3 字节长度 + 1 字节序号,单包往返与 &gt;16MB 切片续发(FR-444)。 */
class PacketCodecTest {

    private static byte[] roundTrip(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketIO writer = new PacketIO(new ByteArrayInputStream(new byte[0]), out);
        writer.writePacket(payload);
        byte[] wire = out.toByteArray();
        PacketIO reader = new PacketIO(new ByteArrayInputStream(wire), new ByteArrayOutputStream());
        return reader.readPacket();
    }

    @Test
    void emptyPayload() throws Exception {
        assertArrayEquals(new byte[0], roundTrip(new byte[0]));
    }

    @Test
    void smallPayloadRoundTrip() throws Exception {
        byte[] payload = "hello tang_db".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(payload, roundTrip(payload));
    }

    @Test
    void exactlyOneChunkBoundary() throws Exception {
        byte[] payload = new byte[PacketIO.MAX_PACKET_PAYLOAD - 1];
        Arrays.fill(payload, (byte) 'x');
        assertArrayEquals(payload, roundTrip(payload));
    }

    @Test
    void payloadLargerThan16MBIsSlicedAndReassembled() throws Exception {
        byte[] payload = new byte[PacketIO.MAX_PACKET_PAYLOAD + 1000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        assertArrayEquals(payload, roundTrip(payload));
    }

    @Test
    void seqIncrementsAcrossSlicedPackets() throws Exception {
        byte[] payload = new byte[PacketIO.MAX_PACKET_PAYLOAD + 5];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketIO writer = new PacketIO(new ByteArrayInputStream(new byte[0]), out);
        writer.writePacket(payload);
        byte[] wire = out.toByteArray();
        // 第一个物理包 seq 0,第二个物理包 seq 1
        assertEquals(0, wire[3] & 0xFF);
        assertEquals(1, wire[PacketIO.MAX_PACKET_PAYLOAD + 7] & 0xFF);
    }
}
