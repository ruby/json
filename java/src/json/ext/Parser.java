package json.ext;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyObject;
import org.jruby.RubyProc;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.ConvertBytes;
import org.jruby.util.ConvertDouble.DoubleConverter;
import org.jruby.util.ConvertBytes;
import org.jruby.util.ConvertDouble.DoubleConverter;
import org.jruby.util.ConvertBytes;
import org.jruby.util.ConvertDouble.DoubleConverter;
import org.jruby.util.ConvertBytes;
import org.jruby.util.ConvertDouble.DoubleConverter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.BiFunction;

import static json.ext.Ryu.ryuS2dFromParts;
import static org.jruby.util.ConvertDouble.DoubleConverter;

/**
 * This is a port of the parser from the C extension.
 */
public class Parser extends RubyObject {
    private final RuntimeInfo info;
    private int maxNesting;
    private boolean allowNaN;
    private boolean allowTrailingComma;
    private boolean allowComments;
    private boolean deprecateComments;
    private boolean allowControlCharacters;
    private boolean allowInvalidEscape;
    private boolean allowDuplicateKey;
    private boolean deprecateDuplicateKey;
    private boolean symbolizeNames;
    private boolean freeze;
    private RubyProc onLoadProc;
    private RubyClass decimalClass;
    BiFunction<ThreadContext, ByteList, IRubyObject> decimalFactory;

    private static final int DEFAULT_MAX_NESTING = 100;

    // Maximum number of deprecation warnings emitted per parse, to avoid
    // flooding output on pathological inputs.
    private static final int MAX_DEPRECATIONS = 5;

    // constant names in the JSON module containing those values
    private static final String CONST_NAN = "NaN";
    private static final String CONST_INFINITY = "Infinity";
    private static final String CONST_MINUS_INFINITY = "MinusInfinity";

    private static final String VALIDATE_UTF8_STRINGS = "jruby.json.validateUTF8Strings";
    private static final String VALIDATE_UTF8_STRINGS_DEFAULT = "true";
    private static final boolean validateUTF8Strings;

    static {
        String enable = System.getProperty(VALIDATE_UTF8_STRINGS, VALIDATE_UTF8_STRINGS_DEFAULT);
        validateUTF8Strings = "true".equalsIgnoreCase(enable) || "1".equals(enable);
    }

    static final ObjectAllocator ALLOCATOR = Parser::new;

    public Parser(Ruby runtime, RubyClass metaClass) {
        super(runtime, metaClass);
        info = RuntimeInfo.forRuntime(runtime);
    }

    @JRubyMethod(name = "new", meta = true)
    public static IRubyObject newInstance(IRubyObject clazz, IRubyObject arg0, Block block) {
        Parser config = (Parser)((RubyClass)clazz).allocate();
        config.callInit(arg0, block);
        return config;
    }

    @JRubyMethod(name = "new", meta = true)
    public static IRubyObject newInstance(IRubyObject clazz, IRubyObject arg0, IRubyObject arg1, Block block) {
        Parser config = (Parser)((RubyClass)clazz).allocate();
        config.callInit(arg0, arg1, block);
        return config;
    }

    @JRubyMethod(visibility = Visibility.PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject options) {
        checkFrozen();
        Ruby runtime = context.runtime;

        OptionsReader opts   = new OptionsReader(context, options);
        this.maxNesting      = opts.getInt("max_nesting", DEFAULT_MAX_NESTING);
        this.allowNaN        = opts.getBool("allow_nan", false);
        if (opts.hasKey("allow_comments")) {
            this.allowComments = opts.getBool("allow_comments", false);
            this.deprecateComments = false;
        } else {
            this.allowComments = true;
            this.deprecateComments = true;
        }

        this.allowControlCharacters = opts.getBool("allow_control_characters", false);
        this.allowInvalidEscape = opts.getBool("allow_invalid_escape", false);
        this.allowTrailingComma = opts.getBool("allow_trailing_comma", false);
        this.symbolizeNames  = opts.getBool("symbolize_names", false);
        if (opts.hasKey("allow_duplicate_key")) {
            this.allowDuplicateKey = opts.getBool("allow_duplicate_key", false);
            this.deprecateDuplicateKey = false;
        } else {
            this.allowDuplicateKey = false;
            this.deprecateDuplicateKey = true;
        }

        this.freeze          = opts.getBool("freeze", false);
        this.onLoadProc      = opts.getProc("on_load");

        this.decimalClass    = opts.getClass("decimal_class", null);

        if (decimalClass == null) {
            this.decimalFactory = this::createFloat;
        } else if (decimalClass == runtime.getClass("BigDecimal")) {
            this.decimalFactory = this::createBigDecimal;
        } else {
            this.decimalFactory = this::createCustomDecimal;
        }

        return this;
    }

