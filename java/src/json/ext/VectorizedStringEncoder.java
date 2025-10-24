package json.ext;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.jruby.util.ByteList;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

class VectorizedStringEncoder extends SWARBasicStringEncoder {
    private static final VectorSpecies<Byte> SP = ByteVector.SPECIES_PREFERRED;
    private static final ByteVector ZERO = ByteVector.zero(SP);
    private static final ByteVector TWO = ByteVector.broadcast(SP, 2);
    private static final ByteVector THIRTY_THREE = ByteVector.broadcast(SP, 33);
    private static final ByteVector BACKSLASH = ByteVector.broadcast(SP, '\\');

    @Override
    public StringEncoder clone() {
        return new VectorizedStringEncoder();
    }

    @Override
    void encode(ByteList src) throws IOException {
        byte[] ptrBytes = src.unsafeBytes();
        int ptr = src.begin();
        int len = src.realSize();
        int beg = 0;
        int pos = ptr;

        while ((pos + SP.length() <= len)) {
            ByteVector chunk = ByteVector.fromArray(SP, ptrBytes, ptr + pos);
            // bytes are signed in java, so we need to remove negative values
            VectorMask<Byte> negative = chunk.lt(ZERO);
            VectorMask<Byte> tooLowOrDblQuote = chunk.lanewise(VectorOperators.XOR, TWO).lt(THIRTY_THREE).andNot(negative);
            VectorMask<Byte> needsEscape = chunk.eq(BACKSLASH).or(tooLowOrDblQuote);
            if (needsEscape.anyTrue()) {
                int chunkStart = pos;
                long mask = needsEscape.toLong();

                while(mask > 0) {
                    // nextMatch inlined
                    int index = Long.numberOfTrailingZeros(mask);
                    mask &= (mask - 1);
                    pos = chunkStart + index;
                    int ch = Byte.toUnsignedInt(ptrBytes[ptr + pos]);
                    
                    beg = pos = flushPos(pos, beg, ptrBytes, ptr, 1);
                    escapeAscii(ch, aux, HEX);
                }
                
                // Skip over any remaining characters in the current chunk
                pos = chunkStart + SP.length();
                continue;
            }

            pos += SP.length();
        }

        ByteBuffer bb = ByteBuffer.wrap(ptrBytes, ptr, len);
        if (pos + 8 <= len) {
            long x = bb.getLong(ptr + pos);
            if (skipChunk(x)) {
                pos += 8;
            } else {
                int chunkEnd = ptr + pos + 8;
                while (pos < chunkEnd) {
                    int ch = Byte.toUnsignedInt(ptrBytes[ptr + pos]);
                    int ch_len = ESCAPE_TABLE[ch];
                    if (ch_len > 0) {
                        beg = pos = flushPos(pos, beg, ptrBytes, ptr, 1);
                        escapeAscii(ch, aux, HEX);
                    } else {
                        pos++;
                    }
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
                escapeAscii(ch, aux, HEX);
            } else {
                pos++;
            }
        }

        if (beg < len) {
            append(ptrBytes, ptr + beg, len - beg);
        }
    }
}
