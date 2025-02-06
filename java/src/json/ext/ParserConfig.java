
// line 1 "ParserConfig.rl"
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
    private RubyString createId;
    private boolean createAdditions;
    private boolean deprecatedCreateAdditions;
    private int maxNesting;
    private boolean allowNaN;
    private boolean allowTrailingComma;
    private boolean symbolizeNames;
    private boolean freeze;
    private RubyClass objectClass;
    private RubyClass arrayClass;
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
        Ruby runtime = context.runtime;

        OptionsReader opts   = new OptionsReader(context, options);
        this.maxNesting      = opts.getInt("max_nesting", DEFAULT_MAX_NESTING);
        this.allowNaN        = opts.getBool("allow_nan", false);
        this.allowTrailingComma = opts.getBool("allow_trailing_comma", false);
        this.symbolizeNames  = opts.getBool("symbolize_names", false);
        this.freeze          = opts.getBool("freeze", false);
        this.createId        = opts.getString("create_id", getCreateId(context));

        IRubyObject additions = opts.get("create_additions");
        this.createAdditions = false;
        this.deprecatedCreateAdditions = false;

        if (additions != null) {
            if (additions.isNil()) {
                this.createAdditions = true;
                this.deprecatedCreateAdditions = true;
            } else {
                this.createAdditions = opts.getBool("create_additions", false);
            }
        }

        this.objectClass     = opts.getClass("object_class", runtime.getHash());
        this.arrayClass      = opts.getClass("array_class", runtime.getArray());
        this.decimalClass    = opts.getClass("decimal_class", null);
        this.match_string    = opts.getHash("match_string");

        if (decimalClass == null) {
            this.decimalFactory = this::createFloat;
        } else if (decimalClass == runtime.getClass("BigDecimal")) {
            this.decimalFactory = this::createBigDecimal;
        } else {
            this.decimalFactory = this::createCustomDecimal;
        }

        if(symbolizeNames && createAdditions) {
          throw runtime.newArgumentError("options :symbolize_names and :create_additions cannot be used in conjunction");
        }

        return this;
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
            this.decoder = new StringDecoder();
        }

        private RaiseException unexpectedToken(ThreadContext context, int absStart, int absEnd) {
            RubyString msg = context.runtime.newString("unexpected token at '")
                    .cat(data, absStart, Math.min(absEnd - absStart, 32))
                    .cat((byte)'\'');
            return newException(context, Utils.M_PARSER_ERROR, msg);
        }

        
// line 329 "ParserConfig.rl"


        
// line 311 "ParserConfig.java"
private static byte[] init__JSON_value_actions_0()
{
	return new byte [] {
	    0,    1,    0,    1,    1,    1,    2,    1,    3,    1,    4,    1,
	    5,    1,    6,    1,    7,    1,    8,    1,    9
	};
}

private static final byte _JSON_value_actions[] = init__JSON_value_actions_0();


private static byte[] init__JSON_value_key_offsets_0()
{
	return new byte [] {
	    0,    0,   11,   12,   13,   14,   15,   16,   17,   18,   19,   20,
	   21,   22,   23,   24,   25,   26,   27,   28,   29,   30
	};
}

private static final byte _JSON_value_key_offsets[] = init__JSON_value_key_offsets_0();


private static char[] init__JSON_value_trans_keys_0()
{
	return new char [] {
	   34,   45,   73,   78,   91,  102,  110,  116,  123,   48,   57,  110,
	  102,  105,  110,  105,  116,  121,   97,   78,   97,  108,  115,  101,
	  117,  108,  108,  114,  117,  101,    0
	};
}

private static final char _JSON_value_trans_keys[] = init__JSON_value_trans_keys_0();


private static byte[] init__JSON_value_single_lengths_0()
{
	return new byte [] {
	    0,    9,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    0
	};
}

private static final byte _JSON_value_single_lengths[] = init__JSON_value_single_lengths_0();


private static byte[] init__JSON_value_range_lengths_0()
{
	return new byte [] {
	    0,    1,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_value_range_lengths[] = init__JSON_value_range_lengths_0();


private static byte[] init__JSON_value_index_offsets_0()
{
	return new byte [] {
	    0,    0,   11,   13,   15,   17,   19,   21,   23,   25,   27,   29,
	   31,   33,   35,   37,   39,   41,   43,   45,   47,   49
	};
}

private static final byte _JSON_value_index_offsets[] = init__JSON_value_index_offsets_0();


private static byte[] init__JSON_value_trans_targs_0()
{
	return new byte [] {
	   21,   21,    2,    9,   21,   11,   15,   18,   21,   21,    0,    3,
	    0,    4,    0,    5,    0,    6,    0,    7,    0,    8,    0,   21,
	    0,   10,    0,   21,    0,   12,    0,   13,    0,   14,    0,   21,
	    0,   16,    0,   17,    0,   21,    0,   19,    0,   20,    0,   21,
	    0,    0,    0
	};
}

private static final byte _JSON_value_trans_targs[] = init__JSON_value_trans_targs_0();


private static byte[] init__JSON_value_trans_actions_0()
{
	return new byte [] {
	   13,   11,    0,    0,   15,    0,    0,    0,   17,   11,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    9,
	    0,    0,    0,    7,    0,    0,    0,    0,    0,    0,    0,    3,
	    0,    0,    0,    0,    0,    1,    0,    0,    0,    0,    0,    5,
	    0,    0,    0
	};
}

private static final byte _JSON_value_trans_actions[] = init__JSON_value_trans_actions_0();


private static byte[] init__JSON_value_from_state_actions_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,   19
	};
}

private static final byte _JSON_value_from_state_actions[] = init__JSON_value_from_state_actions_0();


static final int JSON_value_start = 1;
static final int JSON_value_first_final = 21;
static final int JSON_value_error = 0;

static final int JSON_value_en_main = 1;


