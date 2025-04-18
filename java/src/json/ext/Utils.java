/*
 * This code is copyrighted work by Daniel Luz <dev at mernen dot com>.
 *
 * Distributed under the Ruby license: https://www.ruby-lang.org/en/about/license.txt
 */
package json.ext;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyException;
import org.jruby.RubyString;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Library of miscellaneous utility functions
 */
final class Utils {
    public static final String M_GENERATOR_ERROR = "GeneratorError";
    public static final String M_NESTING_ERROR = "NestingError";
    public static final String M_PARSER_ERROR = "ParserError";

    private Utils() {
        throw new RuntimeException();
    }

    /**
     * Safe {@link RubyArray} type-checking.
     * Returns the given object if it is an <code>Array</code>,
     * or throws an exception if not.
     * @param object The object to test
     * @return The given object if it is an <code>Array</code>
     * @throws RaiseException <code>TypeError</code> if the object is not
     *                        of the expected type
     */
    static RubyArray ensureArray(IRubyObject object) throws RaiseException {
        if (object instanceof RubyArray) return (RubyArray)object;
        Ruby runtime = object.getRuntime();
        throw runtime.newTypeError(object, runtime.getArray());
    }

    static RubyString ensureString(IRubyObject object) throws RaiseException {
        if (object instanceof RubyString) return (RubyString)object;
        Ruby runtime = object.getRuntime();
        throw runtime.newTypeError(object, runtime.getString());
    }

    static RaiseException newException(ThreadContext context,
                                       String className, String message) {
        return newException(context, className,
                            context.runtime.newString(message));
    }

    static RaiseException newException(ThreadContext context,
                                       String className, RubyString message) {
        RuntimeInfo info = RuntimeInfo.forRuntime(context.runtime);
        RubyClass klazz = info.jsonModule.get().getClass(className);
        RubyException excptn = (RubyException)klazz.newInstance(context, message, Block.NULL_BLOCK);
        return excptn.toThrowable();
    }

    static RubyException buildGeneratorError(ThreadContext context, IRubyObject invalidObject, RubyString message) {
        RuntimeInfo info = RuntimeInfo.forRuntime(context.runtime);
        RubyClass klazz = info.jsonModule.get().getClass(M_GENERATOR_ERROR);
        RubyException excptn = (RubyException)klazz.newInstance(context, message, Block.NULL_BLOCK);
        excptn.setInstanceVariable("@invalid_object", invalidObject);
        return excptn;
    }

    static RubyException buildGeneratorError(ThreadContext context, IRubyObject invalidObject, String message) {
        return buildGeneratorError(context, invalidObject, context.runtime.newString(message));
    }

    static byte[] repeat(ByteList a, int n) {
        return repeat(a.unsafeBytes(), a.begin(), a.length(), n);
    }

    static byte[] repeat(byte[] a, int begin, int length, int n) {
        if (length == 0) return ByteList.NULL_ARRAY;

        if (n == 1 && begin == 0 && length == a.length) return a;

        int resultLen = length * n;
        byte[] result = new byte[resultLen];
        for (int pos = 0; pos < resultLen; pos += length) {
            System.arraycopy(a, begin, result, pos, length);
        }

        return result;
    }

    static void repeatWrite(OutputStream out, ByteList a, int n) throws IOException {
        byte[] bytes = a.unsafeBytes();
        int begin = a.begin();
        int length = a.length();

        for (int i = 0; i < n; i++) {
            out.write(bytes, begin, length);
        }
    }
}
