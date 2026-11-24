package medix.model;

/**
 * Where an exchange rate comes from: exchange_rates.source (stored as {@link #name()}).
 * ONLINE = open.er-api.com, MANUAL = typed by an admin (kept by the online update unless replaced).
 */
public enum RateSource {
    ONLINE("Online"),
    MANUAL("Manual");

    private final String label;

    RateSource(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