// line 435 "ParserConfig.rl"


        void parseValue(ThreadContext context, ParserResult res, int p, int pe) {
            int cs;
            IRubyObject result = null;

            
// line 433 "ParserConfig.java"
	{
	cs = JSON_value_start;
	}

// line 442 "ParserConfig.rl"
            
// line 440 "ParserConfig.java"
	{
	int _klen;
	int _trans = 0;
	int _acts;
	int _nacts;
	int _keys;
	int _goto_targ = 0;

	_goto: while (true) {
	switch ( _goto_targ ) {
	case 0:
	if ( p == pe ) {
		_goto_targ = 4;
		continue _goto;
	}
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
case 1:
	_acts = _JSON_value_from_state_actions[cs];
	_nacts = (int) _JSON_value_actions[_acts++];
	while ( _nacts-- > 0 ) {
		switch ( _JSON_value_actions[_acts++] ) {
	case 9:
// line 420 "ParserConfig.rl"
	{
                p--;
                { p += 1; _goto_targ = 5; if (true)  continue _goto;}
            }
	break;
// line 472 "ParserConfig.java"
		}
	}

	_match: do {
	_keys = _JSON_value_key_offsets[cs];
	_trans = _JSON_value_index_offsets[cs];
	_klen = _JSON_value_single_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + _klen - 1;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + ((_upper-_lower) >> 1);
			if ( data[p] < _JSON_value_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( data[p] > _JSON_value_trans_keys[_mid] )
				_lower = _mid + 1;
			else {
				_trans += (_mid - _keys);
				break _match;
			}
		}
		_keys += _klen;
		_trans += _klen;
	}

	_klen = _JSON_value_range_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( data[p] < _JSON_value_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( data[p] > _JSON_value_trans_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				_trans += ((_mid - _keys)>>1);
				break _match;
			}
		}
		_trans += _klen;
	}
	} while (false);

	cs = _JSON_value_trans_targs[_trans];

	if ( _JSON_value_trans_actions[_trans] != 0 ) {
		_acts = _JSON_value_trans_actions[_trans];
		_nacts = (int) _JSON_value_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _JSON_value_actions[_acts++] )
			{
	case 0:
// line 337 "ParserConfig.rl"
	{
                result = context.nil;
            }
	break;
	case 1:
// line 340 "ParserConfig.rl"
	{
                result = context.fals;
            }
	break;
	case 2:
// line 343 "ParserConfig.rl"
	{
                result = context.tru;
            }
	break;
	case 3:
// line 346 "ParserConfig.rl"
	{
                if (config.allowNaN) {
                    result = getConstant(CONST_NAN);
                } else {
                    throw unexpectedToken(context, p - 2, pe);
                }
            }
	break;
	case 4:
// line 353 "ParserConfig.rl"
	{
                if (config.allowNaN) {
                    result = getConstant(CONST_INFINITY);
                } else {
                    throw unexpectedToken(context, p - 7, pe);
                }
            }
	break;
	case 5:
// line 360 "ParserConfig.rl"
	{
                if (pe > p + 8 &&
                    absSubSequence(p, p + 9).equals(JSON_MINUS_INFINITY)) {

                    if (config.allowNaN) {
                        result = getConstant(CONST_MINUS_INFINITY);
                        {p = (( p + 10))-1;}
                        p--;
                        { p += 1; _goto_targ = 5; if (true)  continue _goto;}
                    } else {
                        throw unexpectedToken(context, p, pe);
                    }
                }
                parseFloat(context, res, p, pe);
                if (res.result != null) {
                    result = res.result;
                    {p = (( res.p))-1;}
                }
                parseInteger(context, res, p, pe);
                if (res.result != null) {
                    result = res.result;
                    {p = (( res.p))-1;}
                }
                p--;
                { p += 1; _goto_targ = 5; if (true)  continue _goto;}
            }
	break;
	case 6:
// line 386 "ParserConfig.rl"
	{
                parseString(context, res, p, pe);
                if (res.result == null) {
                    p--;
                    { p += 1; _goto_targ = 5; if (true)  continue _goto;}
                } else {
                    result = res.result;
                    {p = (( res.p))-1;}
                }
            }
	break;
	case 7:
// line 396 "ParserConfig.rl"
	{
                currentNesting++;
                parseArray(context, res, p, pe);
                currentNesting--;
                if (res.result == null) {
                    p--;
                    { p += 1; _goto_targ = 5; if (true)  continue _goto;}
                } else {
                    result = res.result;
                    {p = (( res.p))-1;}
                }
            }
	break;
	case 8:
// line 408 "ParserConfig.rl"
	{
                currentNesting++;
                parseObject(context, res, p, pe);
                currentNesting--;
                if (res.result == null) {
                    p--;
                    { p += 1; _goto_targ = 5; if (true)  continue _goto;}
                } else {
                    result = res.result;
                    {p = (( res.p))-1;}
                }
            }
	break;
// line 644 "ParserConfig.java"
			}
		}
	}

case 2:
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
	if ( ++p != pe ) {
		_goto_targ = 1;
		continue _goto;
	}
case 4:
case 5:
	}
	break; }
	}

// line 443 "ParserConfig.rl"

            if (cs >= JSON_value_first_final && result != null) {
                if (config.freeze) {
                  result.setFrozen(true);
                }
                res.update(result, p);
            } else {
                res.update(null, p);
            }
        }

        
// line 677 "ParserConfig.java"
private static byte[] init__JSON_integer_actions_0()
{
	return new byte [] {
	    0,    1,    0
	};
}

private static final byte _JSON_integer_actions[] = init__JSON_integer_actions_0();


private static byte[] init__JSON_integer_key_offsets_0()
{
	return new byte [] {
	    0,    0,    4,    7,    9,    9
	};
}

private static final byte _JSON_integer_key_offsets[] = init__JSON_integer_key_offsets_0();


private static char[] init__JSON_integer_trans_keys_0()
{
	return new char [] {
	   45,   48,   49,   57,   48,   49,   57,   48,   57,   48,   57,    0
	};
}

private static final char _JSON_integer_trans_keys[] = init__JSON_integer_trans_keys_0();


private static byte[] init__JSON_integer_single_lengths_0()
{
	return new byte [] {
	    0,    2,    1,    0,    0,    0
	};
}

private static final byte _JSON_integer_single_lengths[] = init__JSON_integer_single_lengths_0();


private static byte[] init__JSON_integer_range_lengths_0()
{
	return new byte [] {
	    0,    1,    1,    1,    0,    1
	};
}

private static final byte _JSON_integer_range_lengths[] = init__JSON_integer_range_lengths_0();


private static byte[] init__JSON_integer_index_offsets_0()
{
	return new byte [] {
	    0,    0,    4,    7,    9,   10
	};
}

private static final byte _JSON_integer_index_offsets[] = init__JSON_integer_index_offsets_0();


private static byte[] init__JSON_integer_indicies_0()
{
	return new byte [] {
	    0,    2,    3,    1,    2,    3,    1,    1,    4,    1,    3,    4,
	    0
	};
}

private static final byte _JSON_integer_indicies[] = init__JSON_integer_indicies_0();


private static byte[] init__JSON_integer_trans_targs_0()
{
	return new byte [] {
	    2,    0,    3,    5,    4
	};
}

private static final byte _JSON_integer_trans_targs[] = init__JSON_integer_trans_targs_0();


private static byte[] init__JSON_integer_trans_actions_0()
{
	return new byte [] {
	    0,    0,    0,    0,    1
	};
}

private static final byte _JSON_integer_trans_actions[] = init__JSON_integer_trans_actions_0();


static final int JSON_integer_start = 1;
static final int JSON_integer_first_final = 3;
static final int JSON_integer_error = 0;

static final int JSON_integer_en_main = 1;


