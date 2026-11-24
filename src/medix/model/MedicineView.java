package medix.model;

/**
 * A medicine with the name of who added it ("Seed", the pharmacy's name or the admin's name),
 * for the Medicine Catalog. Not a table: MedicineDao builds it with a JOIN.
 */
public class MedicineView {

    private final Medicine medicine;
    private final String addedBy;

    public MedicineView(Medicine medicine, String addedBy) {
        this.medicine = medicine;
        this.addedBy = addedBy;
    }

    public Medicine getMedicine() {
        return medicine;
    }

    public String getAddedBy() {
        return addedBy;
    }

    @Override
    public String toString() {
        return medicine.getBrandName();
    }
}
