package aquelarre;

/**
 * Message listener contract
 */
public interface MessageListener<T> {
    void onMessage(final Envelope<T> message);
}