// line 465 "ParserConfig.rl"


        void parseInteger(ThreadContext context, ParserResult res, int p, int pe) {
            int new_p = parseIntegerInternal(p, pe);
            if (new_p == -1) {
                res.update(null, p);
                return;
            }
            RubyInteger number = createInteger(context, p, new_p);
            res.update(number, new_p + 1);
        }

        int parseIntegerInternal(int p, int pe) {
            int cs;

            
// line 793 "ParserConfig.java"
	{
	cs = JSON_integer_start;
	}

// line 481 "ParserConfig.rl"
            int memo = p;
            
// line 801 "ParserConfig.java"
	{
	int _klen;
	int _trans = 0;
	int _acts;
	int _nacts;
	int _keys;
	int _goto_targ = 0;

	_goto: while (true) {
	switch ( _goto_targ ) {
	case 0:
	if ( p == pe ) {
		_goto_targ = 4;
		continue _goto;
	}
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
case 1:
	_match: do {
	_keys = _JSON_integer_key_offsets[cs];
	_trans = _JSON_integer_index_offsets[cs];
	_klen = _JSON_integer_single_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + _klen - 1;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + ((_upper-_lower) >> 1);
			if ( data[p] < _JSON_integer_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( data[p] > _JSON_integer_trans_keys[_mid] )
				_lower = _mid + 1;
			else {
				_trans += (_mid - _keys);
				break _match;
			}
		}
		_keys += _klen;
		_trans += _klen;
	}

	_klen = _JSON_integer_range_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( data[p] < _JSON_integer_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( data[p] > _JSON_integer_trans_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				_trans += ((_mid - _keys)>>1);
				break _match;
			}
		}
		_trans += _klen;
	}
	} while (false);

	_trans = _JSON_integer_indicies[_trans];
	cs = _JSON_integer_trans_targs[_trans];

	if ( _JSON_integer_trans_actions[_trans] != 0 ) {
		_acts = _JSON_integer_trans_actions[_trans];
		_nacts = (int) _JSON_integer_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _JSON_integer_actions[_acts++] )
			{
	case 0:
// line 459 "ParserConfig.rl"
	{
                p--;
                { p += 1; _goto_targ = 5; if (true)  continue _goto;}
            }
	break;
// line 888 "ParserConfig.java"
			}
		}
	}

case 2:
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
	if ( ++p != pe ) {
		_goto_targ = 1;
		continue _goto;
	}
case 4:
case 5:
	}
	break; }
	}

// line 483 "ParserConfig.rl"

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

        
// line 928 "ParserConfig.java"
private static byte[] init__JSON_float_actions_0()
{
	return new byte [] {
	    0,    1,    0
	};
}

private static final byte _JSON_float_actions[] = init__JSON_float_actions_0();


private static byte[] init__JSON_float_key_offsets_0()
{
	return new byte [] {
	    0,    0,    4,    7,   10,   12,   16,   18,   23,   29,   29
	};
}

private static final byte _JSON_float_key_offsets[] = init__JSON_float_key_offsets_0();


private static char[] init__JSON_float_trans_keys_0()
{
	return new char [] {
	   45,   48,   49,   57,   48,   49,   57,   46,   69,  101,   48,   57,
	   43,   45,   48,   57,   48,   57,   46,   69,  101,   48,   57,   69,
	  101,   45,   46,   48,   57,   69,  101,   45,   46,   48,   57,    0
	};
}

private static final char _JSON_float_trans_keys[] = init__JSON_float_trans_keys_0();


private static byte[] init__JSON_float_single_lengths_0()
{
	return new byte [] {
	    0,    2,    1,    3,    0,    2,    0,    3,    2,    0,    2
	};
}

private static final byte _JSON_float_single_lengths[] = init__JSON_float_single_lengths_0();


private static byte[] init__JSON_float_range_lengths_0()
{
	return new byte [] {
	    0,    1,    1,    0,    1,    1,    1,    1,    2,    0,    2
	};
}

private static final byte _JSON_float_range_lengths[] = init__JSON_float_range_lengths_0();


private static byte[] init__JSON_float_index_offsets_0()
{
	return new byte [] {
	    0,    0,    4,    7,   11,   13,   17,   19,   24,   29,   30
	};
}

private static final byte _JSON_float_index_offsets[] = init__JSON_float_index_offsets_0();


private static byte[] init__JSON_float_indicies_0()
{
	return new byte [] {
	    0,    2,    3,    1,    2,    3,    1,    4,    5,    5,    1,    6,
	    1,    7,    7,    8,    1,    8,    1,    4,    5,    5,    3,    1,
	    5,    5,    1,    6,    9,    1,    1,    1,    1,    8,    9,    0
	};
}

private static final byte _JSON_float_indicies[] = init__JSON_float_indicies_0();


private static byte[] init__JSON_float_trans_targs_0()
{
	return new byte [] {
	    2,    0,    3,    7,    4,    5,    8,    6,   10,    9
	};
}

private static final byte _JSON_float_trans_targs[] = init__JSON_float_trans_targs_0();


private static byte[] init__JSON_float_trans_actions_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    1
	};
}

private static final byte _JSON_float_trans_actions[] = init__JSON_float_trans_actions_0();


static final int JSON_float_start = 1;
static final int JSON_float_first_final = 8;
static final int JSON_float_error = 0;

static final int JSON_float_en_main = 1;


// line 516 "ParserConfig.rl"


        void parseFloat(ThreadContext context, ParserResult res, int p, int pe) {
            int new_p = parseFloatInternal(p, pe);
            if (new_p == -1) {
                res.update(null, p);
                return;
            }
            final ByteList num = absSubSequence(p, new_p);
            IRubyObject number = config.decimalFactory.apply(context, num);

            res.update(number, new_p + 1);
        }

        int parseFloatInternal(int p, int pe) {
            int cs;

            
// line 1049 "ParserConfig.java"
	{
	cs = JSON_float_start;
	}

// line 534 "ParserConfig.rl"
            int memo = p;
            
// line 1057 "ParserConfig.java"
	{
	int _klen;
	int _trans = 0;
	int _acts;
	int _nacts;
	int _keys;
	int _goto_targ = 0;

	_goto: while (true) {
	switch ( _goto_targ ) {
	case 0:
	if ( p == pe ) {
		_goto_targ = 4;
		continue _goto;
	}
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
case 1:
	_match: do {
	_keys = _JSON_float_key_offsets[cs];
	_trans = _JSON_float_index_offsets[cs];
	_klen = _JSON_float_single_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + _klen - 1;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + ((_upper-_lower) >> 1);
			if ( data[p] < _JSON_float_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( data[p] > _JSON_float_trans_keys[_mid] )
				_lower = _mid + 1;
			else {
				_trans += (_mid - _keys);
				break _match;
			}
		}
		_keys += _klen;
		_trans += _klen;
	}

	_klen = _JSON_float_range_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( data[p] < _JSON_float_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( data[p] > _JSON_float_trans_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				_trans += ((_mid - _keys)>>1);
				break _match;
			}
		}
		_trans += _klen;
	}
	} while (false);

	_trans = _JSON_float_indicies[_trans];
	cs = _JSON_float_trans_targs[_trans];

	if ( _JSON_float_trans_actions[_trans] != 0 ) {
		_acts = _JSON_float_trans_actions[_trans];
		_nacts = (int) _JSON_float_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _JSON_float_actions[_acts++] )
			{
	case 0:
// line 507 "ParserConfig.rl"
	{
                p--;
                { p += 1; _goto_targ = 5; if (true)  continue _goto;}
            }
	break;
// line 1144 "ParserConfig.java"
			}
		}
	}

