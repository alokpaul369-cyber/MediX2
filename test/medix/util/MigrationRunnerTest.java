package medix.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import medix.dao.AreaDao;
import medix.model.Area;
import medix.model.PlaceSource;
import medix.service.DatabaseService;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A database with the OLD structure (Parts 1-10: Dhaka areas only, schema_v1.sql) and data is
 * migrated to version 2 (worldwide locations) without losing a row or breaking a foreign key.
 */
public class MigrationRunnerTest {

    @BeforeClass
    public static void createOldDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        String oldSchema = resource("schema_v1.sql");
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement()) {
            for (String sql : oldSchema.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!sql.trim().isEmpty()) {
                    st.execute(sql);
                }
            }
            st.execute("PRAGMA user_version = 1");
            st.execute("INSERT INTO areas (name, city, latitude, longitude) VALUES ('Dhanmondi', 'Dhaka', 23.7461, 90.3742)");
            st.execute("INSERT INTO areas (name, city, latitude, longitude) VALUES ('Mirpur 10', 'Dhaka', 23.8069, 90.3687)");
            st.execute("INSERT INTO users (full_name, email, phone, password_hash, salt, role, area_id) "
                    + "VALUES ('Old Patient', 'old@test.com', '01711111111', 'x', 'c2FsdA==', 'PATIENT', 2)");
            st.execute("INSERT INTO users (full_name, email, password_hash, salt, role) "
                    + "VALUES ('MediX Admin', 'admin@medix.com', 'x', 'c2FsdA==', 'ADMIN')");
            st.execute("INSERT INTO search_logs (user_id, query, area_id, results_count) VALUES (1, 'napa', 2, 3)");
            // stock and a reservation priced in Taka (before Fix 3 every price was Tk)
            st.execute("INSERT INTO users (full_name, email, password_hash, salt, role, area_id) "
                    + "VALUES ('Old Owner', 'owner@test.com', 'x', 'c2FsdA==', 'PHARMACY', 2)");
            st.execute("INSERT INTO medicines (brand_name, generic_name, strength, form, manufacturer) "
                    + "VALUES ('Napa', 'Paracetamol', '500 mg', 'TABLET', 'Beximco Pharmaceuticals')");
            st.execute("INSERT INTO pharmacies (owner_id, name, license_no, address, area_id, status) "
                    + "VALUES (3, 'Old Pharmacy', 'DL-777777', 'Road 1, Mirpur', 2, 'VERIFIED')");
            st.execute("INSERT INTO inventory (pharmacy_id, medicine_id, batch_no, quantity, reserved_qty, price, expiry_date) "
                    + "VALUES (1, 1, 'B-1', 40, 2, 1.2, '2030-01-01')");
            st.execute("INSERT INTO reservations (code, patient_id, pharmacy_id, medicine_id, inventory_id, quantity, expires_at) "
                    + "VALUES ('MX-0101-0001', 1, 1, 1, 1, 2, '2030-01-01 10:00:00')");
        }
        // the normal start-up: old migrations, schema.sql, MigrationRunner, seed
        new DatabaseService().initialize();
    }

    @Test
    public void areasKeepTheirIdsAndGetWorldwideColumns() throws Exception {
        Area mirpur = new AreaDao().findById(2);
        assertEquals("Mirpur 10", mirpur.getName());
        assertEquals("Mirpur 10, Dhaka, Bangladesh", mirpur.getDisplayName());
        assertEquals("Dhaka", mirpur.getCity());
        assertEquals("Bangladesh", mirpur.getCountry());
        assertEquals("BD", mirpur.getCountryCode());
        assertEquals(PlaceSource.SEED, mirpur.getSource());
        assertEquals(23.8069, mirpur.getLatitude(), 1e-9);
        assertEquals("the old UNIQUE on name is gone: a second 'Dhanmondi' elsewhere is allowed", 1,
                insertDuplicateName());
        assertTrue(text("SELECT sql FROM sqlite_master WHERE name = 'areas'").contains("display_name"));
    }

    @Test
    public void existingUsersAndSearchesGetCoordinatesFromTheirArea() throws Exception {
        assertEquals(23.81, number("SELECT latitude FROM users WHERE email = 'old@test.com'"), 1e-9);
        assertEquals(90.37, number("SELECT longitude FROM users WHERE email = 'old@test.com'"), 1e-9);
        assertEquals("Mirpur 10, Dhaka", text("SELECT location_name FROM users WHERE email = 'old@test.com'"));
        assertEquals(2, (int) number("SELECT area_id FROM users WHERE email = 'old@test.com'"));
        assertNull("no area, no location", text("SELECT location_name FROM users WHERE email = 'admin@medix.com'"));
        assertEquals(23.81, number("SELECT latitude FROM search_logs WHERE query = 'napa'"), 1e-9);
    }

    @Test
    public void migrationRunsOnceAndKeepsTheSchemaRules() throws Exception {
        assertEquals(MigrationRunner.LATEST_VERSION, (int) number("PRAGMA user_version"));
        assertEquals("nothing left to run", 0, MigrationRunner.run());
        // migration 3: must_change_password for every old user (0) and the password_resets table
        assertEquals(0, (int) number("SELECT COUNT(*) FROM users WHERE must_change_password <> 0"));
        assertEquals(1, rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'password_resets'"));
        assertEquals(1, rows("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_password_resets_user_id'"));
        assertEquals("no broken foreign keys", 0, rows("PRAGMA foreign_key_check"));
        assertEquals(1, (int) number("PRAGMA foreign_keys"));
        DatabaseService db = new DatabaseService();
        assertEquals("Schema check: 14/14 tables use id INTEGER PRIMARY KEY AUTOINCREMENT", db.checkSchema().get(0));
        assertTrue(db.isSchemaOk());
        assertEquals("the seed did not add the 10 areas again", 2, rows("SELECT * FROM areas WHERE source = 'SEED'"));
    }

    @Test
    public void version4KeepsEveryRowAndPricesStayTaka() throws Exception {
        assertEquals("the medicine keeps its id", "Napa", text("SELECT brand_name FROM medicines WHERE id = 1"));
        assertEquals("SEED", text("SELECT source FROM medicines WHERE id = 1"));
        assertEquals("APPROVED", text("SELECT status FROM medicines WHERE id = 1"));
        assertEquals("", text("SELECT form_other FROM medicines WHERE id = 1"));
        assertTrue(text("SELECT sql FROM sqlite_master WHERE name = 'medicines'").contains("'SUPPOSITORY'"));
        assertEquals("BDT", text("SELECT currency FROM inventory WHERE id = 1"));
        assertEquals("BDT", text("SELECT currency FROM pharmacies WHERE id = 1"));
        assertEquals("the price when reserving is now saved", 1.2, number("SELECT unit_price FROM reservations WHERE id = 1"), 1e-9);
        assertEquals("BDT", text("SELECT currency FROM reservations WHERE id = 1"));
        assertNull(text("SELECT preferred_currency FROM users WHERE id = 1"));
        assertEquals("inventory still points to the medicine", 1, (int) number("SELECT COUNT(*) FROM inventory i "
                + "JOIN medicines m ON m.id = i.medicine_id"));
        for (String index : new String[]{"idx_medicines_created_by", "idx_medicines_reviewed_by", "idx_medicines_status",
            "idx_medicines_merged_into_id"}) {
            assertEquals(index, 1, rows("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '" + index + "'"));
        }
        assertEquals(1, rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'exchange_rates'"));
        assertEquals("the old medicine is not seeded again", 1, (int) number("SELECT COUNT(*) FROM medicines "
                + "WHERE brand_name = 'Napa' AND strength = '500 mg'"));
    }

    @Test
    public void findByNamePrefersTheMigratedDhakaArea() throws Exception {
        Area dhanmondi = new AreaDao().findByName("Dhanmondi");
        assertEquals(1, dhanmondi.getId());
        assertEquals("Dhanmondi, Dhaka, Bangladesh", dhanmondi.getDisplayName());
    }

    // ---------------------------------------------------------------- helpers

    private static int insertDuplicateName() throws Exception {
        if (new AreaDao().findByDisplayName("Dhanmondi, Elsewhere, Testland") != null) {
            return 1;
        }
        Area other = new Area();
        other.setName("Dhanmondi");
        other.setDisplayName("Dhanmondi, Elsewhere, Testland");
        other.setLatitude(10);
        other.setLongitude(10);
        other.setSource(PlaceSource.MANUAL);
        new AreaDao().insert(other);
        return 1;
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = MigrationRunnerTest.class.getResourceAsStream(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static double number(String sql) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getDouble(1) : Double.NaN;
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int rows(String sql) throws Exception {
        int n = 0;
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                n++;
            }
        }
        return n;
    }
}
