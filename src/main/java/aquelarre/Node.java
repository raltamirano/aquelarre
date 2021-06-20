package aquelarre;

import java.io.IOException;
import java.util.UUID;

/**
 * Participant in a multi-client communication (includes both server and clients)
 */
public abstract class Node<T> {
    private final MessageReader<T> messageReader;
    private final MessageWriter<T> messageWriter;
    private MessageListener<T> messageListener;

    protected Node(final MessageReader<T> messageReader, final MessageWriter<T> messageWriter) {
        if (messageReader == null)
            throw new IllegalArgumentException("messageReader");
        if (messageWriter == null)
            throw new IllegalArgumentException("messageWriter");

        this.messageReader = messageReader;
        this.messageWriter = messageWriter;
    }

    public MessageListener<T> getMessageListener() {
        return messageListener;
    }

    public void setMessageListener(final MessageListener<T> messageListener) {
        this.messageListener = messageListener;
    }

    /**
     * Send a message to every other client through the same server this node is connected to.
     */
    public abstract void broadcast(final T message) throws IOException;

    /**
     * Send a message to a specific node/client.
     */
    public abstract void send(final String to, final T message) throws IOException;

    protected MessageReader<T> reader() {
        return messageReader;
    }

    protected MessageWriter<T> writer() {
        return messageWriter;
    }

    protected void notifyMessage(final Envelope<T> message) {
        final MessageListener<T> theListener = messageListener;
        if (theListener != null) {
            try {
                theListener.onMessage(message);
            } catch (final Throwable t) {
                System.out.println("Error while notifying message: " + t);
            }
        }
    }

    public static final String ALL = "*";
    public static final String SERVER = "s";
    public static final String ME = "m";
}
