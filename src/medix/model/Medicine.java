package medix.model;

/**
 * Row of the medicines table: one catalogue entry (brand + strength + form).
 * Since Fix 3 pharmacies add medicines too (source PHARMACY, status PENDING until an admin
 * approves, edits, merges or rejects them).
 */
public class Medicine {

    private int id;
    private String brandName;
    private String genericName;
    private String strength;
    private MedicineForm form;
    /** Free-text form when form = OTHER, otherwise "" (never null, like the column). */
    private String formOther = "";
    private String manufacturer;
    private boolean rxRequired;
    /** Same defaults as the columns (the DAO inserts every column). */
    private MedicineSource source = MedicineSource.ADMIN;
    private MedicineStatus status = MedicineStatus.APPROVED;
    /** users.id of the admin or pharmacy owner who added it; null for seed medicines. */
    private Integer createdBy;
    private String createdAt;
    private Integer reviewedBy;
    private String reviewedAt;
    private String reviewNote;
    /** The medicine this duplicate was merged into (status MERGED). */
    private Integer mergedIntoId;

    public Medicine() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getBrandName() {
        return brandName;
    }

    public void setBrandName(String brandName) {
        this.brandName = brandName;
    }

    public String getGenericName() {
        return genericName;
    }

    public void setGenericName(String genericName) {
        this.genericName = genericName;
    }

    public String getStrength() {
        return strength;
    }

    public void setStrength(String strength) {
        this.strength = strength;
    }

    public MedicineForm getForm() {
        return form;
    }

    public void setForm(MedicineForm form) {
        this.form = form;
    }

    public String getFormOther() {
        return formOther;
    }

    public void setFormOther(String formOther) {
        this.formOther = formOther == null ? "" : formOther;
    }

    /** "Tablet", or the free text for OTHER (e.g. "Mouthwash"). */
    public String getFormLabel() {
        if (form == MedicineForm.OTHER && formOther != null && !formOther.trim().isEmpty()) {
            return formOther.trim();
        }
        return form == null ? "" : form.toString();
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public boolean isRxRequired() {
        return rxRequired;
    }

    public void setRxRequired(boolean rxRequired) {
        this.rxRequired = rxRequired;
    }

    public MedicineSource getSource() {
        return source;
    }

    public void setSource(MedicineSource source) {
        this.source = source;
    }

    public MedicineStatus getStatus() {
        return status;
    }

    public void setStatus(MedicineStatus status) {
        this.status = status;
    }

    /** Added by a pharmacy and not reviewed yet ("New – not yet reviewed"). */
    public boolean isPending() {
        return status == MedicineStatus.PENDING;
    }

    public Integer getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Integer reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(String reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public Integer getMergedIntoId() {
        return mergedIntoId;
    }

    public void setMergedIntoId(Integer mergedIntoId) {
        this.mergedIntoId = mergedIntoId;
    }

    @Override
    public String toString() {
        return brandName;
    }
}
