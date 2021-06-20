package aquelarre;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import static aquelarre.Utils.safeCloseClientConnection;

/**
 * TCP/IP Client
 */
public class Client<T> extends Node<T> {
    private boolean connected;
    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private Thread thread;
    private AtomicInteger threadCount = new AtomicInteger();

    private Client(final String host, final int port,
                   final MessageReader<T> messageReader,
                   final MessageWriter<T> messageWriter) {
        super(messageReader, messageWriter);

        Utils.validateHost(host);
        Utils.validatePortNumber(port);

        this.host = host;
        this.port = port;
    }

    public static <X> Client<X> of(final String host, final int port,
                                   final MessageReader<X> messageReader,
                                   final MessageWriter<X> messageWriter) {
        return new Client(host, port, messageReader, messageWriter);
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean isConnected() {
        return connected;
    }

    public synchronized void connect() throws IOException {
        if (connected)
            throw new IllegalStateException("Already connected!");

        socket = new Socket(host, port);
        dataInputStream = new DataInputStream(socket.getInputStream());
        dataOutputStream = new DataOutputStream(socket.getOutputStream());
        thread = startClientThread();
        connected = true;
        thread.start();
    }

    private Thread startClientThread() {
        final Thread thread = new Thread(() -> {
            try {
                while(connected && socket.isConnected()) {
                    final Envelope<T> message = reader().read(dataInputStream);
                    if (message != null)
                        notifyMessage(message);
                }
            } catch (final Throwable t) {
                System.out.println("Error in client connection: " + t);
                safeCloseClientConnection(socket);
            }
        });

        thread.setName(String.format("Client Processor Thread #%d",
                threadCount.getAndIncrement()));
        thread.setDaemon(true);

        return thread;
    }

    public synchronized void disconnect() throws IOException {
        if (!connected)
            throw new IllegalStateException("Not connected!");

        connected = false;
        stopClientThread();
        safeCloseClientConnection(socket);
        dataInputStream = null;
        dataOutputStream = null;
    }

    private void stopClientThread() {
        try {
            thread.interrupt();
        } catch (final Throwable ignore) {}
        thread = null;
    }

    @Override
    public void broadcast(final T message) throws IOException {
        writer().write(Envelope.of(Header.of(nodeId().toString(), ALL), message), dataOutputStream);
    }
}