    public IRubyObject onLoad(ThreadContext context, IRubyObject object) {
        if (onLoadProc == null) {
            return object;
        } else {
            return onLoadProc.call(context, object);
        }
    }

    /**
     * Checks the given string's encoding. If a non-UTF-8 encoding is detected,
     * a converted copy is returned.
     * Returns the source string if no conversion is needed.
     */
    private RubyString convertEncoding(ThreadContext context, RubyString source) {
      Encoding encoding = source.getEncoding();
      if (encoding == ASCIIEncoding.INSTANCE) {
          source = (RubyString) source.dup();
          source.setEncoding(UTF8Encoding.INSTANCE);
          source.clearCodeRange();
      } else if (encoding != UTF8Encoding.INSTANCE) {
          source = (RubyString) source.encode(context, context.runtime.getEncodingService().convertEncodingToRubyEncoding(UTF8Encoding.INSTANCE));
      }
      return source;
    }

    /**
     * <code>Parser#parse()</code>
     *
     * <p>Parses the current JSON text <code>source</code> and returns the
     * complete data structure as a result.
     */
    @JRubyMethod
    public IRubyObject parse(ThreadContext context, IRubyObject source) {
        return new ParserSession(this, convertEncoding(context, source.convertToString()), info).parse(context);
    }

    private RubyFloat createFloat(final ThreadContext context, final ByteList num) {
        return RubyFloat.newFloat(context.runtime, new DoubleConverter().parse(num, true, true));
    }

    private IRubyObject createBigDecimal(final ThreadContext context, final ByteList num) {
        final Ruby runtime = context.runtime;
        return runtime.getKernel().callMethod(context, "BigDecimal", runtime.newString(num));
    }

    private IRubyObject createCustomDecimal(final ThreadContext context, final ByteList num) {
        return decimalClass.newInstance(context, context.runtime.newString(num), Block.NULL_BLOCK);
    }

    /**
     * A single parsing session over one source string.
     *
     * <p>Once a ParserSession is instantiated, the source string should not
     * change until the parsing is complete. The ParserSession object assumes
     * the source {@link RubyString} is still associated to its original
     * {@link ByteList}, which in turn must still be bound to the same
     * <code>byte[]</code> value (and on the same offset).
     */
    private static final class ParserSession {
        private static final int INITIAL_FRAME_CAPACITY = 32;
        private static final int INITIAL_VALUE_CAPACITY = 64;

        // Integers with fewer than this many digits are built directly from a
        // long accumulated during the digit scan; longer ones fall back to
        // ConvertBytes (Bignum). 17 digits (< 1e17) always fit a signed long,
        // including after negation. Mirrors parser.c's MAX_FAST_INTEGER_SIZE.
        private static final int MAX_FAST_INTEGER_SIZE = 18;

        // Same idea as the rvalue_cache in the C extension.
        // 
        // This is bigger than the C implementation as this is 
        // heap allocated.
        private static final int KEY_CACHE_CAPA = 128;
        private static final int KEY_CACHE_MAX_ENTRY_LENGTH = 55;

        private static final long SPACES = 0x2020202020202020L;

        private enum FrameType {
            ROOT(FramePhase.DONE),
            ARRAY(FramePhase.ARRAY_COMMA),
            OBJECT(FramePhase.OBJECT_COMMA);

            private final FramePhase nextPhase;

            FrameType(FramePhase nextPhase) {
                this.nextPhase = nextPhase;
            }

            FramePhase nextPhase() {
                return nextPhase;
            }
        }

        private enum FramePhase {
            DONE,
            ARRAY_COMMA,
            OBJECT_COMMA,
            VALUE,
            OBJECT_KEY,
            OBJECT_COLON
        }

        private static final class Frame {
            FrameType type;
            FramePhase phase;
            // The position within the value stack when this frame was created.
            int valueStackHead;
            // The cursor position when we encountered a '{'. 
            // Used for error message reporting when decoding an JSON object.
            int startCursor;
        }

