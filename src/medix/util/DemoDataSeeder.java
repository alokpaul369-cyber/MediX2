package medix.util;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;
import medix.dao.AreaDao;
import medix.dao.InventoryDao;
import medix.dao.JobRunDao;
import medix.dao.MedicineDao;
import medix.dao.PharmacyDao;
import medix.dao.ReservationDao;
import medix.dao.SearchLogDao;
import medix.dao.UserDao;
import medix.engine.AvailabilityCache;
import medix.engine.ExchangeRateService;
import medix.engine.GeoUtil;
import medix.model.Area;
import medix.model.Inventory;
import medix.model.CancelReason;
import medix.model.JobRun;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.PharmacyStock;
import medix.model.Place;
import medix.model.ReportReason;
import medix.model.ReservationView;
import medix.model.Role;
import medix.model.SearchLog;
import medix.model.User;
import medix.service.AlertService;
import medix.service.AuditService;
import medix.service.AuthService;
import medix.service.InventoryService;
import medix.service.JobRunService;
import medix.service.LocationService;
import medix.service.MedicineService;
import medix.service.PharmacyReservationService;
import medix.service.ReportService;
import medix.service.ReservationService;
import medix.service.ServiceException;
import medix.service.TrustScoreService;
import medix.service.VerificationService;

/**
 * "Load demo data" on the login screen: 6 patients, 12 pharmacies with license images, stock,
 * 14 days of searches, reservations in every status, reports, job history and audit entries.
 * Every row goes through the services / DAOs, so ids follow the ID rule and stock movements,
 * notifications and audit logs are written exactly as when people use the app.
 * Logins: patient@medix.com and pharmacy1..14@medix.com, password demo123.
 *
 * Fix 3 additions (also added to demo data loaded before Fix 3, only what is missing): 3 medicines
 * added by demo pharmacies that wait for review (one an obvious duplicate, "Napa 500mg" of Napa 500 mg,
 * with the same batch number, to demo Merge), two verified pharmacies abroad with their own currency
 * (London, GBP and Kolkata, INR) and manual exchange rates for BDT, USD, EUR, GBP and INR so prices
 * convert without internet.
 */
public final class DemoDataSeeder {

    public static final String PASSWORD = "demo123";
    public static final String PATIENT_EMAIL = "patient@medix.com";
    public static final int SEARCH_DAYS = 14;
    /** Search radius used to decide how many pharmacies a demo search found (the default 5 km). */
    private static final double SEARCH_RADIUS_KM = 5.0;
    private static final String H24 = "00:00"; // open = close: 24 hours

    /** Reports what the seeder is doing (shown on the login screen). */
    public interface Progress {
        void step(String text);
    }

    // name, area, address, status, open, close
    private static final Object[][] PHARMACIES = {
        {"Green Life Pharmacy", "Dhanmondi", "House 18, Road 7/A, Dhanmondi, Dhaka", PharmacyStatus.VERIFIED, H24, H24},
        {"Shifa Pharmacy", "Mohammadpur", "12/3 Asad Avenue, Mohammadpur, Dhaka", PharmacyStatus.VERIFIED, "08:00", "23:00"},
        {"City Drug Store", "Farmgate", "56 Green Road, Farmgate, Dhaka", PharmacyStatus.VERIFIED, "09:00", "22:30"},
        {"Care Point Pharmacy", "Mirpur 10", "Plot 4, Begum Rokeya Sarani, Mirpur 10, Dhaka", PharmacyStatus.VERIFIED, "08:00", "23:30"},
        {"Al-Madina Drug House", "Lalbagh", "21 Lalbagh Road, Lalbagh, Dhaka", PharmacyStatus.VERIFIED, "09:00", "22:00"},
        {"Medi Home", "Gulshan 1", "House 9, Road 32, Gulshan 1, Dhaka", PharmacyStatus.VERIFIED, H24, H24},
        {"Popular Medicine Corner", "Dhanmondi", "House 41, Satmasjid Road, Dhanmondi, Dhaka", PharmacyStatus.VERIFIED, H24, H24},
        {"Health Plus", "Banani", "House 67, Road 11, Banani, Dhaka", PharmacyStatus.VERIFIED, "08:00", "23:00"},
        {"Lifeline Pharmacy", "Uttara Sector 7", "House 14, Road 18, Sector 7, Uttara, Dhaka", PharmacyStatus.VERIFIED, H24, H24},
        {"Nova Pharma Point", "Bashundhara R/A", "Block C, Road 5, Bashundhara R/A, Dhaka", PharmacyStatus.PENDING, "09:00", "22:00"},
        {"Sheba Medicine Corner", "Motijheel", "45 Dilkusha C/A, Motijheel, Dhaka", PharmacyStatus.PENDING, "08:30", "21:30"},
        {"Rx Corner", "Mirpur 10", "Shop 7, Mirpur 10 Circle, Dhaka", PharmacyStatus.SUSPENDED, "09:00", "23:00"}
    };
    private static final String REVERIFY_PHARMACY = "City Drug Store";
    private static final String STALE_PHARMACY = "Al-Madina Drug House";
    private static final String NO_INSULIN_PHARMACY = "Lifeline Pharmacy"; // the only one in Uttara Sector 7

    // full name, email, area
    private static final String[][] PATIENTS = {
        {"Ayesha Rahman", PATIENT_EMAIL, "Dhanmondi"},
        {"Tanvir Hasan", "patient2@medix.com", "Mirpur 10"},
        {"Nusrat Jahan", "patient3@medix.com", "Uttara Sector 7"},
        {"Rafiq Islam", "patient4@medix.com", "Mohammadpur"},
        {"Farhana Akter", "patient5@medix.com", "Gulshan 1"},
        {"Imran Hossain", "patient6@medix.com", "Motijheel"}
    };

