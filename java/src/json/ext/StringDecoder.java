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

/**
 * A decoder that reads a JSON-encoded string from the given sources and
 * returns its decoded form on a new ByteList. Escaped Unicode characters
 * are encoded as UTF-8.
 */
final class StringDecoder extends ByteListTranscoder {
    /**
     * Stores the offset of the high surrogate when reading a surrogate pair,
     * or -1 when not.
     */
    private int surrogatePairStart = -1;
    private boolean allowControlCharacters = false;

    private ByteList out;

    // Array used for writing multibyte characters into the buffer at once
    private final byte[] aux = new byte[4];

    public StringDecoder(boolean allowControlCharacters) {
        this.allowControlCharacters = allowControlCharacters;
    }

    ByteList decode(ThreadContext context, ByteList src, int start, int end) {
        try {
            init(src, start, end);
            this.out = new ByteList(end - start);
            while (hasNext()) {
                handleChar(context, readUtf8Char(context));
            }
            quoteStop(pos);
            return out;
        } catch (IOException e) {
            throw context.runtime.newIOErrorFromException(e);
        }
    }

    @Override
    protected int readUtf8Char(ThreadContext context) {
        int c = super.readUtf8Char(context);
        if (c < 0x20 && !allowControlCharacters) {
            throw invalidControlChar(context);
        }
        return c;
    }

    private void handleChar(ThreadContext context, int c) throws IOException {
        if (c == '\\') {
            quoteStop(charStart);
            handleEscapeSequence(context);
        } else {
            quoteStart();
        }
    }

    private void handleEscapeSequence(ThreadContext context) throws IOException {
        ensureMin(context, 1);
        switch (readUtf8Char(context)) {
        case 'b':
            append('\b');
            break;
        case 'f':
            append('\f');
            break;
        case 'n':
            append('\n');
            break;
        case 'r':
            append('\r');
            break;
        case 't':
            append('\t');
            break;
        case '/':
            append('/');
            break;
        case '"':
            append('"');
            break;
        case '\\':
            append('\\');
            break;
        case 'u':
            ensureMin(context, 4);
            int cp = readHex(context);
            if (Character.isHighSurrogate((char)cp)) {
                handleLowSurrogate(context, (char)cp);
            } else if (Character.isLowSurrogate((char)cp)) {
                // low surrogate with no high surrogate
                throw invalidUtf8(context);
            } else {
                writeUtf8Char(cp);
            }
            break;
        default:
            throw invalidEscape(context);
        }
    }

    protected void append(int b) throws IOException {
        out.append(b);
    }

    protected void append(byte[] origin, int start, int length) throws IOException {
        out.append(origin, start, length);
    }

    private void handleLowSurrogate(ThreadContext context, char highSurrogate) throws IOException {
        surrogatePairStart = charStart;
        ensureMin(context, 1);
        int lowSurrogate = readUtf8Char(context);

        if (lowSurrogate == '\\') {
            ensureMin(context, 5);
            if (readUtf8Char(context) != 'u') throw invalidUtf8(context);
            lowSurrogate = readHex(context);
        }

        if (Character.isLowSurrogate((char)lowSurrogate)) {
            writeUtf8Char(Character.toCodePoint(highSurrogate,
                                                (char)lowSurrogate));
            surrogatePairStart = -1;
        } else {
            throw invalidUtf8(context);
        }
    }

    private void writeUtf8Char(int codePoint) throws IOException {
        if (codePoint < 0x80) {
            append(codePoint);
        } else if (codePoint < 0x800) {
            aux[0] = (byte)(0xc0 | (codePoint >>> 6));
            aux[1] = tailByte(codePoint & 0x3f);
            append(aux, 0, 2);
        } else if (codePoint < 0x10000) {
            aux[0] = (byte)(0xe0 | (codePoint >>> 12));
            aux[1] = tailByte(codePoint >>> 6);
            aux[2] = tailByte(codePoint);
            append(aux, 0, 3);
        } else {
            aux[0] = (byte)(0xf0 | codePoint >>> 18);
            aux[1] = tailByte(codePoint >>> 12);
            aux[2] = tailByte(codePoint >>> 6);
            aux[3] = tailByte(codePoint);
            append(aux, 0, 4);
        }
    }

    private byte tailByte(int value) {
        return (byte)(0x80 | (value & 0x3f));
    }

    /**
     * Reads a 4-digit unsigned hexadecimal number from the source.
     */
    private int readHex(ThreadContext context) {
        int numberStart = pos;
        int result = 0;
        int length = 4;
        for (int i = 0; i < length; i++) {
            int digit = readUtf8Char(context);
            int digitValue;
            if (digit >= '0' && digit <= '9') {
                digitValue = digit - '0';
            } else if (digit >= 'a' && digit <= 'f') {
                digitValue = 10 + digit - 'a';
            } else if (digit >= 'A' && digit <= 'F') {
                digitValue = 10 + digit - 'A';
            } else {
                throw new NumberFormatException("Invalid base 16 number "
                        + src.subSequence(numberStart, numberStart + length));
            }
            result = result * 16 + digitValue;
        }
        return result;
    }

    @Override
    protected RaiseException invalidUtf8(ThreadContext context) {
        ByteList message = new ByteList(
                ByteList.plain("partial character in source, " +
                               "but hit end near "));
        int start = surrogatePairStart != -1 ? surrogatePairStart : charStart;
        message.append(src, start, srcEnd - start);
        return Utils.newException(context, Utils.M_PARSER_ERROR,
                                  context.runtime.newString(message));
    }

    protected RaiseException invalidControlChar(ThreadContext context) {
        ByteList message = new ByteList(
                ByteList.plain("invalid ASCII control character in string: "));
        message.append(src, charStart, srcEnd - charStart);
        return Utils.newException(context, Utils.M_PARSER_ERROR,
                                  context.runtime.newString(message));
    }

    protected RaiseException invalidEscape(ThreadContext context) {
        ByteList message = new ByteList(
                ByteList.plain("invalid escape character in string: "));
        message.append(src, charStart, srcEnd - charStart);
        return Utils.newException(context, Utils.M_PARSER_ERROR,
                                  context.runtime.newString(message));
    }
}
