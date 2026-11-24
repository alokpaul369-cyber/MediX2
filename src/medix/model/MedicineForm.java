package medix.model;

/**
 * Dosage form of a medicine: medicines.form.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 * {@link #toString()} is the label shown in combo boxes and tables. OTHER uses the free text in
 * medicines.form_other (see {@link Medicine#getFormLabel()}).
 */
public enum MedicineForm {
    TABLET("Tablet"),
    CAPSULE("Capsule"),
    SYRUP("Syrup"),
    SUSPENSION("Suspension"),
    INJECTION("Injection"),
    DROPS("Drops"),
    CREAM("Cream"),
    OINTMENT("Ointment"),
    GEL("Gel"),
    INHALER("Inhaler"),
    POWDER("Powder"),
    SACHET("Sachet"),
    SUPPOSITORY("Suppository"),
    SPRAY("Spray"),
    PATCH("Patch"),
    OTHER("Other");

    private final String label;

    MedicineForm(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
