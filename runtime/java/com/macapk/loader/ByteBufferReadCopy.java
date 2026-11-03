package com.macapk.loader;

import java.nio.ByteBuffer;

/** Only used for proven, non-escaping arraycopy reads, never as general array() emulation. */
public final class ByteBufferReadCopy {
    private ByteBufferReadCopy() {}
    private static final ThreadLocal<ByteBuffer> SOURCE = new ThreadLocal<>();

    public static byte[] array(ByteBuffer buffer) {
        byte[] copy;
        if (buffer.hasArray() || !buffer.isDirect() || buffer.isReadOnly()) copy = buffer.array();
        else {
            copy = new byte[buffer.capacity()];
            buffer.duplicate().clear().get(copy);
        }
        SOURCE.set(buffer);
        return copy;
    }

    public static int offset(ByteBuffer buffer) {
        ByteBuffer source = SOURCE.get();
        SOURCE.remove();
        java.util.Objects.requireNonNull(buffer);
        if (source != buffer) throw new UnsupportedOperationException("Buffer changed during arraycopy read");
        if (buffer.hasArray() || !buffer.isDirect() || buffer.isReadOnly()) return buffer.arrayOffset();
        return 0;
    }
}
