package json.ext;

import java.nio.ByteBuffer;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

final class VectorizedStringScanner extends StringScanner {
    private static final VectorSpecies<Byte> SP = ByteVector.SPECIES_PREFERRED;
    private static final ByteVector ZERO = ByteVector.zero(SP);
    private static final ByteVector TWO = ByteVector.broadcast(SP, 2);
    private static final ByteVector THIRTY_THREE = ByteVector.broadcast(SP, 33);
    private static final ByteVector SPACE = ByteVector.broadcast(SP, 0x20);
    private static final ByteVector BACKSLASH = ByteVector.broadcast(SP, '\\');
    private static final ByteVector DQUOTE = ByteVector.broadcast(SP, '"');

    @Override
    long scan(byte[] data, ByteBuffer chunks, int start, int end, boolean validateUtf8) {
        final int width = SP.length();
        int p = start;
        boolean plain = true;
        boolean ascii = true;

        // The same structure as the StringEncoder. The logic is
        // duplicated for maximum inlining.
        //
        // When UTF-8 validation is disabled, non-ASCII lanes are copied verbatim
        // and stay on the plain fast path, so the high-bit term drops out: plain
        // scans for control, quote and backslash; non-plain for quote and
        // backslash only.
        outer:
        while (true) {
            while (p + width <= end) {
                ByteVector chunk = ByteVector.fromArray(SP, data, p);
                VectorMask<Byte> interesting;
                if (validateUtf8) {
                    interesting = plain ? interestingLanes(chunk)
                                        : ascii ? quoteBackslashOrHighLanes(chunk)
                                                : quoteOrBackslashLanes(chunk);
                } else {
                    interesting = plain ? controlQuoteBackslashLanes(chunk)
                                        : quoteOrBackslashLanes(chunk);
                }
                if (interesting.anyTrue()) {
                    p += interesting.firstTrue();
                    break;
                }
                p += width;
            }
            while (p < end) {
                int b = data[p] & 0xFF;
                if (b == '"') {
                    return ((long) p) | (plain ? PLAIN_BIT : 0L) | (ascii ? ASCII_BIT : 0L);
                }
                if (b == '\\') {
                    plain = false;
                    p += 2; // skip the backslash and the escaped byte
                    continue outer;
                }
                if (b >= 0x80) {
                    ascii = false;
                    if (validateUtf8) {
                        plain = false;
                    }
                    p++;
                    continue outer;
                }
                if (b < 0x20) {
                    plain = false;
                    p++;
                    continue outer;
                }
                p++;
            }
            return NOT_FOUND;
        }
    }

    @Override
    int scanEscape(byte[] data, ByteBuffer chunks, int start, int end) {
        final int width = SP.length();
        if (start + width > end) {
            return super.scanEscape(data, chunks, start, end);
        }
        int p = start;
        while (p + width <= end) {
            ByteVector chunk = ByteVector.fromArray(SP, data, p);
            VectorMask<Byte> interesting = escapeOrControlLanes(chunk);
            if (interesting.anyTrue()) {
                return p + interesting.firstTrue();
            }
            p += width;
        }
        return super.scanEscape(data, chunks, p, end);
    }

    private static VectorMask<Byte> escapeOrControlLanes(ByteVector chunk) {
        VectorMask<Byte> negative = chunk.lt(ZERO);
        VectorMask<Byte> control = chunk.lt(SPACE).andNot(negative);
        return control.or(chunk.eq(BACKSLASH));
    }

    private static VectorMask<Byte> interestingLanes(ByteVector chunk) {
        VectorMask<Byte> negative = chunk.lt(ZERO);
        VectorMask<Byte> lowOrQuote = chunk.lanewise(VectorOperators.XOR, TWO)
                                           .lt(THIRTY_THREE)
                                           .andNot(negative);
        return lowOrQuote.or(chunk.eq(BACKSLASH)).or(negative);
    }

    private static VectorMask<Byte> quoteOrBackslashLanes(ByteVector chunk) {
        return chunk.eq(DQUOTE).or(chunk.eq(BACKSLASH));
    }

    // Like interestingLanes but without the non-ASCII (high-bit) term: flags
    // ASCII control characters, double quotes and backslashes only. Used as the
    // plain-path mask when UTF-8 validation is disabled.
    private static VectorMask<Byte> controlQuoteBackslashLanes(ByteVector chunk) {
        VectorMask<Byte> negative = chunk.lt(ZERO);
        VectorMask<Byte> lowOrQuote = chunk.lanewise(VectorOperators.XOR, TWO)
                                           .lt(THIRTY_THREE)
                                           .andNot(negative);
        return lowOrQuote.or(chunk.eq(BACKSLASH));
    }

    private static VectorMask<Byte> quoteBackslashOrHighLanes(ByteVector chunk) {
        return chunk.eq(DQUOTE).or(chunk.eq(BACKSLASH)).or(chunk.lt(ZERO));
    }
}
