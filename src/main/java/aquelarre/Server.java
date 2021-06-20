package aquelarre;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static aquelarre.Utils.safeCloseClientConnection;

/**
 * Message based multi-client TCP/IP Server
 */
public class Server<T> extends Node<T> {
    private final int port;
    private final boolean authenticatedMode = false;
    private boolean includeSenderInBroadcasts;
    private boolean running;
    private ServerSocket serverSocket;
    private Thread clientAcceptorThread;
    private final Map<Socket, ClientConnection> clientConnections = new HashMap<>();
    private final Map<UUID, ClientConnection> clientConnectionsById = new HashMap<>();
    private AtomicInteger threadCount = new AtomicInteger();
    private final ExecutorService clientHandlersPool;
    private final int maxClients;
    private final RoutingManager<T> routingManager;

    private Server(final int port, final int maxClients,
                   final boolean includeSenderInBroadcasts,
                   final MessageReader<T> messageReader,
                   final MessageWriter<T> messageWriter,
                   final RoutingManager<T> routingManager) {
        super(messageReader, messageWriter);

        Utils.validatePortNumber(port);
        if (routingManager == null)
            throw new IllegalArgumentException("routingManager");

        this.port = port;
        this.maxClients = maxClients;
        this.includeSenderInBroadcasts = includeSenderInBroadcasts;
        this.routingManager = routingManager;
        clientHandlersPool = configureClientHandlersPool();
    }

    public static <X> Server<X> of(final int port,
                                   final MessageReader<X> messageReader,
                                   final MessageWriter<X> messageWriter,
                                   final RoutingManager<X> routingManager) {
        return of(port, DEFAULT_MAX_CLIENTS, true, messageReader, messageWriter, routingManager);
    }

    public static <X> Server<X> of(final int port, final int maxClients,
                                   final boolean includeSenderInBroadcasts,
                                   final MessageReader<X> messageReader,
                                   final MessageWriter<X> messageWriter,
                                   final RoutingManager<X> routingManager) {
        return new Server<>(port, maxClients, includeSenderInBroadcasts, messageReader, messageWriter, routingManager);
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
        clientConnections.keySet().forEach(Utils::safeCloseClientConnection);
    }

    public RoutingManager<T> getRoutingManager() {
        return routingManager;
    }

    public boolean includeSenderInBroadcasts() {
        return includeSenderInBroadcasts;
    }

    public void setIncludeSenderInBroadcasts(final boolean includeSenderInBroadcasts) {
        this.includeSenderInBroadcasts = includeSenderInBroadcasts;
    }

    private void stopAcceptorThread() {
        try {
            clientAcceptorThread.interrupt();
        } catch (final Throwable ignore) {}
    }

    @Override
    public void send(final String to, final T message) throws IOException {
        if (to == null)
            throw new IllegalArgumentException("to");
        if (message == null)
            throw new IllegalArgumentException("message");

        final Envelope<T> envelope = Envelope.of(Header.of(SERVER, to), message);
        if (routingManager.isValidRoute(envelope)) {
            final ClientConnection target = getClientConnectionByNodeIdOrLogin(to);
            if (target != null)
                writeMessage(envelope, target);
        } else {
            System.out.println("Invalid routing for message: " + envelope);
        }
    }

    @Override
    public void broadcast(final T message) {
        if (message == null)
            throw new IllegalArgumentException("message");

        for(final ClientConnection clientConnection : clientConnectionsCopy().values()) {
            final Envelope<T> envelope = Envelope.of(Header.of(SERVER, actualIdentification(clientConnection)), message);
            if (routingManager.isValidRoute(envelope))
                safeWriteMessage(envelope, clientConnection);
            else
                System.out.println("Invalid routing for message: " + envelope);
        }
    }

    private void safeWriteMessage(final Envelope<T> message, final ClientConnection clientConnection) {
        try {
            writeMessage(message, clientConnection);
        } catch (final Throwable t) {
            System.out.println("Error sending message to client: " + t);
        }
    }

    private void writeMessage(final Envelope<T> message, final ClientConnection clientConnection) throws IOException {
        writer().write(message, clientConnection.dataOutputStream());
    }

    private Map<Socket, ClientConnection> clientConnectionsCopy() {
        synchronized(clientConnections) {
            return new HashMap<>(clientConnections);
        }
    }