case 2:
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
	if ( ++p != pe ) {
		_goto_targ = 1;
		continue _goto;
	}
case 4:
case 5:
	}
	break; }
	}

// line 536 "ParserConfig.rl"

            if (cs < JSON_float_first_final) {
                return -1;
            }

            return p;
        }

        
// line 1174 "ParserConfig.java"
private static byte[] init__JSON_string_actions_0()
{
	return new byte [] {
	    0,    2,    0,    1
	};
}

private static final byte _JSON_string_actions[] = init__JSON_string_actions_0();


private static byte[] init__JSON_string_key_offsets_0()
{
	return new byte [] {
	    0,    0,    1,    5,    8,   14,   20,   26,   32
	};
}

private static final byte _JSON_string_key_offsets[] = init__JSON_string_key_offsets_0();


private static char[] init__JSON_string_trans_keys_0()
{
	return new char [] {
	   34,   34,   92,    0,   31,  117,    0,   31,   48,   57,   65,   70,
	   97,  102,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,
	   97,  102,   48,   57,   65,   70,   97,  102,    0
	};
}

private static final char _JSON_string_trans_keys[] = init__JSON_string_trans_keys_0();


private static byte[] init__JSON_string_single_lengths_0()
{
	return new byte [] {
	    0,    1,    2,    1,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_string_single_lengths[] = init__JSON_string_single_lengths_0();


private static byte[] init__JSON_string_range_lengths_0()
{
	return new byte [] {
	    0,    0,    1,    1,    3,    3,    3,    3,    0
	};
}

private static final byte _JSON_string_range_lengths[] = init__JSON_string_range_lengths_0();


private static byte[] init__JSON_string_index_offsets_0()
{
	return new byte [] {
	    0,    0,    2,    6,    9,   13,   17,   21,   25
	};
}

private static final byte _JSON_string_index_offsets[] = init__JSON_string_index_offsets_0();


private static byte[] init__JSON_string_indicies_0()
{
	return new byte [] {
	    0,    1,    2,    3,    1,    0,    4,    1,    0,    5,    5,    5,
	    1,    6,    6,    6,    1,    7,    7,    7,    1,    0,    0,    0,
	    1,    1,    0
	};
}

private static final byte _JSON_string_indicies[] = init__JSON_string_indicies_0();


private static byte[] init__JSON_string_trans_targs_0()
{
	return new byte [] {
	    2,    0,    8,    3,    4,    5,    6,    7
	};
}

private static final byte _JSON_string_trans_targs[] = init__JSON_string_trans_targs_0();


private static byte[] init__JSON_string_trans_actions_0()
{
	return new byte [] {
	    0,    0,    1,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_string_trans_actions[] = init__JSON_string_trans_actions_0();


static final int JSON_string_start = 1;
static final int JSON_string_first_final = 8;
static final int JSON_string_error = 0;

static final int JSON_string_en_main = 1;


// line 575 "ParserConfig.rl"


        void parseString(ThreadContext context, ParserResult res, int p, int pe) {
            int cs;
            IRubyObject result = null;

            
// line 1284 "ParserConfig.java"
	{
	cs = JSON_string_start;
	}

// line 582 "ParserConfig.rl"
            int memo = p;
            
// line 1292 "ParserConfig.java"
	{
	int _klen;
	int _trans = 0;
	int _acts;
	int _nacts;
	int _keys;
	int _goto_targ = 0;

	_goto: while (true) {
	switch ( _goto_targ ) {
	case 0:
	if ( p == pe ) {
		_goto_targ = 4;
		continue _goto;
	}
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
case 1:
	_match: do {
	_keys = _JSON_string_key_offsets[cs];
	_trans = _JSON_string_index_offsets[cs];
	_klen = _JSON_string_single_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + _klen - 1;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + ((_upper-_lower) >> 1);
			if ( data[p] < _JSON_string_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( data[p] > _JSON_string_trans_keys[_mid] )
				_lower = _mid + 1;
			else {
				_trans += (_mid - _keys);
				break _match;
			}
		}
		_keys += _klen;
		_trans += _klen;
	}

	_klen = _JSON_string_range_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( data[p] < _JSON_string_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( data[p] > _JSON_string_trans_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				_trans += ((_mid - _keys)>>1);
				break _match;
			}
		}
		_trans += _klen;
	}
	} while (false);

	_trans = _JSON_string_indicies[_trans];
	cs = _JSON_string_trans_targs[_trans];

	if ( _JSON_string_trans_actions[_trans] != 0 ) {
		_acts = _JSON_string_trans_actions[_trans];
		_nacts = (int) _JSON_string_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _JSON_string_actions[_acts++] )
			{
	case 0:
// line 550 "ParserConfig.rl"
	{
                int offset = byteList.begin();
                ByteList decoded = decoder.decode(context, byteList, memo + 1 - offset,
                                                  p - offset);
                result = context.runtime.newString(decoded);
                if (result == null) {
                    p--;
                    { p += 1; _goto_targ = 5; if (true)  continue _goto;}
                } else {
                    {p = (( p + 1))-1;}
                }
            }
	break;
	case 1:
// line 563 "ParserConfig.rl"
	{
                p--;
                { p += 1; _goto_targ = 5; if (true)  continue _goto;}
            }
	break;
// line 1394 "ParserConfig.java"
			}
		}
	}

case 2:
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
	if ( ++p != pe ) {
		_goto_targ = 1;
		continue _goto;
	}
case 4:
case 5:
	}
	break; }
	}

// line 584 "ParserConfig.rl"

            if (config.createAdditions) {
                RubyHash matchString = config.match_string;
                if (matchString != null) {
                    final IRubyObject[] memoArray = { result, null };
                    try {
                      matchString.visitAll(context, MATCH_VISITOR, memoArray);
                    } catch (JumpException e) { }
                    if (memoArray[1] != null) {
                        RubyClass klass = (RubyClass) memoArray[1];
                        if (klass.respondsTo("json_creatable?") &&
                            klass.callMethod(context, "json_creatable?").isTrue()) {
                            if (config.deprecatedCreateAdditions) {
                                context.runtime.getWarnings().warn("JSON.load implicit support for `create_additions: true` is deprecated and will be removed in 3.0, use JSON.unsafe_load or explicitly pass `create_additions: true`");
                            }
                            result = klass.callMethod(context, "json_create", result);
                        }
                    }
                }
            }

            if (cs >= JSON_string_first_final && result != null) {
                if (result instanceof RubyString) {
                  RubyString string = (RubyString)result;
                  string.setEncoding(UTF8Encoding.INSTANCE);
                  string.clearCodeRange();
                  if (config.freeze) {
                     string.setFrozen(true);
                     string = context.runtime.freezeAndDedupString(string);
                  }
                  res.update(string, p + 1);
                } else {
                  res.update(result, p + 1);
                }
            } else {
                res.update(null, p + 1);
            }
        }

        
