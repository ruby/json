package json.ext;

import java.io.IOException;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

class VectorizedEscapeScanner implements EscapeScanner {
    public static EscapeScanner.ScalarEscapeScanner FALLBACK = new EscapeScanner.ScalarEscapeScanner(StringEncoder.ESCAPE_TABLE);

    @Override
    public boolean scan(State _state) throws IOException {
        VectorSpecies<Byte> species = ByteVector.SPECIES_PREFERRED;
        
        VectorizedState state = (VectorizedState) _state;

        if (state.hasMatches) {
            if (state.mask > 0) {
                return nextMatch(state);
            } else {
                state.hasMatches = false;
                state.pos = state.chunkStart + species.length();
            }
        }

        while ((state.ptr + state.pos) + species.length() < state.len) {
            ByteVector chunk = ByteVector.fromArray(species, state.ptrBytes, state.ptr + state.pos);

            // bytes are unsigned in java, so we need to check for negative values
            // to determine if we have a byte that is less than 0 (>= 128).
            VectorMask<Byte> nonNegative = ByteVector.zero(species).lt(chunk);

            VectorMask<Byte> tooLowOrDblQuote = chunk.lanewise(VectorOperators.XOR, ByteVector.broadcast(species, 2))
                .lt(ByteVector.broadcast(species, 33));

            VectorMask<Byte> needsEscape = chunk.eq(ByteVector.broadcast(species, '\\')).or(tooLowOrDblQuote).and(nonNegative);
            if (needsEscape.anyTrue()) {
                state.hasMatches = true;
                state.chunkStart = state.ptr + state.pos;
                state.mask = needsEscape.toLong();

                return nextMatch(state);
            }

            state.pos += species.length();
        }

        return FALLBACK.scan(state);
    }

    private boolean nextMatch(VectorizedState state) {
        int index = Long.numberOfTrailingZeros(state.mask);
        state.mask &= (state.mask - 1);
        state.pos = state.chunkStart + index;
        return true;
    }

    @Override
    public EscapeScanner.State createState(byte[] ptrBytes, int ptr, int len, int beg) {
        VectorizedState state = new VectorizedState();
        state.ptrBytes = ptrBytes;
        state.ptr = ptr;
        state.len = len;
        state.beg = beg;
        state.pos = 0; 
        return state;
    }

    private static class VectorizedState extends State {
        private long mask;
        private int chunkStart = 0;
        // private int lastMatchingIndex;
        private boolean hasMatches;
    }
}