    // catalog key (brand + strength), typical retail price per unit in Tk (exact, as text), search weight
    private static final Object[][] CATALOG = {
        {"Napa 500 mg", "1.20", 30}, {"Napa Extra 500 mg + 65 mg", "2.50", 10}, {"Napa 120 mg/5 ml", "35", 5},
        {"Ace 500 mg", "1.20", 4}, {"Seclo 20 mg", "7", 11}, {"Sergel 20 mg", "7", 4}, {"Losectil 20 mg", "5", 3},
        {"Fexo 120 mg", "9", 8}, {"Alatrol 10 mg", "3", 5}, {"Histacin 4 mg", "0.60", 2}, {"Monas 10 mg", "16", 4},
        {"Azithrocin 500 mg", "40", 5}, {"Azithrocin 200 mg/5 ml", "120", 2}, {"Amodis 400 mg", "1.30", 2},
        {"Moxacil 500 mg", "6", 4}, {"Cef-3 200 mg", "35", 3}, {"Ceftron 500 mg", "160", 1}, {"Comet 500 mg", "4", 5},
        {"Insulet R 100 IU/ml", "480", 3}, {"Osartil 50 mg", "8", 4}, {"Bislol 5 mg", "8", 2}, {"Camlodin 5 mg", "6", 4},
        {"Atova 10 mg", "15", 3}, {"Rivotril 0.5 mg", "7", 1}, {"Ventolin 100 mcg/puff", "230", 2},
        {"Ciprocin 0.3% eye drops", "60", 1}, {"Betnovate 0.1%", "70", 1}, {"ORSaline-N 10.25 g/sachet", "6", 5},
        {"Ceevit 250 mg", "1.50", 2}, {"Calbo-D 500 mg + 200 IU", "12", 2}
    };

    // ---------------------------------------------------------------- Fix 3 demo data

    /** Manual exchange rates (units per 1 USD) so conversion works without internet. */
    private static final String[][] DEMO_RATES = {
        {"BDT", "122.00"}, {"USD", "1"}, {"EUR", "0.92"}, {"GBP", "0.78"}, {"INR", "88.50"}
    };

    // email number, name, display name, city, country, country code, lat, lon, address, license, phone, price list
    private static final Object[][] ABROAD = {
        {13, "Westminster Chemist", "Victoria Street, Westminster, London, Greater London, England, United Kingdom",
            "London", "United Kingdom", "GB", 51.4975, -0.1357, "64 Victoria Street, London SW1E 6QP",
            "GB-GPHC-1182733", "+44 20 7946 0123",
            new String[][]{{"Napa 500 mg", "0.15"}, {"Seclo 20 mg", "0.45"}, {"Fexo 120 mg", "0.60"},
                {"Alatrol 10 mg", "0.25"}, {"Ventolin 100 mcg/puff", "3.20"}, {"Azithrocin 500 mg", "1.10"},
                {"Atova 10 mg", "0.40"}, {"Comet 500 mg", "0.12"}, {"Ceevit 250 mg", "0.10"},
                {"ORSaline-N 10.25 g/sachet", "0.35"}}},
        {14, "Park Street Pharmacy", "Park Street, Kolkata, West Bengal, India",
            "Kolkata", "India", "IN", 22.5529, 88.3525, "21 Park Street, Kolkata 700016",
            "WB-KOL-20-114472", "+91 33 2229 4471",
            new String[][]{{"Napa 500 mg", "1.50"}, {"Napa Extra 500 mg + 65 mg", "2.20"}, {"Seclo 20 mg", "6.50"},
                {"Sergel 20 mg", "7.00"}, {"Fexo 120 mg", "12.00"}, {"Monas 10 mg", "14.50"},
                {"Azithrocin 500 mg", "28.00"}, {"Osartil 50 mg", "6.00"}, {"Camlodin 5 mg", "3.50"},
                {"Insulet R 100 IU/ml", "350.00"}, {"ORSaline-N 10.25 g/sachet", "4.00"}}}
    };

    // pharmacy (email number), brand, generic, strength, form, manufacturer, units, price (BDT)
    private static final Object[][] PENDING_MEDICINES = {
        {1, "Napa", "Paracetamol", "500mg", MedicineForm.TABLET, "Beximco Pharmaceuticals", 120, "1.20"},
        {6, "Maxpro", "Esomeprazole", "20 mg", MedicineForm.TABLET, "Renata Limited", 200, "7.00"},
        {7, "Deslor", "Desloratadine", "5 mg", MedicineForm.TABLET, "Square Pharmaceuticals", 150, "4.00"}
    };
    /** Searches for medicines that are not in the catalog (found nothing). */
    private static final String[] UNKNOWN_QUERIES = {"panadol", "maxpro 20", "tufnil", "deslor", "napa extend", "rolac"};

    /** Medicines the reservation pharmacies must have (normal stock, not low or expired). */
    private static final Map<String, String[]> REQUIRED = new HashMap<String, String[]>();

    static {
        REQUIRED.put("Green Life Pharmacy", new String[]{"Napa 500 mg", "Seclo 20 mg", "Napa Extra 500 mg + 65 mg", "Fexo 120 mg"});
        REQUIRED.put("Popular Medicine Corner", new String[]{"Fexo 120 mg", "Seclo 20 mg", "Napa 500 mg", "ORSaline-N 10.25 g/sachet"});
        REQUIRED.put("Lifeline Pharmacy", new String[]{"Napa 500 mg", "Comet 500 mg"});
        REQUIRED.put("Medi Home", new String[]{"Ventolin 100 mcg/puff", "Napa 500 mg", "Seclo 20 mg", "Alatrol 10 mg"});
    }

