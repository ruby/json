/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jruby.runtime.ThreadContext;
import org.jruby.util.ByteList;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A decoder that reads a JSON-encoded string from the given sources and
 * returns its decoded form on a new ByteList. Escaped Unicode characters
 * are encoded as UTF-8.
 */
final class StringDecoder extends ByteListTranscoder {
    /**
     * A decoding error, with the offset in the source ByteList (relative to
     * its begin) that the parser reports as the error's line and column.
     */
    static final class DecodeError extends RuntimeException {
        final ByteList message;
        final int offset;

        DecodeError(ByteList message, int offset) {
            super(null, null, false, false);
            this.message = message;
            this.offset = offset;
        }
    }

    /**
     * Stores the offset of the backslash that starts the escape sequence
     * being read, or -1 when not in one.
     */
    private int escapeStart = -1;

    /**
     * Stores the offset where the text after the last escape sequence (or the
     * string) starts. The C parser reports surrogate errors there.
     */
    private int segmentStart;
    private boolean allowControlCharacters = false;
    private boolean allowInvalidEscape = false;

    private final StringScanner scanner = StringScanner.getInstance();

    private ByteList out;

    // Array used for writing multibyte characters into the buffer at once
    private final byte[] aux = new byte[4];

    public StringDecoder(boolean allowControlCharacters, boolean allowInvalidEscape) {
        this.allowControlCharacters = allowControlCharacters;
        this.allowInvalidEscape = allowInvalidEscape;
    }

    ByteList decode(ThreadContext context, ByteList src, int start, int end) {
        try {
            init(src, start, end);
            this.out = new ByteList(end - start);
            escapeStart = -1;
            segmentStart = start;
            while (hasNext()) {
                handleChar(context, readUtf8Char(context));
            }
            quoteStop(pos);
            return out;
        } catch (IOException e) {
            throw context.runtime.newIOErrorFromException(e);
        }
    }

    // Decodes strings with no UTF-8 validation. It is assumed the string is either ASCII-only
    // or UTF-8 avlidation has been disabled.
    ByteList decodeNoValidate(ThreadContext context, ByteList src, int start, int end, ByteBuffer chunks) {
        try {
            init(src, start, end);
            this.out = new ByteList(end - start);
            escapeStart = -1;
            segmentStart = start;
            final byte[] data = src.unsafeBytes();
            final int base = src.begin();
            final int absEnd = base + srcEnd;
            int cursor = base + pos;
            int runStart = cursor;
            while (cursor < absEnd) {
                cursor = scanner.scanEscape(data, chunks, cursor, absEnd);
                if (cursor >= absEnd) {
                    break;
                }
                int b = Byte.toUnsignedInt(data[cursor]);
                if (b == '\\') {
                    if (cursor > runStart) {
                        append(data, runStart, cursor - runStart);
                    }
                    charStart = cursor - base;
                    pos = charStart + 1;
                    handleEscapeSequence(context);
                    cursor = base + pos;
                    runStart = cursor;
                } else {
                    if (!allowControlCharacters) {
                        charStart = cursor - base;
                        throw invalidControlChar(context);
                    }
                    cursor++;
                }
            }
            if (absEnd > runStart) {
                append(data, runStart, absEnd - runStart);
            }
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
        escapeStart = charStart;
        ensureMin(context, 1);
        int character = super.readUtf8Char(context);
        switch (character) {
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
                throw invalidSurrogate();
            } else {
                writeUtf8Char(cp);
            }
            break;
        default:
            if (character < 0x20 && !allowControlCharacters) {
                throw invalidControlChar(context, escapeStart);
            }
            if (allowInvalidEscape) {
              writeUtf8Char(character);
            } else {
              throw invalidEscape(context);
            }
        }
        escapeStart = -1;
        segmentStart = pos;
    }

    protected void append(int b) throws IOException {
        out.append(b);
    }

    protected void append(byte[] origin, int start, int length) throws IOException {
        out.append(origin, start, length);
    }

    private void handleLowSurrogate(ThreadContext context, char highSurrogate) throws IOException {
        if (pos + 1 > srcEnd) throw invalidSurrogate();
        int lowSurrogate = readUtf8Char(context);

        if (lowSurrogate == '\\') {
            if (pos + 5 > srcEnd) throw invalidSurrogate();
            if (readUtf8Char(context) != 'u') throw invalidSurrogate();
            lowSurrogate = readHex(context);
        }

        if (Character.isLowSurrogate((char)lowSurrogate)) {
            writeUtf8Char(Character.toCodePoint(highSurrogate,
                                                (char)lowSurrogate));
        } else {
            throw invalidSurrogate();
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
                throw invalidUnicodeEscape(context, numberStart);
            }
            result = result * 16 + digitValue;
        }
        return result;
    }

    // The offsets follow the C parser: errors in an escape sequence are
    // reported at its backslash, surrogate errors at the text before it, and
    // raw control characters where they are. The message prefixes are unchanged.

    @Override
    protected DecodeError invalidUtf8(ThreadContext context) {
        int start = escapeStart != -1 ? escapeStart : charStart;
        return error("partial character in source, but hit end near ", start, start);
    }

    protected DecodeError invalidControlChar(ThreadContext context) {
        return invalidControlChar(context, charStart);
    }

    private DecodeError invalidControlChar(ThreadContext context, int offset) {
        return error("invalid ASCII control character in string: ", charStart, offset);
    }

    private DecodeError invalidSurrogate() {
        return error("partial character in source, but hit end near ", segmentStart, segmentStart);
    }

    protected DecodeError invalidEscape(ThreadContext context) {
        return error("invalid escape character in string: ", charStart, escapeStart);
    }

    protected DecodeError invalidUnicodeEscape(ThreadContext context, int numberStart) {
        // Point at the backslash-u that introduced the escape.
        int start = Math.max(numberStart - 2, 0);
        return error("incomplete unicode character escape sequence at ", start, start);
    }

    private DecodeError error(String prefix, int snippetStart, int offset) {
        ByteList message = new ByteList(ByteList.plain(prefix));
        message.append(src, snippetStart, srcEnd - snippetStart);
        return new DecodeError(message, offset);
    }
}
