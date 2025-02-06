/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jcodings.specific.UTF8Encoding;

import org.jruby.Ruby;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyHash;
import org.jruby.RubyInteger;
import org.jruby.RubyNumeric;
import org.jruby.RubyObject;
import org.jruby.RubyProc;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.TypeConverter;

/**
 * The <code>JSON::Ext::Generator::State</code> class.
 *
 * <p>This class is used to create State instances, that are use to hold data
 * while generating a JSON text from a a Ruby data structure.
 *
 * @author mernen
 */
public class GeneratorState extends RubyObject {
    /**
     * The indenting unit string. Will be repeated several times for larger
     * indenting levels.
     */
    private ByteList indent = ByteList.EMPTY_BYTELIST;
    /**
     * The spacing to be added after a semicolon on a JSON object.
     * @see #spaceBefore
     */
    private ByteList space = ByteList.EMPTY_BYTELIST;
    /**
     * The spacing to be added before a semicolon on a JSON object.
     * @see #space
     */
    private ByteList spaceBefore = ByteList.EMPTY_BYTELIST;
    /**
     * Any suffix to be added after the comma for each element on a JSON object.
     * It is assumed to be a newline, if set.
     */
    private ByteList objectNl = ByteList.EMPTY_BYTELIST;
    /**
     * Any suffix to be added after the comma for each element on a JSON Array.
     * It is assumed to be a newline, if set.
     */
    private ByteList arrayNl = ByteList.EMPTY_BYTELIST;

    private RubyProc asJSON;

    /**
     * The maximum level of nesting of structures allowed.
     * <code>0</code> means disabled.
     */
    private int maxNesting = DEFAULT_MAX_NESTING;
    static final int DEFAULT_MAX_NESTING = 100;
    /**
     * Whether special float values (<code>NaN</code>, <code>Infinity</code>,
     * <code>-Infinity</code>) are accepted.
     * If set to <code>false</code>, an exception will be thrown upon
     * encountering one.
     */
    private boolean allowNaN = DEFAULT_ALLOW_NAN;
    static final boolean DEFAULT_ALLOW_NAN = false;
    /**
     * If set to <code>true</code> all JSON documents generated do not contain
     * any other characters than ASCII characters.
     */
    private boolean asciiOnly = DEFAULT_ASCII_ONLY;
    static final boolean DEFAULT_ASCII_ONLY = false;
    /**
     * If set to <code>true</code> all JSON values generated might not be
     * RFC-conform JSON documents.
     */
    private boolean quirksMode = DEFAULT_QUIRKS_MODE;
    static final boolean DEFAULT_QUIRKS_MODE = false;
    /**
     * If set to <code>true</code> the forward slash will be escaped in
     * json output.
     */
    private boolean scriptSafe = DEFAULT_SCRIPT_SAFE;
    static final boolean DEFAULT_SCRIPT_SAFE = false;
    /**
     * If set to <code>true</code> types unsupported by the JSON format will
     * raise a <code>JSON::GeneratorError</code>.
     */
    private boolean strict = DEFAULT_STRICT;
    static final boolean DEFAULT_STRICT = false;
    /**
     * The initial buffer length of this state. (This isn't really used on all
     * non-C implementations.)
     */
    private int bufferInitialLength = DEFAULT_BUFFER_INITIAL_LENGTH;
    static final int DEFAULT_BUFFER_INITIAL_LENGTH = 1024;

    /**
     * The current depth (inside a #to_json call)
     */
    private int depth = 0;

    static final ObjectAllocator ALLOCATOR = GeneratorState::new;

    public GeneratorState(Ruby runtime, RubyClass metaClass) {
        super(runtime, metaClass);
    }

    /**
     * <code>State.from_state(opts)</code>
     *
     * <p>Creates a State object from <code>opts</code>, which ought to be
     * {@link RubyHash Hash} to create a new <code>State</code> instance
     * configured by <codes>opts</code>, something else to create an
     * unconfigured instance. If <code>opts</code> is a <code>State</code>
     * object, it is just returned.
     * @param context The current thread context
     * @param klass The receiver of the method call ({@link RubyClass} <code>State</code>)
     * @param opts The object to use as a base for the new <code>State</code>
     * @return A <code>GeneratorState</code> as determined above
     */
    @JRubyMethod(meta=true)
    public static IRubyObject from_state(ThreadContext context, IRubyObject klass, IRubyObject opts) {
        return fromState(context, opts);
    }

