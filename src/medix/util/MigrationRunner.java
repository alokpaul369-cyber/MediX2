package medix.util;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One-time database migrations, run at start-up right after schema.sql (DatabaseService.initialize).
 * PRAGMA user_version says which migrations are done, so each one runs once. A migration runs in
 * ONE transaction with foreign keys switched off (needed to rebuild a table that others point to)
 * and switched on again afterwards; PRAGMA foreign_key_check must find nothing before it commits.
 *
 * Every step checks the structure first, so on a fresh database (schema.sql already creates the
 * final tables) a migration only moves user_version forward.
 */
public final class MigrationRunner {

    /**
     * user_version after every migration has run. 1 = trust_score fix (DatabaseService.migrate),
     * 2 = worldwide locations, 3 = password resets, 4 = pharmacy-added medicines + any currency.
     */
    public static final int LATEST_VERSION = 4;

    private MigrationRunner() {
    }

    /** Runs the missing migrations; returns how many ran. */
    public static int run() throws SQLException {
        int ran = 0;
        try (Connection con = DBConnection.getConnection()) {
            int version = userVersion(con);
            if (version < 2) {
                migrate(con, 2, MigrationRunner::worldwideLocations);
                ran++;
            }
            if (version < 3) {
                migrate(con, 3, MigrationRunner::passwordResets);
                ran++;
            }
            if (version < 4) {
                migrate(con, 4, MigrationRunner::medicinesAndCurrencies);
                ran++;
            }
        }
        return ran;
    }

    // ---------------------------------------------------------------- migration 2

    /** The final areas table (same as schema.sql), created as areas_new and renamed. */
    private static final String AREAS_NEW = "CREATE TABLE areas_new ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " name TEXT NOT NULL,"
            + " display_name TEXT NOT NULL UNIQUE,"
            + " city TEXT,"
            + " country TEXT,"
            + " country_code TEXT,"
            + " latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),"
            + " longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),"
            + " source TEXT NOT NULL DEFAULT 'SEED' CHECK (source IN ('SEED', 'GEOCODED', 'MANUAL')),"
            + " created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')))";

    /**
     * Version 2 – any location on Earth: areas becomes a cache of saved places (same ids, so every
     * foreign key still works), users get latitude / longitude / location_name, search_logs get
     * latitude / longitude (2 decimals, about 1 km). Existing rows are filled from their area.
     */
    private static void worldwideLocations(Connection con) throws SQLException {
        if (!hasColumn(con, "areas", "display_name")) {
            execute(con, AREAS_NEW,
                    "INSERT INTO areas_new (id, name, display_name, city, country, country_code, latitude, longitude, "
                    + "source, created_at) SELECT id, name, name || ', ' || COALESCE(NULLIF(city, ''), 'Dhaka') "
                    + "|| ', Bangladesh', COALESCE(NULLIF(city, ''), 'Dhaka'), 'Bangladesh', 'BD', latitude, longitude, "
                    + "'SEED', datetime('now', 'localtime') FROM areas ORDER BY id",
                    "DROP TABLE areas",
                    "ALTER TABLE areas_new RENAME TO areas");
        }
        addColumn(con, "users", "latitude", "REAL");
        addColumn(con, "users", "longitude", "REAL");
        addColumn(con, "users", "location_name", "TEXT");
        addColumn(con, "search_logs", "latitude", "REAL");
        addColumn(con, "search_logs", "longitude", "REAL");
        execute(con,
                "UPDATE users SET "
                + "latitude = (SELECT ROUND(a.latitude, 2) FROM areas a WHERE a.id = users.area_id), "
                + "longitude = (SELECT ROUND(a.longitude, 2) FROM areas a WHERE a.id = users.area_id), "
                + "location_name = (SELECT a.name || ', ' || COALESCE(a.city, a.country) FROM areas a "
                + "WHERE a.id = users.area_id) "
                + "WHERE latitude IS NULL AND area_id IS NOT NULL",
                "UPDATE search_logs SET "
                + "latitude = (SELECT ROUND(a.latitude, 2) FROM areas a WHERE a.id = search_logs.area_id), "
                + "longitude = (SELECT ROUND(a.longitude, 2) FROM areas a WHERE a.id = search_logs.area_id) "
                + "WHERE latitude IS NULL AND area_id IS NOT NULL");
    }

