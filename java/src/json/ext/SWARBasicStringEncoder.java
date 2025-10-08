package json.ext;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.jruby.util.ByteList;

public class SWARBasicStringEncoder extends StringEncoder {

    public SWARBasicStringEncoder() {
        super(ESCAPE_TABLE);
    }

    @Override
    void encode(ByteList src) throws IOException {
        byte[] hexdig = HEX;
        byte[] scratch = aux;

        byte[] ptrBytes = src.unsafeBytes();
        int ptr = src.begin();
        int len = src.realSize();

        int beg = 0;
        int pos = 0;

        // There are optimizations in JRuby where ptr > 0 will be true. For example, if we 
        // slice a string, the underlying byte array is the same, but the
        // begin index and real size are different. When reading from the ptrBytes
        // array, we need to always add ptr to the index.
        ByteBuffer bb = ByteBuffer.wrap(ptrBytes, 0, ptr + len);

        while (pos + 8 <= len) {
            long x = bb.getLong(ptr + pos);
            if (skipChunk(x)) {
                pos += 8;
                continue;
            }
            int chunkEnd = ptr + pos + 8;
            while (ptr + pos < chunkEnd) {
                int ch = Byte.toUnsignedInt(ptrBytes[ptr + pos]);
                int ch_len = ESCAPE_TABLE[ch];
                if (ch_len > 0) {
                    beg = pos = flushPos(pos, beg, ptrBytes, ptr, 1);
                    escapeAscii(ch, scratch, hexdig);
                } else {
                    pos++;
                }
            }
        }

        if (pos + 4 <= len) {
            int x = bb.getInt(ptr + pos);
            if (skipChunk(x)) {
                pos += 4;
            }
        }

        while (pos < len) {
            int ch = Byte.toUnsignedInt(ptrBytes[ptr + pos]);
            int ch_len = ESCAPE_TABLE[ch];
            if (ch_len > 0) {
                beg = pos = flushPos(pos, beg, ptrBytes, ptr, 1);
                escapeAscii(ch, scratch, hexdig);
            } else {
                pos++;
            }
        }

        if (beg < len) {
            append(ptrBytes, ptr + beg, len - beg);
        }
    }

    boolean skipChunk(long x) {
        long is_ascii = 0x8080808080808080L & ~x;
        long xor2 = x ^ 0x0202020202020202L;
        long lt32_or_eq34 = xor2 - 0x2121212121212121L;
        long sub92 = x ^ 0x5C5C5C5C5C5C5C5CL;
        long eq92 = (sub92 - 0x0101010101010101L);
        return ((lt32_or_eq34 | eq92) & is_ascii) == 0;
    }

    boolean skipChunk(int x) {
        int is_ascii = 0x80808080 & ~x;
        int xor2 = x ^ 0x02020202;
        int lt32_or_eq34 = xor2 - 0x21212121;
        int sub92 = x ^ 0x5C5C5C5C;
        int eq92 = (sub92 - 0x01010101);
        return ((lt32_or_eq34 | eq92) & is_ascii) == 0;
    }
}