        private final Parser config;
        private final RuntimeInfo info;
        private final ByteList byteList;
        private final ByteList view;
        private final byte[] data;

         // Little-endian view over {@link #data}, used by the SWAR string scan
         // so that {@link Long#numberOfTrailingZeros} locates the first
         // interesting" byte within an 8-byte chunk.
        private final ByteBuffer chunks;
        private final StringScanner scanner;
        private final StringDecoder decoder;
        private final int begin;
        private final int end;
        private int cursor;

        private ThreadContext context;

        // Integer value accumulated by the most recent scanDigits() call.
        private long digitsValue;
        private int currentNesting = 0;

        private int inArray = 0;

        private final IRubyObject[] keyCache = new IRubyObject[KEY_CACHE_CAPA];
        private int keyCacheLength = 0;
        private int emittedDeprecations = 0;


        private Frame[] frameStack = new Frame[INITIAL_FRAME_CAPACITY];
        private int frameDepth = 0;

        private IRubyObject[] valueStack = new IRubyObject[INITIAL_VALUE_CAPACITY];
        private int valueTop = 0;

        private ParserSession(Parser config, RubyString source, RuntimeInfo info) {
            this.config = config;
            this.info = info;
            this.byteList = source.getByteList();
            this.data = byteList.unsafeBytes();
            this.chunks = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            this.scanner = StringScanner.getInstance();
            this.view = new ByteList(data, false);
            this.begin = byteList.begin();
            this.end = begin + byteList.length();
            this.decoder = new StringDecoder(config.allowControlCharacters, config.allowInvalidEscape);
        }

        public IRubyObject parse(ThreadContext context) {
            this.context = context;
            this.cursor = begin;
            pushFrame(FrameType.ROOT, FramePhase.VALUE, 0, -1);

            IRubyObject result = run();

            // Only trailing whitespace (and comments) may follow the document.
            eatWhitespace();
            if (cursor < end) {
                throw unexpectedToken(cursor, end);
            }
            return result;
        }