    // ---------------------------------------------------------------- migration 3

    /**
     * Version 3 – "Forgot password?": users.must_change_password (1 after an admin reset) and the
     * password_resets table (13th table; schema.sql creates it too, so only the column is new here
     * for an existing database). Every existing user keeps must_change_password = 0.
     */
    private static void passwordResets(Connection con) throws SQLException {
        addColumn(con, "users", "must_change_password",
                "INTEGER NOT NULL DEFAULT 0 CHECK (must_change_password IN (0, 1))");
        execute(con, "CREATE TABLE IF NOT EXISTS password_resets ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,"
                + " code_hash TEXT NOT NULL,"
                + " code_salt TEXT NOT NULL,"
                + " created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),"
                + " expires_at TEXT NOT NULL,"
                + " attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),"
                + " used INTEGER NOT NULL DEFAULT 0 CHECK (used IN (0, 1)),"
                + " sent_via TEXT NOT NULL CHECK (sent_via IN ('EMAIL', 'DEMO')))",
                "CREATE INDEX IF NOT EXISTS idx_password_resets_user_id ON password_resets(user_id)");
    }

    // ---------------------------------------------------------------- migration 4

    /** The final medicines table (same as schema.sql), created as medicines_new and renamed. */
    private static final String MEDICINES_NEW = "CREATE TABLE medicines_new ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " brand_name TEXT NOT NULL,"
            + " generic_name TEXT NOT NULL,"
            + " strength TEXT NOT NULL,"
            + " form TEXT NOT NULL CHECK (form IN ('TABLET', 'CAPSULE', 'SYRUP', 'SUSPENSION', 'INJECTION',"
            + " 'DROPS', 'CREAM', 'OINTMENT', 'GEL', 'INHALER', 'POWDER',"
            + " 'SACHET', 'SUPPOSITORY', 'SPRAY', 'PATCH', 'OTHER')),"
            + " form_other TEXT NOT NULL DEFAULT '',"
            + " manufacturer TEXT,"
            + " rx_required INTEGER NOT NULL DEFAULT 0 CHECK (rx_required IN (0, 1)),"
            + " source TEXT NOT NULL DEFAULT 'ADMIN' CHECK (source IN ('SEED', 'ADMIN', 'PHARMACY')),"
            + " status TEXT NOT NULL DEFAULT 'APPROVED' CHECK (status IN ('APPROVED', 'PENDING', 'REJECTED', 'MERGED')),"
            + " created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,"
            + " created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),"
            + " reviewed_by INTEGER REFERENCES users(id) ON DELETE SET NULL,"
            + " reviewed_at TEXT,"
            + " review_note TEXT,"
            + " merged_into_id INTEGER REFERENCES medicines(id) ON DELETE SET NULL,"
            + " UNIQUE (brand_name, strength, form, form_other))";

