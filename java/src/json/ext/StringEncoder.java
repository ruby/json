/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.ThreadContext;
import org.jruby.util.ByteList;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An encoder that reads from the given source and outputs its representation
 * to another ByteList. The source string is fully checked for UTF-8 validity,
 * and throws a GeneratorError if any problem is found.
 */
final class StringEncoder extends ByteListTranscoder {
    private static final int CHAR_LENGTH_MASK = 7;
    private static final byte[] BACKSLASH_DOUBLEQUOTE = {'\\', '"'};
    private static final byte[] BACKSLASH_BACKSLASH = {'\\', '\\'};
    private static final byte[] BACKSLASH_FORWARDSLASH = {'\\', '/'};
    private static final byte[] BACKSLASH_B = {'\\', 'b'};
    private static final byte[] BACKSLASH_F = {'\\', 'f'};
    private static final byte[] BACKSLASH_N = {'\\', 'n'};
    private static final byte[] BACKSLASH_R = {'\\', 'r'};
    private static final byte[] BACKSLASH_T = {'\\', 't'};
    
    static final byte[] ESCAPE_TABLE = {
            // ASCII Control Characters
            9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
            9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
            // ASCII Characters
            0, 0, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // '"'
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0, 0, // '\\'
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    };

    static final byte[] ASCII_ONLY_ESCAPE_TABLE = {
            // ASCII Control Characters
            9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
            9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
            // ASCII Characters
            0, 0, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // '"'
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0, 0, // '\\'
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            // Continuation byte
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            // First byte of a  2-byte code point
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            // First byte of a 3-byte code point
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
            //First byte of a 4+ byte code point
            4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 9, 9,
    };

    static final byte[] SCRIPT_SAFE_ESCAPE_TABLE = {
            // ASCII Control Characters
            9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
            9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
            // ASCII Characters
            0, 0, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9, // '"' and '/'
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0, 0, // '\\'
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            // Continuation byte
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            // First byte of a 2-byte code point
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            // First byte of a 3-byte code point
            3, 3, 11, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, // 0xE2 is the start of \u2028 and \u2029
            //First byte of a 4+ byte code point
            4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 9, 9,
    };

    private final boolean asciiOnly, scriptSafe;

    OutputStream out;

    // Escaped characters will reuse this array, to avoid new allocations
    // or appending them byte-by-byte
    private final byte[] aux =
        new byte[] {/* First Unicode character */
                    '\\', 'u', 0, 0, 0, 0,
                    /* Second unicode character (for surrogate pairs) */
                    '\\', 'u', 0, 0, 0, 0,
                    /* "\X" characters */
                    '\\', 0};
    // offsets on the array above
    private static final int ESCAPE_UNI1_OFFSET = 0;
    private static final int ESCAPE_UNI2_OFFSET = ESCAPE_UNI1_OFFSET + 6;
    private static final int ESCAPE_CHAR_OFFSET = ESCAPE_UNI2_OFFSET + 6;
    /** Array used for code point decomposition in surrogates */
    private final char[] utf16 = new char[2];