// line 1455 "ParserConfig.java"
private static byte[] init__JSON_array_actions_0()
{
	return new byte [] {
	    0,    1,    0,    1,    1
	};
}

private static final byte _JSON_array_actions[] = init__JSON_array_actions_0();


private static byte[] init__JSON_array_cond_offsets_0()
{
	return new byte [] {
	    0,    0,    0,    0,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    6,    6,    6,    6,    6,    8,   11,   16,   19
	};
}

private static final byte _JSON_array_cond_offsets[] = init__JSON_array_cond_offsets_0();


private static byte[] init__JSON_array_cond_lengths_0()
{
	return new byte [] {
	    0,    0,    0,    1,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    5,    0,    0,    0,    0,    2,    3,    5,    3,    0
	};
}

private static final byte _JSON_array_cond_lengths[] = init__JSON_array_cond_lengths_0();


private static int[] init__JSON_array_cond_keys_0()
{
	return new int [] {
	   44,   44,    9,    9,   10,   10,   13,   13,   32,   32,   47,   47,
	   42,   42,   47,   47,    0,   41,   42,   42,   43,65535,    0,   41,
	   42,   42,   43,   46,   47,   47,   48,65535,    0,    9,   10,   10,
	   11,65535,    0
	};
}

private static final int _JSON_array_cond_keys[] = init__JSON_array_cond_keys_0();


private static byte[] init__JSON_array_cond_spaces_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_array_cond_spaces[] = init__JSON_array_cond_spaces_0();


private static byte[] init__JSON_array_key_offsets_0()
{
	return new byte [] {
	    0,    0,    1,   18,   26,   28,   29,   31,   32,   48,   50,   51,
	   53,   54,   76,   78,   79,   81,   82,   86,   92,  100,  106
	};
}

private static final byte _JSON_array_key_offsets[] = init__JSON_array_key_offsets_0();


private static int[] init__JSON_array_trans_keys_0()
{
	return new int [] {
	   91,   13,   32,   34,   45,   47,   73,   78,   91,   93,  102,  110,
	  116,  123,    9,   10,   48,   57,   13,   32,   47,   93,65580,131116,
	    9,   10,   42,   47,   42,   42,   47,   10,   13,   32,   34,   45,
	   47,   73,   78,   91,  102,  110,  116,  123,    9,   10,   48,   57,
	   42,   47,   42,   42,   47,   10,   34,   45,   73,   78,   91,   93,
	  102,  110,  116,  123,65549,65568,65583,131085,131104,131119,   48,   57,
	65545,65546,131081,131082,   42,   47,   42,   42,   47,   10,65578,65583,
	131114,131119,65578,131114,65536,131071,131072,196607,65578,65583,131114,131119,
	65536,131071,131072,196607,65546,131082,65536,131071,131072,196607,    0
	};
}

private static final int _JSON_array_trans_keys[] = init__JSON_array_trans_keys_0();


private static byte[] init__JSON_array_single_lengths_0()
{
	return new byte [] {
	    0,    1,   13,    6,    2,    1,    2,    1,   12,    2,    1,    2,
	    1,   16,    2,    1,    2,    1,    4,    2,    4,    2,    0
	};
}

private static final byte _JSON_array_single_lengths[] = init__JSON_array_single_lengths_0();


private static byte[] init__JSON_array_range_lengths_0()
{
	return new byte [] {
	    0,    0,    2,    1,    0,    0,    0,    0,    2,    0,    0,    0,
	    0,    3,    0,    0,    0,    0,    0,    2,    2,    2,    0
	};
}

private static final byte _JSON_array_range_lengths[] = init__JSON_array_range_lengths_0();


private static short[] init__JSON_array_index_offsets_0()
{
	return new short [] {
	    0,    0,    2,   18,   26,   29,   31,   34,   36,   51,   54,   56,
	   59,   61,   81,   84,   86,   89,   91,   96,  101,  108,  113
	};
}

private static final short _JSON_array_index_offsets[] = init__JSON_array_index_offsets_0();


private static byte[] init__JSON_array_indicies_0()
{
	return new byte [] {
	    0,    1,    0,    0,    2,    2,    3,    2,    2,    2,    4,    2,
	    2,    2,    2,    0,    2,    1,    5,    5,    6,    4,    7,    8,
	    5,    1,    9,   10,    1,   11,    9,   11,    5,    9,    5,   10,
	    7,    7,    2,    2,   12,    2,    2,    2,    2,    2,    2,    2,
	    7,    2,    1,   13,   14,    1,   15,   13,   15,    7,   13,    7,
	   14,    2,    2,    2,    2,    2,    4,    2,    2,    2,    2,    0,
	    0,    3,    8,    8,   16,    2,    0,    8,    1,   17,   18,    1,
	   19,   17,   19,    0,   17,    0,   18,   17,   18,   20,   21,    1,
	   19,   22,   17,   20,    1,   19,    0,   22,    8,   17,   20,    1,
	    0,    8,   18,   21,    1,    1,    0
	};
}

private static final byte _JSON_array_indicies[] = init__JSON_array_indicies_0();


private static byte[] init__JSON_array_trans_targs_0()
{
	return new byte [] {
	    2,    0,    3,   14,   22,    3,    4,    8,   13,    5,    7,    6,
	    9,   10,   12,   11,   18,   15,   17,   16,   19,   21,   20
	};
}

private static final byte _JSON_array_trans_targs[] = init__JSON_array_trans_targs_0();