    private final Random random = new Random(2026);
    private final Progress progress;
    private final UserDao userDao = new UserDao();
    private final PharmacyDao pharmacyDao = new PharmacyDao();
    private final AuthService authService = new AuthService();

    private User admin;
    private final Map<String, Area> areas = new HashMap<String, Area>();
    private final Map<String, Medicine> medicines = new LinkedHashMap<String, Medicine>();
    private final List<User> patients = new ArrayList<User>();
    private final Map<String, Pharmacy> pharmacies = new LinkedHashMap<String, Pharmacy>();
    private final Map<String, User> owners = new HashMap<String, User>();

    private DemoDataSeeder(Progress progress) {
        this.progress = progress == null ? text -> { } : progress;
    }

    /** True when the demo accounts of Parts 1-10 exist (then they are not loaded again). */
    public static boolean isLoaded() throws SQLException {
        UserDao users = new UserDao();
        return users.findByEmail(PATIENT_EMAIL) != null || users.findByEmail("pharmacy1@medix.com") != null;
    }

    /** True when everything is there, including the Fix 3 additions (pharmacies abroad, rates, medicines to review). */
    public static boolean isComplete() throws SQLException {
        if (!isLoaded()) {
            return false;
        }
        UserDao users = new UserDao();
        for (Object[] a : ABROAD) {
            if (users.findByEmail("pharmacy" + a[0] + "@medix.com") == null) {
                return false;
            }
        }
        medix.dao.ExchangeRateDao rates = new medix.dao.ExchangeRateDao();
        for (String[] r : DEMO_RATES) {
            if (rates.findByCode(r[0]) == null) {
                return false;
            }
        }
        MedicineDao medicineDao = new MedicineDao();
        for (Object[] p : PENDING_MEDICINES) {
            if (medicineDao.findByBrandAndStrength((String) p[1], (String) p[3]) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Loads everything (about 20 seconds) – only what is missing: the base demo data when no demo
     * user exists, then the Fix 3 additions that are not there yet. Returns false when nothing was
     * missing. The session is used to act as admin / pharmacy / patient and is logged out at the end.
     */
    public static boolean load(Progress progress) throws Exception {
        if (isComplete()) {
            return false;
        }
        boolean base = !isLoaded();
        DemoDataSeeder seeder = new DemoDataSeeder(progress);
        try {
            seeder.run(base);
        } finally {
            Session.logout();
        }
        return true;
    }

    private void run(boolean base) throws Exception {
        readLookups();
        if (!base) {
            progress.step("Adding pharmacies abroad, exchange rates and medicines to review…");
            loadExistingDemoAccounts();
            addFix3Data();
            AvailabilityCache.clear();
            new AuditService().logAs(null, "DEMO_DATA_UPDATED", null, null,
                    "Fix 3: medicines to review, pharmacies abroad, exchange rates");
            return;
        }
        progress.step("Creating patients…");
        createPatients();
        progress.step("Registering 12 pharmacies…");
        createPharmacies();
        progress.step("Adding stock…");
        addStock();
        suspendRxCorner();
        progress.step("Making reservations…");
        makeReservationsAndReports();
        progress.step("Writing 14 days of searches…");
        writeSearchLogs();
        progress.step("Writing background job history…");
        writeJobHistory();
        progress.step("Adding pharmacies abroad, exchange rates and medicines to review…");
        addFix3Data();
        progress.step("Running alerts and trust scores…");
        finish();
    }

    /** Demo data from before Fix 3: the pharmacies and owners by their demo e-mail addresses. */
    private void loadExistingDemoAccounts() throws SQLException {
        for (int i = 0; i < PHARMACIES.length; i++) {
            User owner = userDao.findByEmail("pharmacy" + (i + 1) + "@medix.com");
            Pharmacy p = owner == null ? null : pharmacyDao.findByOwnerId(owner.getId());
            if (p != null) {
                pharmacies.put((String) PHARMACIES[i][0], p);
                owners.put((String) PHARMACIES[i][0], owner);
            }
        }
    }

    /** The Fix 3 additions; each part only when it is missing (never duplicates). */
    private void addFix3Data() throws Exception {
        ExchangeRateService rates = new ExchangeRateService();
        for (String[] r : DEMO_RATES) {
            rates.saveManualIfMissing(r[0], new BigDecimal(r[1]));
        }
        addPharmaciesAbroad();
        addPendingMedicines();
    }

    /** Westminster Chemist (London, GBP) and Park Street Pharmacy (Kolkata, INR), verified, open 24 hours. */
    private void addPharmaciesAbroad() throws Exception {
        File tempDir = Files.createTempDirectory("medix-demo").toFile();
        LocationService locations = new LocationService();
        InventoryService inventory = new InventoryService();
        for (Object[] a : ABROAD) {
            String email = "pharmacy" + a[0] + "@medix.com";
            if (userDao.findByEmail(email) != null) {
                continue;
            }
            String name = (String) a[1];
            Place place = locations.save(new Place((String) a[2], (Double) a[6], (Double) a[7], (String) a[3],
                    (String) a[4], (String) a[5]));
            Pharmacy p = new Pharmacy();
            p.setName(name);
            p.setLicenseNo((String) a[9]);
            p.setAddress((String) a[8]);
            p.setAreaId(place.getSavedId());
            p.setLatitude((Double) a[6]);
            p.setLongitude((Double) a[7]);
            p.setOpenTime(H24);
            p.setCloseTime(H24);
            File licenseFile = drawLicense(tempDir, name, (String) a[9]);
            Pharmacy saved = authService.registerPharmacy("Owner of " + name, email, (String) a[10], PASSWORD, p,
                    licenseFile); // the currency follows the country: GBP / INR
            Files.deleteIfExists(licenseFile.toPath());
            asAdmin();
            new VerificationService().approve(saved.getId());
            Pharmacy verified = pharmacyDao.findById(saved.getId());
            User owner = userDao.findByEmail(email);
            pharmacies.put(name, verified);
            owners.put(name, owner);
            Session.login(owner, verified);
            for (String[] item : (String[][]) a[11]) {
                Medicine m = medicines.get(item[0]);
                if (m == null) {
                    continue;
                }
                boolean pack = isPack(m.getForm());
                Date expiry = DateUtil.parseDate(DateUtil.todayPlusDays(150 + random.nextInt(500)));
                inventory.addBatch(m.getId(), batchNo(m, expiry), pack ? 10 + random.nextInt(20) : 80 + random.nextInt(300),
                        new BigDecimal(item[1]), verified.getCurrency(), expiry, pack ? 5 : 20);
            }
        }
        Files.deleteIfExists(tempDir.toPath());
    }

    /**
     * Three medicines added by demo pharmacies, waiting for review, each with a batch so it can be
     * found and sold. "Napa 500mg" duplicates Napa 500 mg and uses the SAME batch number as that
     * pharmacy's Napa 500 mg batch: merging it adds the quantities together.
     */
    private void addPendingMedicines() throws Exception {
        MedicineDao medicineDao = new MedicineDao();
        MedicineService medicineService = new MedicineService();
        InventoryService inventory = new InventoryService();
        InventoryDao inventoryDao = new InventoryDao();
        for (Object[] row : PENDING_MEDICINES) {
            if (medicineDao.findByBrandAndStrength((String) row[1], (String) row[3]) != null) {
                continue;
            }
            String pharmacyName = (String) PHARMACIES[(Integer) row[0] - 1][0];
            Pharmacy p = pharmacies.get(pharmacyName);
            User owner = owners.get(pharmacyName);
            if (p == null || owner == null || pharmacyDao.findById(p.getId()).getStatus() != PharmacyStatus.VERIFIED) {
                continue;
            }
            Session.login(owner, pharmacyDao.findById(p.getId()));
            Medicine m = new Medicine();
            m.setBrandName((String) row[1]);
            m.setGenericName((String) row[2]);
            m.setStrength((String) row[3]);
            m.setForm((MedicineForm) row[4]);
            m.setManufacturer((String) row[5]);
            Medicine added = medicineService.addByPharmacy(m).getMedicine();
            Date expiry = DateUtil.parseDate(DateUtil.todayPlusDays(200 + random.nextInt(300)));
            String batch = batchNo(added, expiry);
            Medicine original = medicines.get(row[1] + " " + "500 mg");
            if ("500mg".equals(row[3]) && original != null) {
                for (Inventory i : inventoryDao.findByPharmacy(p.getId())) {
                    if (i.getMedicineId() == original.getId()) {
                        batch = i.getBatchNo(); // same box, typed again as a "new" medicine
                        expiry = DateUtil.parseDate(i.getExpiryDate());
                        break;
                    }
                }
            }
            if (expiry == null || DateUtil.daysUntil(DateUtil.formatDate(expiry)) <= 0) {
                expiry = DateUtil.parseDate(DateUtil.todayPlusDays(200));
            }
            inventory.addBatch(added.getId(), batch, (Integer) row[6], new BigDecimal((String) row[7]),
                    CurrencyUtil.BDT, expiry, 20);
        }
    }

    // ---------------------------------------------------------------- lookups

    private void readLookups() throws SQLException, ServiceException {
        for (Area a : new AreaDao().findAll()) {
            areas.put(a.getName(), a);
        }
        for (Medicine m : new MedicineDao().findAll()) {
            medicines.put(m.getBrandName() + " " + m.getStrength(), m);
        }
        admin = userDao.findByEmail("admin@medix.com");
        if (admin == null) {
            List<User> admins = userDao.findByRole(Role.ADMIN);
            admin = admins.isEmpty() ? null : admins.get(0);
        }
        if (admin == null || areas.size() < 10) {
            throw new ServiceException("The database is missing the admin or the Dhaka areas.");
        }
    }

    private Area area(String name) {
        return areas.get(name);
    }

    // ---------------------------------------------------------------- accounts

    private void createPatients() throws Exception {
        for (int i = 0; i < PATIENTS.length; i++) {
            User u = authService.registerPatient(PATIENTS[i][0], PATIENTS[i][1], "0181200000" + (i + 1), PASSWORD,
                    area(PATIENTS[i][2]).getId());
            patients.add(u);
        }
    }

    private void createPharmacies() throws Exception {
        File tempDir = Files.createTempDirectory("medix-demo").toFile();
        for (int i = 0; i < PHARMACIES.length; i++) {
            Object[] row = PHARMACIES[i];
            String name = (String) row[0];
            Area area = area((String) row[1]);
            String license = "DL-DHK-" + (2401 + i * 37);
            Pharmacy p = new Pharmacy();
            p.setName(name);
            p.setLicenseNo(license);
            p.setAddress((String) row[2]);
            p.setAreaId(area.getId());
            // ±0.004° so pharmacies in one area are not on the same point
            p.setLatitude(round4(area.getLatitude() + (random.nextDouble() - 0.5) * 0.008));
            p.setLongitude(round4(area.getLongitude() + (random.nextDouble() - 0.5) * 0.008));
            p.setOpenTime((String) row[4]);
            p.setCloseTime((String) row[5]);
            File licenseFile = drawLicense(tempDir, name, license);
            String email = "pharmacy" + (i + 1) + "@medix.com";
            Pharmacy saved = authService.registerPharmacy("Owner of " + name, email,
                    String.format("017110000%02d", i + 1), PASSWORD, p, licenseFile);
            Files.deleteIfExists(licenseFile.toPath());
            pharmacies.put(name, saved);
            owners.put(name, userDao.findByEmail(email));
        }
        Files.deleteIfExists(tempDir.toPath());

        asAdmin();
        VerificationService verification = new VerificationService();
        for (Object[] row : PHARMACIES) {
            if (row[3] != PharmacyStatus.PENDING) {
                verification.approve(pharmacies.get((String) row[0]).getId()); // Rx Corner is suspended later
            }
        }
        // verified 14 months ago: the Verification screen shows "Re-verify"
        Pharmacy old = pharmacyDao.findById(pharmacies.get(REVERIFY_PHARMACY).getId());
        pharmacyDao.updateStatus(old.getId(), PharmacyStatus.VERIFIED, PharmacyStatus.VERIFIED, admin.getId(),
                DateUtil.formatDateTime(monthsAgo(14)), null);
        refreshPharmacies();
    }

    private void refreshPharmacies() throws SQLException {
        for (Map.Entry<String, Pharmacy> e : pharmacies.entrySet()) {
            e.setValue(pharmacyDao.findById(e.getValue().getId()));
        }
    }

    /** A PNG "DEMO LICENSE – <pharmacy> – <license no>" drawn with Graphics2D. */
    private static File drawLicense(File dir, String pharmacy, String license) throws IOException {
        int w = 1000;
        int h = 700;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, Color.WHITE, 0, h, Color.decode("#EEF6F3")));
        g.fillRect(0, 0, w, h);
        g.setColor(UITheme.ACCENT);
        g.setStroke(new BasicStroke(10));
        g.drawRect(20, 20, w - 40, h - 40);
        g.setStroke(new BasicStroke(2));
        g.drawRect(38, 38, w - 76, h - 76);
        g.fillRect(38, 38, w - 76, 110);
        g.drawImage(UITheme.createLogoImage(70), 64, 58, null);
        g.setColor(Color.WHITE);
        g.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 40));
        g.drawString("DEMO LICENSE", 156, 108);
        g.setFont(new Font(UITheme.FONT_FAMILY, Font.PLAIN, 18));
        g.drawString("Retail drug license – sample document", 156, 134);