    @JRubyMethod(meta=true)
    public static IRubyObject generate(ThreadContext context, IRubyObject klass, IRubyObject obj, IRubyObject opts, IRubyObject io) {
        return fromState(context, opts).generate(context, obj, io);
    }

    static GeneratorState fromState(ThreadContext context, IRubyObject opts) {
        return fromState(context, RuntimeInfo.forRuntime(context.runtime), opts);
    }

    static GeneratorState fromState(ThreadContext context, RuntimeInfo info,
                                    IRubyObject opts) {
        RubyClass klass = info.generatorStateClass.get();
        if (opts != null) {
            // if the given parameter is a Generator::State, return itself
            if (klass.isInstance(opts)) return (GeneratorState)opts;

            // if the given parameter is a Hash, pass it to the instantiator
            if (context.runtime.getHash().isInstance(opts)) {
                return (GeneratorState)klass.newInstance(context, opts, Block.NULL_BLOCK);
            }
        }

        // for other values, return the safe prototype
        return (GeneratorState)info.getSafeStatePrototype(context).dup();
    }

    /**
     * <code>State#initialize(opts = {})</code>
     * <p>
     * Instantiates a new <code>State</code> object, configured by <code>opts</code>.
     * <p>
     * <code>opts</code> can have the following keys:
     *
     * <dl>
     * <dt><code>:indent</code>
     * <dd>a {@link RubyString String} used to indent levels (default: <code>""</code>)
     * <dt><code>:space</code>
     * <dd>a String that is put after a <code>':'</code> or <code>','</code>
     * delimiter (default: <code>""</code>)
     * <dt><code>:space_before</code>
     * <dd>a String that is put before a <code>":"</code> pair delimiter
     * (default: <code>""</code>)
     * <dt><code>:object_nl</code>
     * <dd>a String that is put at the end of a JSON object (default: <code>""</code>)
     * <dt><code>:array_nl</code>
     * <dd>a String that is put at the end of a JSON array (default: <code>""</code>)
     * <dt><code>:allow_nan</code>
     * <dd><code>true</code> if <code>NaN</code>, <code>Infinity</code>, and
     * <code>-Infinity</code> should be generated, otherwise an exception is
     * thrown if these values are encountered.
     * This options defaults to <code>false</code>.
     * <dt><code>:script_safe</code>
     * <dd>set to <code>true</code> if U+2028, U+2029 and forward slashes should be escaped
     * in the json output to make it safe to include in a JavaScript tag (default: <code>false</code>)
     */
    @JRubyMethod(visibility=Visibility.PRIVATE)
    public IRubyObject initialize(ThreadContext context) {
        _configure(context, null);
        return this;
    }

    @JRubyMethod(visibility=Visibility.PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject arg0) {
        _configure(context, arg0);
        return this;
    }

    @JRubyMethod
    public IRubyObject initialize_copy(ThreadContext context, IRubyObject vOrig) {
        Ruby runtime = context.runtime;
        if (!(vOrig instanceof GeneratorState)) {
            throw runtime.newTypeError(vOrig, getType());
        }
        GeneratorState orig = (GeneratorState)vOrig;
        this.indent = orig.indent;
        this.space = orig.space;
        this.spaceBefore = orig.spaceBefore;
        this.objectNl = orig.objectNl;
        this.arrayNl = orig.arrayNl;
        this.asJSON = orig.asJSON;
        this.maxNesting = orig.maxNesting;
        this.allowNaN = orig.allowNaN;
        this.asciiOnly = orig.asciiOnly;
        this.quirksMode = orig.quirksMode;
        this.scriptSafe = orig.scriptSafe;
        this.strict = orig.strict;
        this.bufferInitialLength = orig.bufferInitialLength;
        this.depth = orig.depth;
        return this;
    }

