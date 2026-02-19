/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyInteger;
import org.jruby.RubyObject;
import org.jruby.RubyProc;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.JumpException;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Block;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.ConvertBytes;

import java.util.function.BiFunction;

import static org.jruby.util.ConvertDouble.DoubleConverter;

/**
 * The <code>JSON::Ext::Parser</code> class.
 *
 * <p>This is the JSON parser implemented as a Java class. To use it as the
 * standard parser, set
 *   <pre>JSON.parser = JSON::Ext::Parser</pre>
 * This is performed for you when you <code>include "json/ext"</code>.
 *
 * <p>This class does not perform the actual parsing, just acts as an interface
 * to Ruby code. When the {@link #parse(ThreadContext)} method is invoked, a
 * ParserConfig.ParserSession object is instantiated, which handles the process.
 *
 * @author mernen
 */
public class ParserConfig extends RubyObject {
    private final RuntimeInfo info;
    private int maxNesting;
    private boolean allowNaN;
    private boolean allowTrailingComma;
    private boolean allowControlCharacters;
    private boolean allowInvalidEscape;
    private boolean allowDuplicateKey;
    private boolean deprecateDuplicateKey;
    private boolean symbolizeNames;
    private boolean freeze;
    private RubyProc onLoadProc;
    private RubyClass decimalClass;
    BiFunction<ThreadContext, ByteList, IRubyObject> decimalFactory;
    private RubyHash match_string;

    private static final int DEFAULT_MAX_NESTING = 100;

    private static final ByteList JSON_MINUS_INFINITY = new ByteList(ByteList.plain("-Infinity"));
    // constant names in the JSON module containing those values
    private static final String CONST_NAN = "NaN";
    private static final String CONST_INFINITY = "Infinity";
    private static final String CONST_MINUS_INFINITY = "MinusInfinity";

    static final ObjectAllocator ALLOCATOR = ParserConfig::new;

    /**
     * Multiple-value return for internal parser methods.
     *
     * <p>All the <code>parse<var>Stuff</var></code> methods return instances of
     * <code>ParserResult</code> when successful, or <code>null</code> when
     * there's a problem with the input data.
     */
    static final class ParserResult {
        /**
         * The result of the successful parsing. Should never be
         * <code>null</code>.
         */
        IRubyObject result;
        /**
         * The point where the parser returned.
         */
        int p;

        void update(IRubyObject result, int p) {
            this.result = result;
            this.p = p;
        }
    }

    public ParserConfig(Ruby runtime, RubyClass metaClass) {
        super(runtime, metaClass);
        info = RuntimeInfo.forRuntime(runtime);
    }

    /**
     * <code>ParserConfig.new(source, opts = {})</code>
     *
     * <p>Creates a new <code>JSON::Ext::Parser</code> instance for the string
     * <code>source</code>.
     * It will be configured by the <code>opts</code> Hash.
     * <code>opts</code> can have the following keys:
     * <p>
     * <dl>
     * <dt><code>:max_nesting</code>
     * <dd>The maximum depth of nesting allowed in the parsed data
     * structures. Disable depth checking with <code>:max_nesting => false|nil|0</code>,
     * it defaults to 100.
     * <p>
     * <dt><code>:allow_nan</code>
     * <dd>If set to <code>true</code>, allow <code>NaN</code>,
     * <code>Infinity</code> and <code>-Infinity</code> in defiance of RFC 4627
     * to be parsed by the Parser. This option defaults to <code>false</code>.
     * <p>
     * <dt><code>:allow_trailing_comma</code>
     * <dd>If set to <code>true</code>, allow arrays and objects with a trailing
     * comma in defiance of RFC 4627 to be parsed by the Parser.
     * This option defaults to <code>false</code>.
     * <p>
     * <dt><code>:symbolize_names</code>
     * <dd>If set to <code>true</code>, returns symbols for the names (keys) in
     * a JSON object. Otherwise strings are returned, which is also the default.
     * <p>
     * <dt><code>:create_additions</code>
     * <dd>If set to <code>false</code>, the Parser doesn't create additions
     * even if a matching class and <code>create_id</code> was found. This option
     * defaults to <code>true</code>.
     * <p>
     * <dt><code>:object_class</code>
     * <dd>Defaults to Hash. If another type is provided, it will be used
     * instead of Hash to represent JSON objects. The type must respond to
     * <code>new</code> without arguments, and return an object that respond to <code>[]=</code>.
     * <p>
     * <dt><code>:array_class</code>
     * <dd>Defaults to Array. If another type is provided, it will be used
     * instead of Hash to represent JSON arrays. The type must respond to
     * <code>new</code> without arguments, and return an object that respond to <code><<</code>.
     * <p>
     * <dt><code>:decimal_class</code>
     * <dd>Specifies which class to use instead of the default (Float) when
     * parsing decimal numbers. This class must accept a single string argument
     * in its constructor.
     * </dl>
     */

