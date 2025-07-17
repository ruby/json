package json.ext;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

interface EscapeScanner {
    static class State {
        byte[] ptrBytes;
        int ptr;
        int len;
        int pos;
        int beg;
        int ch;
    }

    static class VectorSupport {
        private static String VECTORIZED_ESCAPE_SCANNER_CLASS = "json.ext.VectorizedEscapeScanner";
        private static String VECTORIZED_SCANNER_PROP = "json.enableVectorizedEscapeScanner";
        private static String VECTORIZED_SCANNER_DEFAULT = "false";
        static final EscapeScanner VECTORIZED_ESCAPE_SCANNER;

        static {
            EscapeScanner scanner = null;
            String enableVectorizedScanner = System.getProperty(VECTORIZED_SCANNER_PROP, VECTORIZED_SCANNER_DEFAULT);
            if ("true".equalsIgnoreCase(enableVectorizedScanner) || "1".equalsIgnoreCase(enableVectorizedScanner)) {
                try {
                    Class<?> vectorEscapeScannerClass = EscapeScanner.class.getClassLoader().loadClass(VECTORIZED_ESCAPE_SCANNER_CLASS);
                    Constructor<?> vectorizedEscapeScannerConstructor = vectorEscapeScannerClass.getDeclaredConstructor();
                    scanner = (EscapeScanner) vectorizedEscapeScannerConstructor.newInstance();
                } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    // Fallback to the ScalarEscapeScanner if we cannot load the VectorizedEscapeScanner.
                    System.err.println("Failed to load VectorizedEscapeScanner, falling back to ScalarEscapeScanner:");
                    e.printStackTrace();
                    scanner = null;
                }
            } else {
                System.err.println("VectorizedEscapeScanner disabled.");
            }
            VECTORIZED_ESCAPE_SCANNER = scanner;
        }
    }

    boolean scan(EscapeScanner.State state) throws java.io.IOException;

    default State createState(byte[] ptrBytes, int ptr, int len, int beg) {
        State state = new State();
        state.ptrBytes = ptrBytes;
        state.ptr = ptr;
        state.len = len;
        state.beg = beg;
        state.pos = 0; // Start scanning from the beginning of the segment
        return state;
    }

    public static EscapeScanner basicScanner() {
        if (VectorSupport.VECTORIZED_ESCAPE_SCANNER != null) {
            return VectorSupport.VECTORIZED_ESCAPE_SCANNER;
        }

        return new ScalarEscapeScanner(StringEncoder.ESCAPE_TABLE);
    }

    public static EscapeScanner create(byte[] escapeTable) {
        return new ScalarEscapeScanner(escapeTable);
    }

    public static class BasicScanner implements EscapeScanner {
        @Override
        public boolean scan(EscapeScanner.State state) throws java.io.IOException {
            while (state.pos < state.len) {
                state.ch = Byte.toUnsignedInt(state.ptrBytes[state.ptr + state.pos]);
                if (state.ch >= 0 && (state.ch < ' ' || state.ch == '\"' || state.ch == '\\')) {
                    return true;
                }
                state.pos++;
            }
            return false;
        }
    }

    public static class ScalarEscapeScanner implements EscapeScanner {
        private final byte[] escapeTable;

        public ScalarEscapeScanner(byte[] escapeTable) {
            this.escapeTable = escapeTable;
        }

        @Override
        public boolean scan(EscapeScanner.State state) throws java.io.IOException {
            while (state.pos < state.len) {
                state.ch = Byte.toUnsignedInt(state.ptrBytes[state.ptr + state.pos]);
                int ch_len = escapeTable[state.ch];
                if (ch_len > 0) {
                    return true;
                }
                state.pos++;
            }
            return false;
        }

    }
}
