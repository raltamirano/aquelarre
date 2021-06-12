package aquelarre;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static aquelarre.Utils.safeCloseClientConnection;

/**
 * Message based multi-client TCP/IP Server
 */
public class Server<T> extends Node<T> {
    private final int port;
    private boolean running;
    private ServerSocket serverSocket;
    private Thread clientAcceptorThread;
    private final Set<ClientConnection> clientConnections = new HashSet<>();
    private AtomicInteger threadCount = new AtomicInteger();
    private final ExecutorService clientHandlersPool;
    private final int maxClients;

    private Server(final int port, final int maxClients,
                   final MessageReader<T> messageReader,
                   final MessageWriter<T> messageWriter) {
        super(messageReader, messageWriter);

        Utils.validatePortNumber(port);

        this.port = port;
        this.maxClients = maxClients;
        clientHandlersPool = configureClientHandlersPool();
    }

    public static <X> Server<X> of(final int port,
                                   final MessageReader<X> messageReader,
                                   final MessageWriter<X> messageWriter) {
        return of(port, DEFAULT_MAX_CLIENTS, messageReader, messageWriter);
    }

    public static <X> Server<X> of(final int port, final int maxClients,
                                   final MessageReader<X> messageReader,
                                   final MessageWriter<X> messageWriter) {
        return new Server<>(port, maxClients, messageReader, messageWriter);
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() throws IOException {
        if (running)
            throw new IllegalStateException("Server was already started!");

        serverSocket = new ServerSocket(port);
        startClientAcceptor();
        running = true;

    }

    public synchronized void stop() {
        if (!running)
            throw new IllegalStateException("Server was not started!");

        running = false;
        stopAcceptorThread();
        clientHandlersPool.shutdownNow();
        clientConnections.stream().map(ClientConnection::socket).forEach(Utils::safeCloseClientConnection);
    }

    private void stopAcceptorThread() {
        try {
            clientAcceptorThread.interrupt();
        } catch (final Throwable ignore) {}
    }

    @Override
    public void broadcast(final T message) {
        if (message == null)
            throw new IllegalArgumentException("message");

        for(final ClientConnection clientConnection : clientConnectionsCopy())
            safeWriteMessage(message, clientConnection.dataOutputStream());
    }

    private void safeWriteMessage(final T message, final DataOutputStream dataOutputStream) {
        try {
            writer().write(message, dataOutputStream);
        } catch (final Throwable t) {
            System.out.println("Error sending message to client: " + t);
        }
    }

    private Set<ClientConnection> clientConnectionsCopy() {
        synchronized(clientConnections) {
            return new HashSet<>(clientConnections);
        }
    }

    private ExecutorService configureClientHandlersPool() {
        return Executors.newFixedThreadPool(maxClients, r -> {
            final Thread newThread = new Thread(r, String.format("[%s] Client Processor Thread #%d",
                    nodeId().toString(), threadCount.getAndIncrement()));
            newThread.setDaemon(true);
            return newThread;
        });
    }

    private void startClientAcceptor() {
        clientAcceptorThread = new Thread(() -> {
            while(running) {
                try {
                    final Socket clientSocket = serverSocket.accept();
                    startClientHandler(clientSocket);
                    System.out.println("Client connected!");
                } catch (final Throwable t) {
                    System.out.println("Error accepting new client connection: " + t);
                }
            }
        });
        clientAcceptorThread.setDaemon(true);
        clientAcceptorThread.setName(String.format("[%s] Client Acceptor Thread",
                nodeId().toString()));
        clientAcceptorThread.start();
    }

    private void startClientHandler(final Socket clientSocket) throws IOException {
        final ClientConnection clientConnection = new ClientConnection(clientSocket);
        clientHandlersPool.submit(() -> {
            try {
                while(clientSocket.isConnected()) {
                    final T message = reader().read(clientConnection.dataInputStream());
                    // TODO: decide to either broadcast or message nodes
                    if (message != null) {
                        final boolean isBroadcast = true;
                        if (isBroadcast) {
                            notifyMessage(message);
                            for (final ClientConnection c : clientConnectionsCopy())
                                if (!c.equals(clientConnection))
                                    safeWriteMessage(message, c.dataOutputStream());
                        }
                    }
                }
            } catch (final Throwable t) {
                System.out.println("Error in client connection: " + t);
                safeCloseClientConnection(clientSocket);
                clientConnections.remove(clientConnection);
            }
        });
        clientConnections.add(clientConnection);
    }

    private static final int DEFAULT_MAX_CLIENTS = 100;

    private static class ClientConnection {
        private final Socket socket;
        private final DataInputStream dataInputStream;
        private final DataOutputStream dataOutputStream;

        public ClientConnection(final Socket socket) throws IOException {
            this.socket = socket;
            this.dataInputStream = new DataInputStream(socket.getInputStream());
            this.dataOutputStream = new DataOutputStream(socket.getOutputStream());
        }

        public Socket socket() {
            return socket;
        }

        public DataInputStream dataInputStream() {
            return dataInputStream;
        }

        public DataOutputStream dataOutputStream() {
            return dataOutputStream;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ClientConnection that = (ClientConnection) o;
            return socket.equals(that.socket);
        }

        @Override
        public int hashCode() {
            return Objects.hash(socket);
        }
    }
}
