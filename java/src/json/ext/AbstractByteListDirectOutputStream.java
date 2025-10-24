package json.ext;

import java.io.OutputStream;

import org.jcodings.Encoding;
import org.jruby.util.ByteList;

abstract class AbstractByteListDirectOutputStream extends OutputStream {

    private static final String PROP_SEGMENTED_BUFFER = "jruby.json.useSegmentedOutputStream";
    private static final String PROP_SEGMENTED_BUFFER_DEFAULT = "true";

    private static final boolean USE_SEGMENTED_BUFFER;

    static {
        String useSegmentedOutputStream = System.getProperty(PROP_SEGMENTED_BUFFER, PROP_SEGMENTED_BUFFER_DEFAULT);
        USE_SEGMENTED_BUFFER = Boolean.parseBoolean(useSegmentedOutputStream);
    }

    public static AbstractByteListDirectOutputStream create(int estimatedSize) {
        if (USE_SEGMENTED_BUFFER) {
            return new SegmentedByteListDirectOutputStream(estimatedSize);
        } else {
            return new ByteListDirectOutputStream(estimatedSize);
        }
    }

    public abstract ByteList toByteListDirect(Encoding encoding);
}
