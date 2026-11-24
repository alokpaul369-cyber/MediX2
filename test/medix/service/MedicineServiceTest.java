package medix.service;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import medix.dao.AreaDao;
import medix.dao.InventoryDao;
import medix.dao.MedicineDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.engine.AvailabilityCache;
import medix.engine.SearchEngine;
import medix.model.Area;
import medix.model.Inventory;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.model.MedicineSource;
import medix.model.MedicineStatus;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.Place;
import medix.model.ReservationStatus;
import medix.model.ReservationView;
import medix.model.Role;
import medix.model.SearchResult;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.Session;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Fix 3: pharmacies add medicines (PENDING), admins approve / merge / reject them. Temporary
 * database; seeded medicine 1 = Napa 500 mg Tablet (Paracetamol).
 */
public class MedicineServiceTest {

    private static final int NAPA = 1;
    private static final int ACE = 4;

    private static User admin;
    private static User ownerA;
    private static User ownerB;
    private static User patient;
    private static Pharmacy pharmacyA;
    private static Pharmacy pharmacyB;
    private static Area dhanmondi;

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        dhanmondi = new AreaDao().findByName("Dhanmondi");
        admin = new UserDao().findById(1);
        ownerA = user("Owner A", "a@test.com", Role.PHARMACY);
        ownerB = user("Owner B", "b@test.com", Role.PHARMACY);
        patient = user("Patient", "p@test.com", Role.PATIENT);
        pharmacyA = pharmacy(ownerA, "Pharmacy A", "DL-500001");
        pharmacyB = pharmacy(ownerB, "Pharmacy B", "DL-500002");
    }

    @Before
    public void reset() {
        AvailabilityCache.clear();
        Session.logout();
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    public void similarMedicinesAreFoundBeforeSaving() throws Exception {
        Medicine draft = draft("napa", "Paracetamol", "500mg", MedicineForm.TABLET);
        List<Medicine> similar = MedicineService.findSimilar(draft, new MedicineDao().findSellable());
        assertFalse(similar.isEmpty());
        assertEquals("napa 500mg finds Napa 500 mg first", NAPA, similar.get(0).getId());
        assertTrue("same generic + strength + form (Ace 500 mg) is listed too", brands(similar).contains("Ace"));
        assertFalse("another form is a different medicine", brands(similar).contains("Napa Extra"));

        // a spelling mistake in the brand
        Medicine typo = draft("Nappa", "Acetaminophen", "500 MG", MedicineForm.TABLET);
        assertEquals(NAPA, MedicineService.findSimilar(typo, new MedicineDao().findSellable()).get(0).getId());
        // the strength typed into the brand field
        Medicine all = draft("Napa 500mg", "Paracetamol", "500 mg", MedicineForm.TABLET);
        assertEquals(NAPA, MedicineService.findSimilar(all, new MedicineDao().findSellable()).get(0).getId());
        // nothing like it
        assertTrue(MedicineService.findSimilar(draft("Qwertyx", "Unobtainium", "3 mg", MedicineForm.PATCH),
                new MedicineDao().findSellable()).isEmpty());

        String[] parts = MedicineService.splitTypedText("napa 500mg");
        assertEquals("napa", parts[0]);
        assertEquals("500mg", parts[1]);
        assertEquals("", MedicineService.splitTypedText("Ventolin")[1]);
        assertEquals("250 mg/5 ml", MedicineService.splitTypedText("Napa 250 mg/5 ml")[1]);
    }

    @Test
    public void anExactDuplicateIsNeverCreated() throws Exception {
        asPharmacy(ownerA, pharmacyA);
        int before = new MedicineDao().count();
        MedicineService.AddResult r = new MedicineService().addByPharmacy(draft("NAPA", "paracetamol", "500  mg",
                MedicineForm.TABLET));
        assertFalse(r.isCreated());
        assertEquals(NAPA, r.getMedicine().getId());
        assertEquals(before, new MedicineDao().count());
    }

    // ---------------------------------------------------------------- add, edit, approve

    @Test
    public void aPharmacyAddsAMedicineAndSellsItStraightAway() throws Exception {
        asPharmacy(ownerA, pharmacyA);
        int notesBefore = count("SELECT COUNT(*) FROM notifications WHERE user_id = 1");
        Medicine m = new MedicineService().addByPharmacy(draft("Fixit", "Fixamol", "10 mg", MedicineForm.OTHER,
                "Mouthwash")).getMedicine();
        assertTrue(m.getId() > 0);
        Medicine saved = new MedicineDao().findById(m.getId());
        assertEquals(MedicineStatus.PENDING, saved.getStatus());
        assertEquals(MedicineSource.PHARMACY, saved.getSource());
        assertEquals(Integer.valueOf(ownerA.getId()), saved.getCreatedBy());
        assertEquals("Mouthwash", saved.getFormLabel());
        assertEquals("every admin is told", notesBefore + 1, count("SELECT COUNT(*) FROM notifications WHERE user_id = 1"));
        assertEquals("Pharmacy A added a new medicine: Fixit 10 mg Mouthwash. Please review it.",
                text("SELECT message FROM notifications WHERE user_id = 1 ORDER BY id DESC LIMIT 1"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'MEDICINE_ADDED' AND entity_id = " + m.getId()));

        // its own pending medicine is offered to it, not to other pharmacies
        assertTrue(contains(new InventoryService().suggestMedicines("fixit"), m.getId()));
        new InventoryService().addBatch(m.getId(), "FX-1", 30, new BigDecimal("12.50"),
                DateUtil.parseDate(DateUtil.todayPlusDays(200)), 5);
        asPharmacy(ownerB, pharmacyB);
        assertFalse(contains(new InventoryService().suggestMedicines("fixit"), m.getId()));

        // patients find it at once ("New – not yet reviewed")
        Session.login(patient, null);
        SearchResult r = search("fixit");
        assertEquals(m.getId(), r.getMedicine().getId());
        assertTrue(r.getMedicine().isPending());
        assertEquals(1, r.getRows().size());

        // only the pharmacy that added it may edit it, and only until it is reviewed
        asPharmacy(ownerA, pharmacyA);
        assertTrue(MedicineService.canPharmacyEdit(saved));
        saved.setStrength("15 mg");
        new MedicineService().updateMyPending(saved);
        asPharmacy(ownerB, pharmacyB);
        assertFalse(MedicineService.canPharmacyEdit(saved));
        try {
            new MedicineService().updateMyPending(saved);
            fail("pharmacy B did not add it");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("Only the pharmacy"));
        }
        Session.login(admin, null);
        new MedicineService().approve(m.getId());
        assertEquals(MedicineStatus.APPROVED, new MedicineDao().findById(m.getId()).getStatus());
        assertEquals(Integer.valueOf(1), new MedicineDao().findById(m.getId()).getReviewedBy());
        asPharmacy(ownerA, pharmacyA);
        assertFalse("after the review only an admin edits it", MedicineService.canPharmacyEdit(
                new MedicineDao().findById(m.getId())));
        assertTrue("the pharmacy was told", text("SELECT message FROM notifications WHERE user_id = " + ownerA.getId()
                + " ORDER BY id DESC LIMIT 1").contains("approved"));
    }

    // ---------------------------------------------------------------- merge

    @Test
    public void mergeMovesStockCombinesTheSameBatchAndKeepsReservationsValid() throws Exception {
        // pharmacy A already sells Napa 500 mg, batch NAP-77
        int napaBatch = batch(pharmacyA, NAPA, "NAP-77", 50, new BigDecimal("1.20"));
        asPharmacy(ownerA, pharmacyA);
        Medicine dup = new MedicineService().addByPharmacy(draft("Napa", "Paracetamol", "500mg", MedicineForm.TABLET))
                .getMedicine();
        assertEquals(MedicineStatus.PENDING, dup.getStatus());
        InventoryService inventory = new InventoryService();
        int same = inventory.addBatch(dup.getId(), "nap-77", 20, new BigDecimal("1.25"),
                DateUtil.parseDate(DateUtil.todayPlusDays(90)), 5);
        int other = inventory.addBatch(dup.getId(), "NEW-2", 10, new BigDecimal("1.25"),
                DateUtil.parseDate(DateUtil.todayPlusDays(300)), 5);
        // a patient holds 4 units of the duplicate (FEFO: batch "nap-77")
        Session.login(patient, null);
        ReservationView v = new ReservationService().reserve(patient.getId(), pharmacyA.getId(), dup.getId(), 4, 45, null);
        assertEquals(Integer.valueOf(same), v.getReservation().getInventoryId());
        search("napa 500mg"); // a search log row for one of the two

        Session.login(admin, null);
        String summary = new MedicineService().merge(dup.getId(), NAPA);
        assertEquals("1 batch(es) moved, 1 combined with the same batch", summary);

        assertEquals("no stock left on the duplicate", 0, count("SELECT COUNT(*) FROM inventory WHERE medicine_id = " + dup.getId()));
        assertEquals("the same batch: one row, quantities added", 70, count("SELECT quantity FROM inventory WHERE id = " + napaBatch));
        assertEquals("…and the reserved units", 4, count("SELECT reserved_qty FROM inventory WHERE id = " + napaBatch));
        assertEquals("the extra row is gone", 0, count("SELECT COUNT(*) FROM inventory WHERE id = " + same));
        assertEquals("the other batch moved", NAPA, count("SELECT medicine_id FROM inventory WHERE id = " + other));
        assertEquals("the reservation points to the kept row", napaBatch,
                count("SELECT inventory_id FROM reservations WHERE id = " + v.getReservation().getId()));
        assertEquals(NAPA, count("SELECT medicine_id FROM reservations WHERE id = " + v.getReservation().getId()));
        assertEquals("the history of the deleted row is kept", 0,
                count("SELECT COUNT(*) FROM stock_movements WHERE inventory_id = " + same));
        assertTrue(count("SELECT COUNT(*) FROM stock_movements WHERE inventory_id = " + napaBatch) >= 3);
        assertEquals("searches moved too", 0, count("SELECT COUNT(*) FROM search_logs WHERE medicine_id = " + dup.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM (SELECT 1 FROM pragma_foreign_key_check)"));

        Medicine merged = new MedicineDao().findById(dup.getId());
        assertEquals(MedicineStatus.MERGED, merged.getStatus());
        assertEquals(Integer.valueOf(NAPA), merged.getMergedIntoId());
        assertEquals("Napa 500mg Tablet was merged into Napa 500 mg Tablet. Your batches of it now use Napa 500 mg Tablet.",
                text("SELECT message FROM notifications WHERE user_id = " + ownerA.getId() + " ORDER BY id DESC LIMIT 1"));

        // the reservation can still be picked up from the kept row
        asPharmacy(ownerA, pharmacyA);
        PharmacyReservationService pharmacySide = new PharmacyReservationService();
        pharmacySide.confirm(v.getReservation().getId());
        pharmacySide.pickUp(v.getReservation().getId());
        assertEquals(ReservationStatus.COMPLETED, new ReservationService().getView(v.getReservation().getId())
                .getReservation().getStatus());
        assertEquals(66, count("SELECT quantity FROM inventory WHERE id = " + napaBatch));
        assertEquals(0, count("SELECT reserved_qty FROM inventory WHERE id = " + napaBatch));

        // the merged medicine is gone from search; its name finds the medicine it was merged into
        Session.login(patient, null);
        for (Medicine s : new SearchService().suggest("napa 500mg")) {
            assertTrue("never suggested", s.getId() != dup.getId());
        }
        SearchResult r = new SearchService().search("Napa 500mg Tablet", merged, place(), null, SearchEngine.Sort.DISTANCE);
        assertEquals(NAPA, r.getMedicine().getId());
        assertEquals(1, r.getRows().size());
        assertEquals(76, r.getRows().get(0).getAvailable());

        try {
            Session.login(admin, null);
            new MedicineService().approve(dup.getId());
            fail("a merged medicine is not pending any more");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("already reviewed"));
        }
    }

    // ---------------------------------------------------------------- reject

    @Test
    public void rejectHidesTheStockFromSearchButKeepsReservations() throws Exception {
        asPharmacy(ownerB, pharmacyB);
        Medicine m = new MedicineService().addByPharmacy(draft("Zorvex", "Zorvexium", "25 mg", MedicineForm.CAPSULE))
                .getMedicine();
        new InventoryService().addBatch(m.getId(), "ZV-1", 40, new BigDecimal("3.00"),
                DateUtil.parseDate(DateUtil.todayPlusDays(200)), 5);
        Session.login(patient, null);
        assertEquals(1, search("zorvex").getRows().size());
        ReservationView v = new ReservationService().reserve(patient.getId(), pharmacyB.getId(), m.getId(), 2, 45, null);

        Session.login(admin, null);
        try {
            new MedicineService().reject(m.getId(), "no");
            fail("a reason is needed");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("reason"));
        }
        new MedicineService().reject(m.getId(), "Not a registered medicine");
        assertEquals(MedicineStatus.REJECTED, new MedicineDao().findById(m.getId()).getStatus());
        assertEquals("Not a registered medicine", new MedicineDao().findById(m.getId()).getReviewNote());

        Session.login(patient, null);
        SearchResult r = search("zorvex");
        assertTrue("a rejected medicine is never shown", r.getMedicine() == null || r.getMedicine().getId() != m.getId());
        assertTrue(new InventoryDao().findStockedPharmacies(m.getId()).isEmpty());
        assertEquals("the running reservation stays valid", ReservationStatus.PENDING,
                new ReservationService().getView(v.getReservation().getId()).getReservation().getStatus());
        try {
            new ReservationService().reserve(patient.getId(), pharmacyA.getId(), m.getId(), 1, 45, null);
            fail("no new reservations of a rejected medicine");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("no longer"));
        }
        assertTrue(text("SELECT message FROM notifications WHERE user_id = " + ownerB.getId()
                + " AND message LIKE '%rejected%' ORDER BY id DESC LIMIT 1").contains("Not a registered medicine"));

        // the pharmacy moves its batches to the correct medicine: patients find them again
        asPharmacy(ownerB, pharmacyB);
        new MedicineService().moveMyStock(m.getId(), ACE);
        assertEquals(0, count("SELECT COUNT(*) FROM inventory WHERE medicine_id = " + m.getId()));
        assertEquals(ACE, count("SELECT medicine_id FROM reservations WHERE id = " + v.getReservation().getId()));
    }

    // ---------------------------------------------------------------- helpers

    private static Medicine draft(String brand, String generic, String strength, MedicineForm form) {
        return draft(brand, generic, strength, form, "");
    }

    private static Medicine draft(String brand, String generic, String strength, MedicineForm form, String other) {
        Medicine m = new Medicine();
        m.setBrandName(brand);
        m.setGenericName(generic);
        m.setStrength(strength);
        m.setForm(form);
        m.setFormOther(other);
        return m;
    }

    private static List<String> brands(List<Medicine> list) {
        List<String> out = new java.util.ArrayList<String>();
        for (Medicine m : list) {
            out.add(m.getBrandName());
        }
        return out;
    }

    private static boolean contains(List<Medicine> list, int id) {
        for (Medicine m : list) {
            if (m.getId() == id) {
                return true;
            }
        }
        return false;
    }

    private static SearchResult search(String text) throws Exception {
        return new SearchService().search(text, null, place(), null, SearchEngine.Sort.DISTANCE, "BDT");
    }

    private static Place place() {
        Place p = Place.fromArea(dhanmondi);
        return p;
    }

    private static void asPharmacy(User owner, Pharmacy p) throws Exception {
        Session.login(owner, new PharmacyDao().findById(p.getId()));
    }

    private static User user(String name, String email, Role role) throws Exception {
        User u = new User();
        u.setFullName(name);
        u.setEmail(email);
        u.setPhone("01711111111");
        u.setSalt("c2FsdA==");
        u.setPasswordHash("x");
        u.setRole(role);
        u.setAreaId(dhanmondi.getId());
        u.setActive(true);
        new UserDao().insert(u);
        return u;
    }

    private static Pharmacy pharmacy(User owner, String name, String license) throws Exception {
        Pharmacy p = new Pharmacy();
        p.setOwnerId(owner.getId());
        p.setName(name);
        p.setLicenseNo(license);
        p.setAddress("Road 1, Dhanmondi");
        p.setAreaId(dhanmondi.getId());
        p.setLatitude(dhanmondi.getLatitude());
        p.setLongitude(dhanmondi.getLongitude());
        p.setOpenTime("00:00");
        p.setCloseTime("00:00"); // open 24 hours, so reservations work at any time
        p.setStatus(PharmacyStatus.VERIFIED);
        new PharmacyDao().insert(p);
        return p;
    }

    private static int batch(Pharmacy p, int medicineId, String batchNo, int qty, BigDecimal price) throws Exception {
        Inventory i = new Inventory();
        i.setPharmacyId(p.getId());
        i.setMedicineId(medicineId);
        i.setBatchNo(batchNo);
        i.setQuantity(qty);
        i.setPrice(price);
        i.setExpiryDate(DateUtil.todayPlusDays(120));
        i.setLowStockThreshold(5);
        return new InventoryDao().insert(i);
    }

    private static int count(String sql) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

}
