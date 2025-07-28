package json.ext;

import java.io.IOException;

import org.jruby.util.ByteList;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

class VectorizedStringEncoder extends StringEncoder {
    private static final VectorSpecies<Byte> SP = ByteVector.SPECIES_PREFERRED;
    private static final ByteVector ZERO = ByteVector.zero(SP);
    private static final ByteVector TWO = ByteVector.broadcast(SP, 2);
    private static final ByteVector THIRTY_THREE = ByteVector.broadcast(SP, 33);
    private static final ByteVector BACKSLASH = ByteVector.broadcast(SP, '\\');

    VectorizedStringEncoder() {
        super(StringEncoder.ESCAPE_TABLE);
    }

    @Override
    void encode(ByteList src) throws IOException {
        byte[] ptrBytes = src.unsafeBytes();
        int ptr = src.begin();
        int len = src.realSize();
        int beg = 0;
        int pos = ptr;

        while ((pos + SP.length() < len)) {
            ByteVector chunk = ByteVector.fromArray(SP, ptrBytes, pos);
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
                    int ch = Byte.toUnsignedInt(ptrBytes[pos]);
                    
                    beg = pos = flushPos(pos, beg, ptrBytes, ptr, 1);
                    escapeAscii(ch, aux, HEX);
                }
                
                // Skip over any remaining characters in the current chunk
                pos = chunkStart + SP.length();
                continue;
            }

            pos += SP.length();
        }

        while (pos < len) {
            int ch = Byte.toUnsignedInt(ptrBytes[pos]);
            int ch_len = escapeTable[ch];

            if (ch_len > 0) {
                beg = pos = flushPos(pos, beg, ptrBytes, ptr, 1);
                escapeAscii(ch, aux, HEX);
                continue;
            }

            pos++;
        }

        if (beg < len) {
            append(ptrBytes, ptr + beg, len - beg);
        }
    }
}