        private IRubyObject run() {
            while (true) {
                Frame frame = topFrame();

                switch (frame.phase) {
                    case DONE:
                        return valueStack[valueTop - 1];

                    case VALUE: {
                        eatWhitespace();
                        IRubyObject value;
                        byte c = peek();
                        switch (c) {
                            case 'n':
                                if (matchKeyword("null")) { value = context.nil; break; }
                                throw unexpectedToken(cursor, end);
                            case 't':
                                if (matchKeyword("true")) { value = context.tru; break; }
                                throw unexpectedToken(cursor, end);
                            case 'f':
                                if (matchKeyword("false")) { value = context.fals; break; }
                                throw unexpectedToken(cursor, end);
                            case 'N':
                                if (config.allowNaN && matchKeyword("NaN")) {
                                    value = getConstant(CONST_NAN);
                                    break;
                                }
                                throw unexpectedToken(cursor, end);
                            case 'I':
                                if (config.allowNaN && matchKeyword("Infinity")) {
                                    value = getConstant(CONST_INFINITY);
                                    break;
                                }
                                throw unexpectedToken(cursor, end);
                            case '-':
                                cursor++;
                                if (config.allowNaN && matchKeyword("Infinity")) {
                                    value = getConstant(CONST_MINUS_INFINITY);
                                } else {
                                    value = parseNumber(cursor - 1, peek(), true);
                                }
                                break;
                            case '0': case '1': case '2': case '3': case '4':
                            case '5': case '6': case '7': case '8': case '9':
                                value = parseNumber(cursor, c, false);
                                break;
                            case '"':
                                value = parseString(false);
                                break;
                            case '[': {
                                cursor++;
                                eatWhitespace();
                                if (peek() == ']') {
                                    cursor++;
                                    value = decodeArray(0);
                                    break;
                                }
                                currentNesting++;
                                checkNesting();
                                inArray++;
                                // Phase stays VALUE: the next iteration reads
                                // the first element.
                                pushFrame(FrameType.ARRAY, FramePhase.VALUE, valueTop, -1);
                                continue;
                            }
                            case '{': {
                                int objectStart = cursor;
                                cursor++;
                                eatWhitespace();
                                if (peek() == '}') {
                                    cursor++;
                                    value = decodeObject(0);
                                    break;
                                }
                                currentNesting++;
                                checkNesting();
                                // Phase KEY: the next iteration reads the first key.
                                pushFrame(FrameType.OBJECT, FramePhase.OBJECT_KEY, valueTop, objectStart);
                                continue;
                            }
                            case 0:
                                throw newException(Utils.M_PARSER_ERROR, "unexpected end of input");
                            default:
                                throw unexpectedToken(cursor, end);
                        }

                        pushValue(value);
                        valueCompleted(frame);
                        continue;
                    }

                    case OBJECT_KEY: {
                        eatWhitespace();
                        if (peek() == '"') {
                            pushValue(parseString(true));
                            frame.phase = FramePhase.OBJECT_COLON;
                            continue;
                        }
                        throw unexpectedToken(cursor, end);
                    }

                    case OBJECT_COLON: {
                        eatWhitespace();
                        if (peek() == ':') {
                            cursor++;
                            frame.phase = FramePhase.VALUE;
                            continue;
                        }
                        throw unexpectedToken(cursor, end);
                    }

                    case ARRAY_COMMA: {
                        eatWhitespace();
                        byte b = peek();
                        if (b == ',') {
                            cursor++;
                            if (config.allowTrailingComma) {
                                eatWhitespace();
                                if (peek() == ']') {
                                    // Trailing comma: re-enter COMMA to close.
                                    continue;
                                }
                            }
                            frame.phase = FramePhase.VALUE;
                            continue;
                        } else if (b == ']') {
                            cursor++;
                            int count = entryCount(frame);
                            currentNesting--;
                            inArray--;
                            popFrame();
                            pushValue(decodeArray(count));
                            valueCompleted(topFrame());
                            continue;
                        }
                        throw unexpectedToken(cursor, end);
                    }

                    case OBJECT_COMMA: {
                        eatWhitespace();
                        byte b = peek();
                        if (b == ',') {
                            cursor++;
                            if (config.allowTrailingComma) {
                                eatWhitespace();
                                if (peek() == '}') {
                                    // Trailing comma: re-enter COMMA to close.
                                    continue;
                                }
                            }
                            frame.phase = FramePhase.OBJECT_KEY;
                            continue;
                        } else if (b == '}') {
                            cursor++;
                            currentNesting--;
                            int count = entryCount(frame);
                            // Temporarily rewind the cursor so a duplicate-key
                            // error points at the object's opening brace.
                            int finalCursor = cursor;
                            cursor = frame.startCursor;
                            IRubyObject object = decodeObject(count);
                            cursor = finalCursor;
                            popFrame();
                            pushValue(object);
                            valueCompleted(topFrame());
                            continue;
                        }
                        throw unexpectedToken(cursor, end);
                    }

                    default:
                        throw context.runtime.newRuntimeError("unreachable parser state");
                }
            }
        }

        private void pushValue(IRubyObject value) {
            if (valueTop == valueStack.length) {
                valueStack = Arrays.copyOf(valueStack, valueStack.length * 2);
            }
            valueStack[valueTop++] = config.onLoad(context, value);
        }

        private void valueCompleted(Frame frame) {
            frame.phase = frame.type.nextPhase();
        }

        private int entryCount(Frame frame) {
            return valueTop - frame.valueStackHead;
        }

        private Frame topFrame() {
            return frameStack[frameDepth - 1];
        }

        private void pushFrame(FrameType type, FramePhase phase,
                               int valueStackHead, int startCursor) {
            if (frameDepth == frameStack.length) {
                frameStack = Arrays.copyOf(frameStack, frameStack.length * 2);
            }
            Frame frame = frameStack[frameDepth];
            if (frame == null) {
                frame = new Frame();
                frameStack[frameDepth] = frame;
            }
            frame.type = type;
            frame.phase = phase;
            frame.valueStackHead = valueStackHead;
            frame.startCursor = startCursor;
            frameDepth++;
        }

        private void popFrame() {
            frameDepth--;
        }

        private void checkNesting() {
            if (config.maxNesting > 0 && currentNesting > config.maxNesting) {
                throw newException(Utils.M_NESTING_ERROR,
                    "nesting of " + currentNesting + " is too deep");
            }
        }

