package medix.model;

/**
 * Review state of a medicine: medicines.status (stored as {@link #name()}).
 * <ul>
 *   <li>APPROVED – in the catalog for every pharmacy</li>
 *   <li>PENDING – added by a pharmacy, waiting for an admin; it can already be sold and searched</li>
 *   <li>REJECTED – its stock is hidden from patient search (running reservations stay valid)</li>
 *   <li>MERGED – a duplicate; its stock moved to medicines.merged_into_id</li>
 * </ul>
 */
public enum MedicineStatus {
    APPROVED("Approved"),
    PENDING("To review"),
    REJECTED("Rejected"),
    MERGED("Merged");

    private final String label;

    MedicineStatus(String label) {
        this.label = label;
    }

    /** Shown to patients and pharmacies (pharmacy stock of APPROVED and PENDING medicines). */
    public boolean isSellable() {
        return this == APPROVED || this == PENDING;
    }

    @Override
    public String toString() {
        return label;
    }
}