    private Map<UUID, ClientConnection> clientConnectionsByIdCopy() {
        synchronized(clientConnectionsById) {
            return new HashMap<>(clientConnectionsById);
        }
    }

    private ExecutorService configureClientHandlersPool() {
        return Executors.newFixedThreadPool(maxClients, r -> {
            final Thread newThread = new Thread(r, String.format("Client Processor Thread #%d",
                    threadCount.getAndIncrement()));
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
        clientAcceptorThread.setName("Client Acceptor Thread");
        clientAcceptorThread.start();
    }

    private void startClientHandler(final Socket clientSocket) throws IOException {
        final ClientConnection clientConnection = new ClientConnection(UUID.randomUUID(), clientSocket);
        clientHandlersPool.submit(() -> {
            try {
                while(clientSocket.isConnected()) {
                    final Envelope<T> message = reader().read(clientConnection.dataInputStream());
                    if (message != null) {
                        final Envelope<T> rewrittenFrom = message.withFrom(actualIdentification(clientConnection));
                        if (rewrittenFrom.isBroadcast()) {
                            final Envelope<T> toServer = rewrittenFrom.withTo(SERVER);
                            if (routingManager.isValidRoute(toServer)) {
                                notifyMessage(toServer);
                            } else {
                                System.out.println("Invalid routing for message: " + toServer);
                            }

                            for (final ClientConnection c : clientConnectionsCopy().values()) {
                                if (includeSenderInBroadcasts || !c.equals(clientConnection)) {
                                    final Envelope<T> rewrittenTo = rewrittenFrom.withTo(actualIdentification(c));
                                    if (routingManager.isValidRoute(rewrittenTo)) {
                                        safeWriteMessage(rewrittenTo, c);
                                    } else {
                                        System.out.println("Invalid routing for message: " + rewrittenTo);
                                    }
                                }
                            }
                        } else {
                            if (rewrittenFrom.wasSentToServer()) {
                                if (routingManager.isValidRoute(rewrittenFrom)) {
                                    notifyMessage(rewrittenFrom);
                                } else {
                                    System.out.println("Invalid routing for message: " + rewrittenFrom);
                                }
                            } else {
                                if (routingManager.isValidRoute(rewrittenFrom)) {
                                    final ClientConnection target = getClientConnectionByNodeIdOrLogin(rewrittenFrom.header().to());
                                    if (target != null)
                                        safeWriteMessage(rewrittenFrom, target);
                                } else {
                                    System.out.println("Invalid routing for message: " + rewrittenFrom);
                                }
                            }
                        }
                    }
                }
            } catch (final Throwable t) {
                System.out.println("Error in client connection: " + t);
                safeCloseClientConnection(clientSocket);
                unregisterClientConnection(clientConnection);
            }
        });
        registerClientConnection(clientConnection);
    }

    private String actualIdentification(final ClientConnection clientConnection) {
        if (authenticatedMode)
            throw new RuntimeException("Authenticated server mode not implemented yet!");
        else
            return clientConnection.id().toString();
    }

    private ClientConnection getClientConnectionByNodeIdOrLogin(final String nodeIdOrLogin) {
        try {
            try {
                final UUID targetNodeId = UUID.fromString(nodeIdOrLogin);
                return clientConnectionsByIdCopy().get(targetNodeId);
            } catch (final Throwable ignore) {
                throw new RuntimeException("Authenticated server mode not implemented yet!");
            }
        } catch (final Throwable t) {
            System.out.println("Error identifying client connection: " + t);
            return null;
        }
    }

    private void unregisterClientConnection(final ClientConnection clientConnection) {
        clientConnections.remove(clientConnection);
        clientConnectionsById.remove(clientConnection.id());
    }

    private void registerClientConnection(final ClientConnection clientConnection) {
        clientConnections.put(clientConnection.socket(), clientConnection);
        clientConnectionsById.put(clientConnection.id(), clientConnection);
    }

    private static final int DEFAULT_MAX_CLIENTS = 100;

    private static class ClientConnection {
        private final UUID id;
        private final Socket socket;
        private final DataInputStream dataInputStream;
        private final DataOutputStream dataOutputStream;

        public ClientConnection(final UUID id, final Socket socket) throws IOException {
            this.id = id;
            this.socket = socket;
            this.dataInputStream = new DataInputStream(socket.getInputStream());
            this.dataOutputStream = new DataOutputStream(socket.getOutputStream());
        }

        public UUID id() {
            return id;
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
