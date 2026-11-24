-- MediX database schema (SQLite)
-- Rules:
--   * The first column of every table is: id INTEGER PRIMARY KEY AUTOINCREMENT
--   * Every foreign key column has its own CREATE INDEX
--   * Dates: yyyy-MM-dd, date-times: yyyy-MM-dd HH:mm:ss, money: REAL in the row's currency
--     (ISO 4217 code in a currency column next to it, e.g. 'BDT', 'GBP'; Fix 3)
-- This file is split on ';' by DatabaseService, so never use ';' inside a statement.

-- 1. areas: saved places anywhere in the world (the 10 seeded Dhaka areas + places users chose).
--    name is not unique (many places share a name), display_name is. Older databases are rebuilt
--    to this structure by MigrationRunner (version 2), keeping every id.
CREATE TABLE IF NOT EXISTS areas (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,
    display_name    TEXT    NOT NULL UNIQUE,
    city            TEXT,
    country         TEXT,
    country_code    TEXT,
    latitude        REAL    NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude       REAL    NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    source          TEXT    NOT NULL DEFAULT 'SEED' CHECK (source IN ('SEED', 'GEOCODED', 'MANUAL')),
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- 2. users
CREATE TABLE IF NOT EXISTS users (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    full_name       TEXT    NOT NULL,
    email           TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    phone           TEXT,
    password_hash   TEXT    NOT NULL,
    salt            TEXT    NOT NULL,
    role            TEXT    NOT NULL CHECK (role IN ('PATIENT', 'PHARMACY', 'ADMIN')),
    area_id         INTEGER REFERENCES areas(id) ON DELETE SET NULL,
    is_active       INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    latitude        REAL,
    longitude       REAL,
    location_name   TEXT,
    must_change_password INTEGER NOT NULL DEFAULT 0 CHECK (must_change_password IN (0, 1)),
    preferred_currency TEXT
);
CREATE INDEX IF NOT EXISTS idx_users_area_id ON users(area_id);

-- 3. pharmacies
CREATE TABLE IF NOT EXISTS pharmacies (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id            INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    name                TEXT    NOT NULL,
    license_no          TEXT    NOT NULL UNIQUE,
    license_doc_path    TEXT,
    address             TEXT    NOT NULL,
    area_id             INTEGER REFERENCES areas(id) ON DELETE SET NULL,
    latitude            REAL,
    longitude           REAL,
    phone               TEXT,
    open_time           TEXT,
    close_time          TEXT,
    status              TEXT    NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED', 'SUSPENDED')),
    trust_score         INTEGER NOT NULL DEFAULT 80 CHECK (trust_score BETWEEN 0 AND 100),
    stale_penalty       INTEGER NOT NULL DEFAULT 0 CHECK (stale_penalty >= 0),
    currency            TEXT    NOT NULL DEFAULT 'BDT',
    verified_by         INTEGER REFERENCES users(id) ON DELETE SET NULL,
    verified_at         TEXT,
    reject_reason       TEXT,
    created_at          TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_pharmacies_owner_id ON pharmacies(owner_id);
CREATE INDEX IF NOT EXISTS idx_pharmacies_area_id ON pharmacies(area_id);
CREATE INDEX IF NOT EXISTS idx_pharmacies_verified_by ON pharmacies(verified_by);

-- 4. medicines (Fix 3: pharmacies add medicines too; admins review them). form_other is the
--    free-text form when form = OTHER and '' otherwise (NOT NULL, so the UNIQUE also holds for it).
--    The indexes on created_by, reviewed_by, status and merged_into_id are created by
--    MigrationRunner (version 4): an older medicines table gets these columns only there.
CREATE TABLE IF NOT EXISTS medicines (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    brand_name      TEXT    NOT NULL,
    generic_name    TEXT    NOT NULL,
    strength        TEXT    NOT NULL,
    form            TEXT    NOT NULL CHECK (form IN ('TABLET', 'CAPSULE', 'SYRUP', 'SUSPENSION', 'INJECTION',
                                                     'DROPS', 'CREAM', 'OINTMENT', 'GEL', 'INHALER', 'POWDER',
                                                     'SACHET', 'SUPPOSITORY', 'SPRAY', 'PATCH', 'OTHER')),
    form_other      TEXT    NOT NULL DEFAULT '',
    manufacturer    TEXT,
    rx_required     INTEGER NOT NULL DEFAULT 0 CHECK (rx_required IN (0, 1)),
    source          TEXT    NOT NULL DEFAULT 'ADMIN' CHECK (source IN ('SEED', 'ADMIN', 'PHARMACY')),
    status          TEXT    NOT NULL DEFAULT 'APPROVED' CHECK (status IN ('APPROVED', 'PENDING', 'REJECTED', 'MERGED')),
    created_by      INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    reviewed_by     INTEGER REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at     TEXT,
    review_note     TEXT,
    merged_into_id  INTEGER REFERENCES medicines(id) ON DELETE SET NULL,
    UNIQUE (brand_name, strength, form, form_other)
);

-- 5. inventory
CREATE TABLE IF NOT EXISTS inventory (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    pharmacy_id         INTEGER NOT NULL REFERENCES pharmacies(id) ON DELETE CASCADE,
    medicine_id         INTEGER NOT NULL REFERENCES medicines(id) ON DELETE RESTRICT,
    batch_no            TEXT    NOT NULL,
    quantity            INTEGER NOT NULL DEFAULT 0,
    reserved_qty        INTEGER NOT NULL DEFAULT 0,
    price               REAL    NOT NULL CHECK (price >= 0),
    currency            TEXT    NOT NULL DEFAULT 'BDT',
    expiry_date         TEXT    NOT NULL,
    low_stock_threshold INTEGER NOT NULL DEFAULT 10 CHECK (low_stock_threshold >= 0),
    updated_at          TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (pharmacy_id, medicine_id, batch_no),
    CHECK (quantity >= 0 AND reserved_qty >= 0 AND reserved_qty <= quantity)
);
CREATE INDEX IF NOT EXISTS idx_inventory_pharmacy_id ON inventory(pharmacy_id);
CREATE INDEX IF NOT EXISTS idx_inventory_medicine_id ON inventory(medicine_id);

-- 6. stock_movements
CREATE TABLE IF NOT EXISTS stock_movements (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    inventory_id    INTEGER NOT NULL REFERENCES inventory(id) ON DELETE CASCADE,
    change_qty      INTEGER NOT NULL,
    reason          TEXT    NOT NULL CHECK (reason IN ('ADD', 'SALE', 'ADJUST', 'EXPIRED',
                                                       'RESERVED', 'RELEASED', 'PICKED_UP')),
    user_id         INTEGER REFERENCES users(id) ON DELETE SET NULL,
    note            TEXT,
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_stock_movements_inventory_id ON stock_movements(inventory_id);
CREATE INDEX IF NOT EXISTS idx_stock_movements_user_id ON stock_movements(user_id);

-- 7. reservations
CREATE TABLE IF NOT EXISTS reservations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    code            TEXT    NOT NULL UNIQUE,
    patient_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pharmacy_id     INTEGER NOT NULL REFERENCES pharmacies(id) ON DELETE CASCADE,
    medicine_id     INTEGER NOT NULL REFERENCES medicines(id) ON DELETE RESTRICT,
    inventory_id    INTEGER REFERENCES inventory(id) ON DELETE SET NULL,
    quantity        INTEGER NOT NULL CHECK (quantity > 0),
    status          TEXT    NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'CONFIRMED', 'READY', 'COMPLETED',
                                      'CANCELLED', 'EXPIRED')),
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    expires_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    note            TEXT,
    extended        INTEGER NOT NULL DEFAULT 0 CHECK (extended IN (0, 1)),
    cancelled_by    TEXT    CHECK (cancelled_by IN ('PATIENT', 'PHARMACY', 'SYSTEM')),
    cancel_reason   TEXT,
    unit_price      REAL,
    currency        TEXT
);
CREATE INDEX IF NOT EXISTS idx_reservations_patient_id ON reservations(patient_id);
CREATE INDEX IF NOT EXISTS idx_reservations_pharmacy_id ON reservations(pharmacy_id);
CREATE INDEX IF NOT EXISTS idx_reservations_medicine_id ON reservations(medicine_id);
CREATE INDEX IF NOT EXISTS idx_reservations_inventory_id ON reservations(inventory_id);

