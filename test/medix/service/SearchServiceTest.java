package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import medix.dao.AreaDao;
import medix.dao.InventoryDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.engine.AvailabilityCache;
import medix.engine.SearchEngine;
import medix.model.Area;
import medix.model.Place;
import medix.model.PlaceSource;
import medix.model.Inventory;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.PharmacyStock;
import medix.model.Role;
import medix.model.SearchResult;
import medix.model.User;
import medix.util.AppPaths;
import medix.util.DBConnection;
import medix.util.DateUtil;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Search against a real SQLite database in a temporary folder (never data/medix.db).
 * Seeded medicine ids: 1 = Napa 500 mg, 5 = Seclo 20 mg, 7 = Losectil 20 mg (both Omeprazole).
 */
public class SearchServiceTest {

    private static final int NAPA = 1;
    private static final int SECLO = 5;
    private static final int LOSECTIL = 7;

    private static int dhanmondiPharmacy;
    private static int uttaraPharmacy;
    private static int pendingPharmacy;
    private static Area dhanmondi;

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        // AppPaths uses user.dir when running from classes, so the database goes into the temp folder
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        assertTrue(AppPaths.getDatabaseFile().getAbsolutePath().startsWith(dir.getAbsolutePath()));
        dhanmondi = new AreaDao().findByName("Dhanmondi");
        dhanmondiPharmacy = pharmacy("Dhanmondi Pharma", "DL-111111", "o1@test.com", dhanmondi, PharmacyStatus.VERIFIED);
        uttaraPharmacy = pharmacy("Uttara Pharma", "DL-222222", "o2@test.com",
                new AreaDao().findByName("Uttara Sector 7"), PharmacyStatus.VERIFIED);
        pendingPharmacy = pharmacy("Pending Pharma", "DL-333333", "o3@test.com", dhanmondi, PharmacyStatus.PENDING);
    }

    @Before
    public void emptyStock() throws Exception {
        sql("DELETE FROM inventory");
        AvailabilityCache.clear();
    }

    @Test
    public void expiredBatchesAreNeverReturned() throws Exception {
        batch(dhanmondiPharmacy, SECLO, "OLD-1", 40, 0, 5.0, DateUtil.todayPlusDays(-1));
        batch(dhanmondiPharmacy, SECLO, "TODAY", 10, 0, 5.0, DateUtil.today()); // expires today: not in the future
        SearchResult r = search("seclo", 5.0);
        assertEquals("Seclo", r.getMedicine().getBrandName());
        assertEquals(0, r.getRows().size());

        batch(dhanmondiPharmacy, SECLO, "NEW-1", 12, 2, 6.5, DateUtil.todayPlusDays(200));
        AvailabilityCache.clear();
        r = search("seclo", 5.0);
        assertEquals(1, r.getRows().size());
        PharmacyStock row = r.getRows().get(0);
        assertEquals("only the valid batch counts (12 - 2 reserved)", 10, row.getAvailable());
        assertEquals(6.5, row.getLowestPrice().doubleValue(), 0.001);
    }

    @Test
    public void sortByPriceComparesCurrenciesWithTheSavedRates() throws Exception {
        sql("DELETE FROM exchange_rates");
        medix.engine.ExchangeRateService.clearCache();
        medix.engine.ExchangeRateService rates = new medix.engine.ExchangeRateService();
        rates.saveManualIfMissing("BDT", new java.math.BigDecimal("120"));
        rates.saveManualIfMissing("GBP", new java.math.BigDecimal("0.75")); // £1 = Tk 160
        batch(dhanmondiPharmacy, SECLO, "D-1", 10, 0, 7.0, DateUtil.todayPlusDays(100));               // Tk 7
        batchIn(uttaraPharmacy, SECLO, "U-1", 10, "GBP", "0.03", DateUtil.todayPlusDays(100));         // £0.03 = Tk 4.80
        SearchResult r = new SearchService().search("seclo", null, Place.fromArea(dhanmondi), null,
                SearchEngine.Sort.PRICE, "BDT");
        assertEquals("the cheaper one after converting, although 0.03 < 7 means nothing", uttaraPharmacy,
                r.getRows().get(0).getPharmacyId());
        assertEquals("GBP", r.getRows().get(0).getCurrency());
        assertEquals(4.80, r.getRows().get(0).getComparablePrice().doubleValue(), 1e-9);
        assertTrue(r.hasForeignPrices());
        assertTrue(!r.isSomeNotComparable());

        // no GBP rate: that row cannot be compared and goes last (never guessed)
        sql("DELETE FROM exchange_rates WHERE currency_code = 'GBP'");
        medix.engine.ExchangeRateService.clearCache();
        AvailabilityCache.clear();
        r = new SearchService().search("seclo", null, Place.fromArea(dhanmondi), null, SearchEngine.Sort.PRICE, "BDT");
        assertEquals(dhanmondiPharmacy, r.getRows().get(0).getPharmacyId());
        assertEquals(uttaraPharmacy, r.getRows().get(1).getPharmacyId());
        assertNull(r.getRows().get(1).getComparablePrice());
        assertTrue(r.isSomeNotComparable());
    }

    @Test
    public void onePharmacyWithBatchesInTwoCurrenciesShowsTheCheapest() throws Exception {
        sql("DELETE FROM exchange_rates");
        medix.engine.ExchangeRateService.clearCache();
        medix.engine.ExchangeRateService rates = new medix.engine.ExchangeRateService();
        rates.saveManualIfMissing("BDT", new java.math.BigDecimal("120"));
        rates.saveManualIfMissing("GBP", new java.math.BigDecimal("0.75"));
        batch(dhanmondiPharmacy, SECLO, "D-1", 10, 0, 7.0, DateUtil.todayPlusDays(100));          // Tk 7
        batchIn(dhanmondiPharmacy, SECLO, "D-2", 5, "GBP", "0.04", DateUtil.todayPlusDays(200));  // = Tk 6.40
        PharmacyStock row = new SearchService().search("seclo", null, Place.fromArea(dhanmondi), null,
                SearchEngine.Sort.PRICE, "BDT").getRows().get(0);
        assertEquals(15, row.getAvailable());
        assertEquals("GBP", row.getCurrency());
        assertEquals(0.04, row.getLowestPrice().doubleValue(), 1e-9);
    }

    @Test
    public void lowestPriceAndSumOverValidBatches() throws Exception {
        batch(dhanmondiPharmacy, NAPA, "A", 30, 0, 1.30, DateUtil.todayPlusDays(100));
        batch(dhanmondiPharmacy, NAPA, "B", 20, 5, 1.10, DateUtil.todayPlusDays(300));
        batch(dhanmondiPharmacy, NAPA, "C", 4, 4, 0.90, DateUtil.todayPlusDays(300)); // all reserved: ignored
        PharmacyStock row = search("napa 500", null).getRows().get(0);
        assertEquals(45, row.getAvailable());
        assertEquals(1.10, row.getLowestPrice().doubleValue(), 0.001);
    }

    @Test
    public void onlyVerifiedPharmaciesWithinTheRadius() throws Exception {
        batch(dhanmondiPharmacy, SECLO, "D-1", 10, 0, 5.0, DateUtil.todayPlusDays(100));
        batch(uttaraPharmacy, SECLO, "U-1", 10, 0, 4.0, DateUtil.todayPlusDays(100));
        batch(pendingPharmacy, SECLO, "P-1", 10, 0, 3.0, DateUtil.todayPlusDays(100));
        assertEquals(1, search("seclo", 5.0).getRows().size());          // Uttara is ~14 km away
        SearchResult any = search("seclo", null);
        assertEquals(2, any.getRows().size());                          // the PENDING one never shows
        assertEquals(dhanmondiPharmacy, any.getRows().get(0).getPharmacyId());
        assertTrue(any.getRows().get(0).getDistanceKm() < any.getRows().get(1).getDistanceKm());
    }

    @Test
    public void misspelledNameAndOtherBrandSuggestion() throws Exception {
        batch(dhanmondiPharmacy, LOSECTIL, "L-1", 8, 0, 7.0, DateUtil.todayPlusDays(100));
        SearchResult r = search("seclow", 5.0);
        assertEquals("Seclo", r.getDidYouMean());
        assertEquals(0, r.getRows().size());
        assertEquals(1, r.getAlternatives().size());
        assertEquals("Losectil", r.getAlternatives().get(0).getMedicine().getBrandName());
    }

    @Test
    public void secondSearchComesFromTheCacheUntilStockChanges() throws Exception {
        batch(dhanmondiPharmacy, NAPA, "A", 30, 0, 1.30, DateUtil.todayPlusDays(100));
        assertTrue(!search("napa 500", 5.0).isCached());
        assertTrue(search("napa 500", 5.0).isCached());
        AvailabilityCache.invalidateMedicine(NAPA);
        assertTrue(!search("napa 500", 5.0).isCached());
    }

    @Test
    public void everySearchIsLoggedWithTheAreaOnly() throws Exception {
        int before = count("SELECT COUNT(*) FROM search_logs");
        search("zzzzqqq", 5.0);
        assertEquals(before + 1, count("SELECT COUNT(*) FROM search_logs"));
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT medicine_id, area_id, results_count, query FROM search_logs "
                        + "ORDER BY id DESC LIMIT 1")) {
            rs.next();
            assertNull(rs.getObject("medicine_id"));
            assertEquals(dhanmondi.getId(), rs.getInt("area_id"));
            assertEquals(0, rs.getInt("results_count"));
            assertEquals("zzzzqqq", rs.getString("query"));
        }
    }

    @Test
    public void searchLogKeepsOnlyRoundedCoordinates() throws Exception {
        Place exact = new Place("Somewhere in London", 51.507234, -0.127612, "London", "United Kingdom", "GB");
        searchFrom(exact, "napa", 5.0);
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT area_id, latitude, longitude FROM search_logs ORDER BY id DESC LIMIT 1")) {
            rs.next();
            assertNull("an unsaved place has no areas id", rs.getObject("area_id"));
            assertEquals(51.51, rs.getDouble("latitude"), 1e-9);
            assertEquals(-0.13, rs.getDouble("longitude"), 1e-9);
        }
    }

    @Test
    public void searchWorksInAnyCountry() throws Exception {
        Area westminster = place("Westminster", "London", "United Kingdom", "GB", 51.5072, -0.1276);
        int london = pharmacy("Thames Pharmacy", "DL-UK-0001", "thames@test.com", westminster, PharmacyStatus.VERIFIED);
        batch(london, SECLO, "UK-1", 25, 0, 0.85, DateUtil.todayPlusDays(200));
        batch(dhanmondiPharmacy, SECLO, "D-1", 10, 0, 7.0, DateUtil.todayPlusDays(200));

        Place nearby = new Place("Covent Garden, London, United Kingdom", 51.51, -0.13, "London", "United Kingdom", "GB");
        SearchResult inLondon = searchFrom(nearby, "seclo", 5.0);
        assertEquals("only the London pharmacy is within 5 km", 1, inLondon.getRows().size());
        assertEquals(london, inLondon.getRows().get(0).getPharmacyId());
        assertTrue(inLondon.getRows().get(0).getDistanceKm() < 1);

        SearchResult fromDhaka = search("seclo", 5.0);
        assertEquals(1, fromDhaka.getRows().size());
        assertEquals("London is not within 5 km of Dhanmondi", dhanmondiPharmacy, fromDhaka.getRows().get(0).getPharmacyId());

        SearchResult anywhere = search("seclo", null);
        assertEquals("Any distance: both, nearest first", 2, anywhere.getRows().size());
        assertEquals(london, anywhere.getRows().get(1).getPharmacyId());
        assertTrue(anywhere.getRows().get(1).getDistanceKm() > 7900);
    }

    @Test
    public void searchAcrossThe180DegreeLine() throws Exception {
        Area taveuni = place("Taveuni", "Taveuni", "Fiji", "FJ", -17.7, -179.9);
        int fiji = pharmacy("Island Pharmacy", "DL-FJ-0001", "fiji@test.com", taveuni, PharmacyStatus.VERIFIED);
        batch(fiji, NAPA, "FJ-1", 40, 0, 0.50, DateUtil.todayPlusDays(200));
        // 179.9 east and 179.9 west are only about 21 km apart
        Place east = new Place("Rabi, Fiji", -17.7, 179.9, null, "Fiji", "FJ");
        SearchResult r = searchFrom(east, "napa 500", 25.0);
        assertEquals(1, r.getRows().size());
        assertEquals(fiji, r.getRows().get(0).getPharmacyId());
        assertTrue(r.getRows().get(0).getDistanceKm() > 20 && r.getRows().get(0).getDistanceKm() < 22);
        assertEquals(0, searchFrom(east, "napa 500", 10.0).getRows().size());
    }

    // ---------------------------------------------------------------- helpers

    private static SearchResult search(String text, Double radiusKm) throws Exception {
        return searchFrom(Place.fromArea(dhanmondi), text, radiusKm);
    }

    private static SearchResult searchFrom(Place from, String text, Double radiusKm) throws Exception {
        return new SearchService().search(text, null, from, radiusKm, SearchEngine.Sort.DISTANCE);
    }

    /** A saved place (areas row) anywhere on Earth, like the ones LocationService saves. */
    private static Area place(String name, String city, String country, String code, double lat, double lon)
            throws Exception {
        Area a = new Area();
        a.setName(name);
        a.setDisplayName(name + ", " + city + ", " + country);
        a.setCity(city);
        a.setCountry(country);
        a.setCountryCode(code);
        a.setLatitude(lat);
        a.setLongitude(lon);
        a.setSource(PlaceSource.GEOCODED);
        new AreaDao().insert(a);
        return a;
    }

    private static int pharmacy(String name, String license, String email, Area area, PharmacyStatus status)
            throws Exception {
        User owner = new User();
        owner.setFullName("Owner of " + name);
        owner.setEmail(email);
        owner.setPhone("01711111111");
        owner.setSalt("c2FsdA==");
        owner.setPasswordHash("x");
        owner.setRole(Role.PHARMACY);
        owner.setAreaId(area.getId());
        owner.setActive(true);
        new UserDao().insert(owner);
        Pharmacy p = new Pharmacy();
        p.setOwnerId(owner.getId());
        p.setName(name);
        p.setLicenseNo(license);
        p.setAddress("Road 1, " + area.getName());
        p.setAreaId(area.getId());
        p.setLatitude(area.getLatitude());
        p.setLongitude(area.getLongitude());
        p.setOpenTime("08:00");
        p.setCloseTime("23:00");
        p.setStatus(status);
        return new PharmacyDao().insert(p);
    }

    private static void batch(int pharmacyId, int medicineId, String batchNo, int qty, int reserved, double price,
            String expiry) throws Exception {
        Inventory i = new Inventory();
        i.setPharmacyId(pharmacyId);
        i.setMedicineId(medicineId);
        i.setBatchNo(batchNo);
        i.setQuantity(qty);
        i.setReservedQty(reserved);
        i.setPrice(java.math.BigDecimal.valueOf(price));
        i.setExpiryDate(expiry);
        i.setLowStockThreshold(10);
        new InventoryDao().insert(i);
    }

    private static void batchIn(int pharmacyId, int medicineId, String batchNo, int qty, String currency, String price,
            String expiry) throws Exception {
        Inventory i = new Inventory();
        i.setPharmacyId(pharmacyId);
        i.setMedicineId(medicineId);
        i.setBatchNo(batchNo);
        i.setQuantity(qty);
        i.setPrice(new java.math.BigDecimal(price));
        i.setCurrency(currency);
        i.setExpiryDate(expiry);
        i.setLowStockThreshold(10);
        new InventoryDao().insert(i);
    }

    private static void sql(String s) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(s);
        }
    }

    private static int count(String s) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(s)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
