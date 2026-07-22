package org.kseco.currency;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable request applied atomically and exactly once for its idempotency key. */
public record LedgerMutation(
        IdempotencyKey operationId,
        String operationType,
        String reference,
        List<LedgerPosting> postings
) {
    private static final Pattern VALID_TYPE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,47}");
    private static final int MAX_POSTINGS = 256;

    public LedgerMutation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationType, "operationType");
        operationType = operationType.trim().toUpperCase(Locale.ROOT);
        if (!VALID_TYPE.matcher(operationType).matches()) {
            throw new IllegalArgumentException("Invalid ledger operation type: " + operationType);
        }
        reference = reference == null ? "" : reference.trim();
        if (reference.length() > 255) {
            throw new IllegalArgumentException("Ledger reference is longer than 255 characters");
        }
        postings = List.copyOf(Objects.requireNonNull(postings, "postings"));
        if (postings.isEmpty() || postings.size() > MAX_POSTINGS) {
            throw new IllegalArgumentException("Ledger mutation must contain 1-" + MAX_POSTINGS + " postings");
        }
        if (postings.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Ledger mutation contains a null posting");
        }
    }
}
