package json.ext;

import java.nio.ByteBuffer;

/**
 * Scans the body of a JSON string for its closing quote, reporting whether the
 * body can be copied verbatim (the "plain" fast path) or must be handed to
 * {@link StringDecoder} for escape expansion and UTF-8/control validation.
 *
 * <p>The default implementation is SWAR (8 bytes per step). A vectorized
 * subclass ({@code VectorizedStringScanner}) is loaded reflectively when the
 * {@code jruby.json.useVectorizedParser} system property is set and the
 * {@code jdk.incubator.vector} module is available; otherwise this SWAR
 * implementation is used. Instances are stateless and therefore shared.
 */
class StringScanner {
    /** Set in the returned bits when the whole body is plain printable ASCII. */
    static final long PLAIN_BIT = 1L << 32;
    static final long ASCII_BIT = 1L << 33;

    /** Returned when no closing quote is found before {@code end}. */
    static final long NOT_FOUND = -1L;

    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long ONES      = 0x0101010101010101L;

    private static final long SPACES = 0x2020202020202020L;
    private static final long DOUBLE_QUOTES = 0x2222222222222222L;
    private static final long BACKSLASHES = 0x5C5C5C5C5C5C5C5CL;

    private static final String VECTORIZED_SCANNER_CLASS = "json.ext.VectorizedStringScanner";
    private static final String USE_VECTORIZED_PARSER_PROP = "jruby.json.useVectorizedParser";
    private static final String USE_VECTORIZED_PARSER_DEFAULT = "false";

    private static final StringScanner INSTANCE;

    static {
        StringScanner scanner = new StringScanner();
        String enable = System.getProperty(USE_VECTORIZED_PARSER_PROP, USE_VECTORIZED_PARSER_DEFAULT);
        if ("true".equalsIgnoreCase(enable) || "1".equals(enable)) {
            try {
                Class<?> vectorized = StringScanner.class.getClassLoader()
                    .loadClass(VECTORIZED_SCANNER_CLASS);
                scanner = (StringScanner) vectorized.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                scanner = new StringScanner();
            }
        }
        INSTANCE = scanner;
    }

    static StringScanner getInstance() {
        return INSTANCE;
    }

