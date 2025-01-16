/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyException;
import org.jruby.RubyString;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.ThreadContext;
import org.jruby.util.ByteList;
import org.jruby.util.StringSupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * An encoder that reads from the given source and outputs its representation
 * to another ByteList. The source string is fully checked for UTF-8 validity,
 * and throws a GeneratorError if any problem is found.
 */
class StringEncoder extends ByteListTranscoder {
    protected static final int CHAR_LENGTH_MASK = 7;
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

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
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

    private static final byte[] BACKSLASH_U2028 = "\\u2028".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BACKSLASH_U2029 = "\\u2029".getBytes(StandardCharsets.US_ASCII);

    protected final byte[] escapeTable;

    OutputStream out;

    // Escaped characters will reuse this array, to avoid new allocations
    // or appending them byte-by-byte
    protected final byte[] aux =
        new byte[] {/* First Unicode character */
                    '\\', 'u', 0, 0, 0, 0,
                    /* Second unicode character (for surrogate pairs) */
                    '\\', 'u', 0, 0, 0, 0,
                    /* "\X" characters */
                    '\\', 0};

    protected static final byte[] HEX =
            new byte[] {'0', '1', '2', '3', '4', '5', '6', '7',
                        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    StringEncoder(boolean scriptSafe) {
        this(scriptSafe ? SCRIPT_SAFE_ESCAPE_TABLE : ESCAPE_TABLE);
    }

    StringEncoder(byte[] escapeTable) {
        this.escapeTable = escapeTable;
    }

    // C: generate_json_string
    void generate(ThreadContext context, RubyString object, OutputStream buffer) throws IOException {
        object = ensureValidEncoding(context, object);

        ByteList byteList = object.getByteList();
        init(byteList);
        out = buffer;
        append('"');
        switch (object.scanForCodeRange()) {
            case StringSupport.CR_7BIT:
            case StringSupport.CR_VALID:
                encode(byteList);
                break;
            default:
                throw Utils.buildGeneratorError(context, object, "source sequence is illegal/malformed utf-8").toThrowable();
        }
        quoteStop(pos);
        append('"');
    }

    static RubyString ensureValidEncoding(ThreadContext context, RubyString str) {
        Encoding encoding = str.getEncoding();

        if (encoding == USASCIIEncoding.INSTANCE || encoding == UTF8Encoding.INSTANCE) {
            return str;
        }

        return tryWeirdEncodings(context, str, encoding);
    }

    private static RubyString tryWeirdEncodings(ThreadContext context, RubyString str, Encoding encoding) {
        Ruby runtime = context.runtime;

        RubyString utf8String;

        if (encoding == ASCIIEncoding.INSTANCE) {
            utf8String = str.strDup(runtime);
            utf8String.setEncoding(UTF8Encoding.INSTANCE);
            switch (utf8String.getCodeRange()) {
                case StringSupport.CR_7BIT:
                    return utf8String;
                case StringSupport.CR_VALID:
                    // For historical reason, we silently reinterpret binary strings as UTF-8 if it would work.
                    // TODO: Raise in 3.0.0
                    runtime.getWarnings().warn("JSON.generate: UTF-8 string passed as BINARY, this will raise an encoding error in json 3.0");
                    return utf8String;
            }
        }

        try {
            str = (RubyString) str.encode(context, runtime.getEncodingService().convertEncodingToRubyEncoding(UTF8Encoding.INSTANCE));
        } catch (RaiseException re) {
            RubyException exc = Utils.buildGeneratorError(context, str, re.getMessage());
            exc.setCause(re.getException());
            throw exc.toThrowable();
        }

        return str;
    }

    // C: convert_UTF8_to_JSON
    void encode(ByteList src) throws IOException {
        byte[] hexdig = HEX;
        byte[] scratch = aux;
        byte[] escapeTable = this.escapeTable;

        byte[] ptrBytes = src.unsafeBytes();
        int ptr = src.begin();
        int len = src.realSize();

        int beg = 0;
        int pos = 0;

        while (pos < len) {
            int ch = Byte.toUnsignedInt(ptrBytes[ptr + pos]);
            int ch_len = escapeTable[ch];
            /* JSON encoding */

            if (ch_len > 0) {
                switch (ch_len) {
                    case 9: {
                        beg = pos = flushPos(pos, beg, ptrBytes, ptr, 1);
                        escapeAscii(ch, scratch, hexdig);
                        break;
                    }
                    case 11: {
                        int b2 = Byte.toUnsignedInt(ptrBytes[ptr + pos + 1]);
                        if (b2 == 0x80) {
                            int b3 = Byte.toUnsignedInt(ptrBytes[ptr + pos + 2]);
                            if (b3 == 0xA8) {
                                beg = pos = flushPos(pos, beg, ptrBytes, ptr, 3);
                                append(BACKSLASH_U2028, 0, 6);
                                break;
                            } else if (b3 == 0xA9) {
                                beg = pos = flushPos(pos, beg, ptrBytes, ptr, 3);
                                append(BACKSLASH_U2029, 0, 6);
                                break;
                            }
                        }
                        ch_len = 3;
                        // fallthrough
                    }
                    default:
                        pos += ch_len;
                        break;
                }
            } else {
                pos++;
            }
        }

        if (beg < len) {
            append(ptrBytes, ptr + beg, len - beg);
        }
    }

    protected int flushPos(int pos, int beg, byte[] ptrBytes, int ptr, int size) throws IOException {
        if (pos > beg) { append(ptrBytes, ptr + beg, pos - beg); }
        return pos + size;
    }

    protected void escapeAscii(int ch, byte[] scratch, byte[] hexdig) throws IOException {
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

    @Override
    protected RaiseException invalidUtf8(ThreadContext context) {
        return Utils.newException(context, Utils.M_GENERATOR_ERROR, "source sequence is illegal/malformed utf-8");
    }
}