private static byte[] init__JSON_array_trans_actions_0()
{
	return new byte [] {
	    0,    0,    1,    0,    3,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_array_trans_actions[] = init__JSON_array_trans_actions_0();


static final int JSON_array_start = 1;
static final int JSON_array_first_final = 22;
static final int JSON_array_error = 0;

static final int JSON_array_en_main = 1;


// line 662 "ParserConfig.rl"


        void parseArray(ThreadContext context, ParserResult res, int p, int pe) {
            int cs;

            if (config.maxNesting > 0 && currentNesting > config.maxNesting) {
                throw newException(context, Utils.M_NESTING_ERROR,
                    "nesting of " + currentNesting + " is too deep");
            }

            IRubyObject result;
            if (config.arrayClass == context.runtime.getArray()) {
                result = RubyArray.newArray(context.runtime);
            } else {
                result = config.arrayClass.newInstance(context,
                        IRubyObject.NULL_ARRAY, Block.NULL_BLOCK);
            }

            
// line 1642 "ParserConfig.java"
	{
	cs = JSON_array_start;
	}

// line 681 "ParserConfig.rl"
            
// line 1649 "ParserConfig.java"
	{
	int _klen;
	int _trans = 0;
	int _widec;
	int _acts;
	int _nacts;
	int _keys;
	int _goto_targ = 0;

	_goto: while (true) {
	switch ( _goto_targ ) {
	case 0:
	if ( p == pe ) {
		_goto_targ = 4;
		continue _goto;
	}
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
case 1:
	_widec = data[p];
	_keys = _JSON_array_cond_offsets[cs]*2
;	_klen = _JSON_array_cond_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys
;		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( _widec < _JSON_array_cond_keys[_mid] )
				_upper = _mid - 2;
			else if ( _widec > _JSON_array_cond_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				switch ( _JSON_array_cond_spaces[_JSON_array_cond_offsets[cs] + ((_mid - _keys)>>1)] ) {
	case 0: {
		_widec = 65536 + (data[p] - 0);
		if ( 
// line 629 "ParserConfig.rl"
 config.allowTrailingComma  ) _widec += 65536;
		break;
	}
				}
				break;
			}
		}
	}

	_match: do {
	_keys = _JSON_array_key_offsets[cs];
	_trans = _JSON_array_index_offsets[cs];
	_klen = _JSON_array_single_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + _klen - 1;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + ((_upper-_lower) >> 1);
			if ( _widec < _JSON_array_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( _widec > _JSON_array_trans_keys[_mid] )
				_lower = _mid + 1;
			else {
				_trans += (_mid - _keys);
				break _match;
			}
		}
		_keys += _klen;
		_trans += _klen;
	}

	_klen = _JSON_array_range_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( _widec < _JSON_array_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( _widec > _JSON_array_trans_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				_trans += ((_mid - _keys)>>1);
				break _match;
			}
		}
		_trans += _klen;
	}
	} while (false);

	_trans = _JSON_array_indicies[_trans];
	cs = _JSON_array_trans_targs[_trans];

	if ( _JSON_array_trans_actions[_trans] != 0 ) {
		_acts = _JSON_array_trans_actions[_trans];
		_nacts = (int) _JSON_array_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _JSON_array_actions[_acts++] )
			{
	case 0:
// line 631 "ParserConfig.rl"
	{
                parseValue(context, res, p, pe);
                if (res.result == null) {
                    p--;
                    { p += 1; _goto_targ = 5; if (true)  continue _goto;}
                } else {
                    if (config.arrayClass == context.runtime.getArray()) {
                        ((RubyArray)result).append(res.result);
                    } else {
                        result.callMethod(context, "<<", res.result);
                    }
                    {p = (( res.p))-1;}
                }
            }
	break;
	case 1:
// line 646 "ParserConfig.rl"
	{
                p--;
                { p += 1; _goto_targ = 5; if (true)  continue _goto;}
            }
	break;
// line 1785 "ParserConfig.java"
			}
		}
	}

case 2:
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
	if ( ++p != pe ) {
		_goto_targ = 1;
		continue _goto;
	}
case 4:
case 5:
	}
	break; }
	}

// line 682 "ParserConfig.rl"

            if (cs >= JSON_array_first_final) {
                res.update(result, p + 1);
            } else {
                throw unexpectedToken(context, p, pe);
            }
        }

        
// line 1815 "ParserConfig.java"
private static byte[] init__JSON_object_actions_0()
{
	return new byte [] {
	    0,    1,    0,    1,    1,    1,    2
	};
}

private static final byte _JSON_object_actions[] = init__JSON_object_actions_0();


private static byte[] init__JSON_object_cond_offsets_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    6,    6,
	    6,    6,    6,    6,    6,    6,    6,    6,    6,    8,   11,   16,
	   19,   19,   19,   19,   19,   19,   19,   19,   19
	};
}

private static final byte _JSON_object_cond_offsets[] = init__JSON_object_cond_offsets_0();


private static byte[] init__JSON_object_cond_lengths_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    6,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    2,    3,    5,    3,
	    0,    0,    0,    0,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_object_cond_lengths[] = init__JSON_object_cond_lengths_0();


private static int[] init__JSON_object_cond_keys_0()
{
	return new int [] {
	    9,    9,   10,   10,   13,   13,   32,   32,   44,   44,   47,   47,
	   42,   42,   47,   47,    0,   41,   42,   42,   43,65535,    0,   41,
	   42,   42,   43,   46,   47,   47,   48,65535,    0,    9,   10,   10,
	   11,65535,    0
	};
}

private static final int _JSON_object_cond_keys[] = init__JSON_object_cond_keys_0();


private static byte[] init__JSON_object_cond_spaces_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_object_cond_spaces[] = init__JSON_object_cond_spaces_0();


private static byte[] init__JSON_object_key_offsets_0()
{
	return new byte [] {
	    0,    0,    1,    8,   14,   16,   17,   19,   20,   36,   49,   56,
	   62,   64,   65,   67,   68,   70,   71,   73,   74,   78,   84,   92,
	   98,  100,  101,  103,  104,  106,  107,  109,  110
	};
}

private static final byte _JSON_object_key_offsets[] = init__JSON_object_key_offsets_0();


private static int[] init__JSON_object_trans_keys_0()
{
	return new int [] {
	  123,   13,   32,   34,   47,  125,    9,   10,   13,   32,   47,   58,
	    9,   10,   42,   47,   42,   42,   47,   10,   13,   32,   34,   45,
	   47,   73,   78,   91,  102,  110,  116,  123,    9,   10,   48,   57,
	  125,65549,65568,65580,65583,131085,131104,131116,131119,65545,65546,131081,
	131082,   13,   32,   44,   47,  125,    9,   10,   13,   32,   34,   47,
	    9,   10,   42,   47,   42,   42,   47,   10,   42,   47,   42,   42,
	   47,   10,65578,65583,131114,131119,65578,131114,65536,131071,131072,196607,
	65578,65583,131114,131119,65536,131071,131072,196607,65546,131082,65536,131071,
	131072,196607,   42,   47,   42,   42,   47,   10,   42,   47,   42,   42,
	   47,   10,    0
	};
}

private static final int _JSON_object_trans_keys[] = init__JSON_object_trans_keys_0();


private static byte[] init__JSON_object_single_lengths_0()
{
	return new byte [] {
	    0,    1,    5,    4,    2,    1,    2,    1,   12,    9,    5,    4,
	    2,    1,    2,    1,    2,    1,    2,    1,    4,    2,    4,    2,
	    2,    1,    2,    1,    2,    1,    2,    1,    0
	};
}

private static final byte _JSON_object_single_lengths[] = init__JSON_object_single_lengths_0();