    /**
     * Generates a valid JSON document from object <code>obj</code> and returns
     * the result. If no valid JSON document can be created this method raises
     * a GeneratorError exception.
     */
    @JRubyMethod(alias="generate_new")
    public IRubyObject generate(ThreadContext context, IRubyObject obj, IRubyObject io) {
        IRubyObject result = Generator.generateJson(context, obj, this, io);
        RuntimeInfo info = RuntimeInfo.forRuntime(context.runtime);
        if (!(result instanceof RubyString)) {
            return result;
        }

        RubyString resultString = result.convertToString();
        if (resultString.getEncoding() != UTF8Encoding.INSTANCE) {
            if (resultString.isFrozen()) {
                resultString = resultString.strDup(context.runtime);
            }
            resultString.setEncoding(UTF8Encoding.INSTANCE);
            resultString.clearCodeRange();
        }

        return resultString;
    }

    @JRubyMethod(alias="generate_new")
    public IRubyObject generate(ThreadContext context, IRubyObject obj) {
        return generate(context, obj, context.nil);
    }

    @JRubyMethod(name="[]")
    public IRubyObject op_aref(ThreadContext context, IRubyObject vName) {
        String name = vName.asJavaString();
        if (getMetaClass().isMethodBound(name, true)) {
            return send(context, vName, Block.NULL_BLOCK);
        } else {
            IRubyObject value = getInstanceVariables().getInstanceVariable("@" + name);
            return value == null ? context.nil : value;
        }
    }

    @JRubyMethod(name="[]=")
    public IRubyObject op_aset(ThreadContext context, IRubyObject vName, IRubyObject value) {
        String name = vName.asJavaString();
        String nameWriter = name + "=";
        if (getMetaClass().isMethodBound(nameWriter, true)) {
            return send(context, context.runtime.newString(nameWriter), value, Block.NULL_BLOCK);
        } else {
            getInstanceVariables().setInstanceVariable("@" + name, value);
        }
        return context.nil;
    }

    public ByteList getIndent() {
        return indent;
    }

    @JRubyMethod(name="indent")
    public RubyString indent_get(ThreadContext context) {
        return context.runtime.newString(indent);
    }

    @JRubyMethod(name="indent=")
    public IRubyObject indent_set(ThreadContext context, IRubyObject indent) {
        this.indent = prepareByteList(context, indent);
        return indent;
    }

    public ByteList getSpace() {
        return space;
    }

    @JRubyMethod(name="space")
    public RubyString space_get(ThreadContext context) {
        return context.runtime.newString(space);
    }

    @JRubyMethod(name="space=")
    public IRubyObject space_set(ThreadContext context, IRubyObject space) {
        this.space = prepareByteList(context, space);
        return space;
    }

    public ByteList getSpaceBefore() {
        return spaceBefore;
    }

    @JRubyMethod(name="space_before")
    public RubyString space_before_get(ThreadContext context) {
        return context.runtime.newString(spaceBefore);
    }

    @JRubyMethod(name="space_before=")
    public IRubyObject space_before_set(ThreadContext context,
                                        IRubyObject spaceBefore) {
        this.spaceBefore = prepareByteList(context, spaceBefore);
        return spaceBefore;
    }

    public ByteList getObjectNl() {
        return objectNl;
    }

    @JRubyMethod(name="object_nl")
    public RubyString object_nl_get(ThreadContext context) {
        return context.runtime.newString(objectNl);
    }

    @JRubyMethod(name="object_nl=")
    public IRubyObject object_nl_set(ThreadContext context,
                                     IRubyObject objectNl) {
        this.objectNl = prepareByteList(context, objectNl);
        return objectNl;
    }

    public ByteList getArrayNl() {
        return arrayNl;
    }

    @JRubyMethod(name="array_nl")
    public RubyString array_nl_get(ThreadContext context) {
        return context.runtime.newString(arrayNl);
    }

    @JRubyMethod(name="array_nl=")
    public IRubyObject array_nl_set(ThreadContext context,
                                    IRubyObject arrayNl) {
        this.arrayNl = prepareByteList(context, arrayNl);
        return arrayNl;
    }

    public RubyProc getAsJSON() {
        return asJSON;
    }

