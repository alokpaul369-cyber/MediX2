package medix.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.model.MedicineSource;
import medix.model.MedicineStatus;
import medix.model.MedicineView;
import medix.util.DBConnection;
import medix.util.DateUtil;

/**
 * Data access for the medicines table.
 *
 * Status rules (Fix 3): patients and pharmacies see APPROVED and PENDING medicines
 * ({@link #SELLABLE}); a pharmacy's autocomplete only offers ITS OWN pending ones; REJECTED and
 * MERGED medicines are never offered again (a merged one is still matched by name in patient search,
 * which then shows the medicine it was merged into).
 */
public class MedicineDao extends BaseDao<Medicine> {

    /** SQL condition for medicines whose stock patients can find. */
    public static final String SELLABLE = "status IN ('APPROVED', 'PENDING')";

    /** The catalog screen: the medicine + who added it (pharmacy name for pharmacy owners). */
    private static final String VIEW_SELECT = "SELECT m.*, u.full_name AS creator_name, "
            + "p.name AS creator_pharmacy FROM medicines m LEFT JOIN users u ON u.id = m.created_by "
            + "LEFT JOIN pharmacies p ON p.owner_id = m.created_by ";

    @Override
    protected String tableName() {
        return "medicines";
    }

    @Override
    protected Medicine mapRow(ResultSet rs) throws SQLException {
        Medicine m = new Medicine();
        m.setId(rs.getInt("id"));
        m.setBrandName(rs.getString("brand_name"));
        m.setGenericName(rs.getString("generic_name"));
        m.setStrength(rs.getString("strength"));
        m.setForm(getEnum(rs, "form", MedicineForm.class));
        m.setFormOther(rs.getString("form_other"));
        m.setManufacturer(rs.getString("manufacturer"));
        m.setRxRequired(getBoolean(rs, "rx_required"));
        m.setSource(getEnum(rs, "source", MedicineSource.class));
        m.setStatus(getEnum(rs, "status", MedicineStatus.class));
        m.setCreatedBy(getInteger(rs, "created_by"));
        m.setCreatedAt(rs.getString("created_at"));
        m.setReviewedBy(getInteger(rs, "reviewed_by"));
        m.setReviewedAt(rs.getString("reviewed_at"));
        m.setReviewNote(rs.getString("review_note"));
        m.setMergedIntoId(getInteger(rs, "merged_into_id"));
        return m;
    }

    private MedicineView mapView(ResultSet rs) throws SQLException {
        Medicine m = mapRow(rs);
        String addedBy;
        if (m.getCreatedBy() == null) {
            addedBy = m.getSource() == MedicineSource.SEED ? "Seed" : "–";
        } else if (rs.getString("creator_pharmacy") != null) {
            addedBy = rs.getString("creator_pharmacy");
        } else {
            addedBy = rs.getString("creator_name") == null ? "Deleted user" : rs.getString("creator_name");
        }
        return new MedicineView(m, addedBy);
    }