-- 8. notifications
CREATE TABLE IF NOT EXISTS notifications (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type            TEXT    NOT NULL CHECK (type IN ('LOW_STOCK', 'EXPIRY', 'RESERVATION', 'STALE',
                                                     'VERIFICATION', 'REPORT', 'INFO')),
    message         TEXT    NOT NULL,
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    is_read         INTEGER NOT NULL DEFAULT 0 CHECK (is_read IN (0, 1)),
    link_entity     TEXT,
    link_id         INTEGER
);
CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications(user_id);

-- 9. search_logs
CREATE TABLE IF NOT EXISTS search_logs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER REFERENCES users(id) ON DELETE SET NULL,
    query           TEXT    NOT NULL,
    medicine_id     INTEGER REFERENCES medicines(id) ON DELETE SET NULL,
    area_id         INTEGER REFERENCES areas(id) ON DELETE SET NULL,
    results_count   INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    latitude        REAL,
    longitude       REAL
);
CREATE INDEX IF NOT EXISTS idx_search_logs_user_id ON search_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_search_logs_medicine_id ON search_logs(medicine_id);
CREATE INDEX IF NOT EXISTS idx_search_logs_area_id ON search_logs(area_id);

-- 10. reports
CREATE TABLE IF NOT EXISTS reports (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    reporter_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pharmacy_id     INTEGER NOT NULL REFERENCES pharmacies(id) ON DELETE CASCADE,
    reservation_id  INTEGER REFERENCES reservations(id) ON DELETE SET NULL,
    reason          TEXT    NOT NULL CHECK (reason IN ('WRONG_STOCK', 'RESERVATION_NOT_HONOURED',
                                                       'WRONG_PRICE', 'FAKE_PHARMACY', 'OTHER')),
    details         TEXT,
    status          TEXT    NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    admin_note      TEXT,
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    resolved_at     TEXT
);
CREATE INDEX IF NOT EXISTS idx_reports_reporter_id ON reports(reporter_id);
CREATE INDEX IF NOT EXISTS idx_reports_pharmacy_id ON reports(pharmacy_id);
CREATE INDEX IF NOT EXISTS idx_reports_reservation_id ON reports(reservation_id);