private static byte[] init__JSON_object_range_lengths_0()
{
	return new byte [] {
	    0,    0,    1,    1,    0,    0,    0,    0,    2,    2,    1,    1,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    2,    2,    2,
	    0,    0,    0,    0,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_object_range_lengths[] = init__JSON_object_range_lengths_0();


private static short[] init__JSON_object_index_offsets_0()
{
	return new short [] {
	    0,    0,    2,    9,   15,   18,   20,   23,   25,   40,   52,   59,
	   65,   68,   70,   73,   75,   78,   80,   83,   85,   90,   95,  102,
	  107,  110,  112,  115,  117,  120,  122,  125,  127
	};
}

private static final short _JSON_object_index_offsets[] = init__JSON_object_index_offsets_0();


private static byte[] init__JSON_object_indicies_0()
{
	return new byte [] {
	    0,    1,    0,    0,    2,    3,    4,    0,    1,    5,    5,    6,
	    7,    5,    1,    8,    9,    1,   10,    8,   10,    5,    8,    5,
	    9,    7,    7,   11,   11,   12,   11,   11,   11,   11,   11,   11,
	   11,    7,   11,    1,    4,   13,   13,   14,   15,   16,   16,    0,
	   17,   13,   16,    1,   13,   13,   14,   15,    4,   13,    1,   14,
	   14,    2,   18,   14,    1,   19,   20,    1,   21,   19,   21,   14,
	   19,   14,   20,   22,   23,    1,   24,   22,   24,   13,   22,   13,
	   23,   22,   23,   25,   26,    1,   24,   27,   22,   25,    1,   24,
	   13,   27,   16,   22,   25,    1,   13,   16,   23,   26,    1,   28,
	   29,    1,   30,   28,   30,    7,   28,    7,   29,   31,   32,    1,
	   33,   31,   33,    0,   31,    0,   32,    1,    0
	};
}

private static final byte _JSON_object_indicies[] = init__JSON_object_indicies_0();


private static byte[] init__JSON_object_trans_targs_0()
{
	return new byte [] {
	    2,    0,    3,   28,   32,    3,    4,    8,    5,    7,    6,    9,
	   24,   10,   11,   16,    9,   20,   12,   13,   15,   14,   17,   19,
	   18,   21,   23,   22,   25,   27,   26,   29,   31,   30
	};
}

private static final byte _JSON_object_trans_targs[] = init__JSON_object_trans_targs_0();


private static byte[] init__JSON_object_trans_actions_0()
{
	return new byte [] {
	    0,    0,    3,    0,    5,    0,    0,    0,    0,    0,    0,    1,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_object_trans_actions[] = init__JSON_object_trans_actions_0();


static final int JSON_object_start = 1;
static final int JSON_object_first_final = 32;
static final int JSON_object_error = 0;

static final int JSON_object_en_main = 1;


// line 743 "ParserConfig.rl"


        void parseObject(ThreadContext context, ParserResult res, int p, int pe) {
            int cs;
            IRubyObject lastName = null;
            boolean objectDefault = true;

            if (config.maxNesting > 0 && currentNesting > config.maxNesting) {
                throw newException(context, Utils.M_NESTING_ERROR,
                    "nesting of " + currentNesting + " is too deep");
            }

            // this is guaranteed to be a RubyHash due to the earlier
            // allocator test at OptionsReader#getClass
            IRubyObject result;
            if (config.objectClass == context.runtime.getHash()) {
                result = RubyHash.newHash(context.runtime);
            } else {
                objectDefault = false;
                result = config.objectClass.newInstance(context,
                        IRubyObject.NULL_ARRAY, Block.NULL_BLOCK);
            }

            
// line 2017 "ParserConfig.java"
	{
	cs = JSON_object_start;
	}

// line 767 "ParserConfig.rl"
            
// line 2024 "ParserConfig.java"
	{
	int _klen;
	int _trans = 0;
	int _widec;
	int _acts;
	int _nacts;
	int _keys;
	int _goto_targ = 0;

	_goto: while (true) {
	switch ( _goto_targ ) {
	case 0:
	if ( p == pe ) {
		_goto_targ = 4;
		continue _goto;
	}
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
case 1:
	_widec = data[p];
	_keys = _JSON_object_cond_offsets[cs]*2
;	_klen = _JSON_object_cond_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys
;		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( _widec < _JSON_object_cond_keys[_mid] )
				_upper = _mid - 2;
			else if ( _widec > _JSON_object_cond_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				switch ( _JSON_object_cond_spaces[_JSON_object_cond_offsets[cs] + ((_mid - _keys)>>1)] ) {
	case 0: {
		_widec = 65536 + (data[p] - 0);
		if ( 
// line 696 "ParserConfig.rl"
 config.allowTrailingComma  ) _widec += 65536;
		break;
	}
				}
				break;
			}
		}
	}

	_match: do {
	_keys = _JSON_object_key_offsets[cs];
	_trans = _JSON_object_index_offsets[cs];
	_klen = _JSON_object_single_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + _klen - 1;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + ((_upper-_lower) >> 1);
			if ( _widec < _JSON_object_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( _widec > _JSON_object_trans_keys[_mid] )
				_lower = _mid + 1;
			else {
				_trans += (_mid - _keys);
				break _match;
			}
		}
		_keys += _klen;
		_trans += _klen;
	}

	_klen = _JSON_object_range_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( _widec < _JSON_object_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( _widec > _JSON_object_trans_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				_trans += ((_mid - _keys)>>1);
				break _match;
			}
		}
		_trans += _klen;
	}
	} while (false);

	_trans = _JSON_object_indicies[_trans];
	cs = _JSON_object_trans_targs[_trans];

	if ( _JSON_object_trans_actions[_trans] != 0 ) {
		_acts = _JSON_object_trans_actions[_trans];
		_nacts = (int) _JSON_object_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _JSON_object_actions[_acts++] )
			{
	case 0:
// line 698 "ParserConfig.rl"
	{
                parseValue(context, res, p, pe);
                if (res.result == null) {
                    p--;
                    { p += 1; _goto_targ = 5; if (true)  continue _goto;}
                } else {
                    if (config.objectClass == context.runtime.getHash()) {
                        ((RubyHash)result).op_aset(context, lastName, res.result);
                    } else {
                        Helpers.invoke(context, result, "[]=", lastName, res.result);
                    }
                    {p = (( res.p))-1;}
                }
            }
	break;
	case 1:
// line 713 "ParserConfig.rl"
	{
                parseString(context, res, p, pe);
                if (res.result == null) {
                    p--;
                    { p += 1; _goto_targ = 5; if (true)  continue _goto;}
                } else {
                    RubyString name = (RubyString)res.result;
                    if (config.symbolizeNames) {
                        lastName = name.intern();
                    } else {
                        lastName = name;
                    }
                    {p = (( res.p))-1;}
                }
            }
	break;
	case 2:
// line 729 "ParserConfig.rl"
	{
                p--;
                { p += 1; _goto_targ = 5; if (true)  continue _goto;}
            }
	break;
// line 2178 "ParserConfig.java"
			}
		}
	}

case 2:
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
	if ( ++p != pe ) {
		_goto_targ = 1;
		continue _goto;
	}
case 4:
case 5:
	}
	break; }
	}

