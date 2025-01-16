/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBasicObject;
import org.jruby.RubyBignum;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyFixnum;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.IOOutputStream;
import org.jruby.util.TypeConverter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class Generator {

    private static final int IO_BUFFER_SIZE = 8192;

    private Generator() {
        throw new RuntimeException();
    }

    /**
     * Encodes the given object as a JSON string, using the given handler.
     */
    static <T extends IRubyObject> RubyString generateJson(ThreadContext context, T object, Handler<? super T> handler) {
        Session session = new Session(null);
        return handler.generateNew(context, session, object);
    }

    static <T extends IRubyObject> RubyString generateJson(ThreadContext context, T object, Handler<? super T> handler, IRubyObject arg0) {
        Session session = new Session(arg0);
        return handler.generateNew(context, session, object);
    }

    /**
     * Encodes the given object as a JSON string, detecting the appropriate handler
     * for the given object.
     */
    static <T extends IRubyObject> RubyString generateJson(ThreadContext context, T object) {
        Handler<? super T> handler = getHandlerFor(context.runtime, object);
        return generateJson(context, object, handler);
    }

    static <T extends IRubyObject> RubyString generateJson(ThreadContext context, T object, IRubyObject arg0) {
        Handler<? super T> handler = getHandlerFor(context.runtime, object);
        return generateJson(context, object, handler, arg0);
    }

    /**
     * Encodes the given object as a JSON string, using the appropriate
     * handler if one is found or calling #to_json if not.
     */
    public static <T extends IRubyObject> IRubyObject
            generateJson(ThreadContext context, T object,
                         GeneratorState config, IRubyObject io) {
        Session session = new Session(config);
        Handler<? super T> handler = getHandlerFor(context.runtime, object);

        if (io.isNil()) {
            return handler.generateNew(context, session, object);
        }

        BufferedOutputStream buffer = new BufferedOutputStream(new IOOutputStream(io), IO_BUFFER_SIZE);
        handler.generateToBuffer(context, session, object, buffer);
        return io;
    }

    /**
     * Returns the best serialization handler for the given object.
     */
    // Java's generics can't handle this satisfactorily, so I'll just leave
    // the best I could get and ignore the warnings
    @SuppressWarnings("unchecked")
    private static <T extends IRubyObject> Handler<? super T> getHandlerFor(Ruby runtime, T object) {
        switch (((RubyBasicObject) object).getNativeClassIndex()) {
            case NIL    : return NIL_HANDLER;
            case TRUE   : return (Handler<T>) TRUE_HANDLER;
            case FALSE  : return (Handler<T>) FALSE_HANDLER;
            case FLOAT  : return (Handler<T>) FLOAT_HANDLER;
            case FIXNUM : return (Handler<T>) FIXNUM_HANDLER;
            case BIGNUM : return (Handler<T>) BIGNUM_HANDLER;
            case STRING :
                if (Helpers.metaclass(object) != runtime.getString()) break;
                return (Handler<T>) STRING_HANDLER;
            case ARRAY  :
                if (Helpers.metaclass(object) != runtime.getArray()) break;
                return (Handler<T>) ARRAY_HANDLER;
            case HASH   :
                if (Helpers.metaclass(object) != runtime.getHash()) break;
                return (Handler<T>) HASH_HANDLER;
        }
        return GENERIC_HANDLER;
    }

    private static <T extends IRubyObject> void generateFor(ThreadContext context, Session session, T object, OutputStream buffer) throws IOException {
        switch (((RubyBasicObject) object).getNativeClassIndex()) {
            case NIL    : buffer.write(NULL_STRING); return;
            case TRUE   : buffer.write(TRUE_STRING); return;
            case FALSE  : buffer.write(FALSE_STRING); return;
            case FLOAT  : generateFloat(context, session, (RubyFloat) object, buffer); return;
            case FIXNUM : generateFixnum(session, (RubyFixnum) object, buffer); return;
            case BIGNUM : generateBignum((RubyBignum) object, buffer); return;
            case STRING :
                if (Helpers.metaclass(object) != context.runtime.getString()) break;
                generateString(context, session, (RubyString) object, buffer); return;
            case ARRAY  :
                if (Helpers.metaclass(object) != context.runtime.getArray()) break;
                generateArray(context, session, (RubyArray<IRubyObject>) object, buffer); return;
            case HASH   :
                if (Helpers.metaclass(object) != context.runtime.getHash()) break;
                generateHash(context, session, (RubyHash) object, buffer); return;
        }
        generateGeneric(context, session, object, buffer);
    }

    /* Generator context */

    /**
     * A class that concentrates all the information that is shared by
     * generators working on a single session.
     *
     * <p>A session is defined as the process of serializing a single root
     * object; any handler directly called by container handlers (arrays and
     * hashes/objects) shares this object with its caller.
     *
     * <p>Note that anything called indirectly (via {@link #GENERIC_HANDLER})
     * won't be part of the session.
     */
    static class Session {
        private static final int MAX_LONG_CHARS = Long.toString(Long.MIN_VALUE).length();
        private GeneratorState state;
        private IRubyObject possibleState;
        private RuntimeInfo info;
        private StringEncoder stringEncoder;
        private byte[] charBytes;

        Session(GeneratorState state) {
            this.state = state;
        }

        Session(IRubyObject possibleState) {
            this.possibleState = possibleState == null || possibleState.isNil()
                    ? null : possibleState;
        }

        public GeneratorState getState(ThreadContext context) {
            if (state == null) {
                state = GeneratorState.fromState(context, getInfo(context), possibleState);
            }
            return state;
        }

        public RuntimeInfo getInfo(ThreadContext context) {
            if (info == null) info = RuntimeInfo.forRuntime(context.runtime);
            return info;
        }

        public byte[] getCharBytes() {
            byte[] charBytes = this.charBytes;
            if (charBytes == null) charBytes = this.charBytes = new byte[MAX_LONG_CHARS];
            return charBytes;
        }

        public StringEncoder getStringEncoder(ThreadContext context) {
            if (stringEncoder == null) {
                GeneratorState state = getState(context);
                stringEncoder = state.asciiOnly() ?
                        new StringEncoderAsciiOnly(state.scriptSafe()) :
                        new StringEncoder(state.scriptSafe());
            }
            return stringEncoder;
        }
    }


    /* Handler base classes */

    private static abstract class Handler<T extends IRubyObject> {
        /**
         * Returns an estimative of how much space the serialization of the
         * given object will take. Used for allocating enough buffer space
         * before invoking other methods.
         */
        int guessSize(ThreadContext context, Session session, T object) {
            return 4;
        }

        RubyString generateNew(ThreadContext context, Session session, T object) {
            ByteListDirectOutputStream buffer = new ByteListDirectOutputStream(guessSize(context, session, object));
            generateToBuffer(context, session, object, buffer);
            return RubyString.newString(context.runtime, buffer.toByteListDirect(UTF8Encoding.INSTANCE));
        }

        void generateToBuffer(ThreadContext context, Session session, T object, OutputStream buffer)  {
            try {
                generate(context, session, object, buffer);
                buffer.flush();
            } catch (IOException ioe) {
                throw context.runtime.newIOErrorFromException(ioe);
            }
        }

        abstract void generate(ThreadContext context, Session session, T object, OutputStream buffer) throws IOException;
    }

    /**
     * A handler that returns a fixed keyword regardless of the passed object.
     */
    private static class KeywordHandler<T extends IRubyObject>
            extends Handler<T> {
        private final byte[] keyword;

        private KeywordHandler(byte[] keyword) {
            this.keyword = keyword;
        }

        @Override
        int guessSize(ThreadContext context, Session session, T object) {
            return keyword.length;
        }

        @Override
        RubyString generateNew(ThreadContext context, Session session, T object) {
            return RubyString.newStringShared(context.runtime, keyword);
        }

        @Override
        void generate(ThreadContext context, Session session, T object, OutputStream buffer) throws IOException {
            buffer.write(keyword);
        }
    }


    /* Handlers */

    static final Handler<RubyBignum> BIGNUM_HANDLER = new BignumHandler();
    static final Handler<RubyFixnum> FIXNUM_HANDLER = new FixnumHandler();
    static final Handler<RubyFloat> FLOAT_HANDLER = new FloatHandler();
    static final Handler<RubyArray<IRubyObject>> ARRAY_HANDLER = new ArrayHandler();
    static final Handler<RubyHash> HASH_HANDLER = new HashHandler();
    static final Handler<RubyString> STRING_HANDLER = new StringHandler();
    private static final byte[] TRUE_STRING = "true".getBytes();
    static final Handler<RubyBoolean> TRUE_HANDLER = new KeywordHandler<>(TRUE_STRING);
    private static final byte[] FALSE_STRING = "false".getBytes();
    static final Handler<RubyBoolean> FALSE_HANDLER = new KeywordHandler<>(FALSE_STRING);
    private static final byte[] NULL_STRING = "null".getBytes();
    static final Handler<IRubyObject> NIL_HANDLER = new KeywordHandler<>(NULL_STRING);

    /**
     * The default handler (<code>Object#to_json</code>): coerces the object
     * to string using <code>#to_s</code>, and serializes that string.
     */
    static final Handler<IRubyObject> OBJECT_HANDLER = new ObjectHandler();

    /**
     * A handler that simply calls <code>#to_json(state)</code> on the
     * given object.
     */
    static final Handler<IRubyObject> GENERIC_HANDLER = new GenericHandler();

    private static class BignumHandler extends Handler<RubyBignum> {
        @Override
        void generate(ThreadContext context, Session session, RubyBignum object, OutputStream buffer) throws IOException {
            generateBignum(object, buffer);
        }
    }

    private static void generateBignum(RubyBignum object, OutputStream buffer) throws IOException {
        BigInteger bigInt = object.getValue();
        buffer.write(bigInt.toString().getBytes(UTF_8));
    }

    private static class FixnumHandler extends Handler<RubyFixnum> {
        @Override
        void generate(ThreadContext context, Session session, RubyFixnum object, OutputStream buffer) throws IOException {
            generateFixnum(session, object, buffer);
        }
    }

    private static void generateFixnum(Session session, RubyFixnum object, OutputStream buffer) throws IOException {
        long i = object.getLongValue();
        if (i == 0) {
            buffer.write('0');
        } else if (i == Long.MIN_VALUE) {
            // special case to avoid -i
            buffer.write(MIN_VALUE_BYTES_RADIX_10);
        } else {
            byte[] charBytes = session.getCharBytes();
            appendFixnum(buffer, charBytes, i);
        }
    }

    private static final byte[] MIN_VALUE_BYTES_RADIX_10 = ByteList.plain(Long.toString(Long.MIN_VALUE, 10));

    // C: fbuffer_append_long
    static void appendFixnum(OutputStream buffer, byte[] buf, long number) throws IOException {
        int end = buf.length;
        int len = fltoa(number, buf, end);
        buffer.write(buf, end - len, len);
    }

    static int fltoa(long number, byte[] buf, int end) {
        boolean negative = number < 0;
        int tmp = end;

        if (negative) number = -number;
        do {
            buf[--tmp] = (byte) ((int) (number % 10) + '0');
        } while ((number /= 10) != 0);
        if (negative) buf[--tmp] = '-';
        return end - tmp;
    }

    private static class FloatHandler extends Handler<RubyFloat> {
        @Override
        void generate(ThreadContext context, Session session, RubyFloat object, OutputStream buffer) throws IOException {
            generateFloat(context, session, object, buffer);
        }
    }

    private static void generateFloat(ThreadContext context, Session session, RubyFloat object, OutputStream buffer) throws IOException {
        double value = object.getValue();

        if (Double.isInfinite(value) || Double.isNaN(value)) {
            if (!session.getState(context).allowNaN()) {
                throw Utils.buildGeneratorError(context, object, object + " not allowed in JSON").toThrowable();
            }
        }

        buffer.write(Double.toString(value).getBytes(UTF_8));
    }

    private static final byte[] EMPTY_ARRAY_BYTES = "[]".getBytes();
    private static class ArrayHandler extends Handler<RubyArray<IRubyObject>> {
        @Override
        int guessSize(ThreadContext context, Session session, RubyArray<IRubyObject> object) {
            GeneratorState state = session.getState(context);
            int depth = state.getDepth();
            int perItem =
                    4                                           // prealloc
                            + (depth + 1) * state.getIndent().length()  // indent
                            + 1 + state.getArrayNl().length();          // ',' arrayNl
            return 2 + object.size() * perItem;
        }

        @Override
        void generate(ThreadContext context, Session session, RubyArray<IRubyObject> object, OutputStream buffer) throws IOException {
            generateArray(context, session, object, buffer);
        }
    }

    private static void generateArray(ThreadContext context, Session session, RubyArray<IRubyObject> object, OutputStream buffer) throws IOException {
        GeneratorState state = session.getState(context);
        int depth = state.increaseDepth(context);

        if (object.isEmpty()) {
            buffer.write(EMPTY_ARRAY_BYTES);
            state.decreaseDepth();
            return;
        }

        ByteList indentUnit = state.getIndent();
        ByteList arrayNl = state.getArrayNl();
        byte[] arrayNLBytes = arrayNl.unsafeBytes();
        int arrayNLBegin = arrayNl.begin();
        int arrayNLSize = arrayNl.realSize();
        boolean arrayNLEmpty = arrayNLSize == 0;

        buffer.write('[');
        buffer.write(arrayNLBytes, arrayNLBegin, arrayNLSize);

        int length = object.getLength();
        for (int i = 0; i < length; i++) {
            IRubyObject element = object.eltInternal(i);
            if (i > 0) {
                buffer.write(',');
                if (!arrayNLEmpty) {
                    buffer.write(arrayNLBytes, arrayNLBegin, arrayNLSize);
                }
            }
            Utils.repeatWrite(buffer, indentUnit, depth);
            generateFor(context, session, element, buffer);
        }

        int oldDepth = state.decreaseDepth();
        if (!arrayNLEmpty) {
            buffer.write(arrayNLBytes, arrayNLBegin, arrayNLSize);
            Utils.repeatWrite(buffer, indentUnit, oldDepth);
        }

        buffer.write((byte) ']');
    }

    private static final byte[] EMPTY_HASH_BYTES = "{}".getBytes();
    private static class HashHandler extends Handler<RubyHash> {
        @Override
        int guessSize(ThreadContext context, Session session, RubyHash object) {
            GeneratorState state = session.getState(context);
            int perItem =
                    12    // key, colon, comma
                            + (state.getDepth() + 1) * state.getIndent().length()
                            + state.getSpaceBefore().length()
                            + state.getSpace().length();
            return 2 + object.size() * perItem;
        }

        @Override
        void generate(ThreadContext context, final Session session, RubyHash object, final OutputStream buffer) throws IOException {
            generateHash(context, session, object, buffer);
        }
    }

    private static void generateHash(ThreadContext context, Session session, RubyHash object, OutputStream buffer) throws IOException {
        final GeneratorState state = session.getState(context);
        final int depth = state.increaseDepth(context);

        if (object.isEmpty()) {
            buffer.write(EMPTY_HASH_BYTES);
            state.decreaseDepth();
            return;
        }

        final ByteList objectNl = state.getObjectNl();
        byte[] objectNLBytes = objectNl.unsafeBytes();
        final byte[] indent = Utils.repeat(state.getIndent(), depth);
        final ByteList spaceBefore = state.getSpaceBefore();
        final ByteList space = state.getSpace();

        buffer.write('{');
        buffer.write(objectNLBytes);

        boolean firstPair = true;
        for (RubyHash.RubyHashEntry entry : (Set<RubyHash.RubyHashEntry>) object.directEntrySet()) {
            processEntry(context, session, buffer, entry, firstPair, objectNl, indent, spaceBefore, space);
            firstPair = false;
        }
        int oldDepth = state.decreaseDepth();
        if (!firstPair && !objectNl.isEmpty()) {
            buffer.write(objectNLBytes);
        }
        Utils.repeatWrite(buffer, state.getIndent(), oldDepth);
        buffer.write('}');
    }

    private static void processEntry(ThreadContext context, Session session, OutputStream buffer, RubyHash.RubyHashEntry entry, boolean firstPair, ByteList objectNl, byte[] indent, ByteList spaceBefore, ByteList space) {
        IRubyObject key = (IRubyObject) entry.getKey();
        IRubyObject value = (IRubyObject) entry.getValue();

        try {
            if (!firstPair) {
                buffer.write((byte) ',');
                buffer.write(objectNl.unsafeBytes());
            }
            if (!objectNl.isEmpty()) buffer.write(indent);

            Ruby runtime = context.runtime;

            IRubyObject keyStr;
            RubyClass keyClass = key.getType();
            if (key instanceof RubyString) {
                if (keyClass == runtime.getString()) {
                    keyStr = key;
                } else {
                    keyStr = key.callMethod(context, "to_s");
                }
            } else if (keyClass == runtime.getSymbol()) {
                keyStr = ((RubySymbol) key).id2name(context);
            } else {
                keyStr = TypeConverter.convertToType(key, runtime.getString(), "to_s");
            }

            if (keyStr.getMetaClass() == runtime.getString()) {
                generateString(context, session, (RubyString) keyStr, buffer);
            } else {
                Utils.ensureString(keyStr);
                generateFor(context, session, keyStr, buffer);
            }

            buffer.write(spaceBefore.unsafeBytes());
            buffer.write((byte) ':');
            buffer.write(space.unsafeBytes());

            generateFor(context, session, value, buffer);
        } catch (Throwable t) {
            Helpers.throwException(t);
        }
    }

    private static class StringHandler extends Handler<RubyString> {
        @Override
        int guessSize(ThreadContext context, Session session, RubyString object) {
            // for most applications, most strings will be just a set of
            // printable ASCII characters without any escaping, so let's
            // just allocate enough space for that + the quotes
            return 2 + object.getByteList().length();
        }

        @Override
        void generate(ThreadContext context, Session session, RubyString object, OutputStream buffer) throws IOException {
            generateString(context, session, object, buffer);
        }
    }

    private static void generateString(ThreadContext context, Session session, RubyString object, OutputStream buffer) throws IOException {
        session.getStringEncoder(context).generate(context, object, buffer);
    }

    private static class ObjectHandler extends Handler<IRubyObject> {
        @Override
        RubyString generateNew(ThreadContext context, Session session, IRubyObject object) {
            return generateObjectNew(context, session, object);
        }

        @Override
        void generate(ThreadContext context, Session session, IRubyObject object, OutputStream buffer) throws IOException {
            generateObject(context, session, object, buffer);
        }
    }

    private static RubyString generateObjectNew(ThreadContext context, Session session, IRubyObject object) {
        RubyString str = object.asString();
        return STRING_HANDLER.generateNew(context, session, str);
    }

    private static void generateObject(ThreadContext context, Session session, IRubyObject object, OutputStream buffer) throws IOException {
        generateString(context, session, object.asString(), buffer);
    }

    private static class GenericHandler extends Handler<IRubyObject> {
        @Override
        RubyString generateNew(ThreadContext context, Session session, IRubyObject object) {
            return generateGenericNew(context, session, object);
        }

        @Override
        void generate(ThreadContext context, Session session, IRubyObject object, OutputStream buffer) throws IOException {
            generateGeneric(context, session, object, buffer);
        }
    }

    private static RubyString generateGenericNew(ThreadContext context, Session session, IRubyObject object) {
        GeneratorState state = session.getState(context);
        if (state.strict()) {
            throw Utils.buildGeneratorError(context, object, object + " not allowed in JSON").toThrowable();
        } else if (object.respondsTo("to_json")) {
            IRubyObject result = object.callMethod(context, "to_json", state);
            if (result instanceof RubyString) return (RubyString) result;
            throw context.runtime.newTypeError("to_json must return a String");
        } else {
            return OBJECT_HANDLER.generateNew(context, session, object);
        }
    }

    private static void generateGeneric(ThreadContext context, Session session, IRubyObject object, OutputStream buffer) throws IOException {
        RubyString result = generateGenericNew(context, session, object);
        ByteList bytes = result.getByteList();
        buffer.write(bytes.unsafeBytes(), bytes.begin(), bytes.length());
    }
}
