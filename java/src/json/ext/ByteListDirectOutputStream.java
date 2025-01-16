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
        int currentLength = this.length;
        int newLength = currentLength + 1;
        byte[] buffer = ensureBuffer(this, newLength);
        buffer[currentLength] = (byte) b;
        this.length = newLength;
    }

    @Override
    public void write(byte[] bytes, int start, int length) throws IOException {
        int currentLength = this.length;
        int newLength = currentLength + length;
        byte[] buffer = ensureBuffer(this, newLength);
        System.arraycopy(bytes, start, buffer, currentLength, length);
        this.length = newLength;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        int myLength = this.length;
        int moreLength = bytes.length;
        int newLength = myLength + moreLength;
        byte[] buffer = ensureBuffer(this, newLength);
        System.arraycopy(bytes, 0, buffer, myLength, moreLength);
        this.length = newLength;
    }

    private static byte[] ensureBuffer(ByteListDirectOutputStream self, int minimumLength) {
        byte[] buffer = self.buffer;
        int myCapacity = buffer.length;
        int diff = minimumLength - myCapacity;
        if (diff > 0) {
            buffer = self.buffer = grow(buffer, myCapacity, diff);
        }

        return buffer;
    }

    private static byte[] grow(byte[] oldBuffer, int myCapacity, int diff) {
        // grow to double current buffer length or capacity + diff, whichever is greater
        int newLength = myCapacity + Math.max(myCapacity, diff);
        // check overflow
        if (newLength < 0) {
            // try just diff length in case it can fit
            newLength = myCapacity + diff;
            if (newLength < 0) {
                throw new ArrayIndexOutOfBoundsException("cannot allocate array of size " + myCapacity + "+" + diff);
            }
        }
        return Arrays.copyOf(oldBuffer, newLength);
    }
}
