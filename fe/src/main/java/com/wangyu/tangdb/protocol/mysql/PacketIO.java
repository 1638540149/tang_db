package com.wangyu.tangdb.protocol.mysql;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * MySQL 协议分帧(SPEC-P3.5 FR-453,纯 JDK socket,不引任何网络框架)。
 *
 * <p>物理包 = 3 字节小端长度 + 1 字节序号 + 载荷。逻辑包超过 {@code 16MB-1} 时
 * 按协议切片续发(FR-444):长度字段等于 {@code 0xFFFFFF} 表示还有后续物理包。</p>
 */
public final class PacketIO {

    /** 单物理包载荷上限(16MB-1)。 */
    public static final int MAX_PACKET_PAYLOAD = 0xFFFFFF;

    private final InputStream in;
    private final OutputStream out;
    private int writeSeq;

    public PacketIO(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** 读一个逻辑包(自动拼接 {@code 0xFFFFFF} 的续包)。 */
    public byte[] readPacket() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int length = readInt3(in);
            int seq = in.read();
            if (seq < 0) {
                throw new EOFException("连接在读取包序号时关闭");
            }
            byte[] chunk = readFully(length);
            buf.write(chunk, 0, chunk.length);
            if (length < MAX_PACKET_PAYLOAD) {
                break;
            }
        }
        return buf.toByteArray();
    }

    /** 写一个逻辑包(载荷超过上限自动切片),序号从当前 {@code writeSeq} 起并递增。 */
    public void writePacket(byte[] payload) throws IOException {
        int seq = writeSeq;
        int pos = 0;
        int remaining = payload.length;
        if (remaining == 0) {
            writeInt3(out, 0);
            out.write(seq & 0xFF);
            out.flush();
            writeSeq = seq + 1;
            return;
        }
        do {
            int chunk = Math.min(remaining, MAX_PACKET_PAYLOAD);
            writeInt3(out, chunk);
            out.write(seq & 0xFF);
            out.write(payload, pos, chunk);
            pos += chunk;
            remaining -= chunk;
            seq++;
        } while (remaining > 0);
        out.flush();
        writeSeq = seq;
    }

    /** 重置/设置写序号。握手后 auth 结果从 seq 2 开始,命令响应从 seq 1 开始。 */
    public void setWriteSeq(int seq) {
        this.writeSeq = seq;
    }

    private static int readInt3(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if ((b1 | b2 | b3) < 0) {
            throw new EOFException("连接在读取包长度时关闭");
        }
        return b1 | (b2 << 8) | (b3 << 16);
    }

    private static void writeInt3(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
    }

    private byte[] readFully(int length) throws IOException {
        byte[] data = new byte[length];
        int pos = 0;
        while (pos < length) {
            int n = in.read(data, pos, length - pos);
            if (n < 0) {
                throw new EOFException("连接在读取包体时关闭");
            }
            pos += n;
        }
        return data;
    }
}
