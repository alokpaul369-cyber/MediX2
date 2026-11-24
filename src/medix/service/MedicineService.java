package medix.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import medix.dao.InventoryDao;
import medix.dao.MedicineDao;
import medix.dao.PharmacyDao;
import medix.dao.ReservationDao;
import medix.dao.SearchLogDao;
import medix.dao.StockMovementDao;
import medix.engine.AvailabilityCache;
import medix.engine.SearchEngine;
import medix.model.Inventory;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.model.MedicineSource;
import medix.model.MedicineStatus;
import medix.model.MedicineView;
import medix.model.MovementReason;
import medix.model.NotificationType;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.Role;
import medix.model.StockMovement;
import medix.util.DBConnection;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * The medicine catalog.
 *
 * Admins add medicines (source ADMIN, APPROVED) and review the ones pharmacies add from their
 * inventory screen (source PHARMACY, PENDING): approve, edit and approve, merge into the correct
 * existing medicine (stock, reservations and search history move with it) or reject (its stock is
 * hidden from patient search). A PENDING medicine can be sold straight away; only the pharmacy that
 * added it may edit it until an admin has reviewed it.
 *
 * Duplicates: an exact duplicate (same brand, strength and form, ignoring case and extra spaces) is
 * never created – the existing medicine is used. Similar medicines (spelling, "500mg" vs "500 mg",
 * same generic + strength + form) are listed first so the pharmacy can pick one instead.
 */
public class MedicineService {

    public static final String MSG_DUPLICATE = "This medicine is already in the catalog.";
    public static final int MIN_REASON_LENGTH = 5;
    public static final int MAX_NAME_LENGTH = 80;

    private final MedicineDao medicineDao = new MedicineDao();
    private final InventoryDao inventoryDao = new InventoryDao();
    private final ReservationDao reservationDao = new ReservationDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final SearchLogDao searchLogDao = new SearchLogDao();
    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final AuditService auditService = new AuditService();
    private final NotificationService notificationService = new NotificationService();

    /** What addByPharmacy did: the medicine to use and whether it is new. */
    public static final class AddResult {
        private final Medicine medicine;
        private final boolean created;

        AddResult(Medicine medicine, boolean created) {
            this.medicine = medicine;
            this.created = created;
        }

        public Medicine getMedicine() {
            return medicine;
        }

        /** False when an exact duplicate already existed and is used instead. */
        public boolean isCreated() {
            return created;
        }
    }

    // ---------------------------------------------------------------- reading

    /** All medicines whose brand or generic name contains the text (blank = all), by brand. */
    public List<Medicine> search(String text) throws SQLException {
        return medicineDao.searchByName(text == null ? "" : text);
    }

    /** Catalog rows with "Added by"; status / source null = any. PENDING first. */
    public List<MedicineView> searchViews(String text, MedicineStatus status, MedicineSource source) throws SQLException {
        return medicineDao.findViews(text, status, source);
    }

    public Medicine findById(int id) throws SQLException {
        return medicineDao.findById(id);
    }

    /** Medicines waiting for an admin ("To review (n)"). */
    public int countToReview() throws SQLException {
        return medicineDao.countByStatus(MedicineStatus.PENDING);
    }