        private IRubyObject decodeArray(int count) {
            int base = valueTop - count;
            IRubyObject[] elements = new IRubyObject[count];
            System.arraycopy(valueStack, base, elements, 0, count);
            valueTop = base;
            RubyArray array = RubyArray.newArrayNoCopy(context.runtime, elements);
            if (config.freeze) {
                array.setFrozen(true);
            }
            return array;
        }

        private IRubyObject decodeObject(int count) {
            final Ruby runtime = context.runtime;
            int base = valueTop - count;
            int limit = valueTop;
            RubyHash hash = RubyHash.newHash(runtime);
            for (int i = base; i < limit; i += 2) {
                // We use RubyHash#fastASet because all object keys have already been
                // frozen and deduplicated. 
                // 
                // This was significantly faster than RubyHash#op_aset.
                hash.fastASet(valueStack[i], valueStack[i + 1]);
            }
            valueTop = base;

            if (!config.allowDuplicateKey && hash.size() < count / 2) {
                onDuplicateKey(findDuplicateKey(base, limit));
            }

            if (config.freeze) {
                hash.setFrozen(true);
            }
            return hash;
        }

        private IRubyObject findDuplicateKey(int base, int limit) {
            RubyHash seen = RubyHash.newHash(context.runtime);
            for (int i = base; i < limit; i += 2) {
                int before = seen.size();
                IRubyObject key = valueStack[i];
                seen.fastASetCheckString(context.runtime, key, context.tru);
                if (seen.size() == before) {
                    return key;
                }
            }
            return context.nil;
        }

        private void onDuplicateKey(IRubyObject key) {
            // Symbol keys are reported by their string form (":a" -> "a") to
            // match the C parser's message.
            String keyInspect = key.callMethod(context, "to_s")
                                   .callMethod(context, "inspect").asJavaString();
            if (config.deprecateDuplicateKey) {
                if (emittedDeprecations < MAX_DEPRECATIONS) {
                    emittedDeprecations++;
                    context.runtime.getWarnings().warning(
                        "detected duplicate key " + keyInspect + " in JSON object. " +
                        "This will raise an error in json 3.0 unless enabled via `allow_duplicate_key: true`");
                }
            } else {
                throw newException(Utils.M_PARSER_ERROR, "duplicate key " + keyInspect);
            }
        }

        private int parseDigits(long value) {
            int start = cursor;
            byte c = peek();
            for (; c >= '0' && c <= '9'; c = advance()) {
                value = value * 10 + (c - '0');
            }
            digitsValue = value;
            return cursor - start;
        }

        private IRubyObject parseNumber(int numberStart, byte first, boolean negative) {
            int mantissaDigits = parseDigits(0);
            long mantissa = digitsValue;

            if ((first == '0' && mantissaDigits > 1) || mantissaDigits == 0) {
                throw unexpectedToken(numberStart, end);
            }

            boolean integer = true;
            int decimal_point_pos = -1;

            if (peek() == '.') {
                integer = false;
                decimal_point_pos = mantissaDigits;
                cursor++;
                int fracDigits = parseDigits(mantissa);
                if (fracDigits == 0) {
                    throw unexpectedToken(numberStart, end);
                }
                mantissaDigits += fracDigits;
                mantissa = digitsValue;
            }

            int c = peek();
            long exponent = 0;
            if (c == 'e' || c == 'E') {
                integer = false;
                c = advance();
                boolean negative_exponent = c == '-';
                if (negative_exponent || c == '+') advance();

                int exponent_digits = parseDigits(0);
                long abs_exponent = digitsValue;
                if (exponent_digits == 0) {
                    throw unexpectedToken(numberStart, end);
                }

                if (exponent_digits >= 20 || Long.compareUnsigned(abs_exponent, Long.MAX_VALUE) > 0) {
                    exponent = negative_exponent ? Long.MIN_VALUE : Long.MAX_VALUE;
                } else {
                    exponent = negative_exponent ? -abs_exponent : abs_exponent;
                }
            }

            if (integer) {
                if (mantissaDigits < MAX_FAST_INTEGER_SIZE) {
                    return context.runtime.newFixnum(negative ? -mantissa : mantissa);
                }
                return ConvertBytes.byteListToInum(context.runtime, absSubSequence(numberStart, cursor), 10, true);
            }

            // Adjust exponent based on decimal point position
            if (decimal_point_pos >= 0) exponent -= (mantissaDigits - decimal_point_pos);

           return decodeFloat(context, mantissa, mantissaDigits, exponent, negative, numberStart);
        }