        g.setColor(UITheme.SIDEBAR);
        g.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 30));
        g.drawString("DEMO LICENSE – " + pharmacy + " – " + license, 70, 230);
        g.setFont(new Font(UITheme.FONT_FAMILY, Font.PLAIN, 22));
        String[][] lines = {{"Pharmacy", pharmacy}, {"License no.", license}, {"Type", "Retail drug shop (allopathic)"},
            {"Valid until", DateUtil.toDisplayDate(DateUtil.todayPlusDays(700))}};
        int y = 300;
        for (String[] l : lines) {
            g.setColor(UITheme.TEXT_MUTED);
            g.drawString(l[0], 90, y);
            g.setColor(UITheme.TEXT);
            g.drawString(l[1], 300, y);
            y += 48;
        }
        g.setColor(UITheme.DANGER);
        g.setFont(new Font(UITheme.FONT_FAMILY, Font.BOLD, 20));
        g.drawString("Sample for the MediX demo only. This is not a real license.", 90, 610);
        g.dispose();
        File file = new File(dir, "demo_license_" + license + ".png");
        ImageIO.write(img, "png", file);
        return file;
    }

    // ---------------------------------------------------------------- stock

    private void addStock() throws Exception {
        InventoryService inventory = new InventoryService();
        int n = 0;
        for (Map.Entry<String, Pharmacy> e : pharmacies.entrySet()) {
            Pharmacy p = e.getValue();
            if (p.getStatus() != PharmacyStatus.VERIFIED) {
                continue;
            }
            Session.login(owners.get(e.getKey()), p);
            List<String> keys = pickMedicines(e.getKey());
            String[] required = REQUIRED.get(e.getKey());
            int lowStock = 0;
            int expiring = 0;
            int expired = 0;
            for (String key : keys) {
                Medicine m = medicines.get(key);
                boolean isRequired = required != null && contains(required, key);
                boolean liquidOrDevice = isPack(m.getForm());
                BigDecimal price = CurrencyUtil.round(basePrice(key).multiply(
                        BigDecimal.valueOf(0.92 + random.nextDouble() * 0.16)), CurrencyUtil.BDT);
                int qty = liquidOrDevice ? 8 + random.nextInt(30) : 60 + random.nextInt(500);
                int threshold = liquidOrDevice ? 5 : 20;
                Date expiry = DateUtil.parseDate(DateUtil.todayPlusDays(120 + random.nextInt(600)));
                String kind = "normal";
                if (!isRequired && lowStock == 0) {
                    kind = "low";
                    lowStock++;
                } else if (!isRequired && expiring == 0 && n % 2 == 0) {
                    kind = "expiring";
                    expiring++;
                } else if (!isRequired && expired == 0 && n % 3 == 0) {
                    kind = "expired";
                    expired++;
                }
                if ("low".equals(kind)) {
                    qty = 2 + random.nextInt(threshold - 2);
                } else if ("expiring".equals(kind)) {
                    expiry = DateUtil.parseDate(DateUtil.todayPlusDays(12 + random.nextInt(40)));
                    qty = liquidOrDevice ? 6 : 30 + random.nextInt(40);
                }
                String batch = batchNo(m, expiry);
                int id = inventory.addBatch(m.getId(), batch, qty, price, expiry, threshold);
                if ("expired".equals(kind)) {
                    // a correction to a past date: the batch stays on the shelf until it is removed
                    inventory.updateBatch(id, batch, price, DateUtil.parseDate(DateUtil.todayPlusDays(-5 - random.nextInt(35))),
                            threshold);
                } else if ("normal".equals(kind) && !liquidOrDevice && random.nextInt(3) == 0) {
                    inventory.sell(id, 2 + random.nextInt(10), "Counter sale");
                } else if ("normal".equals(kind) && random.nextInt(6) == 0) {
                    inventory.stockIn(id, liquidOrDevice ? 6 : 50, "Delivery from distributor");
                }
            }
            n++;
        }
    }

    /** 8–20 medicines for the pharmacy; the required ones always, never Insulet R in Uttara. */
    private List<String> pickMedicines(String pharmacy) {
        List<String> all = new ArrayList<String>(medicines.keySet());
        List<String> picked = new ArrayList<String>();
        String[] required = REQUIRED.get(pharmacy);
        if (required != null) {
            for (String key : required) {
                if (medicines.containsKey(key)) {
                    picked.add(key);
                }
            }
        }
        if (medicines.containsKey("Napa 500 mg") && !picked.contains("Napa 500 mg")) {
            picked.add("Napa 500 mg"); // every pharmacy sells Napa
        }
        int target = 8 + random.nextInt(13);
        while (picked.size() < target && picked.size() < all.size()) {
            String key = all.get(random.nextInt(all.size()));
            if (picked.contains(key)) {
                continue;
            }
            if (key.startsWith("Insulet R") && NO_INSULIN_PHARMACY.equals(pharmacy)) {
                continue;
            }
            picked.add(key);
        }
        return picked;
    }

    private void suspendRxCorner() throws Exception {
        asAdmin();
        new VerificationService().suspend(pharmacies.get("Rx Corner").getId(),
                "Two patients reported prices above the label. License under review.");
        refreshPharmacies();
    }

    // ---------------------------------------------------------------- reservations

    private void makeReservationsAndReports() throws Exception {
        ReservationService reservations = new ReservationService();
        PharmacyReservationService pharmacySide = new PharmacyReservationService();
        // patient index, pharmacy, medicine, units, outcome
        Object[][] plan = {
            {0, "Green Life Pharmacy", "Napa 500 mg", 10, "COMPLETED"},
            {0, "Green Life Pharmacy", "Seclo 20 mg", 4, "PENDING"},
            {0, "Popular Medicine Corner", "Fexo 120 mg", 2, "CONFIRMED"},
            {1, "Green Life Pharmacy", "Napa Extra 500 mg + 65 mg", 6, "COMPLETED"},
            {1, "Popular Medicine Corner", "Seclo 20 mg", 5, "CANCELLED_BY_PATIENT"},
            {2, "Lifeline Pharmacy", "Napa 500 mg", 10, "READY"},
            {2, "Lifeline Pharmacy", "Comet 500 mg", 10, "COMPLETED"},
            {3, "Green Life Pharmacy", "Fexo 120 mg", 3, "EXPIRED"},
            {3, "Popular Medicine Corner", "Napa 500 mg", 8, "COMPLETED"},
            {4, "Medi Home", "Ventolin 100 mcg/puff", 1, "CANCELLED_BY_PHARMACY"},
            {4, "Medi Home", "Napa 500 mg", 10, "COMPLETED"},
            {5, "Popular Medicine Corner", "ORSaline-N 10.25 g/sachet", 10, "EXPIRED"},
            {5, "Medi Home", "Seclo 20 mg", 4, "COMPLETED"},
            {0, "Medi Home", "Alatrol 10 mg", 5, "COMPLETED"}
        };
        Map<Integer, Integer> idByPlanRow = new HashMap<Integer, Integer>();
        List<Integer> toExpire = new ArrayList<Integer>();
        for (int i = 0; i < plan.length; i++) {
            User patient = patients.get((Integer) plan[i][0]);
            String pharmacyName = (String) plan[i][1];
            Pharmacy pharmacy = pharmacies.get(pharmacyName);
            Medicine medicine = medicines.get((String) plan[i][2]);
            if (medicine == null) {
                continue;
            }
            Session.login(patient, null);
            ReservationView v = reservations.reserve(patient.getId(), pharmacy.getId(), medicine.getId(),
                    (Integer) plan[i][3], ReservationService.DEFAULT_HOLD_MINUTES, i == 0 ? "I will come after work." : null);
            int id = v.getReservation().getId();
            idByPlanRow.put(i, id);
            String outcome = (String) plan[i][4];
            if ("CANCELLED_BY_PATIENT".equals(outcome)) {
                reservations.cancel(id, patient.getId());
                continue;
            }
            if ("EXPIRED".equals(outcome)) {
                toExpire.add(id);
                continue;
            }
            Session.login(owners.get(pharmacyName), pharmacy);
            if ("CANCELLED_BY_PHARMACY".equals(outcome)) {
                pharmacySide.cancel(id, CancelReason.OUT_OF_STOCK, null);
            } else if (!"PENDING".equals(outcome)) {
                pharmacySide.confirm(id);
                if ("READY".equals(outcome) || "COMPLETED".equals(outcome)) {
                    pharmacySide.markReady(id);
                }
                if ("COMPLETED".equals(outcome)) {
                    pharmacySide.pickUp(id);
                }
            }
        }
        // the patients did not come: the hold ran out and the expiry job releases the units
        ReservationDao reservationDao = new ReservationDao();
        for (int id : toExpire) {
            reservationDao.setExpiresAt(id, DateUtil.formatDateTime(new Date(System.currentTimeMillis() - 60000)));
        }
        new ReservationService().expireOverdue();

        // three reports: one stays open, one is upheld, one is dismissed
        ReportService reports = new ReportService();
        int open = report(reports, 3, idByPlanRow.get(7), ReportReason.RESERVATION_NOT_HONOURED,
                "I arrived 20 minutes before the hold ended but the staff said they had no record of my reservation.");
        int upheld = report(reports, 5, idByPlanRow.get(11), ReportReason.WRONG_STOCK,
                "The app showed plenty of ORSaline sachets but the shop had none for two days.");
        int dismissed = report(reports, 1, idByPlanRow.get(4), ReportReason.WRONG_PRICE,
                "They asked Tk 9 per capsule instead of the Tk 7 shown in MediX.");
        asAdmin();
        if (upheld > 0) {
            reports.uphold(upheld, "The pharmacy confirmed its stock count was not updated.");
        }
        if (dismissed > 0) {
            reports.dismiss(dismissed, "The price list shows Tk 7 and the patient cancelled before buying.");
        }
        if (open < 0) {
            ErrorLog.log("Demo data: the open report could not be created.");
        }
    }

    private int report(ReportService reports, int patientIndex, Integer reservationId, ReportReason reason, String text)
            throws Exception {
        if (reservationId == null) {
            return -1;
        }
        User patient = patients.get(patientIndex);
        Session.login(patient, null);
        return reports.reportReservation(patient.getId(), reservationId, reason, text);
    }

    // ---------------------------------------------------------------- searches

    /** About 1,200 searches over 14 days: Napa most searched, some found nothing. */
    private void writeSearchLogs() throws SQLException {
        Map<String, List<PharmacyStock>> stocked = new HashMap<String, List<PharmacyStock>>();
        InventoryDao inventoryDao = new InventoryDao();
        for (Map.Entry<String, Medicine> m : medicines.entrySet()) {
            stocked.put(m.getKey(), inventoryDao.findStockedPharmacies(m.getValue().getId()));
        }
        List<String> weighted = new ArrayList<String>();
        for (Object[] c : CATALOG) {
            if (medicines.containsKey((String) c[0])) {
                for (int i = 0; i < (Integer) c[2]; i++) {
                    weighted.add((String) c[0]);
                }
            }
        }
        List<Area> areaList = new ArrayList<Area>(areas.values());
        Area uttara = area("Uttara Sector 7");
        SearchLogDao dao = new SearchLogDao();
        long now = System.currentTimeMillis();
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                for (int day = SEARCH_DAYS - 1; day >= 0; day--) {
                    int count = 64 + (SEARCH_DAYS - day) + random.nextInt(18);
                    for (int i = 0; i < count; i++) {
                        Date when = randomTime(day, now);
                        if (when == null) {
                            continue;
                        }
                        User who = patients.get(random.nextInt(patients.size()));
                        Area where = random.nextInt(3) == 0 ? areaList.get(random.nextInt(areaList.size()))
                                : areaOf(who);
                        if (random.nextInt(25) == 0) {
                            String q = UNKNOWN_QUERIES[random.nextInt(UNKNOWN_QUERIES.length)];
                            dao.insert(con, log(who, q, null, where, 0, when));
                        } else {
                            String key = weighted.get(random.nextInt(weighted.size()));
                            dao.insert(con, log(who, query(key), medicines.get(key), where,
                                    found(stocked.get(key), where), when));
                        }
                    }
                    // patients in Uttara keep looking for insulin that no pharmacy near them has
                    Medicine insulin = medicines.get("Insulet R 100 IU/ml");
                    for (int i = 0; insulin != null && i < 3 + random.nextInt(2); i++) {
                        Date when = randomTime(day, now);
                        if (when != null) {
                            dao.insert(con, log(patients.get(2), "insulet r", insulin, uttara,
                                    found(stocked.get("Insulet R 100 IU/ml"), uttara), when));
                        }
                    }
                }
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
    }

    private static SearchLog log(User who, String query, Medicine m, Area where, int results, Date when) {
        SearchLog s = new SearchLog();
        s.setUserId(who == null ? null : who.getId());
        s.setQuery(query);
        s.setMedicineId(m == null ? null : m.getId());
        s.setAreaId(where.getId());
        s.setLatitude(GeoUtil.round2(where.getLatitude()));
        s.setLongitude(GeoUtil.round2(where.getLongitude()));
        s.setResultsCount(results);
        s.setCreatedAt(DateUtil.formatDateTime(when));
        return s;
    }

    /** How many pharmacies within 5 km of the area centre have it (what the search showed). */
    private static int found(List<PharmacyStock> rows, Area where) {
        int n = 0;
        for (PharmacyStock p : rows == null ? new ArrayList<PharmacyStock>() : rows) {
            if (GeoUtil.distanceKm(where.getLatitude(), where.getLongitude(), p.getLatitude(), p.getLongitude())
                    <= SEARCH_RADIUS_KM) {
                n++;
            }
        }
        return n;
    }

    /** What a patient typed: "napa", "Napa" or "napa 500 mg". */
    private String query(String key) {
        String brand = medicines.get(key).getBrandName();
        int r = random.nextInt(4);
        return r == 0 ? key.toLowerCase() : r == 1 ? brand : brand.toLowerCase();
    }

    private Area areaOf(User u) throws SQLException {
        for (Area a : areas.values()) {
            if (u.getAreaId() != null && a.getId() == u.getAreaId()) {
                return a;
            }
        }
        return area("Dhanmondi");
    }

    /** A time between 08:00 and 23:00 `day` days ago (today: not after now); null if none is left. */
    private Date randomTime(int day, long now) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -day);
        c.set(Calendar.HOUR_OF_DAY, 8 + random.nextInt(15));
        c.set(Calendar.MINUTE, random.nextInt(60));
        c.set(Calendar.SECOND, random.nextInt(60));
        if (c.getTimeInMillis() > now) {
            if (day > 0) {
                return null;
            }
            c.setTimeInMillis(now - 60000L * (1 + random.nextInt(240))); // earlier today
            if (!DateUtil.formatDate(c.getTime()).equals(DateUtil.today())) {
                return null;
            }
        }
        return c.getTime();
    }

    // ---------------------------------------------------------------- jobs, alerts, trust

    /** job_runs history: daily jobs for 14 days, the frequent ones for the last hours, one failure. */
    private void writeJobHistory() throws SQLException {
        JobRunDao dao = new JobRunDao();
        long now = System.currentTimeMillis();
        String[][] daily = {{"Expiry sweep", "08:00"}, {"Trust score (nightly)", "02:00"}, {"Search log clean-up", "03:00"}};
        for (int day = SEARCH_DAYS; day >= 1; day--) {
            for (String[] job : daily) {
                Calendar c = Calendar.getInstance();
                c.add(Calendar.DAY_OF_MONTH, -day);
                String[] hm = job[1].split(":");
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                c.set(Calendar.SECOND, 0);
                boolean failed = "Search log clean-up".equals(job[0]) && day == 5;
                boolean retried = "Expiry sweep".equals(job[0]) && day == 9;
                insertRun(dao, job[0], c.getTimeInMillis(), failed ? JobRun.FAILED : JobRun.OK,
                        failed ? 0 : random.nextInt(12),
                        failed ? "[SQLITE_BUSY] The database file is locked" : retried ? "OK after 2 attempts" : "OK");
            }
        }
        for (int h = 24; h >= 1; h--) {
            insertRun(dao, "Stale stock", now - h * 3600000L, JobRun.OK, h < 4 ? 1 : 0, "OK");
        }
        for (int q = 24; q >= 1; q--) {
            insertRun(dao, "Low stock", now - q * 900000L, JobRun.OK, random.nextInt(3), "OK");
        }
        for (int m = 30; m >= 1; m--) {
            insertRun(dao, "Reservation expiry", now - m * 60000L, JobRun.OK, 0, "OK");
        }
    }

    private void insertRun(JobRunDao dao, String job, long start, String status, int items, String message)
            throws SQLException {
        JobRun r = new JobRun();
        r.setJobName(job);
        r.setStartedAt(DateUtil.formatDateTime(new Date(start)));
        r.setFinishedAt(DateUtil.formatDateTime(new Date(start + 400 + random.nextInt(2500))));
        r.setStatus(status);
        r.setItemsAffected(items);
        r.setMessage(message);
        dao.insert(r);
    }

    private void finish() throws SQLException {
        // the stale pharmacy last touched its stock two days ago
        new InventoryDao().setUpdatedAt(pharmacies.get(STALE_PHARMACY).getId(),
                DateUtil.formatDateTime(new Date(System.currentTimeMillis() - 50L * 3600000L)));
        AlertService alerts = new AlertService();
        JobRunService runs = new JobRunService();
        String started = DateUtil.now();
        runs.record("Low stock", started, JobRun.OK, alerts.runLowStockCheck(), "OK");
        runs.record("Expiry sweep", started, JobRun.OK, alerts.runExpirySweep(), "OK");
        runs.record("Stale stock", started, JobRun.OK, alerts.runStaleCheck(), "OK");
        runs.record("Trust score (nightly)", started, JobRun.OK, new TrustScoreService().runNightlyCheck(), "OK");
        AvailabilityCache.clear();
        new AuditService().logAs(null, "DEMO_DATA_LOADED", null, null,
                patients.size() + " patients, " + pharmacies.size() + " pharmacies");
    }

    // ---------------------------------------------------------------- helpers

    private void asAdmin() {
        Session.login(admin, null);
    }

    private static boolean isPack(MedicineForm form) {
        return form == MedicineForm.SYRUP || form == MedicineForm.SUSPENSION || form == MedicineForm.INJECTION
                || form == MedicineForm.INHALER || form == MedicineForm.DROPS || form == MedicineForm.CREAM;
    }

    private static BigDecimal basePrice(String key) {
        for (Object[] c : CATALOG) {
            if (c[0].equals(key)) {
                return new BigDecimal((String) c[1]);
            }
        }
        return BigDecimal.TEN; // a medicine an admin added later
    }

    /** e.g. "NAP-2710-B": brand letters, expiry yymm, a letter. */
    private String batchNo(Medicine m, Date expiry) {
        String letters = m.getBrandName().replaceAll("[^A-Za-z]", "").toUpperCase();
        String prefix = letters.substring(0, Math.min(3, letters.length()));
        return prefix + "-" + new java.text.SimpleDateFormat("yyMM").format(expiry) + "-" + (char) ('A' + random.nextInt(6));
    }

    private static boolean contains(String[] list, String key) {
        for (String s : list) {
            if (s.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static Date monthsAgo(int months) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, -months);
        return c.getTime();
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