    @JRubyMethod(name="as_json")
    public IRubyObject as_json_get(ThreadContext context) {
        return asJSON == null ? context.getRuntime().getFalse() : asJSON;
    }

    @JRubyMethod(name="as_json=")
    public IRubyObject as_json_set(ThreadContext context,
                                   IRubyObject asJSON) {
        this.asJSON = (RubyProc)TypeConverter.convertToType(asJSON, context.getRuntime().getProc(), "to_proc");
        return asJSON;
    }

    @JRubyMethod(name="check_circular?")
    public RubyBoolean check_circular_p(ThreadContext context) {
        return RubyBoolean.newBoolean(context, maxNesting != 0);
    }

    @JRubyMethod(name="max_nesting")
    public RubyInteger max_nesting_get(ThreadContext context) {
        return context.runtime.newFixnum(maxNesting);
    }

    @JRubyMethod(name="max_nesting=")
    public IRubyObject max_nesting_set(IRubyObject max_nesting) {
        maxNesting = RubyNumeric.fix2int(max_nesting);
        return max_nesting;
    }

    /**
     * Returns true if forward slashes are escaped in the json output.
     */
    public boolean scriptSafe() {
        return scriptSafe;
    }

    @JRubyMethod(name="script_safe", alias="escape_slash")
    public RubyBoolean script_safe_get(ThreadContext context) {
        return RubyBoolean.newBoolean(context, scriptSafe);
    }

    @JRubyMethod(name="script_safe=", alias="escape_slash=")
    public IRubyObject script_safe_set(IRubyObject script_safe) {
        scriptSafe = script_safe.isTrue();
        return script_safe.getRuntime().newBoolean(scriptSafe);
    }

    @JRubyMethod(name="script_safe?", alias="escape_slash?")
    public RubyBoolean script_safe_p(ThreadContext context) {
        return RubyBoolean.newBoolean(context, scriptSafe);
    }

    /**
     * Returns true if strict mode is enabled.
     */
    public boolean strict() {
        return strict;
    }

    @JRubyMethod(name={"strict","strict?"})
    public RubyBoolean strict_get(ThreadContext context) {
        return RubyBoolean.newBoolean(context, strict);
    }

    @JRubyMethod(name="strict=")
    public IRubyObject strict_set(IRubyObject isStrict) {
        strict = isStrict.isTrue();
        return isStrict.getRuntime().newBoolean(strict);
    }

    public boolean allowNaN() {
        return allowNaN;
    }

    @JRubyMethod(name="allow_nan?")
    public RubyBoolean allow_nan_p(ThreadContext context) {
        return RubyBoolean.newBoolean(context, allowNaN);
    }

    public boolean asciiOnly() {
        return asciiOnly;
    }

    @JRubyMethod(name="ascii_only?")
    public RubyBoolean ascii_only_p(ThreadContext context) {
        return RubyBoolean.newBoolean(context, asciiOnly);
    }

    @JRubyMethod(name="buffer_initial_length")
    public RubyInteger buffer_initial_length_get(ThreadContext context) {
        return context.runtime.newFixnum(bufferInitialLength);
    }

    @JRubyMethod(name="buffer_initial_length=")
    public IRubyObject buffer_initial_length_set(IRubyObject buffer_initial_length) {
        int newLength = RubyNumeric.fix2int(buffer_initial_length);
        if (newLength > 0) bufferInitialLength = newLength;
        return buffer_initial_length;
    }

    public int getDepth() {
        return depth;
    }

    @JRubyMethod(name="depth")
    public RubyInteger depth_get(ThreadContext context) {
        return context.runtime.newFixnum(depth);
    }

    @JRubyMethod(name="depth=")
    public IRubyObject depth_set(IRubyObject vDepth) {
        depth = RubyNumeric.fix2int(vDepth);
        return vDepth;
    }

    private ByteList prepareByteList(ThreadContext context, IRubyObject value) {
        RubyString str = value.convertToString();
        if (str.getEncoding() != UTF8Encoding.INSTANCE) {
            str = (RubyString)str.encode(context, context.runtime.getEncodingService().convertEncodingToRubyEncoding(UTF8Encoding.INSTANCE));
        }
        return str.getByteList().dup();
    }

