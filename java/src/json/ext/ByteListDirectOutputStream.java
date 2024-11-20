package json.ext;

import org.jcodings.Encoding;
import org.jruby.util.ByteList;

import java.io.ByteArrayOutputStream;

public class ByteListDirectOutputStream extends ByteArrayOutputStream {
    ByteListDirectOutputStream(int size) {
        super(size);
    }

    public ByteList toByteListDirect(Encoding encoding) {
        return new ByteList(buf, 0, count, encoding, false);
    }
}