        IRubyObject decodeFloat(ThreadContext context, long mantissa, int mantissa_digits, long exponent, boolean negative, int start) {
            if (config.decimalClass != null) return config.decimalFactory.apply(context, absSubSequence(start, cursor));
            if (exponent > Integer.MAX_VALUE) return getConstant(negative ? CONST_MINUS_INFINITY : CONST_INFINITY);
            if (exponent < Integer.MIN_VALUE) return RubyFloat.newFloat(context.runtime, negative ? -0.0 : 0.0);

            // Ryu has rounding issues with subnormals around 1e-310 (< 2.225e-308)
            if (mantissa_digits > 17 || mantissa_digits + exponent < -307) {
                return RubyFloat.newFloat(context.runtime, Double.parseDouble(new String(data, start, cursor - start)));
            }

            return RubyFloat.newFloat(context.runtime, ryuS2dFromParts(mantissa, mantissa_digits, (int) exponent, negative));
        }

        private IRubyObject parseString(boolean isName) {
            final byte[] data = this.data;
            final int contentStart = cursor + 1; // skip opening quote

            long scanned = scanner.scan(data, chunks, contentStart, end, validateUTF8Strings);
            final int q = (int) scanned;
            if (q < 0) {
                throw newException(Utils.M_PARSER_ERROR,
                    "unexpected end of input, expected closing \"");
            }

            boolean plain = (scanned & StringScanner.PLAIN_BIT) != 0;
            boolean isAscii = (scanned & StringScanner.ASCII_BIT) != 0;

            cursor = q + 1; // past closing quote

            // Note: When running multiple read-world benchmarks in the same JVM,
            // this seems consistently faster than "if (isName && plain)"
            // and only handling the ASCII-only path in the cache.
            //
            // Note 2: It's important that all object keys are frozen and deduplicated,
            // decodeObject relies this.
            if (isName) {
                // Resolve the key's decoded bytes without building a RubyString
                // yet, so a cache hit skips allocation entirely.
                byte[] buf;
                int off;
                int len;
                if (plain) {
                    buf = data;
                    off = contentStart;
                    len = q - contentStart;
                } else {
                    ByteList decoded = decodeString(context, byteList, contentStart - begin, q - begin, isAscii);
                    buf = decoded.getUnsafeBytes();
                    off = decoded.begin();
                    len = decoded.realSize();
                }
                // Same as the C extension.
                if (inArray > 0 && len > 0 && len <= KEY_CACHE_MAX_ENTRY_LENGTH && isLetter(buf[off])) {
                    return cachedKey(buf, off, len);
                }
                return internedKey(buf, off, len);
            }

            RubyString string;
            if (plain) {
                string = RubyString.newString(context.runtime, data, contentStart,
                                              q - contentStart, UTF8Encoding.INSTANCE);
            } else {
                ByteList content = decodeString(context, byteList, contentStart - begin, q - begin, isAscii);
                string = context.runtime.newString(content);
                string.setEncoding(UTF8Encoding.INSTANCE);
                string.clearCodeRange();
            }

            if (config.freeze) {
                return context.runtime.freezeAndDedupString(string);
            }

            return string;
        }

        private ByteList decodeString(ThreadContext context, ByteList byteList, int start, int end, boolean isAscii) {
            if (isAscii) {
                return decoder.decodeNoValidate(context, byteList, start, end, chunks);
            }
            if (!validateUTF8Strings) {
                return decoder.decodeNoValidate(context, byteList, start, end, chunks);
            }
            return decoder.decode(context, byteList, start, end);
        }

        private static boolean isLetter(byte b) {
            return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z');
        }

        private IRubyObject internedKey(byte[] buf, int off, int len) {
            RubyString string = context.runtime.newString(
                new ByteList(buf, off, len, UTF8Encoding.INSTANCE, true));
            if (config.symbolizeNames) {
                return string.intern();
            }
            string.setFrozen(true);
            return context.runtime.freezeAndDedupString(string);
        }

        private IRubyObject cachedKey(byte[] buf, int off, int len) {
            int low = 0;
            int high = keyCacheLength - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int cmp = compareKey(buf, off, len, keyCache[mid]);
                if (cmp == 0) {
                    return keyCache[mid];
                } else if (cmp > 0) {
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }

            IRubyObject key = internedKey(buf, off, len);
            if (keyCacheLength < KEY_CACHE_CAPA) {
                System.arraycopy(keyCache, low, keyCache, low + 1, keyCacheLength - low);
                keyCache[low] = key;
                keyCacheLength++;
            }
            return key;
        }