    /**
     * Version 4 – pharmacies can add any medicine, prices in any currency.
     * medicines is rebuilt with the SAME ids (inventory, reservations and search_logs point to them):
     * more forms, form_other, source / status / review columns; every existing row becomes SEED +
     * APPROVED. pharmacies, inventory get currency (default BDT, so every old price stays Taka);
     * reservations get unit_price + currency, filled from their batch (the price is now kept even if
     * the batch changes); users get preferred_currency (NULL = the currency of their country);
     * exchange_rates is the 14th table. A pharmacy outside Bangladesh gets its country's currency as
     * its default for NEW batches (its old batches stay BDT, they were entered in Taka).
     */
    private static void medicinesAndCurrencies(Connection con) throws SQLException {
        if (!hasColumn(con, "medicines", "status")) {
            execute(con, MEDICINES_NEW,
                    "INSERT INTO medicines_new (id, brand_name, generic_name, strength, form, form_other, manufacturer, "
                    + "rx_required, source, status, created_by, created_at) SELECT id, brand_name, generic_name, strength, "
                    + "form, '', manufacturer, rx_required, 'SEED', 'APPROVED', NULL, created_at FROM medicines ORDER BY id",
                    "DROP TABLE medicines",
                    "ALTER TABLE medicines_new RENAME TO medicines");
        }
        execute(con,
                "CREATE INDEX IF NOT EXISTS idx_medicines_created_by ON medicines(created_by)",
                "CREATE INDEX IF NOT EXISTS idx_medicines_reviewed_by ON medicines(reviewed_by)",
                "CREATE INDEX IF NOT EXISTS idx_medicines_status ON medicines(status)",
                "CREATE INDEX IF NOT EXISTS idx_medicines_merged_into_id ON medicines(merged_into_id)");
        boolean newPharmacyColumn = !hasColumn(con, "pharmacies", "currency");
        addColumn(con, "pharmacies", "currency", "TEXT NOT NULL DEFAULT 'BDT'");
        addColumn(con, "inventory", "currency", "TEXT NOT NULL DEFAULT 'BDT'");
        boolean newReservationColumns = !hasColumn(con, "reservations", "unit_price");
        addColumn(con, "reservations", "unit_price", "REAL");
        addColumn(con, "reservations", "currency", "TEXT");
        addColumn(con, "users", "preferred_currency", "TEXT");
        execute(con, "CREATE TABLE IF NOT EXISTS exchange_rates ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " currency_code TEXT NOT NULL UNIQUE,"
                + " rate_per_usd REAL NOT NULL CHECK (rate_per_usd > 0),"
                + " source TEXT NOT NULL CHECK (source IN ('ONLINE', 'MANUAL')),"
                + " updated_at TEXT NOT NULL)");
        if (newReservationColumns) {
            execute(con, "UPDATE reservations SET "
                    + "unit_price = (SELECT i.price FROM inventory i WHERE i.id = reservations.inventory_id), "
                    + "currency = COALESCE((SELECT i.currency FROM inventory i WHERE i.id = reservations.inventory_id), "
                    + "(SELECT p.currency FROM pharmacies p WHERE p.id = reservations.pharmacy_id), 'BDT') "
                    + "WHERE unit_price IS NULL AND currency IS NULL");
        }
        if (newPharmacyColumn) {
            defaultCurrencyFromCountry(con);
        }
    }

    /** pharmacies.currency from the country of the pharmacy's place (BD keeps BDT). */
    private static void defaultCurrencyFromCountry(Connection con) throws SQLException {
        List<Object[]> rows = new ArrayList<Object[]>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT p.id, a.country_code FROM pharmacies p JOIN areas a ON a.id = p.area_id "
                + "WHERE a.country_code IS NOT NULL AND UPPER(a.country_code) <> 'BD'")) {
            while (rs.next()) {
                rows.add(new Object[]{rs.getInt(1), rs.getString(2)});
            }
        }
        try (java.sql.PreparedStatement ps = con.prepareStatement("UPDATE pharmacies SET currency = ? WHERE id = ?")) {
            for (Object[] r : rows) {
                ps.setString(1, CurrencyUtil.currencyForCountry((String) r[1]));
                ps.setInt(2, (Integer) r[0]);
                ps.executeUpdate();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private interface Migration {
        void apply(Connection con) throws SQLException;
    }

    private static void migrate(Connection con, int toVersion, Migration migration) throws SQLException {
        execute(con, "PRAGMA foreign_keys = OFF"); // has no effect inside a transaction, so before it
        con.setAutoCommit(false);
        try {
            migration.apply(con);
            List<String> broken = foreignKeyProblems(con);
            if (!broken.isEmpty()) {
                throw new SQLException("Migration " + toVersion + " would break foreign keys: " + broken);
            }
            execute(con, "PRAGMA user_version = " + toVersion);
            con.commit();
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            ErrorLog.log("Database migration " + toVersion + " failed and was rolled back", e);
            throw e;
        } finally {
            con.setAutoCommit(true);
            execute(con, "PRAGMA foreign_keys = ON");
        }
    }

    private static List<String> foreignKeyProblems(Connection con) throws SQLException {
        List<String> rows = new ArrayList<String>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + " row " + rs.getLong(2) + " -> " + rs.getString(3));
            }
        }
        return rows;
    }

    static int userVersion(Connection con) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Table and column names here are constants of this class, never user input. */
    private static boolean hasColumn(Connection con, String table, String column) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addColumn(Connection con, String table, String column, String type) throws SQLException {
        if (!hasColumn(con, table, column)) {
            execute(con, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private static void execute(Connection con, String... statements) throws SQLException {
        execute(con, Arrays.asList(statements));
    }

    private static void execute(Connection con, List<String> statements) throws SQLException {
        try (Statement st = con.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }
}