    private static final byte[] HEX =
            new byte[] {'0', '1', '2', '3', '4', '5', '6', '7',
                        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    StringEncoder(boolean asciiOnly, boolean scriptSafe) {
        this.asciiOnly = asciiOnly;
        this.scriptSafe = scriptSafe;
    }

    void encode(ThreadContext context, ByteList src, OutputStream out) throws IOException {
        while (hasNext()) {
            handleChar(readUtf8Char(context));
        }
    }

    // C: convert_UTF8_to_ASCII_only_JSON
    void encodeASCII(ThreadContext context, ByteList src, OutputStream out) throws IOException {
        byte[] escape_table = scriptSafe ? SCRIPT_SAFE_ESCAPE_TABLE : ASCII_ONLY_ESCAPE_TABLE;
        byte[] hexdig = HEX;
        byte[] scratch = aux;

        byte[] ptrBytes = src.unsafeBytes();
        int ptr = src.begin();
        int len = src.realSize();

        int beg = 0;
        int pos = 0;

        while (pos < len) {
            byte ch = ptrBytes[ptr + pos];
            int ch_len = escape_table[ch];

            if (ch_len != 0) {
                switch (ch_len) {
                    case 9: {
                        if (pos > beg) { append(ptrBytes, ptr + beg, pos - beg); } pos += 1; beg = pos; // FLUSH_POS
                        switch (ch) {
                            case '"':  appendEscape(BACKSLASH_DOUBLEQUOTE); break;
                            case '\\': appendEscape(BACKSLASH_BACKSLASH); break;
                            case '/':  appendEscape(BACKSLASH_FORWARDSLASH); break;
                            case '\b': appendEscape(BACKSLASH_B); break;
                            case '\f': appendEscape(BACKSLASH_F); break;
                            case '\n': appendEscape(BACKSLASH_N); break;
                            case '\r': appendEscape(BACKSLASH_R); break;
                            case '\t': appendEscape(BACKSLASH_T); break;
                            default: {
                                scratch[2] = '0';
                                scratch[3] = '0';
                                scratch[4] = hexdig[(ch >> 4) & 0xf];
                                scratch[5] = hexdig[ch & 0xf];
                                append(scratch, 0, 6);
                                break;
                            }
                        }
                        break;
                    }
                    default: {
                        int wchar = 0;
                        ch_len = ch_len & CHAR_LENGTH_MASK;

                        switch(ch_len) {
                            case 2:
                                wchar = ptrBytes[ptr + pos] & 0x1F;
                                break;
                            case 3:
                                wchar = ptrBytes[ptr + pos] & 0x0F;
                                break;
                            case 4:
                                wchar = ptrBytes[ptr + pos] & CHAR_LENGTH_MASK;
                                break;
                        }

                        for (short i = 1; i < ch_len; i++) {
                            wchar = (wchar << 6) | (ptrBytes[ptr + pos +i] & 0x3F);
                        }

                        if (pos > beg) { append(ptrBytes, ptr + beg, pos - beg); } pos += ch_len; beg = pos; // FLUSH_POS

                        if (wchar <= 0xFFFF) {
                            scratch[2] = hexdig[wchar >> 12];
                            scratch[3] = hexdig[(wchar >> 8) & 0xf];
                            scratch[4] = hexdig[(wchar >> 4) & 0xf];
                            scratch[5] = hexdig[wchar & 0xf];
                            append(scratch, 0, 6);
                        } else {
                            int hi, lo;
                            wchar -= 0x10000;
                            hi = 0xD800 + (wchar >> 10);
                            lo = 0xDC00 + (wchar & 0x3FF);

                            scratch[2] = hexdig[hi >> 12];
                            scratch[3] = hexdig[(hi >> 8) & 0xf];
                            scratch[4] = hexdig[(hi >> 4) & 0xf];
                            scratch[5] = hexdig[hi & 0xf];

                            scratch[8] = hexdig[lo >> 12];
                            scratch[9] = hexdig[(lo >> 8) & 0xf];
                            scratch[10] = hexdig[(lo >> 4) & 0xf];
                            scratch[11] = hexdig[lo & 0xf];

                            append(scratch, 0, 12);
                        }

                        break;
                    }
                }
            } else {
                pos++;
            }
        }

        if (beg < len) {
            append(ptrBytes, ptr + beg, len - beg);
        }
    }

    private void appendEscape(byte[] escape) throws IOException {
        append(escape, 0, 2);
    }

    protected void append(int b) throws IOException {
        out.write(b);
    }

    protected void append(byte[] origin, int start, int length) throws IOException {
        out.write(origin, start, length);
    }

    private void handleChar(int c) throws IOException {
        switch (c) {
        case '"':
        case '\\':
            escapeChar((char)c);
            break;
        case '\n':
            escapeChar('n');
            break;
        case '\r':
            escapeChar('r');
            break;
        case '\t':
            escapeChar('t');
            break;
        case '\f':
            escapeChar('f');
            break;
        case '\b':
            escapeChar('b');
            break;
        case '/':
            if(scriptSafe) {
                escapeChar((char)c);
                break;
            }
        case 0x2028:
        case 0x2029:
            if (scriptSafe) {
                quoteStop(charStart);
                escapeUtf8Char(c);
                break;
            }
        default:
            if (c >= 0x20 && c <= 0x7f ||
                    (c >= 0x80 && !asciiOnly)) {
                quoteStart();
            } else {
                quoteStop(charStart);
                escapeUtf8Char(c);
            }
        }
    }

    private void escapeChar(char c) throws IOException {
        quoteStop(charStart);
        aux[ESCAPE_CHAR_OFFSET + 1] = (byte)c;
        append(aux, ESCAPE_CHAR_OFFSET, 2);
    }

    private void escapeUtf8Char(int codePoint) throws IOException {
        int numChars = Character.toChars(codePoint, utf16, 0);
        escapeCodeUnit(utf16[0], ESCAPE_UNI1_OFFSET + 2);
        if (numChars > 1) escapeCodeUnit(utf16[1], ESCAPE_UNI2_OFFSET + 2);
        append(aux, ESCAPE_UNI1_OFFSET, 6 * numChars);
    }

    private void escapeCodeUnit(char c, int auxOffset) {
        for (int i = 0; i < 4; i++) {
            aux[auxOffset + i] = HEX[(c >>> (12 - 4 * i)) & 0xf];
        }
    }

    @Override
    protected RaiseException invalidUtf8(ThreadContext context) {
        return Utils.newException(context, Utils.M_GENERATOR_ERROR, "source sequence is illegal/malformed utf-8");
    }
}
