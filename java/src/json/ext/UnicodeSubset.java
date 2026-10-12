package json.ext;

import org.jruby.RubySymbol;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

/** RFC 9839 code point classes and strict UTF-8 decoding. */
final class UnicodeSubset {
    static final int ILL_FORMED = 1;
    static final int SURROGATE = 2;
    static final int LEGACY_C0 = 4;
    static final int DEL = 8;
    static final int C1 = 16;
    static final int NONCHAR_FDD0 = 32;
    static final int NONCHAR_BMP_END = 64;
    static final int NONCHAR_SUPP = 128;
    static final int SCALARS = ILL_FORMED | SURROGATE;
    static final int XML_CHARACTERS = SCALARS | LEGACY_C0 | NONCHAR_BMP_END;
    static final int ASSIGNABLES = 255;

    private static final int[] ASCII_CLASSES = new int[128];
    static {
        for (int cp = 0; cp < 0x20; cp++) {
            if (cp != 9 && cp != 10 && cp != 13) ASCII_CLASSES[cp] = LEGACY_C0;
        }
        ASCII_CLASSES[0x7f] = DEL;
    }

    static int mask(ThreadContext context, IRubyObject value) {
        if (value == null || value.isNil()) return 0;
        if (value instanceof RubySymbol) {
            switch (((RubySymbol)value).idString()) {
                case "scalars": return SCALARS;
                case "xml_characters": return XML_CHARACTERS;
                case "assignables": return ASSIGNABLES;
            }
        }
        throw context.runtime.newArgumentError("unicode_subset must be nil, :scalars, :xml_characters, or :assignables");
    }

    static boolean replace(ThreadContext context, IRubyObject value) {
        if (value == null) return false;
        if (value instanceof RubySymbol) {
            switch (((RubySymbol)value).idString()) {
                case "raise": return false;
                case "replace": return true;
            }
        }
        throw context.runtime.newArgumentError("on_invalid_char must be :raise or :replace");
    }

    static String name(int mask) {
        switch (mask) {
            case SCALARS: return "scalars";
            case XML_CHARACTERS: return "xml_characters";
            case ASSIGNABLES: return "assignables";
            default: return null;
        }
    }

    static int classify(int cp) {
        if (cp < 0 || cp > 0x10ffff) return ILL_FORMED;
        if (cp < 0x80) return ASCII_CLASSES[cp];
        if (cp <= 0x9f) return C1;
        if (cp >= 0xd800 && cp <= 0xdfff) return SURROGATE;
        if (cp >= 0xfdd0 && cp <= 0xfdef) return NONCHAR_FDD0;
        if ((cp & 0xffff) >= 0xfffe) return cp < 0x10000 ? NONCHAR_BMP_END : NONCHAR_SUPP;
        return 0;
    }

    // The high word is the code point (-1 if malformed); the low word is its
    // byte length, or the maximal subpart length for an ill-formed sequence.
    static long decode(byte[] bytes, int start, int end) {
        int first = bytes[start] & 0xff;
        if (first < 0x80) return ((long)first << 32) | 1;
        int length;
        int cp;
        int min = 0x80;
        int max = 0xbf;
        if (first >= 0xc2 && first <= 0xdf) {
            length = 2;
            cp = first & 0x1f;
        } else if (first >= 0xe0 && first <= 0xef) {
            length = 3;
            cp = first & 0xf;
            if (first == 0xe0) min = 0xa0;
            if (first == 0xed) max = 0x9f;
        } else if (first >= 0xf0 && first <= 0xf4) {
            length = 4;
            cp = first & 7;
            if (first == 0xf0) min = 0x90;
            if (first == 0xf4) max = 0x8f;
        } else {
            return (-1L << 32) | 1;
        }
        for (int i = 1; i < length; i++) {
            if (start + i == end) return (-1L << 32) | i;
            int b = bytes[start + i] & 0xff;
            if (b < min || b > max) return (-1L << 32) | i;
            cp = (cp << 6) | (b & 0x3f);
            min = 0x80;
            max = 0xbf;
        }
        return ((long)cp << 32) | length;
    }

    static String message(int cp, int mask) {
        String character = cp < 0 ? "invalid UTF-8 sequence" : String.format("U+%04X", cp);
        return character + " is not allowed by unicode_subset: :" + name(mask);
    }

    static void append(ByteList out, int cp) {
        if (cp < 0x80) {
            out.append(cp);
        } else if (cp < 0x800) {
            out.append(0xc0 | (cp >>> 6));
            out.append(0x80 | (cp & 0x3f));
        } else if (cp < 0x10000) {
            out.append(0xe0 | (cp >>> 12));
            out.append(0x80 | ((cp >>> 6) & 0x3f));
            out.append(0x80 | (cp & 0x3f));
        } else {
            out.append(0xf0 | (cp >>> 18));
            out.append(0x80 | ((cp >>> 12) & 0x3f));
            out.append(0x80 | ((cp >>> 6) & 0x3f));
            out.append(0x80 | (cp & 0x3f));
        }
    }
}