        // Orders by length first, then unsigned byte value
        private static int compareKey(byte[] buf, int off, int len, IRubyObject entry) {
            ByteList eb = entry instanceof RubySymbol
                ? ((RubySymbol) entry).getBytes()
                : ((RubyString) entry).getByteList();
            int elen = eb.realSize();
            if (len != elen) {
                return len - elen;
            }
            byte[] ebuf = eb.getUnsafeBytes();
            int ebeg = eb.begin();
            for (int i = 0; i < len; i++) {
                int cmp = Byte.toUnsignedInt(buf[off + i]) - Byte.toUnsignedInt(ebuf[ebeg + i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }

        private byte peek() {
            return cursor < end ? data[cursor] : 0;
        }
        private byte advance() {
            cursor++;
            return peek();
        }

        private boolean matchKeyword(String keyword) {
            int len = keyword.length();
            if (end - cursor < len) {
                return false;
            }
            for (int i = 0; i < len; i++) {
                if (data[cursor + i] != (byte) keyword.charAt(i)) {
                    return false;
                }
            }
            cursor += len;
            return true;
        }

        private void eatWhitespace() {
            while (cursor < end) {
                switch (data[cursor]) {
                    case ' ':
                    case '\t':
                    case '\r':
                        cursor++;
                        break;
                    case '\n':
                        cursor++;
                        // Same heuristic from the C parser: a newline in
                        // pretty-printed JSON is almost always followed by a run
                        // of indentation spaces, so skip them eight at a time.
                        while (cursor + 8 <= end) {
                            long x = chunks.getLong(cursor);
                            if (x == SPACES) {
                                cursor += 8;
                            } else {
                                cursor += Long.numberOfTrailingZeros(x ^ SPACES) >>> 3;
                                break;
                            }
                        }
                        break;
                    case '/':
                        eatComments();
                        break;
                    default:
                        return;
                }
            }
        }

        private void eatComments() {
            if (!config.allowComments) {
                if (config.deprecateComments) {
                    if (emittedDeprecations < MAX_DEPRECATIONS) {
                        emittedDeprecations++;
                        context.runtime.getWarnings().warning(
                            "Encountered comment in JSON. This will raise an error in json 3.0 unless enabled via `allow_comments: true`");
                    }
                } else {
                    throw unexpectedToken(cursor, end);
                }
            }

            int start = cursor;
            cursor++; // skip '/'
            switch (peek()) {
                case '/':
                    cursor++;
                    while (cursor < end && data[cursor] != '\n') cursor++;
                    if (cursor < end) cursor++; // consume newline
                    break;
                case '*':
                    cursor++;
                    while (true) {
                        while (cursor < end && data[cursor] != '*') cursor++;
                        if (cursor >= end) {
                            throw newException(Utils.M_PARSER_ERROR,
                                "unterminated comment, expected closing '*/'");
                        }
                        cursor++; // past '*'
                        if (peek() == '/') {
                            cursor++;
                            break;
                        }
                    }
                    break;
                default:
                    throw unexpectedToken(start, end);
            }
        }

        /**
         * Updates the "view" ByteList with the new offsets and returns it. The
         * returned ByteList must be consumed before the next call, since the
         * same instance is reused.
         */
        private ByteList absSubSequence(int absStart, int absEnd) {
            view.setBegin(absStart);
            view.setRealSize(absEnd - absStart);
            return view;
        }

        private IRubyObject getConstant(String name) {
            return info.jsonModule.get().getConstant(name);
        }

        private RaiseException parsingError(int absStart, int absEnd) {
            RubyString msg = context.runtime.newString("unexpected token at '")
                    .cat(data, absStart, Math.min(absEnd - absStart, 32))
                    .cat((byte)'\'');
            return newException(Utils.M_PARSER_ERROR, msg);
        }

        private RaiseException unexpectedToken(int absStart, int absEnd) {
            return parsingError(absStart, absEnd);
        }

        private RaiseException newException(String className, String message) {
            return Utils.newException(context, className, message);
        }

        private RaiseException newException(String className, RubyString message) {
            return Utils.newException(context, className, message);
        }
    }
}
