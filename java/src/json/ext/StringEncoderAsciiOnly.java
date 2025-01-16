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
final class StringEncoderAsciiOnly extends StringEncoder {
    StringEncoderAsciiOnly(boolean scriptSafe) {
        super(scriptSafe ? SCRIPT_SAFE_ESCAPE_TABLE : ASCII_ONLY_ESCAPE_TABLE);
    }

    // C: convert_UTF8_to_ASCII_only_JSON
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

            if (ch_len != 0) {
                switch (ch_len) {
                    case 9: {
                        beg = pos = flushPos(pos, beg, ptrBytes, ptr, 1);
                        escapeAscii(ch, scratch, hexdig);
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

                        beg = pos = flushPos(pos, beg, ptrBytes, ptr, ch_len);

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
}
