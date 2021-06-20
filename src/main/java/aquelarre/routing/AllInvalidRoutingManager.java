package aquelarre.routing;

import aquelarre.Envelope;
import aquelarre.RoutingManager;

public class AllInvalidRoutingManager<T> implements RoutingManager<T> {
    private static final AllInvalidRoutingManager INSTANCE = new AllInvalidRoutingManager();

    private AllInvalidRoutingManager() {}

    @Override
    public boolean isValidRoute(final Envelope<T> message) {
        return false;
    }

    public static <X> AllInvalidRoutingManager<X> getInstance() {
        return INSTANCE;
    }
}
