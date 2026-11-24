package medix.model;

/**
 * Who added a medicine: medicines.source (stored as {@link #name()}).
 * SEED = the start-up catalog (and every medicine that existed before Fix 3), ADMIN = added in the
 * Medicine Catalog, PHARMACY = added by a pharmacy from its inventory screen.
 */
public enum MedicineSource {
    SEED("Seed"),
    ADMIN("Admin"),
    PHARMACY("Pharmacy");

    private final String label;

    MedicineSource(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
