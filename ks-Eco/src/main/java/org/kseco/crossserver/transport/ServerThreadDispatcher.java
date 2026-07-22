package org.kseco.crossserver.transport;

/**
 * Boundary supplied by the plugin integration. Implementations must enqueue the task on Paper's server thread.
 */
@FunctionalInterface
public interface ServerThreadDispatcher {
    void execute(Runnable task);
}