    @JRubyMethod(name = "new", meta = true)
    public static IRubyObject newInstance(IRubyObject clazz, IRubyObject arg0, Block block) {
        ParserConfig config = (ParserConfig)((RubyClass)clazz).allocate();

        config.callInit(arg0, block);

        return config;
    }

    @JRubyMethod(name = "new", meta = true)
    public static IRubyObject newInstance(IRubyObject clazz, IRubyObject arg0, IRubyObject arg1, Block block) {
        ParserConfig config = (ParserConfig)((RubyClass)clazz).allocate();

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
        return new ParserSession(this, convertEncoding(context, source.convertToString()), context, info).parse(context);
    }

    /**
     * Queries <code>JSON.create_id</code>. Returns <code>null</code> if it is
     * set to <code>nil</code> or <code>false</code>, and a String if not.
     */
    private RubyString getCreateId(ThreadContext context) {
        IRubyObject v = info.jsonModule.get().callMethod(context, "create_id");
        return v.isTrue() ? v.convertToString() : null;
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
     * A string parsing session.
     *
     * <p>Once a ParserSession is instantiated, the source string should not
     * change until the parsing is complete. The ParserSession object assumes
     * the source {@link RubyString} is still associated to its original
     * {@link ByteList}, which in turn must still be bound to the same
     * <code>byte[]</code> value (and on the same offset).
     */
    // Ragel uses lots of fall-through
    @SuppressWarnings("fallthrough")
    private static class ParserSession {
        private final ParserConfig config;
        private final RuntimeInfo info;
        private final ByteList byteList;
        private final ByteList view;
        private final byte[] data;
        private final StringDecoder decoder;
        private int currentNesting = 0;

        private ParserSession(ParserConfig config, RubyString source, ThreadContext context, RuntimeInfo info) {
            this.config = config;
            this.info = info;
            this.byteList = source.getByteList();
            this.data = byteList.unsafeBytes();
            this.view = new ByteList(data, false);
            this.decoder = new StringDecoder(config.allowControlCharacters, config.allowInvalidEscape);
        }

        private RaiseException parsingError(ThreadContext context, String message, int absStart, int absEnd) {
            RubyString msg = context.runtime.newString("unexpected token at '")
                    .cat(data, absStart, Math.min(absEnd - absStart, 32))
                    .cat((byte)'\'');
            return newException(context, Utils.M_PARSER_ERROR, msg);
        }

        private RaiseException unexpectedToken(ThreadContext context, int absStart, int absEnd) {
            return parsingError(context, "unexpected token at '", absStart, absEnd);
        }

        %%{
            machine JSON_common;

            cr                  = '\n';
            cr_neg              = [^\n];
            ws                  = [ \t\r\n];
            c_comment           = '/*' ( any* - (any* '*/' any* ) ) '*/';
            cpp_comment         = '//' cr_neg* cr;
            comment             = c_comment | cpp_comment;
            ignore              = ws | comment;
            name_separator      = ':';
            value_separator     = ',';
            Vnull               = 'null';
            Vfalse              = 'false';
            Vtrue               = 'true';
            VNaN                = 'NaN';
            VInfinity           = 'Infinity';
            VMinusInfinity      = '-Infinity';
            begin_value         = [nft"\-[{NI] | digit;
            begin_object        = '{';
            end_object          = '}';
            begin_array         = '[';
            end_array           = ']';
            begin_string        = '"';
            begin_name          = begin_string;
            begin_number        = digit | '-';
        }%%

        %%{
            machine JSON_value;
            include JSON_common;

            write data;

            action parse_null {
                result = context.nil;
            }
            action parse_false {
                result = context.fals;
            }
            action parse_true {
                result = context.tru;
            }
            action parse_nan {
                if (config.allowNaN) {
                    result = getConstant(CONST_NAN);
                } else {
                    throw unexpectedToken(context, p - 2, pe);
                }
            }
            action parse_infinity {
                if (config.allowNaN) {
                    result = getConstant(CONST_INFINITY);
                } else {
                    throw unexpectedToken(context, p - 7, pe);
                }
            }
            action parse_number {
                if (pe > fpc + 8 &&
                    absSubSequence(fpc, fpc + 9).equals(JSON_MINUS_INFINITY)) {

                    if (config.allowNaN) {
                        result = getConstant(CONST_MINUS_INFINITY);
                        fexec p + 10;
                        fhold;
                        fbreak;
                    } else {
                        throw unexpectedToken(context, p, pe);
                    }
                }
                parseFloat(context, res, fpc, pe);
                if (res.result != null) {
                    result = res.result;
                    fexec res.p;
                }
                parseInteger(context, res, fpc, pe);
                if (res.result != null) {
                    result = res.result;
                    fexec res.p;
                }
                fhold;
                fbreak;
            }
            action parse_string {
                parseString(context, res, fpc, pe);
                if (res.result == null) {
                    fhold;
                    fbreak;
                } else {
                    result = res.result;
                    fexec res.p;
                }
            }
            action parse_array {
                currentNesting++;
                parseArray(context, res, fpc, pe);
                currentNesting--;
                if (res.result == null) {
                    fhold;
                    fbreak;
                } else {
                    result = res.result;
                    fexec res.p;
                }
            }
            action parse_object {
                currentNesting++;
                parseObject(context, res, fpc, pe);
                currentNesting--;
                if (res.result == null) {
                    fhold;
                    fbreak;
                } else {
                    result = res.result;
                    fexec res.p;
                }
            }
            action exit {
                fhold;
                fbreak;
            }

            main := ( Vnull @parse_null |
                      Vfalse @parse_false |
                      Vtrue @parse_true |
                      VNaN @parse_nan |
                      VInfinity @parse_infinity |
                      begin_number >parse_number |
                      begin_string >parse_string |
                      begin_array >parse_array |
                      begin_object >parse_object
                    ) %*exit;
        }%%

        void parseValue(ThreadContext context, ParserResult res, int p, int pe) {
            int cs;
            IRubyObject result = null;

            %% write init;
            %% write exec;

            if (cs >= JSON_value_first_final && result != null) {
                if (config.freeze) {
                  result.setFrozen(true);
                }
                res.update(result, p);
            } else {
                res.update(null, p);
            }
        }

        %%{
            machine JSON_integer;

            write data;

            action exit {
                fhold;
                fbreak;
            }

            main := '-'? ( '0' | [1-9][0-9]* ) ( ^[0-9]? @exit );
        }%%

        void parseInteger(ThreadContext context, ParserResult res, int p, int pe) {
            int new_p = parseIntegerInternal(p, pe);
            if (new_p == -1) {
                res.update(null, p);
                return;
            }
            RubyInteger number = createInteger(context, p, new_p);
            res.update(config.onLoad(context, number), new_p + 1);
        }

        int parseIntegerInternal(int p, int pe) {
            int cs;

            %% write init;
            int memo = p;
            %% write exec;

            if (cs < JSON_integer_first_final) {
                return -1;
            }

            return p;
        }

        RubyInteger createInteger(ThreadContext context, int p, int new_p) {
            Ruby runtime = context.runtime;
            ByteList num = absSubSequence(p, new_p);
            return bytesToInum(runtime, num);
        }

        RubyInteger bytesToInum(Ruby runtime, ByteList num) {
            return ConvertBytes.byteListToInum(runtime, num, 10, true);
        }

        %%{
            machine JSON_float;
            include JSON_common;

            write data;

            action exit {
                fhold;
                fbreak;
            }

            main := '-'?
                    ( ( ( '0' | [1-9][0-9]* ) '.' [0-9]+ ( [Ee] [+\-]?[0-9]+ )? )
                    | ( ( '0' | [1-9][0-9]* ) ( [Ee] [+\-]? [0-9]+ ) ) )
                    ( ^[0-9Ee.\-]? @exit );
        }%%

        void parseFloat(ThreadContext context, ParserResult res, int p, int pe) {
            int new_p = parseFloatInternal(p, pe);
            if (new_p == -1) {
                res.update(null, p);
                return;
            }
            final ByteList num = absSubSequence(p, new_p);
            IRubyObject number = config.decimalFactory.apply(context, num);

            res.update(config.onLoad(context, number), new_p + 1);
        }

        int parseFloatInternal(int p, int pe) {
            int cs;

            %% write init;
            int memo = p;
            %% write exec;

            if (cs < JSON_float_first_final) {
                return -1;
            }

            return p;
        }

        %%{
            machine JSON_string;
            include JSON_common;

            write data;

            action parse_string {
                int offset = byteList.begin();
                ByteList decoded = decoder.decode(context, byteList, memo + 1 - offset,
                                                  p - offset);
                result = context.runtime.newString(decoded);
                if (result == null) {
                    fhold;
                    fbreak;
                } else {
                    fexec p + 1;
                }
            }

            action exit {
                fhold;
                fbreak;
            }

            main := '"'
                    ( ( ^(["\\])
                      | '\\'["\\/bfnrt]
                      | '\\u'[0-9a-fA-F]{4}
                      | '\\'^(["\\/bfnrtu])
                      )* %parse_string
                    ) '"' @exit;
        }%%

        void parseString(ThreadContext context, ParserResult res, int p, int pe) {
            int cs;
            IRubyObject result = null;

            %% write init;
            int memo = p;
            %% write exec;

            if (cs >= JSON_string_first_final && result != null) {
                if (result instanceof RubyString) {
                  RubyString string = (RubyString)result;
                  string.setEncoding(UTF8Encoding.INSTANCE);
                  string.clearCodeRange();
                  if (config.freeze) {
                     string.setFrozen(true);
                     string = context.runtime.freezeAndDedupString(string);
                  }
                  res.update(config.onLoad(context, string), p + 1);
                } else {
                  res.update(config.onLoad(context, result), p + 1);
                }
            } else {
                res.update(null, p + 1);
            }
        }

        %%{
            machine JSON_array;
            include JSON_common;

            write data;

            action allow_trailing_comma { config.allowTrailingComma }

            action parse_value {
                parseValue(context, res, fpc, pe);
                if (res.result == null) {
                    fhold;
                    fbreak;
                } else {
                    ((RubyArray)result).append(res.result);
                    fexec res.p;
                }
            }

            action exit {
                fhold;
                fbreak;
            }

            next_element = value_separator ignore* begin_value >parse_value;

            main := begin_array
                    ignore*
                    ( ( begin_value >parse_value
                        ignore* )
                      ( ignore*
                        next_element
                        ignore* )* ( (value_separator ignore*) when allow_trailing_comma )? )?
                    ignore*
                    end_array @exit;
        }%%

        void parseArray(ThreadContext context, ParserResult res, int p, int pe) {
            int cs;

            if (config.maxNesting > 0 && currentNesting > config.maxNesting) {
                throw newException(context, Utils.M_NESTING_ERROR,
                    "nesting of " + currentNesting + " is too deep");
            }

            IRubyObject result = RubyArray.newArray(context.runtime);

            %% write init;
            %% write exec;

            if (cs >= JSON_array_first_final) {
                res.update(config.onLoad(context, result), p + 1);
            } else {
                throw unexpectedToken(context, p, pe);
            }
        }

        %%{
            machine JSON_object;
            include JSON_common;

            write data;

            action allow_trailing_comma { config.allowTrailingComma }

            action parse_value {
                parseValue(context, res, fpc, pe);
                if (res.result == null) {
                    fhold;
                    fbreak;
                } else {
                    ((RubyHash)result).op_aset(context, lastName, res.result);
                    fexec res.p;
                }
            }

            action parse_name {
                parseString(context, res, fpc, pe);
                if (res.result == null) {
                    fhold;
                    fbreak;
                } else {
                    RubyString name = (RubyString)res.result;
                    if (config.symbolizeNames) {
                        lastName = name.intern();
                    } else {
                        lastName = name;
                    }

                    if (!config.allowDuplicateKey) {
                        if (((RubyHash)result).hasKey(lastName)) {
                            if (config.deprecateDuplicateKey) {
                                context.runtime.getWarnings().warning(
                                    "detected duplicate key " + name.inspect() + " in JSON object. This will raise an error in json 3.0 unless enabled via `allow_duplicate_key: true`"
                                );
                            } else {
                                throw parsingError(context, "duplicate key" + name.inspect(), p, pe);
                            }
                        }
                    }

                    fexec res.p;
                }
            }

            action exit {
                fhold;
                fbreak;
            }

            pair      = ignore* begin_name >parse_name ignore* name_separator
              ignore* begin_value >parse_value;
            next_pair = ignore* value_separator pair;

            main := (
                begin_object
                (pair (next_pair)*((ignore* value_separator) when allow_trailing_comma)?)? ignore*
                end_object
            ) @exit;
        }%%

        void parseObject(ThreadContext context, ParserResult res, int p, int pe) {
            int cs;
            IRubyObject lastName = null;

            if (config.maxNesting > 0 && currentNesting > config.maxNesting) {
                throw newException(context, Utils.M_NESTING_ERROR,
                    "nesting of " + currentNesting + " is too deep");
            }

            // this is guaranteed to be a RubyHash due to the earlier
            // allocator test at OptionsReader#getClass
            IRubyObject result = RubyHash.newHash(context.runtime);

            %% write init;
            %% write exec;

            if (cs < JSON_object_first_final) {
                res.update(null, p + 1);
                return;
            }

            res.update(config.onLoad(context, result), p + 1);
        }

        %%{
            machine JSON;
            include JSON_common;

            write data;

            action parse_value {
                parseValue(context, res, fpc, pe);
                if (res.result == null) {
                    fhold;
                    fbreak;
                } else {
                    result = res.result;
                    fexec res.p;
                }
            }

            main := ignore*
                    ( begin_value >parse_value)
                    ignore*;
        }%%

        public IRubyObject parseImplementation(ThreadContext context) {
            int cs;
            int p, pe;
            IRubyObject result = null;
            ParserResult res = new ParserResult();

            %% write init;
            p = byteList.begin();
            pe = p + byteList.length();
            %% write exec;

            if (cs >= JSON_first_final && p == pe) {
                return result;
            } else {
                throw unexpectedToken(context, p, pe);
            }
        }

        public IRubyObject parse(ThreadContext context) {
            return parseImplementation(context);
        }

        /**
         * Updates the "view" bytelist with the new offsets and returns it.
         * @param absStart
         * @param absEnd
         */
        private ByteList absSubSequence(int absStart, int absEnd) {
            view.setBegin(absStart);
            view.setRealSize(absEnd - absStart);
            return view;
        }

        /**
         * Retrieves a constant directly descended from the <code>JSON</code> module.
         * @param name The constant name
         */
        private IRubyObject getConstant(String name) {
            return config.info.jsonModule.get().getConstant(name);
        }

        private RaiseException newException(ThreadContext context, String className, String message) {
            return Utils.newException(context, className, message);
        }

        private RaiseException newException(ThreadContext context, String className, RubyString message) {
            return Utils.newException(context, className, message);
        }

        RubyHash.VisitorWithState<IRubyObject[]> MATCH_VISITOR = new RubyHash.VisitorWithState<IRubyObject[]>() {
            @Override
            public void visit(ThreadContext context, RubyHash self, IRubyObject pattern, IRubyObject klass, int index, IRubyObject[] state) {
                if (pattern.callMethod(context, "===", state[0]).isTrue()) {
                    state[1] = klass;
                    throw JumpException.SPECIAL_JUMP;
                }
            }
        };
    }
}
