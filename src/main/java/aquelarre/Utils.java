package aquelarre;

import java.net.Socket;

public class Utils {
    private Utils() {}

    public static void validateHost(final String host) {
        if (host == null || host.trim().isEmpty())
            throw new RuntimeException("Invalid host: " + host);
    }

    public static void validatePortNumber(final int portNumber) {
        if (portNumber <= 0 || portNumber > 65535)
            throw new RuntimeException("Invalid port number: " + portNumber);
    }

    public static void safeCloseClientConnection(final Socket clientSocket) {
        try {
            clientSocket.close();
        } catch (final Throwable ignore) {}
    }
}