-- 11. audit_logs
CREATE TABLE IF NOT EXISTS audit_logs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER REFERENCES users(id) ON DELETE SET NULL,
    action          TEXT    NOT NULL,
    entity          TEXT,
    entity_id       INTEGER,
    details         TEXT,
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id ON audit_logs(user_id);

-- 12. job_runs (Part 8): one row per run of a background job (JobScheduler)
CREATE TABLE IF NOT EXISTS job_runs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    job_name        TEXT    NOT NULL,
    started_at      TEXT    NOT NULL,
    finished_at     TEXT,
    status          TEXT    NOT NULL CHECK (status IN ('OK', 'FAILED')),
    items_affected  INTEGER NOT NULL DEFAULT 0,
    message         TEXT
);
CREATE INDEX IF NOT EXISTS idx_job_runs_job_name ON job_runs(job_name);

-- 13. password_resets (Fix 2): "Forgot password?" codes. Only a salted SHA-256 hash of the 6-digit
--     code is stored (PasswordUtil), never the code. used = 1 after a reset, a newer code, or 5 wrong tries.
CREATE TABLE IF NOT EXISTS password_resets (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash       TEXT    NOT NULL,
    code_salt       TEXT    NOT NULL,
    created_at      TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    expires_at      TEXT    NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    used            INTEGER NOT NULL DEFAULT 0 CHECK (used IN (0, 1)),
    sent_via        TEXT    NOT NULL CHECK (sent_via IN ('EMAIL', 'DEMO'))
);
CREATE INDEX IF NOT EXISTS idx_password_resets_user_id ON password_resets(user_id);

-- 14. exchange_rates (Fix 3): units of each currency per 1 US dollar. ONLINE rows come from
--     open.er-api.com (once a day), MANUAL rows were typed by an admin and are kept by the online update.
CREATE TABLE IF NOT EXISTS exchange_rates (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    currency_code   TEXT    NOT NULL UNIQUE,
    rate_per_usd    REAL    NOT NULL CHECK (rate_per_usd > 0),
    source          TEXT    NOT NULL CHECK (source IN ('ONLINE', 'MANUAL')),
    updated_at      TEXT    NOT NULL
)
