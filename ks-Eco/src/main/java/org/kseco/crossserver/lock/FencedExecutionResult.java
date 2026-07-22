package org.kseco.crossserver.lock;

/** Result of a SQL operation guarded by a current lease generation. */
public sealed interface FencedExecutionResult<T>
        permits FencedExecutionResult.Executed, FencedExecutionResult.LeaseLost {

    record Executed<T>(T value, long fencingToken) implements FencedExecutionResult<T> {
        public Executed {
            if (fencingToken <= 0L) {
                throw new IllegalArgumentException("fencingToken must be positive");
            }
        }
    }

    record LeaseLost<T>() implements FencedExecutionResult<T> {}
}
