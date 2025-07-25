package json.ext;

import java.io.IOException;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

class VectorizedEscapeScanner implements EscapeScanner {
    private static final VectorSpecies<Byte> SP = ByteVector.SPECIES_PREFERRED;
    private static final ByteVector ZERO = ByteVector.zero(SP);
    private static final ByteVector TWO = ByteVector.broadcast(SP, 2);
    private static final ByteVector THIRTY_THREE = ByteVector.broadcast(SP, 33);
    private static final ByteVector BACKSLASH = ByteVector.broadcast(SP, '\\');

    @Override
    public boolean scan(State _st) throws IOException {
        VectorizedState state = (VectorizedState) _st;

        if (state.hasMatches) {
            if (state.mask > 0) {
                // nextMatch inlined
                int index = Long.numberOfTrailingZeros(state.mask);
                state.mask &= (state.mask - 1);
                state.pos = state.chunkStart + index;
                state.ch = Byte.toUnsignedInt(state.ptrBytes[state.ptr + state.pos]);
                return true;
            } else {
                state.hasMatches = false;
                state.pos = state.chunkStart + state.chunkLength;
            }
        }

        while (((state.ptr + state.pos) + SP.length() < state.len)) {
            ByteVector chunk = ByteVector.fromArray(SP, state.ptrBytes, state.ptr + state.pos);
            state.chunkLength = SP.length();

            // bytes are unsigned in java, so we need to check for negative values
            // to determine if we have a byte that is less than 0 (>= 128).
            VectorMask<Byte> negative = chunk.lt(ZERO);
            VectorMask<Byte> tooLowOrDblQuote = chunk.lanewise(VectorOperators.XOR, TWO).lt(THIRTY_THREE).andNot(negative);
            VectorMask<Byte> needsEscape = chunk.eq(BACKSLASH).or(tooLowOrDblQuote);
            if (needsEscape.anyTrue()) {
                state.hasMatches = true;
                state.chunkStart = state.ptr + state.pos;
                state.mask = needsEscape.toLong();

                // nextMatch - inlined
                int index = Long.numberOfTrailingZeros(state.mask);
                state.mask &= (state.mask - 1);
                state.pos = state.chunkStart + index;
                state.ch = Byte.toUnsignedInt(state.ptrBytes[state.ptr + state.pos]);

                return true;
            }

            state.pos += SP.length();
        }

        int remaining = state.len - (state.ptr + state.pos);
            for (int i=0; i<remaining; i++) {
            state.ch = Byte.toUnsignedInt(state.ptrBytes[state.ptr + state.pos]);
            int ch_len = StringEncoder.ESCAPE_TABLE[state.ch];
            if (ch_len > 0) {
                return true;
            }
            state.pos++;
        }

        return false;
    }

    // private boolean nextMatch(VectorizedState state) {
    //     int index = Long.numberOfTrailingZeros(state.mask);
    //     state.mask &= (state.mask - 1);
    //     state.pos = state.chunkStart + index;
    //     state.ch = Byte.toUnsignedInt(state.ptrBytes[state.ptr + state.pos]);
    //     return true;
    // }

    @Override
     public State createState(byte[] ptrBytes, int ptr, int len, int beg) {
        VectorizedState state = new VectorizedState();
        state.ptrBytes = ptrBytes;
        state.ptr = ptr;
        state.len = len;
        state.beg = beg;
        state.pos = 0; // Start scanning from the beginning of the segment
        return state;
    }

    private static class VectorizedState extends State {
        private long mask;
        private int chunkStart = 0;
        private boolean hasMatches;
        private int chunkLength;
    }
}
