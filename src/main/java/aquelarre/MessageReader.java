package aquelarre;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Reads a message from the input stream.
 * @param <T>
 */
public interface MessageReader<T> {
    Envelope<T> read(final DataInputStream dataInputStream) throws IOException;
}
