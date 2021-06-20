package aquelarre;

/**
 * Validates routing of messages.
 */
public interface RoutingManager<T> {
    boolean isValidRoute(final Envelope<T> message);
}