    /**
     * <code>State#configure(opts)</code>
     *
     * <p>Configures this State instance with the {@link RubyHash Hash}
     * <code>opts</code>, and returns itself.
     * @param vOpts The options hash
     * @return The receiver
     */
  @JRubyMethod(visibility=Visibility.PRIVATE)
    public IRubyObject _configure(ThreadContext context, IRubyObject vOpts) {
        OptionsReader opts = new OptionsReader(context, vOpts);

        ByteList indent = opts.getString("indent");
        if (indent != null) this.indent = indent;

        ByteList space = opts.getString("space");
        if (space != null) this.space = space;

        ByteList spaceBefore = opts.getString("space_before");
        if (spaceBefore != null) this.spaceBefore = spaceBefore;

        ByteList arrayNl = opts.getString("array_nl");
        if (arrayNl != null) this.arrayNl = arrayNl;

        this.asJSON = opts.getProc("as_json");

        ByteList objectNl = opts.getString("object_nl");
        if (objectNl != null) this.objectNl = objectNl;

        maxNesting = opts.getInt("max_nesting", DEFAULT_MAX_NESTING);
        allowNaN   = opts.getBool("allow_nan",  DEFAULT_ALLOW_NAN);
        asciiOnly  = opts.getBool("ascii_only", DEFAULT_ASCII_ONLY);
        scriptSafe = opts.getBool("script_safe", DEFAULT_SCRIPT_SAFE);
        if (!scriptSafe) {
            scriptSafe = opts.getBool("escape_slash", DEFAULT_SCRIPT_SAFE);
        }
        strict = opts.getBool("strict", DEFAULT_STRICT);
        bufferInitialLength = opts.getInt("buffer_initial_length", DEFAULT_BUFFER_INITIAL_LENGTH);

        depth = opts.getInt("depth", 0);

        return this;
    }

    /**
     * <code>State#to_h()</code>
     *
     * <p>Returns the configuration instance variables as a hash, that can be
     * passed to the configure method.
     * @return the hash
     */
    @JRubyMethod(alias = "to_hash")
    public RubyHash to_h(ThreadContext context) {
        Ruby runtime = context.runtime;
        RubyHash result = RubyHash.newHash(runtime);

        result.op_aset(context, runtime.newSymbol("indent"), indent_get(context));
        result.op_aset(context, runtime.newSymbol("space"), space_get(context));
        result.op_aset(context, runtime.newSymbol("space_before"), space_before_get(context));
        result.op_aset(context, runtime.newSymbol("object_nl"), object_nl_get(context));
        result.op_aset(context, runtime.newSymbol("array_nl"), array_nl_get(context));
        result.op_aset(context, runtime.newSymbol("as_json"), as_json_get(context));
        result.op_aset(context, runtime.newSymbol("allow_nan"), allow_nan_p(context));
        result.op_aset(context, runtime.newSymbol("ascii_only"), ascii_only_p(context));
        result.op_aset(context, runtime.newSymbol("max_nesting"), max_nesting_get(context));
        result.op_aset(context, runtime.newSymbol("script_safe"), script_safe_get(context));
        result.op_aset(context, runtime.newSymbol("strict"), strict_get(context));
        result.op_aset(context, runtime.newSymbol("depth"), depth_get(context));
        result.op_aset(context, runtime.newSymbol("buffer_initial_length"), buffer_initial_length_get(context));
        for (String name: getInstanceVariableNameList()) {
            result.op_aset(context, runtime.newSymbol(name.substring(1)), getInstanceVariables().getInstanceVariable(name));
        }
        return result;
    }

    public int increaseDepth(ThreadContext context) {
        depth++;
        checkMaxNesting(context);
        return depth;
    }

    public int decreaseDepth() {
        return --depth;
    }

    /**
     * Checks if the current depth is allowed as per this state's options.
     * @param context The current context
     */
    private void checkMaxNesting(ThreadContext context) {
        if (maxNesting != 0 && depth > maxNesting) {
            depth--;
            throw Utils.newException(context, Utils.M_NESTING_ERROR, "nesting of " + depth + " is too deep");
        }
    }
}