// line 768 "ParserConfig.rl"

            if (cs < JSON_object_first_final) {
                res.update(null, p + 1);
                return;
            }

            IRubyObject returnedResult = result;

            // attempt to de-serialize object
            if (config.createAdditions) {
                IRubyObject vKlassName;
                if (objectDefault) {
                    vKlassName = ((RubyHash)result).op_aref(context, config.createId);
                } else {
                    vKlassName = result.callMethod(context, "[]", config.createId);
                }

                if (!vKlassName.isNil()) {
                    // might throw ArgumentError, we let it propagate
                    IRubyObject klass = config.info.jsonModule.get().
                            callMethod(context, "deep_const_get", vKlassName);
                    if (klass.respondsTo("json_creatable?") &&
                        klass.callMethod(context, "json_creatable?").isTrue()) {
                        if (config.deprecatedCreateAdditions) {
                            context.runtime.getWarnings().warn("JSON.load implicit support for `create_additions: true` is deprecated and will be removed in 3.0, use JSON.unsafe_load or explicitly pass `create_additions: true`");
                        }

                        returnedResult = klass.callMethod(context, "json_create", result);
                    }
                }
            }
            res.update(returnedResult, p + 1);
        }

        
// line 2234 "ParserConfig.java"
private static byte[] init__JSON_actions_0()
{
	return new byte [] {
	    0,    1,    0
	};
}

private static final byte _JSON_actions[] = init__JSON_actions_0();


private static byte[] init__JSON_key_offsets_0()
{
	return new byte [] {
	    0,    0,   16,   18,   19,   21,   22,   24,   25,   27,   28
	};
}

private static final byte _JSON_key_offsets[] = init__JSON_key_offsets_0();


private static char[] init__JSON_trans_keys_0()
{
	return new char [] {
	   13,   32,   34,   45,   47,   73,   78,   91,  102,  110,  116,  123,
	    9,   10,   48,   57,   42,   47,   42,   42,   47,   10,   42,   47,
	   42,   42,   47,   10,   13,   32,   47,    9,   10,    0
	};
}

private static final char _JSON_trans_keys[] = init__JSON_trans_keys_0();


private static byte[] init__JSON_single_lengths_0()
{
	return new byte [] {
	    0,   12,    2,    1,    2,    1,    2,    1,    2,    1,    3
	};
}

private static final byte _JSON_single_lengths[] = init__JSON_single_lengths_0();


private static byte[] init__JSON_range_lengths_0()
{
	return new byte [] {
	    0,    2,    0,    0,    0,    0,    0,    0,    0,    0,    1
	};
}

private static final byte _JSON_range_lengths[] = init__JSON_range_lengths_0();


private static byte[] init__JSON_index_offsets_0()
{
	return new byte [] {
	    0,    0,   15,   18,   20,   23,   25,   28,   30,   33,   35
	};
}

private static final byte _JSON_index_offsets[] = init__JSON_index_offsets_0();


private static byte[] init__JSON_indicies_0()
{
	return new byte [] {
	    0,    0,    2,    2,    3,    2,    2,    2,    2,    2,    2,    2,
	    0,    2,    1,    4,    5,    1,    6,    4,    6,    7,    4,    7,
	    5,    8,    9,    1,   10,    8,   10,    0,    8,    0,    9,    7,
	    7,   11,    7,    1,    0
	};
}

private static final byte _JSON_indicies[] = init__JSON_indicies_0();


private static byte[] init__JSON_trans_targs_0()
{
	return new byte [] {
	    1,    0,   10,    6,    3,    5,    4,   10,    7,    9,    8,    2
	};
}

private static final byte _JSON_trans_targs[] = init__JSON_trans_targs_0();


private static byte[] init__JSON_trans_actions_0()
{
	return new byte [] {
	    0,    0,    1,    0,    0,    0,    0,    0,    0,    0,    0,    0
	};
}

private static final byte _JSON_trans_actions[] = init__JSON_trans_actions_0();


static final int JSON_start = 1;
static final int JSON_first_final = 10;
static final int JSON_error = 0;

static final int JSON_en_main = 1;


// line 822 "ParserConfig.rl"


        public IRubyObject parseImplementation(ThreadContext context) {
            int cs;
            int p, pe;
            IRubyObject result = null;
            ParserResult res = new ParserResult();

            
// line 2347 "ParserConfig.java"
	{
	cs = JSON_start;
	}

// line 831 "ParserConfig.rl"
            p = byteList.begin();
            pe = p + byteList.length();
            
// line 2356 "ParserConfig.java"
	{
	int _klen;
	int _trans = 0;
	int _acts;
	int _nacts;
	int _keys;
	int _goto_targ = 0;

	_goto: while (true) {
	switch ( _goto_targ ) {
	case 0:
	if ( p == pe ) {
		_goto_targ = 4;
		continue _goto;
	}
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
case 1:
	_match: do {
	_keys = _JSON_key_offsets[cs];
	_trans = _JSON_index_offsets[cs];
	_klen = _JSON_single_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + _klen - 1;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + ((_upper-_lower) >> 1);
			if ( data[p] < _JSON_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( data[p] > _JSON_trans_keys[_mid] )
				_lower = _mid + 1;
			else {
				_trans += (_mid - _keys);
				break _match;
			}
		}
		_keys += _klen;
		_trans += _klen;
	}

	_klen = _JSON_range_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( data[p] < _JSON_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( data[p] > _JSON_trans_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				_trans += ((_mid - _keys)>>1);
				break _match;
			}
		}
		_trans += _klen;
	}
	} while (false);

	_trans = _JSON_indicies[_trans];
	cs = _JSON_trans_targs[_trans];

	if ( _JSON_trans_actions[_trans] != 0 ) {
		_acts = _JSON_trans_actions[_trans];
		_nacts = (int) _JSON_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _JSON_actions[_acts++] )
			{
	case 0:
// line 808 "ParserConfig.rl"
	{
                parseValue(context, res, p, pe);
                if (res.result == null) {
                    p--;
                    { p += 1; _goto_targ = 5; if (true)  continue _goto;}
                } else {
                    result = res.result;
                    {p = (( res.p))-1;}
                }
            }
	break;
// line 2449 "ParserConfig.java"
			}
		}
	}

case 2:
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
	if ( ++p != pe ) {
		_goto_targ = 1;
		continue _goto;
	}
case 4:
case 5:
	}
	break; }
	}

// line 834 "ParserConfig.rl"

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