    /**
     * Scans {@code data[start..end)} for a closing quote. This method will
     * also report if there are any interesting bytes within the range 
     * {@link data[start..end)}. An interestin byte is defined as control
     * characters, backslashes or bytes with the high bit set.
     *
     * @param chunks a little-endian {@link ByteBuffer} over {@code data}
     *
     * @return packed result: the low 32 bits hold the index of the closing
     *         quote, or {@code -1} ({@link #NOT_FOUND}) when none is found
     *         before {@code end}; {@link #PLAIN_BIT} is set when the entire body
     *         is plain printable ASCII (no escape, no ASCII control character,
     *         and no non-ASCII byte) and can be copied verbatim;
     *         {@link #ASCII_BIT} is set when the body contains no non-ASCII byte
     *         (it may still hold escapes or control characters)
     */
    long scan(byte[] data, ByteBuffer chunks, int start, int end, boolean validateUtf8) {
        int p = start;
        boolean plain = true;
        boolean ascii = true;

        outer:
        while (true) {
            while (p + 8 <= end) {
                long x = chunks.getLong(p);
                // Pick the cheapest mask that still observes every transition we
                // still care about:
                //   plain             -> control, quote, backslash and high bytes
                //   non-plain, ASCII  -> quote, backslash and high bytes, so the
                //                        first non-ASCII byte still stops us and
                //                        clears ASCII_BIT
                //   non-plain, !ASCII -> quote and backslash only; multi-byte
                //                        UTF-8 is skipped eight bytes at a time
                //
                // When UTF-8 validation is disabled, non-ASCII bytes are copied
                // verbatim, so they stay on the plain fast path and the high-bit
                // term drops out of every mask: plain scans for control, quote
                // and backslash; non-plain scans for quote and backslash only.
                long m;
                if (validateUtf8) {
                    m = plain ? stringScanMask(x)
                             : ascii ? quoteBackslashHighMask(x)
                                     : quoteBackslashMask(x);
                } else {
                    m = plain ? controlQuoteBackslashMask(x)
                             : quoteBackslashMask(x);
                }
                if (m == 0) {
                    p += 8;
                } else {
                    p += Long.numberOfTrailingZeros(m) >>> 3;
                    break;
                }
            }
            // If we match on a byte above and/or tail handling for <8 remaining bytes.
            while (p < end) {
                int b = data[p] & 0xFF;
                if (b == '"') {
                    return (((long) p) | (plain ? PLAIN_BIT : 0L) | (ascii ? ASCII_BIT : 0L));
                }
                if (b == '\\') {
                    plain = false;
                    p += 2; // skip the backslash and the escaped byte
                    continue outer;
                }
                if (b < 0x20) {
                    plain = false;
                    p++;
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
                p++;
            }
            return NOT_FOUND;
        }
    }


    /**
     * Scans {@code data[start..end)} for the next backslash or control character.
     *
     * <p>The caller must guarantee the {@link data[start..end]} contains
     * no non-ASCII bytes that need to be decoded.</p>
     *
     * @return the index of the first backslash or control byte, or {@code end}
     *         when none is found.
     */
    int scanEscape(byte[] data, ByteBuffer chunks, int start, int end) {
        int p = start;
        while (p + 8 <= end) {
            long x = chunks.getLong(p);
            long m = backslashControlMask(x);
            if (m == 0) {
                p += 8;
            } else {
                return p + (Long.numberOfTrailingZeros(m) >>> 3);
            }
        }
        while (p < end) {
            int b = data[p] & 0xFF;
            if (b == '\\' || b < 0x20) {
                return p;
            }
            p++;
        }
        return end;
    }

    /**
     * Returns a mask whose high bit (0x80) is set in every lane of {@code x}
     * that needs scalar attention: an ASCII control character (&lt; 0x20), a
     * double quote, a backslash, or a non-ASCII byte (high bit set). Returns 0
     * when the whole 8-byte chunk is printable ASCII copyable verbatim.
     */
    private static long stringScanMask(long x) {
        long control = (x - SPACES) & ~x; // bytes < 0x20 (ASCII)
        long high    = x;                              // bit 0x80 set iff non-ASCII
        long q       = x ^ DOUBLE_QUOTES;
        long quote   = (q - ONES) & ~q;
        long s       = x ^ BACKSLASHES;
        long bslash  = (s - ONES) & ~s;
        return (control | high | quote | bslash) & HIGH_BITS;
    }

    /**
     * Like {@link #quoteBackslashMask} but for the ASCII-only decode fast path:
     * flags backslashes and ASCII control characters (&lt; 0x20). The control
     * test relies on every byte being ASCII (&lt; 0x80), which the caller
     * guarantees, so no high-bit cleanup is needed.
     */
    private static long backslashControlMask(long x) {
        long control = (x - SPACES) & ~x; // bytes < 0x20 (ASCII)
        long s       = x ^ BACKSLASHES;
        long bslash  = (s - ONES) & ~s;
        return (control | bslash) & HIGH_BITS;
    }

    /**
     * Like {@link #stringScanMask} but omits the non-ASCII (high-bit) term:
     * flags double quotes, backslashes and ASCII control characters (&lt; 0x20)
     * only. Used as the starting mask when UTF-8 validation is disabled, where
     * non-ASCII bytes are copied verbatim and so stay on the plain fast path.
     */
    private static long controlQuoteBackslashMask(long x) {
        long control = (x - SPACES) & ~x; // bytes < 0x20 (ASCII)
        long q       = x ^ DOUBLE_QUOTES;
        long quote   = (q - ONES) & ~q;
        long s       = x ^ BACKSLASHES;
        long bslash  = (s - ONES) & ~s;
        return (control | quote | bslash) & HIGH_BITS;
    }

    /**
     * Like {@link #stringScanMask} but only flags double quotes and backslashes.
     * Used once a string is known to require the decoder <em>and</em> to already
     * contain non-ASCII bytes, so the remaining scan for the closing quote skips
     * clean chunks (including multi-byte UTF-8) eight bytes at a time.
     */
    private static long quoteBackslashMask(long x) {
        long q      = x ^ DOUBLE_QUOTES;
        long quote  = (q - ONES) & ~q;
        long s      = x ^ BACKSLASHES;
        long bslash = (s - ONES) & ~s;
        return (quote | bslash) & HIGH_BITS;
    }

    /**
     * Like {@link #quoteBackslashMask} but also flags non-ASCII bytes. Used once
     * a string is known to require the decoder but is still ASCII-only, so the
     * scan keeps skipping printable runs eight bytes at a time yet still stops on
     * the first high byte and clears {@link #ASCII_BIT}.
     */
    private static long quoteBackslashHighMask(long x) {
        long high   = x;                 // bit 0x80 set iff non-ASCII
        long q      = x ^ DOUBLE_QUOTES;
        long quote  = (q - ONES) & ~q;
        long s      = x ^ BACKSLASHES;
        long bslash = (s - ONES) & ~s;
        return (high | quote | bslash) & HIGH_BITS;
    }
}