    /** APPROVED medicines a pending one can be merged into (every one but itself). */
    public List<Medicine> getMergeTargets(int excludeId) throws SQLException {
        List<Medicine> list = new ArrayList<Medicine>();
        for (Medicine m : medicineDao.findByStatus(MedicineStatus.APPROVED)) {
            if (m.getId() != excludeId) {
                list.add(m);
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- admin: add, edit, delete

    public int add(Medicine m) throws ServiceException, SQLException {
        requireAdmin();
        normalizeAndCheck(m, 0);
        m.setSource(MedicineSource.ADMIN);
        m.setStatus(MedicineStatus.APPROVED);
        m.setCreatedBy(Session.getUserId());
        int id = medicineDao.insert(m);
        auditService.log("MEDICINE_ADDED", "medicines", id, label(m));
        return id;
    }

    public void update(Medicine m) throws ServiceException, SQLException {
        requireAdmin();
        Medicine old = medicineDao.findById(m.getId());
        if (old == null) {
            throw new ServiceException("This medicine no longer exists.");
        }
        if (old.getStatus() == MedicineStatus.MERGED) {
            throw new ServiceException("This medicine was merged into another one and cannot be changed.");
        }
        normalizeAndCheck(m, m.getId());
        medicineDao.update(m);
        AvailabilityCache.invalidateMedicine(m.getId());
        auditService.log("MEDICINE_UPDATED", "medicines", m.getId(), label(m));
    }

    /** Deletes the medicine unless stock or reservations still use it. */
    public void delete(int id) throws ServiceException, SQLException {
        requireAdmin();
        Medicine m = medicineDao.findById(id);
        if (m == null) {
            throw new ServiceException("This medicine no longer exists.");
        }
        int stock = inventoryDao.countByMedicine(id);
        if (stock > 0) {
            throw new ServiceException(label(m) + " cannot be deleted because " + stock
                    + " inventory row(s) use it.\nPharmacies must remove that stock first.");
        }
        int reservations = reservationDao.countByMedicine(id);
        if (reservations > 0) {
            throw new ServiceException(label(m) + " cannot be deleted because " + reservations
                    + " reservation(s) refer to it.");
        }
        medicineDao.delete(id);
        AvailabilityCache.invalidateMedicine(id);
        auditService.log("MEDICINE_DELETED", "medicines", id, label(m));
    }

    // ---------------------------------------------------------------- pharmacy: add and edit

    /**
     * A verified pharmacy adds a medicine that is not in its list. An exact duplicate is not created:
     * the existing medicine is returned (a merged one's target). Otherwise it is saved as source
     * PHARMACY, status PENDING, created_by = the owner; every admin is notified and the audit log
     * gets MEDICINE_ADDED. Call {@link #findSimilar(Medicine)} first to offer similar medicines.
     */
    public AddResult addByPharmacy(Medicine m) throws ServiceException, SQLException {
        Pharmacy pharmacy = requireVerifiedPharmacy();
        normalizeFields(m);
        validate(m);
        Medicine existing = medicineDao.findDuplicate(m.getBrandName(), m.getStrength(), m.getForm(), m.getFormOther(), 0);
        if (existing != null) {
            return new AddResult(usable(existing), false);
        }
        m.setSource(MedicineSource.PHARMACY);
        m.setStatus(MedicineStatus.PENDING);
        m.setCreatedBy(Session.getUserId());
        m.setReviewedBy(null);
        m.setReviewedAt(null);
        m.setMergedIntoId(null);
        int id = medicineDao.insert(m);
        auditService.log("MEDICINE_ADDED", "medicines", id, label(m) + " (by " + pharmacy.getName() + ", to review)");
        notificationService.notifyAdmins(NotificationType.INFO, pharmacy.getName() + " added a new medicine: "
                + label(m) + ". Please review it.", NotificationService.LINK_MEDICINE, id);
        return new AddResult(m, true);
    }

    /** An existing exact duplicate as the medicine to use (its target when merged; rejected = an error). */
    private Medicine usable(Medicine existing) throws ServiceException, SQLException {
        Medicine m = existing;
        for (int hops = 0; m.getStatus() == MedicineStatus.MERGED && m.getMergedIntoId() != null && hops < 10; hops++) {
            Medicine target = medicineDao.findById(m.getMergedIntoId());
            if (target == null) {
                break;
            }
            m = target;
        }
        if (m.getStatus() == MedicineStatus.REJECTED || m.getStatus() == MedicineStatus.MERGED) {
            throw new ServiceException(label(m) + " was rejected by the admin"
                    + (m.getReviewNote() == null ? "" : ": " + m.getReviewNote()) + ".\nPlease choose the correct medicine.");
        }
        return m;
    }

    /** True when the logged-in pharmacy may edit the medicine (it added it and it is still PENDING). */
    public static boolean canPharmacyEdit(Medicine m) {
        Integer me = Session.getUserId();
        return m != null && m.isPending() && me != null && me.equals(m.getCreatedBy()) && Session.hasRole(Role.PHARMACY);
    }

    /** The pharmacy that added a PENDING medicine corrects it (only until an admin reviewed it). */
    public void updateMyPending(Medicine m) throws ServiceException, SQLException {
        requireVerifiedPharmacy();
        Medicine old = medicineDao.findById(m.getId());
        if (old == null) {
            throw new ServiceException("This medicine no longer exists.");
        }
        if (!canPharmacyEdit(old)) {
            throw new ServiceException(old.isPending() ? "Only the pharmacy that added this medicine can change it."
                    : "This medicine was already reviewed by an admin. Only an admin can change it now.");
        }
        normalizeAndCheck(m, m.getId());
        medicineDao.update(m);
        AvailabilityCache.invalidateMedicine(m.getId());
        auditService.log("MEDICINE_UPDATED", "medicines", m.getId(), label(m) + " (by the pharmacy, still to review)");
    }

    /**
     * Medicines that look like the draft (APPROVED and PENDING, the draft itself excluded), most
     * similar first. See {@link #findSimilar(Medicine, List)}.
     */
    public List<Medicine> findSimilar(Medicine draft) throws SQLException {
        return findSimilar(draft, medicineDao.findSellable());
    }

    /**
     * Pure similarity check (SearchEngine normalising + Levenshtein), same form (for OTHER the same
     * free text) and the same strength ignoring spaces and case ("500mg" = "500 mg"), plus either a
     * brand within the spelling tolerance or the same generic name. "napa 500mg" finds Napa 500 mg.
     */
    public static List<Medicine> findSimilar(Medicine draft, List<Medicine> catalog) {
        final String brand = compact(draft.getBrandName());
        final String generic = compact(draft.getGenericName());
        String strength = compact(draft.getStrength());
        String product = compact(draft.getBrandName() + draft.getStrength());
        List<Object[]> found = new ArrayList<Object[]>();
        for (Medicine m : catalog) {
            if (m.getId() == draft.getId() && draft.getId() != 0) {
                continue;
            }
            if (!sameForm(draft, m)) {
                continue;
            }
            String otherBrand = compact(m.getBrandName());
            boolean sameStrength = strength.equals(compact(m.getStrength()));
            String otherProduct = compact(m.getBrandName() + m.getStrength());
            // "Napa 500mg" typed as the brand is the same product as Napa + 500 mg
            boolean sameProduct = product.equals(otherProduct) || brand.equals(otherProduct);
            int brandDistance = SearchEngine.levenshtein(brand, otherBrand);
            boolean brandClose = brandDistance <= tolerance(Math.min(brand.length(), otherBrand.length()));
            String otherGeneric = compact(m.getGenericName());
            boolean genericClose = !generic.isEmpty() && SearchEngine.levenshtein(generic, otherGeneric)
                    <= tolerance(Math.min(generic.length(), otherGeneric.length()));
            if (sameProduct || (sameStrength && (brandClose || genericClose))) {
                // score: same product / close brand first, then same generic
                int score = sameProduct ? 0 : brandClose ? 1 + brandDistance : 10;
                found.add(new Object[]{m, score});
            }
        }
        Collections.sort(found, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                int r = Integer.compare((Integer) a[1], (Integer) b[1]);
                return r != 0 ? r : ((Medicine) a[0]).getBrandName().compareToIgnoreCase(((Medicine) b[0]).getBrandName());
            }
        });
        List<Medicine> list = new ArrayList<Medicine>();
        for (Object[] f : found) {
            list.add((Medicine) f[0]);
        }
        return list;
    }

    /** Letters allowed to differ: 0 for very short names, 1 from 3 letters, 2 from 5 (like SearchEngine). */
    private static int tolerance(int length) {
        if (length >= SearchEngine.MIN_FUZZY_WORD_LENGTH) {
            return SearchEngine.MAX_EDIT_DISTANCE;
        }
        return length >= 3 ? 1 : 0;
    }

    private static boolean sameForm(Medicine a, Medicine b) {
        if (a.getForm() != b.getForm()) {
            return false;
        }
        return a.getForm() != MedicineForm.OTHER || compact(a.getFormOther()).equals(compact(b.getFormOther()));
    }

    /** SearchEngine.normalize without any spaces: "Napa 500 MG" → "napa500mg". */
    public static String compact(String text) {
        return SearchEngine.normalize(text).replace(" ", "");
    }

    private static final Pattern TRAILING_STRENGTH = Pattern.compile(
            "^(.*?\\S)\\s*(\\d+(?:[.,]\\d+)?\\s*(?:mg|mcg|µg|g|ml|iu|%|units?)(?:\\s*/\\s*\\d*(?:[.,]\\d+)?\\s*(?:ml|g|l))?)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Splits what a pharmacy typed into brand and strength for the "Add medicine" form:
     * "napa 500mg" → {"napa", "500mg"}, "Ventolin" → {"Ventolin", ""}.
     */
    public static String[] splitTypedText(String typed) {
        String t = typed == null ? "" : typed.trim().replaceAll("\\s+", " ");
        Matcher m = TRAILING_STRENGTH.matcher(t);
        if (m.matches()) {
            return new String[]{m.group(1).trim(), m.group(2).trim()};
        }
        return new String[]{t, ""};
    }

    // ---------------------------------------------------------------- admin review

    /** PENDING → APPROVED; the pharmacy that added it is notified. */
    public void approve(int id) throws ServiceException, SQLException {
        requireAdmin();
        Medicine m = requirePending(id);
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                if (!medicineDao.review(con, id, MedicineStatus.APPROVED, Session.getUserId(), null, null)) {
                    throw new ServiceException("This medicine was already reviewed.");
                }
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        AvailabilityCache.invalidateMedicine(id);
        notifyCreator(m, label(m) + " was approved by the admin and is now in the catalog for every pharmacy.");
        auditService.log("MEDICINE_APPROVED", "medicines", id, label(m));
    }

    /** Fixes the fields (typos) and approves, in one transaction. */
    public void approveWithChanges(Medicine changed) throws ServiceException, SQLException {
        requireAdmin();
        Medicine old = requirePending(changed.getId());
        normalizeAndCheck(changed, changed.getId());
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                medicineDao.update(con, changed);
                if (!medicineDao.review(con, changed.getId(), MedicineStatus.APPROVED, Session.getUserId(),
                        "Edited: " + label(old) + " → " + label(changed), null)) {
                    throw new ServiceException("This medicine was already reviewed.");
                }
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        AvailabilityCache.invalidateMedicine(changed.getId());
        String text = label(old).equals(label(changed)) ? label(changed) + " was approved by the admin."
                : label(old) + " was corrected to " + label(changed) + " and approved by the admin.";
        notifyCreator(old, text);
        auditService.log("MEDICINE_APPROVED", "medicines", changed.getId(), label(old) + " → " + label(changed) + " (edited)");
    }

    /**
     * PENDING → REJECTED with a reason (≥ 5 characters). Its stock disappears from patient search;
     * running reservations stay valid until they end. The pharmacy is told why and asked to choose
     * the correct medicine.
     */
    public void reject(int id, String reason) throws ServiceException, SQLException {
        requireAdmin();
        String why = ValidationUtil.trimToNull(reason);
        if (why == null || why.length() < MIN_REASON_LENGTH) {
            throw new ServiceException("Write the reason (at least " + MIN_REASON_LENGTH + " characters). The pharmacy sees it.");
        }
        Medicine m = requirePending(id);
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                if (!medicineDao.review(con, id, MedicineStatus.REJECTED, Session.getUserId(), why, null)) {
                    throw new ServiceException("This medicine was already reviewed.");
                }
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        AvailabilityCache.clear();
        notifyCreator(m, label(m) + " was rejected by the admin: " + why + ". Its stock is hidden from patient search. "
                + "In Inventory, select a batch of it and click \"Choose correct medicine\".");
        auditService.log("MEDICINE_REJECTED", "medicines", id, label(m) + ": " + why);
    }

    /**
     * Merges a PENDING duplicate into the correct APPROVED medicine in ONE transaction: every
     * inventory row, reservation and search_logs row moves to it. When a pharmacy already has the
     * same batch number for the correct medicine, quantity and reserved_qty are added together, its
     * reservations and history point to the kept row and the extra row is deleted. Then the duplicate
     * becomes MERGED (merged_into_id). Returns a short summary ("2 batch(es) moved, 1 combined").
     */
    public String merge(int duplicateId, int correctId) throws ServiceException, SQLException {
        requireAdmin();
        Medicine duplicate = requirePending(duplicateId);
        Medicine correct = medicineDao.findById(correctId);
        if (correct == null || correct.getStatus() != MedicineStatus.APPROVED || correctId == duplicateId) {
            throw new ServiceException("Choose an approved medicine to merge into.");
        }
        int[] counts;
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                counts = moveStock(con, duplicateId, correctId, null,
                        "merged " + label(duplicate) + " into " + label(correct));
                searchLogDao.moveMedicine(con, duplicateId, correctId);
                medicineDao.repointMerged(con, duplicateId, correctId);
                if (!medicineDao.review(con, duplicateId, MedicineStatus.MERGED, Session.getUserId(),
                        "Merged into #" + correctId + " " + label(correct), correctId)) {
                    throw new ServiceException("This medicine was already reviewed.");
                }
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        AvailabilityCache.clear();
        String summary = counts[0] + " batch(es) moved, " + counts[1] + " combined with the same batch";
        notifyCreator(duplicate, label(duplicate) + " was merged into " + label(correct) + ". Your batches of it now use "
                + label(correct) + ".");
        auditService.log("MEDICINE_MERGED", "medicines", duplicateId, label(duplicate) + " → #" + correctId + " "
                + label(correct) + ": " + summary);
        return summary;
    }

    /**
     * A pharmacy moves ITS batches (and reservations) of a REJECTED medicine to the correct one,
     * the same way as a merge. Returns the summary.
     */
    public String moveMyStock(int rejectedId, int correctId) throws ServiceException, SQLException {
        Pharmacy pharmacy = requireVerifiedPharmacy();
        Medicine rejected = medicineDao.findById(rejectedId);
        Medicine correct = medicineDao.findById(correctId);
        if (rejected == null || rejected.getStatus() != MedicineStatus.REJECTED) {
            throw new ServiceException("Only the batches of a rejected medicine can be moved.");
        }
        if (correct == null || correctId == rejectedId || !(correct.getStatus() == MedicineStatus.APPROVED
                || (correct.isPending() && pharmacy.getOwnerId() == nz(correct.getCreatedBy())))) {
            throw new ServiceException("Choose an approved medicine.");
        }
        int[] counts;
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                counts = moveStock(con, rejectedId, correctId, pharmacy.getId(),
                        "moved from rejected " + label(rejected) + " to " + label(correct));
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        AvailabilityCache.invalidateMedicine(rejectedId);
        AvailabilityCache.invalidateMedicine(correctId);
        String summary = counts[0] + " batch(es) moved, " + counts[1] + " combined with the same batch";
        auditService.log("STOCK_MEDICINE_CHANGED", "medicines", correctId, label(rejected) + " → " + label(correct)
                + " at " + pharmacy.getName() + ": " + summary);
        return summary;
    }

    /**
     * Moves the batches of medicine `from` (of one pharmacy, or all when pharmacyId is null) to
     * `to`; same pharmacy + batch number = one row (units added, reservations + history re-pointed,
     * the extra row deleted, an ADJUST movement on the kept row). Reservations of `from` follow.
     * Returns {moved, combined}.
     */
    private int[] moveStock(Connection con, int from, int to, Integer pharmacyId, String why) throws SQLException {
        int moved = 0;
        int combined = 0;
        for (Inventory row : inventoryDao.findByMedicine(con, from, pharmacyId)) {
            Inventory same = inventoryDao.findBatch(con, row.getPharmacyId(), to, row.getBatchNo());
            if (same == null) {
                inventoryDao.moveToMedicine(con, row.getId(), to);
                moved++;
                continue;
            }
            inventoryDao.addUnitsFrom(con, same.getId(), row);
            reservationDao.repointInventory(con, row.getId(), same.getId());
            movementDao.repointInventory(con, row.getId(), same.getId());
            StockMovement mv = new StockMovement();
            mv.setInventoryId(same.getId());
            mv.setChangeQty(row.getQuantity());
            mv.setReason(MovementReason.ADJUST);
            mv.setUserId(Session.getUserId());
            mv.setNote("Stock #" + row.getId() + " (same batch " + row.getBatchNo() + ", " + row.getQuantity()
                    + " units, " + row.getReservedQty() + " reserved) added: " + why);
            movementDao.insert(con, mv);
            inventoryDao.delete(con, row.getId());
            combined++;
        }
        reservationDao.moveMedicine(con, from, to, pharmacyId);
        return new int[]{moved, combined};
    }

    private Medicine requirePending(int id) throws ServiceException, SQLException {
        Medicine m = medicineDao.findById(id);
        if (m == null) {
            throw new ServiceException("This medicine no longer exists.");
        }
        if (!m.isPending()) {
            throw new ServiceException(label(m) + " was already reviewed (" + m.getStatus().toString().toLowerCase(Locale.ROOT) + ").");
        }
        return m;
    }

    /** INFO notification for the pharmacy owner (or admin) who added the medicine. */
    private void notifyCreator(Medicine m, String message) throws SQLException {
        if (m.getCreatedBy() != null) {
            notificationService.notifyUser(m.getCreatedBy(), NotificationType.INFO, message,
                    NotificationService.LINK_MEDICINE, m.getId());
        }
    }

    private static int nz(Integer i) {
        return i == null ? -1 : i;
    }

    // ---------------------------------------------------------------- labels

    /** Autocomplete row, e.g. "Seclo 20 mg Capsule – Omeprazole". */
    public static String suggestionLabel(Medicine m) {
        return label(m) + " – " + m.getGenericName();
    }

    /** e.g. "Napa 500 mg Tablet" (the free text for an OTHER form). */
    public static String label(Medicine m) {
        return m.getBrandName() + " " + m.getStrength() + " " + m.getFormLabel();
    }

    // ---------------------------------------------------------------- checks

    private void normalizeAndCheck(Medicine m, int excludeId) throws ServiceException, SQLException {
        normalizeFields(m);
        validate(m);
        if (medicineDao.findDuplicate(m.getBrandName(), m.getStrength(), m.getForm(), m.getFormOther(), excludeId) != null) {
            throw new ServiceException(MSG_DUPLICATE);
        }
    }

    private static void normalizeFields(Medicine m) {
        m.setBrandName(clean(m.getBrandName()));
        m.setGenericName(clean(m.getGenericName()));
        m.setStrength(clean(m.getStrength()));
        m.setManufacturer(ValidationUtil.trimToNull(clean(m.getManufacturer())));
        m.setFormOther(m.getForm() == MedicineForm.OTHER ? clean(m.getFormOther()) : "");
    }

    private static void validate(Medicine m) throws ServiceException {
        if (m.getBrandName().isEmpty() || m.getGenericName().isEmpty() || m.getStrength().isEmpty()) {
            throw new ServiceException("Brand name, generic name and strength are required.");
        }
        if (m.getBrandName().length() > MAX_NAME_LENGTH || m.getGenericName().length() > MAX_NAME_LENGTH
                || m.getStrength().length() > 40) {
            throw new ServiceException("The names must be under " + MAX_NAME_LENGTH + " characters and the strength under 40.");
        }
        if (m.getForm() == null) {
            throw new ServiceException("Choose the form (tablet, syrup, ...).");
        }
        if (m.getForm() == MedicineForm.OTHER && m.getFormOther().isEmpty()) {
            throw new ServiceException("Write the form (e.g. Mouthwash) when you choose Other.");
        }
        if (m.getFormOther().length() > 30) {
            throw new ServiceException("Please keep the form under 30 characters.");
        }
    }

    /** Trims and collapses repeated spaces ("500  mg" → "500 mg"). */
    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    public static MedicineForm[] getForms() {
        return MedicineForm.values();
    }

    private static void requireAdmin() throws ServiceException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new ServiceException("Only an admin can change the medicine catalog.");
        }
    }

    /** The logged-in pharmacy, read fresh; it must be VERIFIED to add medicines or stock. */
    private Pharmacy requireVerifiedPharmacy() throws ServiceException, SQLException {
        if (!Session.hasRole(Role.PHARMACY) || Session.getPharmacy() == null) {
            throw new ServiceException("Only a pharmacy account can do this.");
        }
        Pharmacy p = pharmacyDao.findById(Session.getPharmacy().getId());
        if (p == null || p.getStatus() != PharmacyStatus.VERIFIED) {
            throw new ServiceException("Only a verified pharmacy can add medicines or stock.");
        }
        return p;
    }
}
