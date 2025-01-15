package json.ext;

import org.jcodings.Encoding;
import org.jruby.util.ByteList;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

public class ByteListDirectOutputStream extends OutputStream {
    private byte[] buffer;
    private int length;

    ByteListDirectOutputStream(int size) {
        buffer = new byte[size];
    }

    public ByteList toByteListDirect(Encoding encoding) {
        return new ByteList(buffer, 0, length, encoding, false);
    }

    @Override
    public void write(int b) throws IOException {
        int myLength = this.length;
        grow(this, buffer, myLength, 1);
        buffer[length++] = (byte) b;
    }

    @Override
    public void write(byte[] bytes, int start, int length) throws IOException {
        int myLength = this.length;
        grow(this, buffer, myLength, length);
        System.arraycopy(bytes, start, buffer, myLength, length);
        this.length = myLength + length;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        int myLength = this.length;
        int moreLength = bytes.length;
        grow(this, buffer, myLength, moreLength);
        System.arraycopy(bytes, 0, buffer, myLength, moreLength);
        this.length = myLength + moreLength;
    }

    private static void grow(ByteListDirectOutputStream self, byte[] buffer, int myLength, int more) {
        int newLength = myLength + more;
        int myCapacity = buffer.length;
        int diff = newLength - myCapacity;
        if (diff > 0) {
            // grow to double current length or capacity + diff, whichever is greater
            int growBy = Math.max(myLength, diff);
            self.buffer = Arrays.copyOf(self.buffer, myCapacity + growBy);
        }
    }
}