    public int insert(Medicine m) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return insert(con, m);
        }
    }

    /** Insert on the caller's connection (for transactions). */
    public int insert(Connection con, Medicine m) throws SQLException {
        if (m.getCreatedAt() == null) {
            m.setCreatedAt(DateUtil.now());
        }
        int id = executeInsert(con,
                "INSERT INTO medicines (brand_name, generic_name, strength, form, form_other, manufacturer, "
                + "rx_required, source, status, created_by, created_at, reviewed_by, reviewed_at, review_note, "
                + "merged_into_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                m.getBrandName(), m.getGenericName(), m.getStrength(), m.getForm(), m.getFormOther(),
                m.getManufacturer(), m.isRxRequired(), m.getSource(), m.getStatus(), m.getCreatedBy(),
                m.getCreatedAt(), m.getReviewedBy(), m.getReviewedAt(), m.getReviewNote(), m.getMergedIntoId());
        m.setId(id);
        return id;
    }

    /** The descriptive fields (names, strength, form, manufacturer, Rx); not the review fields. */
    public boolean update(Medicine m) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return update(con, m);
        }
    }

    public boolean update(Connection con, Medicine m) throws SQLException {
        return executeUpdate(con,
                "UPDATE medicines SET brand_name = ?, generic_name = ?, strength = ?, form = ?, form_other = ?, "
                + "manufacturer = ?, rx_required = ? WHERE id = ?",
                m.getBrandName(), m.getGenericName(), m.getStrength(), m.getForm(), m.getFormOther(),
                m.getManufacturer(), m.isRxRequired(), m.getId()) > 0;
    }

    /**
     * Review decision: status (from PENDING only, so two admins cannot decide twice), reviewer,
     * time, note and – for MERGED – the medicine it was merged into. False when it was not pending.
     */
    public boolean review(Connection con, int id, MedicineStatus status, int reviewerId, String note,
            Integer mergedIntoId) throws SQLException {
        return executeUpdate(con, "UPDATE medicines SET status = ?, reviewed_by = ?, reviewed_at = ?, review_note = ?, "
                + "merged_into_id = ? WHERE id = ? AND status = 'PENDING'",
                status, reviewerId, DateUtil.now(), note, mergedIntoId, id) > 0;
    }

    /** Medicines that were merged into `from` now point to `to` (no chains). */
    public int repointMerged(Connection con, int from, int to) throws SQLException {
        return executeUpdate(con, "UPDATE medicines SET merged_into_id = ? WHERE merged_into_id = ?", to, from);
    }

    /**
     * Another medicine with the same brand + strength + form (+ form_other), ignoring case, except
     * the row with excludeId (0 when adding). Null if there is none. Every status counts: the UNIQUE
     * constraint does too.
     */
    public Medicine findDuplicate(String brand, String strength, MedicineForm form, String formOther, int excludeId)
            throws SQLException {
        return findOne("SELECT * FROM medicines WHERE brand_name = ? COLLATE NOCASE "
                + "AND strength = ? COLLATE NOCASE AND form = ? AND form_other = ? COLLATE NOCASE AND id <> ?",
                brand, strength, form, formOther == null ? "" : formOther, excludeId);
    }

    /**
     * Up to limit medicines whose brand or generic name contains the text; brands that START with
     * the text come first (for autocomplete). APPROVED medicines plus the PENDING ones added by the
     * user with id pendingOwner (null = approved only).
     */
    public List<Medicine> suggest(String text, Integer pendingOwner, int limit) throws SQLException {
        String t = text.trim();
        return findList("SELECT * FROM medicines WHERE (status = 'APPROVED' OR (status = 'PENDING' AND created_by = ?)) "
                + "AND (brand_name LIKE ? OR generic_name LIKE ?) "
                + "ORDER BY CASE WHEN brand_name LIKE ? THEN 0 ELSE 1 END, brand_name, strength LIMIT ?",
                pendingOwner, "%" + t + "%", "%" + t + "%", t + "%", limit);
    }

    /** Other sellable medicines with the same generic name (ignoring case), e.g. other Omeprazole brands. */
    public List<Medicine> findByGeneric(String genericName, int excludeId) throws SQLException {
        return findList("SELECT * FROM medicines WHERE generic_name = ? COLLATE NOCASE AND id <> ? AND " + SELLABLE
                + " ORDER BY brand_name, strength", genericName.trim(), excludeId);
    }

    /** Brand or generic name contains the text (case-insensitive), any status. */
    public List<Medicine> searchByName(String text) throws SQLException {
        String like = "%" + text.trim() + "%";
        return findList("SELECT * FROM medicines WHERE brand_name LIKE ? OR generic_name LIKE ? "
                + "ORDER BY brand_name", like, like);
    }

    /** Patient search catalog: APPROVED, PENDING and MERGED (a merged name leads to its target). */
    public List<Medicine> findSearchable() throws SQLException {
        return findList("SELECT * FROM medicines WHERE status IN ('APPROVED', 'PENDING', 'MERGED') ORDER BY id");
    }

    /** What a new medicine is compared with for "These look similar": APPROVED and PENDING medicines. */
    public List<Medicine> findSellable() throws SQLException {
        return findList("SELECT * FROM medicines WHERE " + SELLABLE + " ORDER BY id");
    }

    public List<Medicine> findByStatus(MedicineStatus status) throws SQLException {
        return findList("SELECT * FROM medicines WHERE status = ? ORDER BY brand_name, strength", status);
    }

    public int countByStatus(MedicineStatus status) throws SQLException {
        return queryInt("SELECT COUNT(*) FROM medicines WHERE status = ?", status);
    }

    /** Catalog screen rows: text in brand or generic name, status and source filters (null = any). */
    public List<MedicineView> findViews(String text, MedicineStatus status, MedicineSource source) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return query(VIEW_SELECT + "WHERE (m.brand_name LIKE ? OR m.generic_name LIKE ?) "
                + "AND (? IS NULL OR m.status = ?) AND (? IS NULL OR m.source = ?) "
                + "ORDER BY CASE WHEN m.status = 'PENDING' THEN 0 ELSE 1 END, m.brand_name, m.strength",
                this::mapView, like, like, status, status, source, source);
    }

    public MedicineView findView(int id) throws SQLException {
        List<MedicineView> list = query(VIEW_SELECT + "WHERE m.id = ?", this::mapView, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** A medicine with exactly this brand + strength (ignoring case), any form; null if none (demo data). */
    public Medicine findByBrandAndStrength(String brand, String strength) throws SQLException {
        return findOne("SELECT * FROM medicines WHERE brand_name = ? COLLATE NOCASE AND strength = ? COLLATE NOCASE "
                + "ORDER BY id LIMIT 1", brand, strength);
    }
}
