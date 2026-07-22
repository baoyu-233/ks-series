package org.kseco.demand;

import java.util.Objects;

/** Immutable signature prepared from an item on the server thread. */
public record StandardItemSignature(String value) {
    public StandardItemSignature {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > 512 || !isPrintableAscii(value)) {
            throw new IllegalArgumentException("item signature must contain 1-512 printable ASCII characters");
        }
    }

    private static boolean isPrintableAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return value;
    }
}
