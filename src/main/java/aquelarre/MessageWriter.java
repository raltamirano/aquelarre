package aquelarre;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Writes a message to the output stream.
 */
public interface MessageWriter<T> {
    void write(final T message, final DataOutputStream dataOutputStream) throws IOException;
}
