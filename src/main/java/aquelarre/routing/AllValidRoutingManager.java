package aquelarre.routing;

import aquelarre.Envelope;
import aquelarre.RoutingManager;

public class AllValidRoutingManager<T> implements RoutingManager<T> {
    private static final AllValidRoutingManager INSTANCE = new AllValidRoutingManager();

    private AllValidRoutingManager() {}

    @Override
    public boolean isValidRoute(final Envelope<T> message) {
        return true;
    }

    public static <X> AllValidRoutingManager<X> getInstance() {
        return INSTANCE;
    }
}
