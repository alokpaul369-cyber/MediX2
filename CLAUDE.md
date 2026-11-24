# MediX – Medicine Availability Finder

University project: a Java desktop app. Pharmacies keep a live inventory of medicines
(quantity, batch, price, expiry). Patients search a medicine by name, see nearby pharmacies
that have it ranked by distance, and reserve units for a short time before travelling.
Admins verify pharmacy licenses and monitor the platform.

The app is built in 10 parts. Every part MUST follow the rules below.
- **Part 1 (done):** project setup, database, theme.
- **Part 2 (done):** LoginFrame, RegisterFrame, role home frames, SidebarUtil, AuthService.
- **Part 3 (done):** admin VerificationFrame (pharmacy verification) and MedicineCatalogFrame.
- **Part 4 (done):** pharmacy InventoryFrame, StockDialogFrame, PharmacyProfileFrame, InventoryService.
- **Part 5 (done):** patient SearchFrame, SearchEngine, AvailabilityCache, search_logs, JUnit 4 tests.
- **Part 6 (done):** PharmacyDetailFrame, reservations (ReservationService, confirm, My Reservations),
  ReportFrame.
- **Part 7 (done):** PharmacyReservationsFrame (pharmacy side of reservations), TrustScoreService,
  nightly trust job, trust as search tie-breaker.
- **Part 8 (done):** background jobs (JobScheduler + job_runs, the 12th table), stock / expiry /
  stale alerts, AlertsFrame, NotificationFrame, ToastFrame, tray messages, sidebar unread badge.
- **Part 9 (done):** AdminDashboardFrame (cards + JFreeChart charts, PNG export), ReportsFrame (uphold /
  dismiss / suspend), UsersFrame + AddAdminFrame, AuditLogFrame (filters, CSV export), patient ProfileFrame.
- **Part 10 (done):** DemoDataSeeder + "Load demo data", project review (start-up schema check, role
  guards, data/error.log), more JUnit tests, packaging (`ant package-fat`, run.bat, Launch4j), README.
- **Fix 1 (done): any location on Earth.** Saved places (areas), OpenStreetMap geocoding, LocationPickerFrame
  with a JXMapViewer2 map, coordinate search with a bounding box, international phone numbers, migration v2.
  See "Worldwide locations (Fix 1)" below – it replaces every older "Dhaka areas only" rule.
- **Fix 2 (done): "Forgot password?"** 6-digit e-mail codes (JavaMail, demo mode without mail settings),
  ResetPasswordFrame, admin reset with a temporary password + forced ChangePasswordFrame, migration 3,
  password_resets = the 13th table. See "Password reset (Fix 2)" below.
- **Fix 3 (done): pharmacies add any medicine, prices in any currency.** Pharmacy-added medicines (PENDING,
  sellable at once) reviewed by admins (approve / edit & approve / merge / reject), AddMedicineFrame,
  MergeMedicineFrame, `CurrencyUtil`, `engine.ExchangeRateService` (open.er-api.com),
  migration 4, exchange_rates = the 14th table. See "Any medicine, any currency (Fix 3)" below.

## Three roles

| Role       | Who                    | Does                                                          |
|------------|------------------------|---------------------------------------------------------------|
| `PATIENT`  | Member of the public   | Searches medicines, sees nearby pharmacies, makes reservations, reports pharmacies |
| `PHARMACY` | Pharmacy owner (1 user = 1 pharmacy) | Registers pharmacy + license, manages inventory, handles reservations |
| `ADMIN`    | Platform admin         | Verifies/rejects/suspends pharmacies, handles reports, monitors logs |

Seeded admin: `admin@medix.com` / `admin123` (user id 1).

## Java 8 ONLY

- `javac.source=1.8`, `javac.target=1.8`. Developed with JDK 1.8 and NetBeans 8.2.
- Forbidden (added after Java 8): `var`, records, text blocks, switch expressions,
  `List.of` / `Set.of` / `Map.of`, `String.isBlank` / `strip` / `repeat` / `lines`,
  `Optional.isEmpty`, `Files.readString` / `writeString`, `Stream.toList()`,
  `InputStream.readAllBytes`, `Collectors.toUnmodifiableList`, etc.
- Allowed Java 8 features: lambdas, streams, `java.util.Base64`, try-with-resources,
  multi-catch, diamond operator.
- Always confirm a build with Ant / NetBeans "Clean and Build" on JDK 8.

## Project type

- NetBeans "Java with Ant" project named `MediX`: `build.xml`, `manifest.mf`,
  `nbproject/project.xml`, `nbproject/project.properties`, `nbproject/build-impl.xml`,
  `nbproject/genfiles.properties`. Do not hand-edit `build-impl.xml`.
- Main class: `medix.Main`. Source root `src/`, test root `test/`.
- Libraries live in `lib/` and are registered in `project.properties`
  (`file.reference.*` + `javac.classpath`):
  - `sqlite-jdbc-3.54.1.0.jar` (SQLite driver)
  - `jfreechart-1.5.6.jar` (charts)
  - `jcalendar-1.4.jar` (`com.toedter.calendar.JDateChooser`)
  - `flatlaf-3.7.2.jar` (look and feel)
  - `javax.mail-1.6.2.jar` (JavaMail: password reset e-mails; Java 7 classes)
  - `json-20260814.jar` (org.json, parses Nominatim answers; compiled for Java 8)
  - `jxmapviewer2-2.8.1.jar` (the map in LocationPickerFrame; Java 7 classes) + `commons-logging-1.2.jar`
    (its runtime dependency)
  To add a library: copy the jar to `lib/`, add a `file.reference.<jar>=lib/<jar>` line and
  append `${file.reference.<jar>}` to `javac.classpath`. Only versions compiled for Java 8.
- Tests: JUnit 4 (`junit-4.13.2.jar`, `hamcrest-core-1.3.jar` in `lib/`, on
  `javac.test.classpath` ONLY so they are not shipped). Test classes live in `test/` in the
  same package as the class under test (e.g. `test/medix/engine/SearchEngineTest.java`).
  Run with NetBeans "Test" (Alt+F6) or `ant test`. Engine tests use plain lists; database
  tests set `user.dir` to a temp folder in `@BeforeClass` BEFORE anything touches `AppPaths`
  (see `SearchServiceTest`), so they never touch `data/medix.db`.

## UI rule: JFrame Forms ONLY

- Every screen is a NetBeans **JFrame Form**: `Xxx.java` + `Xxx.form` with the same name in
  `medix.forms`, exactly like NetBeans "New > JFrame Form", so the Design view opens it.
- Generated code stays between `//GEN-BEGIN:initComponents` and `//GEN-END:initComponents`
  (and the `//GEN-BEGIN:variables` block). The `.form` XML must match the generated code
  exactly: same component names, same layout, same properties. Layout = GroupLayout
  (Free Design). Use `.form` settings like `LoginFrame.form`: generateFQN=true,
  generateCenter=true (gives `setLocationRelativeTo(null)`), private variables,
  anonymous-inner-class listeners, formSizePolicy=1 (`pack()`). A fixed window size is the
  frame's `preferredSize` property (e.g. 1200x720) plus `pack()`.
- Event handlers are the NetBeans style
  `private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed`
  ... `}//GEN-LAST:event_btnSaveActionPerformed`, registered under `<Events>` in the `.form`.
- Own logic goes OUTSIDE the guarded blocks, in normal methods (constructor calls
  `initComponents()` then e.g. `applyTheme()` and `loadData()`). Styling (fonts, colours,
  table setup) is done in code through `UITheme` / `TableUtil`, not in the `.form`.
- NO JPanel pages, NO CardLayout screens, NO JInternalFrame.
  Navigation = `new NextFrame().setVisible(true); dispose();`
  JPanels INSIDE one JFrame Form are fine for grouping (the `pnlSidebar`, the "card" panels
  that are the tabs of `RegisterFrame.tabRegister`) – they are not separate pages.
- Pop-up messages: `JOptionPane` only. Validation shows the first problem in a
  JOptionPane and puts the cursor in that field
  (`SwingUtilities.invokeLater(field::requestFocusInWindow)` after the dialog, see `RegisterFrame.fail`).
- Every logged-in frame has a left `pnlSidebar` (170 px wide, empty GroupLayout in the
  `.form`) and calls `SidebarUtil.attach(this, pnlSidebar, SidebarUtil.<ITEM>)` in its
  constructor. When a part builds a menu screen, replace its `null` in `SidebarUtil.menuFor`
  with `TheFrame::new`. Logged-in frames: 1200x720, `EXIT_ON_CLOSE`, `lblWelcome` + role label
  (`SidebarUtil.roleText()`) at the top.
- Every frame calls `UITheme.styleFrame(this)` (window icon + background) in `applyTheme()`.
- Every frame: centered (`setLocationRelativeTo(null)`), title `"MediX – <Screen>"`
  (en dash), e.g. `MediX – Login`.
- Component names use prefixes: `txt` (text field and JPasswordField, e.g. `txtPassword`), `btn`, `tbl`, `cmb`,
  `lbl`, `chk`, `rdo`, `scr` (scroll pane), `pnl` (panel inside a frame), `dtc` (JDateChooser),
  `spn` (spinner), `txa` (text area), `lst` (JList). E.g. `txtEmail`, `btnSave`, `tblInventory`, `cmbNear`,
  `lblStatus`.
- The only `main` method is in `medix.Main`; forms do not get their own `main` method.

## Packages

| Package         | Contents                                                             |
|-----------------|----------------------------------------------------------------------|
| `medix`         | `Main.java` – FlatLaf, create DB, open first frame                   |
| `medix.forms`   | all JFrame Forms                                                     |
| `medix.model`   | POJOs + enums                                                        |
| `medix.dao`     | JDBC data access, one DAO per table (+ `BaseDao`, `SchemaDao`)       |
| `medix.service` | business logic. **Forms call services, never DAOs.** `LocationService` = places |
| `medix.engine`  | search matching, distance (`GeoUtil`), geocoding (`GeocodingService`), exchange rates (`ExchangeRateService`), scheduled jobs |
| `medix.util`    | `DBConnection`, `Session`, `DateUtil`, `UITheme`, `TableUtil`, `AppPaths`, `PasswordUtil`, `ValidationUtil`, `FileStorageUtil`, `SidebarUtil`, `FormUtil`, `AutoComplete`, `MapUtil`, `ChartUtil`, `ErrorLog`, `DemoDataSeeder`, `MigrationRunner`, `AppConfig`, `CurrencyUtil`, `TrustStoreUtil`, `PharmacyLocationFields` |
| `medix.resources` | `schema.sql`, `certs/isrg-root-x1.pem`                            |

## Database (SQLite)

- File `data/medix.db` next to the project (when run from NetBeans) or next to the JAR.
  License uploads go in `data/licenses/`. `AppPaths` resolves both and creates the folders.
- Schema: `src/medix/resources/schema.sql`, run on every start with
  `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`. It is split on `;`, so never
  put `;` inside a statement (no triggers). Schema changes: add new `CREATE ... IF NOT EXISTS`
  statements; for new columns on existing tables add an explicit migration in
  `DatabaseService.migrate()` (runs before schema.sql). Structural checks look at the table's
  CREATE sql (e.g. "has column note?"). One-time migrations: `PRAGMA user_version` – 1 = trust_score
  fix (in `DatabaseService.migrate()`), 2 = worldwide locations, 3 = password resets, 4 = pharmacy medicines +
  currencies (`util.MigrationRunner`, runs right AFTER schema.sql in ONE transaction with foreign_keys OFF, then
  `PRAGMA foreign_key_check`, then ON). Add the next one as version 5 in MigrationRunner (structure-checked
  steps, so a fresh install only moves the version). schema.sql runs BEFORE the migrations, so it must never
  create an index on a column a migration adds (old databases fail): such indexes go in the migration
  (e.g. the medicines indexes of version 4).
- Model int fields that have a column DEFAULT must start with that default in Java
  (e.g. `Pharmacy.trustScore = 80`), because the DAO inserts every column.
- `DBConnection.getConnection()` runs `PRAGMA foreign_keys = ON` on every connection.
  Always close connections with try-with-resources.
- Dates are TEXT: date `yyyy-MM-dd`, date-time `yyyy-MM-dd HH:mm:ss` (use `DateUtil`).
  Money is REAL next to a `currency` column (ISO 4217, e.g. 'BDT', 'GBP'; Fix 3); in Java money is ALWAYS
  `BigDecimal` (`CurrencyUtil.fromDb` rounds a REAL to the currency's decimals, `BaseDao.bind` writes a
  BigDecimal) – never float/double for money, never add two currencies without converting. Booleans are INTEGER 0/1.
- Enums are stored as `name()`; every enum column has a CHECK constraint listing the values.
- FK rules: `ON DELETE CASCADE` where the child cannot exist without the parent
  (inventory → pharmacy, stock_movements → inventory, pharmacy → owner user,
  reservations/notifications/reports → user/pharmacy), `ON DELETE SET NULL` for log tables
  and optional links, `ON DELETE RESTRICT` for medicines referenced by stock/reservations.

### ID rule (very important)

- The FIRST column of every table is `id INTEGER PRIMARY KEY AUTOINCREMENT`, so ids are
  1, 2, 3 ... and never reused.
- Never insert an id by hand. DAOs read the new id with `getGeneratedKeys()`
  (`BaseDao.executeInsert`) and `insert()` returns it (and sets it on the model).
- Every foreign key column has its own `CREATE INDEX idx_<table>_<column>`.
- Every JTable that shows table rows has `"ID"` as its FIRST column, narrow and centered:
  use `TableUtil.setupTable(tbl, "ID", ...)` (it rejects any other first column) and
  `TableUtil.getSelectedId(tbl)` to read the selection.

### Tables

1. `areas` = saved places anywhere on Earth (id, name – NOT unique –, display_name UNIQUE, city, country,
   country_code, latitude -90..90, longitude -180..180, source SEED/GEOCODED/MANUAL DEFAULT 'SEED', created_at)
   – rebuilt by migration 2 keeping every id; the 10 Dhaka areas are SEED places.
2. `users`(id, full_name, email UNIQUE, phone, password_hash, salt, role PATIENT/PHARMACY/ADMIN,
   area_id, is_active DEFAULT 1, created_at, latitude, longitude, location_name, must_change_password,
   preferred_currency) – the home location rounded to 2 decimals (about 1 km) + a short label "Dhanmondi, Dhaka";
   area_id = the saved place; preferred_currency NULL = the currency of the user's country (Fix 3)
3. `pharmacies`(id, owner_id UNIQUE, name, license_no UNIQUE, license_doc_path, address, area_id,
   latitude, longitude, phone, open_time, close_time,
   status PENDING/VERIFIED/REJECTED/SUSPENDED DEFAULT 'PENDING', trust_score DEFAULT 80,
   verified_by, verified_at, reject_reason, created_at, stale_penalty DEFAULT 0, currency DEFAULT 'BDT')
   – `stale_penalty` (0 or 5, set by the nightly trust check) added in Part 7; `currency` = default currency
   of new batches (Fix 3; set from the country at registration)
4. `medicines`(id, brand_name, generic_name, strength,
   form TABLET/CAPSULE/SYRUP/SUSPENSION/INJECTION/DROPS/CREAM/OINTMENT/GEL/INHALER/POWDER/SACHET/SUPPOSITORY/
   SPRAY/PATCH/OTHER, form_other NOT NULL DEFAULT '' (the free text for OTHER; '' so the UNIQUE holds), manufacturer,
   rx_required DEFAULT 0, source SEED/ADMIN/PHARMACY DEFAULT 'ADMIN', status APPROVED/PENDING/REJECTED/MERGED
   DEFAULT 'APPROVED', created_by → users SET NULL, created_at, reviewed_by → users SET NULL, reviewed_at,
   review_note, merged_into_id → medicines SET NULL), UNIQUE(brand_name, strength, form, form_other) –
   rebuilt by migration 4 keeping every id (old rows SEED + APPROVED); indexes on created_by, reviewed_by, status,
   merged_into_id are created by migration 4.
5. `inventory`(id, pharmacy_id, medicine_id, batch_no, quantity, reserved_qty DEFAULT 0, price,
   currency DEFAULT 'BDT', expiry_date, low_stock_threshold DEFAULT 10, updated_at), UNIQUE(pharmacy_id, medicine_id, batch_no),
   CHECK(quantity >= 0 AND reserved_qty >= 0 AND reserved_qty <= quantity)
6. `stock_movements`(id, inventory_id, change_qty, reason ADD/SALE/ADJUST/EXPIRED/RESERVED/RELEASED/PICKED_UP,
   user_id, note, created_at) – `note` added in Part 4 (ALTER TABLE migration)
7. `reservations`(id, code UNIQUE, patient_id, pharmacy_id, medicine_id, inventory_id, quantity,
   status PENDING/CONFIRMED/READY/COMPLETED/CANCELLED/EXPIRED, created_at, expires_at, updated_at, note,
   extended DEFAULT 0, cancelled_by PATIENT/PHARMACY/SYSTEM, cancel_reason, unit_price, currency)
   – extended / cancelled_by / cancel_reason added in Part 7; updated_at = time of the last status change;
   unit_price + currency = the batch price when reserving (Fix 3; migration 4 filled old rows from their batch)
8. `notifications`(id, user_id, type LOW_STOCK/EXPIRY/RESERVATION/STALE/VERIFICATION/REPORT/INFO,
   message, created_at, is_read DEFAULT 0, link_entity, link_id)
   – link_entity ("inventory" / "reservations" / "pharmacies" / "reports" / "medicines") + link_id added in Part 8:
   what the notification is about, so "Open" can show it
9. `search_logs`(id, user_id NULL, query, medicine_id NULL, area_id, results_count, created_at, latitude,
   longitude) – the searched place (area_id when saved) and the point rounded to 2 decimals, never exact
10. `reports`(id, reporter_id, pharmacy_id, reservation_id NULL,
    reason WRONG_STOCK/RESERVATION_NOT_HONOURED/WRONG_PRICE/FAKE_PHARMACY/OTHER, details,
    status OPEN/RESOLVED/DISMISSED, admin_note, created_at, resolved_at)
11. `audit_logs`(id, user_id, action, entity, entity_id, details, created_at)
12. `job_runs`(id, job_name, started_at, finished_at, status OK/FAILED, items_affected, message),
    index idx_job_runs_job_name – added in Part 8 (one row per background-job run).
13. `password_resets`(id, user_id NOT NULL → users ON DELETE CASCADE, code_hash, code_salt, created_at,
    expires_at, attempts DEFAULT 0, used DEFAULT 0, sent_via EMAIL/DEMO), index idx_password_resets_user_id –
    added in Fix 2 (migration 3, which also adds users.must_change_password DEFAULT 0).
14. `exchange_rates`(id, currency_code UNIQUE, rate_per_usd REAL > 0, source ONLINE/MANUAL, updated_at) – added in
    Fix 3: units of the currency per 1 USD.
    The database now has **14 tables** (`DatabaseService.EXPECTED_TABLE_COUNT`); the start-up check prints
    "Schema check: 14/14 tables use id INTEGER PRIMARY KEY AUTOINCREMENT".

### DAOs and models

- One model class per table in `medix.model` (`Area`, `User`, `Pharmacy`, `Medicine`,
  `Inventory`, `StockMovement`, `Reservation`, `Notification`, `SearchLog`, `Report`, `AuditLog`)
  plus enums `Role`, `PharmacyStatus`, `MedicineForm`, `MovementReason`, `ReservationStatus`,
  `NotificationType`, `ReportReason`, `ReportStatus`. Nullable FK columns are `Integer`.
- One DAO per table in `medix.dao`, extending `BaseDao<T>`: `insert` (returns new id), `update`,
  `delete`, `findById`, `findAll`, `count`, plus finders. PreparedStatement +
  try-with-resources only; never build SQL from user input.
- Transactions: the service opens a connection, calls `setAutoCommit(false)`,
  passes the connection to DAO overloads like `insert(Connection, T)`, `findById(Connection, id)`,
  `findList(Connection, ...)` (built on `BaseDao.executeInsert/executeUpdate/findOne/queryInt(Connection, ...)`),
  then commits or rolls back (catch `ServiceException | SQLException | RuntimeException`, rollback, rethrow).
- **Any transaction that touches stock or reservations, or that reads and then writes, MUST use
  `DBConnection.getTransactionConnection()`** (BEGIN IMMEDIATE: the write lock is taken at the
  start, others wait up to 5 s). With the normal (deferred) connection two read-then-write
  transactions deadlock and SQLite fails one immediately with SQLITE_BUSY – proven by
  `ReservationServiceTest.simultaneousReservationsNeverTakeMoreUnitsThanExist`.
  InventoryService and ReservationService use it; `getConnection()` is for plain reads/single writes.
- Seed data (first run, via the DAOs, in `SeedService`): 10 Dhaka areas as SEED places (ids 1-10:
  Dhanmondi, Mohammadpur, Farmgate, Mirpur 10, Gulshan 1, Banani, Uttara Sector 7,
  Bashundhara R/A, Motijheel, Lalbagh), the admin user (id 1) and, when the medicines table
  is empty, 30 common Bangladeshi medicines (ids 1-30, `SeedService.MEDICINES`).
- Enums shown in the UI override `toString()` with a label (`MedicineForm.TABLET` → "Tablet",
  `PharmacyStatus.PENDING` → "Pending"); the database always stores `name()`.
- Service errors the user should read are thrown as `ServiceException` (or `AuthException` for
  login/sign-up); forms show `e.getMessage()` in a JOptionPane.
- Joined views for screens are plain model classes that are not tables, e.g.
  `PharmacyDetails` (pharmacy + owner + area name).

### Passwords

`PasswordUtil`: SHA-256 (`MessageDigest`) over salt bytes + password UTF-8 bytes, with a random
16-byte salt per user (`SecureRandom`); both stored as Base64 (`java.util.Base64`) in
`users.salt` / `users.password_hash`. Check with `PasswordUtil.verify(password, salt, hash)`.
Never store or log plain passwords.

## Accounts, login and session

- `AuthService.login(email, password)` checks the password, `is_active` and (for PHARMACY)
  the pharmacy status, then calls `Session.login(user, pharmacy)`. Messages:
  wrong login "Email or password is incorrect.", disabled "This account is disabled. Contact
  the admin.", PENDING "Your pharmacy is waiting for admin verification.", REJECTED shows
  `reject_reason`, SUSPENDED "Your pharmacy is suspended. Contact the admin."
- `AuthService.registerPatient(...)`, `registerPharmacy(...)` (user + pharmacy in one
  transaction, license copied to `data/licenses/pharmacy_<id>_<file name>`, relative path in
  `license_doc_path`, all admins notified), `logout()`.
- `Session.getUser()`, `getPharmacy()` (PHARMACY users only), `getUserId()`, `getRole()`,
  `hasRole(...)`, `logout()`.
- `AuditService.log(action, entity, entityId, details)` (logged-in user) and
  `logAs(userId, ...)`. Actions so far: LOGIN, LOGIN_FAILED (user_id NULL), LOGOUT, SIGNUP (and the ones
  listed per part below, e.g. PASSWORD_RESET_REQUESTED, PASSWORD_RESET, ADMIN_PASSWORD_RESET).
- `NotificationService.notifyUser(...)`, `notifyAdmins(type, message)`.
- `ValidationUtil`: email, `isValidPhone` = Bangladeshi `01[3-9]XXXXXXXX` OR international `+<country code>
  <number>` 8-15 digits (`MSG_PHONE`; spaces, dashes, dots, brackets stripped before saving),
  `parseCoordinate(text, latitude)` (-90..90 / -180..180, max 6 decimals),
  Emails are stored lower-case.
- `FileStorageUtil`: `checkLicenseFile` (PDF/PNG/JPG/JPEG, max 5 MB), `copyLicense`,
  `resolve(relativePath)`, `open(relativePath)` (java.awt.Desktop).
- `medix.engine.GeoUtil`: `distanceKm` (haversine), `boundingBox`, `isValidLatitude/Longitude`, `round2`
  (users, search logs), `round6` (pharmacies), `nearestArea`, `formatCoordinate` (4 decimals),
  `formatCoordinateFull` (up to 6), `formatKm` / `formatDistance` ("650 m", "2.6 km", "245 km").
- Locations: see "Worldwide locations (Fix 1)" (the old Dhaka-only AreaService was removed).
- `DateUtil.parseOpeningHours("8:00 AM – 11:00 PM")` → {"08:00", "23:00"} (blank → 09:00–22:00,
  wrong format → null); `formatTime12("23:00")` → "11:00 PM"; `toDisplayDate(...)` →
  "01 Nov 2026"; `isOlderThanMonths(dateTime, n)`.

## Admin: verification and catalog (Part 3)

- `VerificationService` (admin only): Approve PENDING/REJECTED → VERIFIED (or VERIFIED again
  when re-verification is due; sets verified_by, verified_at, clears reject_reason);
  Reject PENDING → REJECTED and Suspend VERIFIED → SUSPENDED (reason ≥ 5 chars, saved in
  reject_reason); Reactivate SUSPENDED → VERIFIED. Status updates use
  `PharmacyDao.updateStatus(id, expectedStatus, ...)` so a stale screen cannot overwrite a newer
  change. Every action notifies the owner (VERIFICATION) and writes audit_logs:
  PHARMACY_APPROVED / _REVERIFIED / _REJECTED / _SUSPENDED / _REACTIVATED, LICENSE_VIEWED.
- Re-verification: VERIFIED with verified_at older than `REVERIFY_MONTHS` (12) → yellow row
  (#FFF6DA), orange "Re-verify" (`UITheme.ORANGE`), Approve shows "Approve again".
- VerificationFrame: Approve is enabled only after "View license document" opened the file
  for the selected pharmacy AND chkLicenseChecked is ticked. Status colours:
  `UITheme.pharmacyStatusColor` (Pending amber, Verified green, Rejected red, Suspended grey).
- `MedicineService` (admin only for changes): add/update reject duplicates of brand +
  strength + form, ignoring case ("This medicine is already in the catalog."); delete is
  blocked while inventory rows or reservations use the medicine. Audit: MEDICINE_ADDED /
  _UPDATED / _DELETED.
- Table helpers added: `TableUtil.setRowBackground(table, modelRow -> colorOrNull)` (call after
  all other renderers), `TableUtil.selectId(table, id)`. Live search:
  `FormUtil.onTextChange(txtSearch, this::reload)`.

## Pharmacy: inventory and stock (Part 4)

- Pharmacy login opens `InventoryFrame` (PharmacyHomeFrame was removed). Header
  "Inventory · <name>", sub-line "<area> · Trust score N · Stock last updated 25 min ago".
- `InventoryService` (logged-in PHARMACY; changes need status VERIFIED, read fresh from the DB):
  `addBatch` (medicine from the catalog, batch ≤ 40 chars, qty ≥ 1, price > 0, expiry after
  today; same medicine + batch ignoring case → "This batch already exists. Use Stock in to add
  units."), `updateBatch` (batch no., price, expiry – past allowed for corrections –, threshold;
  NOT the quantity), `stockIn`, `sell` (never more than available = quantity − reserved_qty),
  `adjust` (exact count ≥ reserved_qty, note required), `confirmAllStock` (updated_at = now on
  every batch, no quantity change – for the 24-hour stale rule in Part 8).
- Every change is one transaction: conditional UPDATE in `InventoryDao` (returns false when e.g.
  not enough is available) + `stock_movements` row (ADD / SALE / ADJUST, user id, note; detail
  edits are ADJUST with change 0 and the changes in the note) + audit_logs
  (STOCK_BATCH_ADDED / _UPDATED, STOCK_IN, STOCK_SOLD, STOCK_ADJUSTED, STOCK_CONFIRMED).
- Low stock (available <= low_stock_threshold) after a change → one LOW_STOCK notification per
  batch per day for the owner; the message starts with "Low stock (stock #<inventory id>):".
- `InventoryItem` (batch + medicine, built by a JOIN in `InventoryDao`): `isExpired`,
  `isExpiringSoon` (≤ 60 days), `isLowStock`, `isOutOfStock`, `getStockValue`.
  Row colours: expired `UITheme.ROW_EXPIRED` (light red, expiry shown with `UITheme.crossHtml()`),
  expiring `ROW_EXPIRING` (light orange), low stock `ROW_LOW_STOCK` (light yellow).
- `StockDialogFrame(inventoryId, onSaved)`: small JFrame (DISPOSE_ON_CLOSE) with a ButtonGroup
  `grpType`; it is the pattern for small pop-up windows (JFrame Form, never JDialog).
- `PharmacyProfileFrame` + `PharmacyService.updateProfile(...)`: users + pharmacies updated in
  one transaction; license number and email read-only; a new license is copied to
  data/licenses and notifies admins "<name> uploaded a new license document." (audit
  PHARMACY_PROFILE_UPDATED, LICENSE_UPLOADED). Session user/pharmacy are refreshed.
- `AutoComplete.attach(field, source, label, onSelect)` (medix.util): JPopupMenu + JList under
  a text field, max 8 rows, Up/Down/Enter/Escape; onSelect(null) when the text is edited.
  Catalog suggestions: `InventoryService.suggestMedicines` ("Brand strength form – generic").
- `FormUtil.intValue(spinner)` reads a JSpinner including typed-but-unconfirmed text.
  JSpinner models and JDateChooser dates are set in code (outside initComponents).
- `DateUtil.ago(dateTime)` → "now" / "25 min" / "2 h" / "3 d".

## Patient: medicine search (Part 5)

- Patient login opens `SearchFrame` (PatientHomeFrame was removed). Search box with
  `AutoComplete.attach(txtQuery, 2, ...)` (suggestions after 2 letters, label
  `MedicineService.suggestionLabel`: "Seclo 20 mg Capsule – Omeprazole"); picking one runs the
  search. cmbNear ("My location (…)", recent places, "Choose another location…"), cmbWithin
  (2 / 5 / 10 km / Any), cmbSort (`SearchEngine.Sort`: Distance, Price, Most stock).
  Changing area or distance searches the same medicine again; changing the sort only re-orders.
- `medix.engine.SearchEngine` (pure, no DB): `normalize` (lower case, trim, dashes → space,
  one space), `match(query, catalog)` ranks EXACT < STARTS_WITH < CONTAINS < WORDS (every word is
  a word prefix) < FUZZY (a word of 5+ letters within Levenshtein 2 of a name word);
  `getDidYouMean()` gives "Seclo" / "Paracetamol" for fuzzy best matches. `withinRadius` adds
  `distanceKm` (search point → pharmacy, `GeoUtil.distanceKm`) and filters; `sort`; `isStale`
  (updated_at 24+ hours ago, `STALE_HOURS`).
- `InventoryDao.findStockedPharmacies(medicineId)`: VERIFIED pharmacies only, batches with
  expiry_date AFTER today and units available; per pharmacy available = SUM(quantity −
  reserved_qty), price = MIN(price), last_updated = MAX(updated_at). Missing pharmacy
  coordinates fall back to the area centre. Result model: `PharmacyStock`.
- `SearchService.search(text, chosenOrNull, Place from, radiusOrNull, sort)` → `SearchResult`:
  free text picks, among the equally good matches, the medicine most pharmacies nearby have
  (others listed as "Also matches"); no rows → `alternatives` = same generic name, other brands
  in stock within the radius. EVERY search writes `search_logs` (query, medicine_id or NULL,
  area_id, results_count, user or NULL) – privacy: only the area, never exact coordinates.
- `medix.engine.AvailabilityCache` (stands in for Redis): synchronized LinkedHashMap in access
  order, key `medicineId:lat,lon (2 decimals):radius`, 60-second expiry, max 200 entries (LRU).
  `invalidateMedicine(id)` after ANY stock or reservation change of that medicine (InventoryService
  does it; Part 6 reservations MUST too); `clear()` when a pharmacy's visibility/location changes
  (VerificationService, PharmacyService). The status label shows "(cached)".
- `medix.engine.OpeningHours.isOpenNow(open, close)` handles hours past midnight; equal times =
  24 hours. Distances: `GeoUtil.formatKm` ("650 m", "2.6 km", "245 km").
- Stale rows: light yellow (`UITheme.ROW_LOW_STOCK`), "2 d ⚠", footer "Stock not updated for over
  24 hours. Call the pharmacy first."
- `MapUtil.mapUrl/openMap` (OpenStreetMap, Desktop.browse) and `isOnline()` (3-second TCP check,
  run it in a SwingWorker); `MapUtil.openMapOrExplain(parent, button, title, lat, lon, where)` does
  the check + browser + friendly offline message in one call.
- Symbols missing from Segoe UI (⚠, ✕): use `UITheme.warnHtml()` / `crossHtml()` inside `<html>`
  text – they switch to the "Segoe UI Symbol" font. Java never borrows glyphs by itself.
- Wrapping HTML text: ALWAYS `UITheme.wrapHtml(html, pixels)` (pixels = the layout width available),
  never a hand-written `style='width:Npx'` – Swing draws CSS px wider than layout px (×1.3 measured on
  a 125% Windows display; the ratio is measured at runtime). Leave room for a label's border padding.

## Patient: reservations (Part 6)

- SearchFrame "View & reserve" opens `PharmacyDetailFrame(pharmacyId, medicineId, fromLat, fromLon, onReserved)`
  (720x520, DISPOSE_ON_CLOSE): pharmacy card (Verified badge, address, distance from the searched
  area, hours + Open now/Closed via `DateUtil.formatOpeningHours` – equal times = "Open 24 hours",
  large selectable phone, trust score), Copy phone (clipboard), Open map, tblBatches of the
  non-expired batches (FEFO order), and the reserve card: spnQuantity 1..min(total available, 10),
  cmbHold 30/45/60 min (default 45), note, total = qty × price of the batch that would be used,
  Rx box (warning note) when rx_required, disclaimer (info note) + chkUnderstand. btnReserve
  "Reserve for N minutes" is enabled only when ticked AND open now AND in stock.
- `ReservationService.reserve(patientId, pharmacyId, medicineId, units, minutes, note)` – ONE
  IMMEDIATE transaction: pharmacy VERIFIED and open now, max `MAX_ACTIVE_PER_PATIENT` (3) active
  (PENDING/CONFIRMED/READY = `ReservationStatus.isActive()`), no second active reservation of the
  same medicine at the same pharmacy, re-read the batches, `pickBatch` = the earliest-expiring
  batch that ALONE has enough (FEFO, never split; else "Only N available now."), conditional
  `InventoryDao.reserve` (reserved_qty += units), insert with a UUID placeholder code, then
  code = `MX-MMDD-<id padded to 4>` (e.g. MX-1101-0042), stock_movements RESERVED. After commit:
  `AvailabilityCache.invalidateMedicine`, owner notification "New reservation MX-…: Seclo 20 mg × 4,
  hold until 17:27.", audit RESERVATION_CREATED. Returns a `ReservationView`.
- `ReservationService.cancel(reservationId, patientId)` – IMMEDIATE transaction: status → CANCELLED
  (`ReservationDao.changeActiveStatus` only from an active status), `InventoryDao.release`
  (reserved_qty −= qty), stock_movements RELEASED; then cache invalidation, owner notification,
  audit RESERVATION_CANCELLED.
- stock_movements for holds: RESERVED change_qty = +units held, RELEASED = −units released
  (the change of reserved_qty; quantity is not changed until pickup in Part 7). A reservation
  does NOT change inventory.updated_at (it is not a stock check).
- Part 8 (expiry job) must move PENDING/CONFIRMED/READY reservations past expires_at to EXPIRED
  with `ReservationDao.changeStatus`, set `setCancelInfo(con, id, "SYSTEM", ...)` only if useful,
  release units (`InventoryDao.release` + RELEASED movement) in an IMMEDIATE transaction,
  invalidate the cache, notify, audit and recalculate trust – like `PharmacyReservationService.cancel`.
- `ReservationConfirmFrame(view)`: code in a large dashed box, pharmacy + phone, hold-until, amount;
  "Go to My Reservations" uses `FormUtil.switchTo(next)` (shows next, disposes every other frame).
- `MyReservationsFrame` (sidebar "My Reservations"): ID | Code | Medicine | Qty | Pharmacy | Status |
  Hold until | Time left; a `javax.swing.Timer` re-reads the list every 30 s (stopped in an overridden
  `dispose()`); Time left red/bold under 10 minutes; status colours `UITheme.reservationStatusColor`
  (Pending amber, Confirmed teal, Ready green, Completed grey, Cancelled/Expired red).
  Cancel (active only, YES/NO) and Report a problem.
- `ReportFrame(reservationId)` + `ReportService.reportReservation(...)`: reason (`ReportReason`, UI
  labels) + details (≥ 10 chars), one OPEN report per patient per reservation, saved as OPEN,
  admins notified (type REPORT), audit REPORT_CREATED. Admins handle reports in `ReportsFrame` (Part 9).
- Enum labels: `ReservationStatus` and `ReportReason` now override `toString()` too (DB keeps name()).

## Pharmacy: reservations and trust score (Part 7)

- `PharmacyReservationsFrame` (sidebar "Reservations"): header "N active · M to confirm";
  txtCode + btnFind (`findByCode` accepts "MX-1101-0042", "mx-1101-0042" or "1101-0042"; only this
  pharmacy's; switches the filter to All when needed and selects the row); cmbFilter
  (`PharmacyReservationService.Filter`: Active, Completed, Cancelled, Expired, All);
  tblReservations ID | Code | Patient | Phone | Medicine | Qty | Amount | Status | Hold until | Left
  (phone masked with `ValidationUtil.maskPhone` "01819-…552" until CONFIRMED/READY/COMPLETED;
  Left red under 10 min; javax.swing.Timer reload every 30 s, stopped in `dispose()`).
  Buttons are enabled by `PharmacyReservationService.canConfirm/canMarkReady/canPickUp/canExtend/canCancel`.
  Bottom: today's completed / expired (no-show) / cancelled-by-pharmacy and trust ▲/▼ vs last week.
- `PharmacyReservationService` (logged-in PHARMACY, own reservations only): `confirm` PENDING→CONFIRMED,
  `markReady` CONFIRMED→READY, `pickUp` CONFIRMED/READY→COMPLETED (same transaction:
  `InventoryDao.pickUp` quantity −= qty AND reserved_qty −= qty, updated_at = now, PICKED_UP
  movement change_qty = −qty; asks "Patient showed code …?" in the form), `extend` (+15 min once,
  only while the hold runs; `extendOnce`), `cancel(id, CancelReason, details)` (OUT_OF_STOCK /
  PATIENT_ASKED / OTHER – details required for OTHER; cancelled_by PHARMACY, cancel_reason =
  name[: details]; release + RELEASED). Each: IMMEDIATE transaction, status moved only from the
  expected step(s) via `ReservationDao.changeStatus(con, id, from, to)` (sets updated_at), then
  patient notification (RESERVATION), audit (RESERVATION_CONFIRMED / _READY / _PICKED_UP /
  _EXTENDED / _CANCELLED_BY_PHARMACY), `AvailabilityCache.invalidateMedicine`, trust recalculation.
  Patient cancels (Part 6) now save cancelled_by PATIENT.
- `TrustScoreService`: 80 + min(15, COMPLETED in the last 30 days) − 5 × pharmacy "Out of stock"
  cancels in 30 days − 10 × upheld reports (status RESOLVED, resolved_at in 30 days; Part 9 sets
  them) − stale_penalty, clamped 0..100, saved in pharmacies.trust_score (audit TRUST_SCORE_CHANGED with
  the breakdown). `calculate(id, asOf, stalePenalty)` gives the score at any moment;
  `scoreLastWeek` = asOf 7 days ago (for ▲/▼). `recalculateQuietly` after every reservation change.
  `runNightlyCheck()`: for VERIFIED pharmacies stale_penalty = 5 if the newest inventory.updated_at is
  24+ h old (no stock rows = not stale), STALE notification when it starts, recalculate all, audit
  TRUST_NIGHTLY_CHECK. It runs as `TrustScoreJob` (see Part 8).
- Search tie-breaker: `SearchEngine.sort(DISTANCE)` compares the SHOWN distance (0.1 km), then trust
  score (highest first), then price.

## Background jobs, alerts and notifications (Part 8)

They replace Spring @Scheduled / Quartz from the report.

- `medix.engine.JobScheduler`: ONE ScheduledExecutorService with 2 daemon threads ("medix-jobs-N").
  `Main` calls `Jobs.registerAll()` after the database is ready and a shutdown hook calls
  `JobScheduler.stop()` (and removes the tray icon). A job implements `JobScheduler.Job`
  (`getName()`, `int run()` = items handled). Every run goes through `JobScheduler.runNow(job)`:
  try/catch, up to 3 retries 30 s apart (`setRetryDelayMillis` for tests), then ONE `job_runs` row
  (OK "OK" / "OK after N attempts", or FAILED + message) and, on failure, admins get INFO
  "Job <name> failed: <message>" (`JobRunService`). The same job never runs twice at once.
  `every(job, delay, period, unit)`, `dailyAt(job, LocalTime, catchUp)` (catch-up: runs 1 min after
  start-up when the time passed today and there is no OK run today – the desktop app is often closed),
  `repeat(task, ...)` for unrecorded UI polls. Jobs never touch Swing or Session; UI updates go
  through `SwingUtilities.invokeLater`.
- Timetable (`medix.engine.Jobs`), each job a thin class calling a service:
  | Job | When | Service |
  |---|---|---|
  | `ReservationExpiryJob` "Reservation expiry" | every 1 min | `ReservationService.expireOverdue()` – PENDING/CONFIRMED/READY past expires_at → EXPIRED in an IMMEDIATE transaction, release + RELEASED (user NULL), notify patient and pharmacy, audit RESERVATION_EXPIRED, cache, trust |
  | `LowStockJob` "Low stock" | every 15 min | `AlertService.runLowStockCheck()` |
  | `StaleStockJob` "Stale stock" | every hour | `AlertService.runStaleCheck()` |
  | `ExpirySweepJob` "Expiry sweep" | daily 08:00 + catch-up | `AlertService.runExpirySweep()` |
  | `TrustScoreJob` "Trust score (nightly)" | daily 02:00 + catch-up | `TrustScoreService.runNightlyCheck()` |
  | `SearchLogCleanupJob` "Search log clean-up" | daily 03:00 + catch-up | `SearchService.deleteOldSearchLogs()` (90 days, privacy) |
- `AlertService`: alerts for VERIFIED pharmacies, at most ONCE PER BATCH (or pharmacy) PER DAY
  (`NotificationDao.existsForLinkSince` on link_entity/link_id). Texts:
  "Seclo 20 mg (batch SC-2510) has 2 left. Alert level is 10." (LOW_STOCK, non-expired batches);
  "… expires in 45 days: 30 units." / "… has expired. Remove 40 units from the shelf." (EXPIRY, within
  60 days, units left); "Your stock was last updated 2 d ago. …" (STALE, 24+ h). InventoryService
  uses the same `notifyIfLowStock` rule right after a change.
- `NotificationService.notifyUser(userId, type, message, linkEntity, linkId)` – ALWAYS pass the link
  (`LINK_INVENTORY`, `LINK_RESERVATION`, `LINK_PHARMACY`, `LINK_REPORT`, `LINK_MEDICINE`) when the message is about a record.
- `NotificationFrame` (patients: sidebar "Notifications"; admins: new item "Notifications") and
  `AlertsFrame` (pharmacies: "Alerts", plus tblJobs = latest run per job from job_runs):
  tblNotifications ID | Type | Message | Time | Status (unread rows bold via `TableUtil.setRowBold`,
  type colours `UITheme.notificationTypeColor`: Reservation teal, Low stock amber, Expiry orange /
  red when expired, Stale grey, Verification green, Report purple, Info grey), cmbFilterType,
  Open (also double-click; marks read; `NotificationNavigator`: inventory → `InventoryFrame(id)`,
  reservations → `PharmacyReservationsFrame(id)` / `MyReservationsFrame(id)`, pharmacies (admin) →
  `VerificationFrame(id)`), Mark read, Mark all read; 30-s Timer refresh. Shared table code:
  `NotificationTableUtil`.
- `NotificationWatcher` (started in `LoginFrame.openHome`, stopped on logout): every 30 s on a job
  thread reads notifications newer than the last seen id, then via invokeLater shows `ToastFrame`s
  (max 3, then "+N more"), a `SystemTray` `TrayIcon.displayMessage` when supported, and updates the
  sidebar badge. At login one toast says how many are unread.
- `ToastFrame.pop(title, message, colour)`: undecorated 300x80 JFrame Form, bottom-right of the work
  area, stacks upwards, never takes focus, closes after 4 s or on click; text cut to 64 chars (full text
  in the tooltip).
- Sidebar badge: `SidebarUtil` shows "Alerts (4)" / "Notifications (4)"; `SidebarUtil.updateBadge(n)` /
  `refreshBadge()` after marking read.
- Frames that can open with a record selected have an extra constructor: `InventoryFrame(int)`,
  `PharmacyReservationsFrame(int)`, `MyReservationsFrame(int)`, `VerificationFrame(int)`.
- `Session` fields are volatile (job threads read them). `NotificationType` has UI labels.

## Admin dashboard, reports, users, audit log; patient profile (Part 9)

- `AdminDashboardFrame` (sidebar "Dashboard"): cmbRange (Last 7 / 30 days), btnExportPng (both charts as
  900x600 PNGs into a folder chosen with JFileChooser, `ChartUtil.savePng`, audit DASHBOARD_EXPORTED).
  Four cards (`UITheme.styleSummaryCard(panel, colour)`: white, 3 px coloured top border): Verified
  pharmacies + areas, Waiting for verification + "Review now →" (VerificationFrame), Searches this week
  ▲/▼ % vs the period before, Reservations completed "71%" + "5 of 7 finished reservations".
  Charts: `ChartUtil.horizontalBar` (most searched, top 8) and `ChartUtil.dailyLine` (searches per day,
  line + light area fill) in `ChartPanel`s that `chartPanel(holder)` adds in code to the empty
  pnlChartTop / pnlChartDaily of the .form (BorderLayout set in code). tblShortages ID | Medicine | Area |
  Failed (ID = medicine id; ≥ `AdminService.SHORTAGE_ALERT` (20) light red); tblOpenReports ID | Pharmacy |
  Reason | Status (latest 5 OPEN; double-click → `ReportsFrame(id)`).
- `AdminService` (admin only) gives every number: a period of N days = today and the N − 1 days before,
  from 00:00 (`periodStart(days, periodsBack)`, `periodEnd()` = 00:00 tomorrow). Completed % = COMPLETED
  of the reservations CREATED in the period that are finished (COMPLETED / CANCELLED / EXPIRED).
  Shortages = search_logs with results_count = 0 and a medicine, grouped by medicine + area.
- `ReportsFrame` (sidebar "Reports (n)" = OPEN reports, `SidebarUtil.updateReportsCount(n)`;
  `ReportsFrame(int)` selects one): cmbFilterStatus "Open (n)" / Upheld / Dismissed / All;
  tblReports ID | Date | Reporter | Pharmacy | Reason | Reservation | Status; detail card with the
  patient's text, reservation (code + "Seclo 20 mg Capsule × 2 · Expired · date"), the pharmacy's trust
  score and earlier upheld reports; txtAdminNote (≥ 5 chars, the patient sees it).
  `ReportService.uphold` (OPEN → RESOLVED via `ReportDao.close(id, status, note, now)` which only changes
  OPEN rows, `TrustScoreService.recalculate` → −10 for 30 days, reporter + owner notified type REPORT with
  `LINK_REPORT`, audit REPORT_UPHELD "…, trust 80 → 70: note"); `dismiss` (DISMISSED, reporter notified,
  audit REPORT_DISMISSED). btnSuspendPharmacy → `VerificationFrame(pharmacyId, reason)` with the reason
  filled in (the Part 3 suspend flow; enabled while the pharmacy is VERIFIED).
  Admin notifications about reports open `ReportsFrame(id)` (`NotificationNavigator`).
- `UsersFrame`: tblUsers ID | Name | Email | Phone | Role | Area | Active | Joined, cmbRole + txtSearch,
  disabled rows grey. `UserService.setActive` (admin only; not yourself, not the last active admin;
  audit USER_DISABLED / USER_ENABLED, INFO notification on enable). btnAddAdmin opens `AddAdminFrame`
  (small pop-up JFrame Form, DISPOSE_ON_CLOSE, `onSaved` callback) → `AuthService.registerAdmin` (admin
  only, audit ADMIN_CREATED).
- `AuditLogFrame`: tblAudit ID | Time | User | Action | Entity | Entity ID | Details, newest first, max
  `AuditService.MAX_ROWS` (2000) with "Showing the newest 2000 of N"; filters cmbUser (All / "System (no
  user)" = `AuditService.SYSTEM_USER` 0 / users found in the log), cmbAction (actions found in the log),
  dtcFrom / dtcTo (whole days, "date" property listeners added in code). Double-click shows the whole
  entry. btnExportCsv → `AuditService.exportCsv` (UTF-8 with BOM, RFC 4180 quoting, audit AUDIT_LOG_EXPORTED).
- `ProfileFrame` (patients, sidebar "Profile"): name, email (read-only), phone, area
  (`UserService.updateMyProfile`, audit PROFILE_UPDATED, Session refreshed); change password
  (`changeMyPassword(current, new, confirm)`, new salt, audit PASSWORD_CHANGED); privacy "Delete my search
  history" (`SearchService.deleteMySearchHistory` deletes the user's search_logs rows, audit
  SEARCH_HISTORY_DELETED).
- Joined views: `ReportView`, `UserDetails`, `AuditLogView` (user name or "System"); `DashboardStats`,
  `NamedCount` (label + count), `ShortageRow`. `Role` and `ReportStatus` now have UI labels
  (RESOLVED shows "Upheld"); the database still stores name().

## Demo data, review rules, packaging (Part 10)

- `DemoDataSeeder.load(progress)` (medix.util; LoginFrame link "Load demo data" → YES/NO → SwingWorker): does
  nothing when `patient@medix.com` or `pharmacy1@medix.com` exists (`isLoaded()`). Everything goes through the
  services (acting as admin / owner / patient via `Session`, logged out at the end) or DAOs: 6 patients
  (`patient@medix.com`, `patient2..6@medix.com`), 12 pharmacies `pharmacy1..12@medix.com` (password `demo123`;
  9 VERIFIED, 2 PENDING, Rx Corner SUSPENDED, City Drug Store verified 14 months ago, Al-Madina stale 50 h,
  coordinates ±0.004°, 4 open 24 hours so reservations work at any time), a Graphics2D PNG license each
  (registerPharmacy copies it to data/licenses), 8–20 batches per verified pharmacy (`CATALOG` prices,
  low / expiring / expired batches, sales, stock-ins; Lifeline in Uttara never stocks Insulet R), 14 days of
  search_logs (one transaction, results_count = verified pharmacies with stock within 5 km of the area),
  reservations in all six statuses, 3 reports (open / upheld / dismissed), job_runs history, the real alert
  checks and `TrustScoreService.runNightlyCheck()` at the end, audit DEMO_DATA_LOADED. Seeded with
  `new Random(2026)` (same data every time). Demo-only DAO helpers: `InventoryDao.setUpdatedAt`,
  `ReservationDao.setExpiresAt`, `SearchLogDao.insert(Connection, …)`.
- Start-up schema check: `DatabaseService.checkSchema()` (sqlite_master + PRAGMA table_info / foreign_key_list /
  index_list via `SchemaDao`, `TableInfo.isIdRuleOk()`) – Main prints
  "Schema check: 14/14 tables use id INTEGER PRIMARY KEY AUTOINCREMENT" and "…: 24/24 foreign key columns have an
  index", plus a line per problem. `isSchemaOk()` for tests. Keep it N/N (all tables) when adding tables.
- Errors: `ErrorLog.log(context, throwable)` appends to data/error.log (restarted above 2 MB); screens show
  `ErrorLog.describe(e)` (friendly text, SQLITE_BUSY → "busy, try again") for SQL/IO exceptions – never
  `e.getMessage()` of a SQLException. `ServiceException` / `AuthException` messages are shown as they are.
  Main installs a default uncaught-exception handler (log + short JOptionPane). Never use System.err /
  printStackTrace.
- Role guards: `SidebarUtil.attach` denies a frame whose sidebar item is not in the logged-in role's menu;
  frames without a sidebar call `SidebarUtil.guard(this, Role.X)` right after `initComponents()`
  (PharmacyDetailFrame, ReservationConfirmFrame, ReportFrame: PATIENT; StockDialogFrame: PHARMACY;
  AddAdminFrame: ADMIN). Denied: the frame closes after opening, a message, audit ACCESS_DENIED, then
  `SidebarUtil.homeFor(role)` (also used by LoginFrame). Services still check roles / own pharmacy.
- Compiler: `javac.compilerargs=-Xlint:all -Xlint:-serial` (Swing classes are Serializable); keep the build
  free of warnings.
- Packaging: Clean and Build → dist/MediX.jar + dist/lib (Class-Path, Main-Class medix.Main);
  `ant package-fat` (build.xml) → dist/MediX-all.jar (libraries merged, signatures / META-INF/versions removed,
  JDBC service file kept) + copies run.bat; README has the Launch4j steps (min JRE 1.8.0, Change dir ".").
  `AppPaths` treats MediX.exe like a jar (data/ next to it).
- Tests (16 classes, `ant test`): also PasswordUtilTest, InventoryServiceTest, TrustScoreServiceTest,
  DemoDataSeederTest (loads the demo data + asserts the 14/14 schema check). Fix 3 added CurrencyUtilTest,
  ExchangeRateServiceTest (saved sample answer test/medix/engine/er_api_latest_usd.json, no network),
  MedicineServiceTest (duplicates, merge, reject) and currency tests in SearchEngineTest / SearchServiceTest:
  22 classes; LocationServiceTest (recent places file) makes 23.

## Worldwide locations (Fix 1)

- Any place on Earth. `model.Place` (displayName, latitude, longitude, city, country, countryCode, savedId,
  source; `getName()` "Dhanmondi", `getShortName()` "Dhanmondi, Dhaka" / "London, United Kingdom") is a
  geocoding result or a point; chosen places are saved as `areas` rows (`model.Area`, `AreaDao`:
  `findByDisplayName`, `findNear(lat, lon, tolerance)`, `search(text, limit)`).
- `engine.GeocodingService` (OpenStreetMap Nominatim, no key): `search(text)` → up to 5 places,
  `reverse(lat, lon)` → one place or null (open sea); `parseSearch/parseReverse` (org.json) are tested with
  saved answers in test/medix/engine/*.json. HttpURLConnection, 8-s timeouts, User-Agent
  "MediX/1.0 (university project; <contact.email>)" – the address is left out while it is the example.com
  placeholder (Nominatim answers 403 to it); max 1 request/second (static throttle), session LRU caches.
  NEVER call it per key press – only on a button / Enter, always in a SwingWorker.
- `util.AppConfig`: data/config.properties (created with `contact.email=your.email@example.com`).
- `util.TrustStoreUtil.install()` (Main, first thing): adds the bundled Let's Encrypt root ISRG Root X1
  (resources/certs) to Java's trusted roots – JDK 8u111 lacks it, so Nominatim failed with "PKIX path building
  failed". Nothing is switched off; tiles (GlobalSign) already worked.
- `service.LocationService`: `search(text)` → `PlaceSearch` (online results, marked with the saved id when
  known; offline = saved places LIKE + `MSG_OFFLINE` "Can't reach the map service…"), `reverse`, `point(lat,
  lon)` (MANUAL), `save(place)` (no duplicates: same display name or within `SAME_PLACE_DEGREES` 0.0005),
  `getSaved(id)`, recent places `getRecent()` / `remember(place)` (data/recent-places.properties, per user or
  "guest", max 5 – NOT java.util.prefs: on JDK 8 / Windows it prints "Could not open/create prefs root node … error
  code 5" for every non-admin user; never use java.util.prefs in MediX).
- `LocationPickerFrame(owner, PlacePickedListener, search, found, current)` (JFrame Form 780x580,
  DISPOSE_ON_CLOSE, the owner is disabled until it closes): txtPlaceSearch + btnFind (Enter too),
  tblPlaceResults ID ("—" when not saved) | Place | Country | Latitude | Longitude, pnlMap with a JXMapViewer2
  map added in code (OSM tiles via `DefaultTileFactory.setUserAgent`, 2 threads, disk cache data/tile-cache,
  pan, wheel zoom, marker, "© OpenStreetMap contributors"; a HEAD probe hides it → "Map not available offline"),
  click = point + reverse lookup, txtLatitude/txtLongitude + btnUseCoordinates, lstRecent, lblChosen,
  btnUseThisLocation saves + remembers the place and calls the listener.
- Patients: RegisterFrame "Your location *" (txtLocation read-only + btnChooseLocation) and ProfileFrame
  "My location" (Change…) → `AuthService.registerPatient(…, Place)` / `UserService.updateMyProfile(name,
  phone, Place)`: area_id = saved place, latitude/longitude rounded to 2 decimals, location_name = short name.
- Pharmacies: `util.PharmacyLocationFields` (RegisterFrame Pharmacy tab + PharmacyProfileFrame): Find from
  address (1 result fills, several open the picker, none: "Address not found. Try a shorter address (area, city,
  country) or click Pick on map."), btnPickOnMap, typed coordinates reverse-geocoded on focus lost into
  lblFoundLocation; coordinates keep 6 decimals; area_id = the found place, else the point as a MANUAL place.
- Search: `InventoryDao.findStockedPharmacies(medicineId, GeoUtil.BoundingBox)` filters lat ± r/111 and
  lon ± r/(111·cos lat) in SQL (no longitude filter near the poles or across the 180° line), then
  `SearchEngine.withinRadius(rows, lat, lon, radius)` checks the haversine distance. cmbWithin 1, 2, 5 (default),
  10, 25, 50, 100 km, Any. Empty text: "No verified pharmacy within 5 km of Chattogram has Seclo 20 mg in stock.
  Try a bigger distance." `ReservationService.getOffer(pharmacyId, medicineId, fromLat, fromLon)`.
- Admin: VerificationFrame shows the address + "Found: <place>", full coordinates and btnOpenMap; dashboard
  shortages are grouped by city + country ("City" column); UsersFrame shows location_name ("Location").
- Phones: `ValidationUtil.isValidPhone` everywhere (the Bangladesh-only checks are gone).

## Password reset (Fix 2)

- Library: `javax.mail-1.6.2.jar` (com.sun.mail, Java 7 classes; javax.activation comes with JDK 8).
- `service.EmailService`: SMTP via JavaMail with data/mail.properties (mail.enabled, smtp.host, smtp.port,
  smtp.starttls, smtp.username, smtp.password = a Gmail APP password, from.name), read on every send;
  data/mail.properties.example documents it; data/mail.properties is in .gitignore. `isEnabled()` false
  (file missing / mail.enabled=false) = DEMO MODE. `TrustStoreUtil` also calls `SSLContext.setDefault`, so
  STARTTLS uses the same trusted roots (checked: TLSv1.2 handshake with smtp.gmail.com on JDK 8u111).
- `service.PasswordResetService` (Clock + EmailService injectable for tests): `requestCode(email)` →
  `Request` (message, demoMode, demoCode). Always `MSG_SENT` "If an account exists for this email, a 6-digit
  code has been sent. It expires in 15 minutes." – unknown and disabled accounts get no code. Limits 1 per
  60 s and 3 per hour per address, kept in memory for EVERY address (so unknown ones look the same) and
  checked again in the database; a new code marks older unused codes used. Code = 6 digits from
  SecureRandom; only `PasswordUtil.hash(code, salt)` is stored; expires 15 min after created_at.
  E-mail subject "MediX password reset code"; SMTP failure → the code is cancelled, data/error.log,
  `MSG_SEND_FAILED`. `resetPassword(email, code, new, confirm)`: latest unused code; expired →
  `MSG_EXPIRED`; wrong → attempts + 1 ("The code is not correct. N tries left."), at 5 the code is cancelled
  (`MSG_TOO_MANY_WRONG`); the new password follows the sign-up rules and must differ from the old one; ONE
  IMMEDIATE transaction: new salt + hash, must_change_password 0, codes used, audit PASSWORD_RESET and the
  INFO notification "Your password was changed on <date time>. If this wasn't you, contact the admin."
  (`AuditLogDao.insert(Connection, …)` / `NotificationDao.insert(Connection, …)`).
- Forms (JFrame Forms, DISPOSE_ON_CLOSE, closing them returns to LoginFrame): LoginFrame btnForgotPassword
  (link, right of "Show password") → `ForgotPasswordFrame(email)` (txtEmail, btnSendCode, btnBackToLogin,
  lblDemoMode "Demo mode: codes appear on screen"; demo code shown in a JOptionPane titled
  "Demo mode – email is not set up") → `ResetPasswordFrame(email)` (txtEmail read-only, txtCode,
  txtNewPassword, txtConfirmPassword, chkShowPassword, btnResendCode "Resend code (45 s)" with a
  javax.swing.Timer stopped in dispose(), btnBack, btnResetPassword) → "Password changed. You can log in
  now." → `LoginFrame(email)`. Titles use the en dash: "MediX – Reset Password", "MediX – New Password".
- Admin reset: UsersFrame btnResetPassword → `UserService.adminResetPassword(userId)` (admin only, not
  yourself): 8 random characters (no 0/O/1/l), must_change_password = 1, open codes cancelled, audit
  ADMIN_PASSWORD_RESET; shown once in a JOptionPane with "Copy" (clipboard). Login (`LoginFrame.openHome`)
  with must_change_password = 1 opens `ChangePasswordFrame` (cannot be skipped: Log out / closing logs out)
  → `UserService.completeForcedChange(new, confirm)` (must differ from the temporary password) → the role's
  home + NotificationWatcher. Pending / rejected / suspended pharmacy messages still come first (AuthService).
- Tests: `PasswordResetServiceTest` (demo mode, moving test clock).

## Any medicine, any currency (Fix 3)

- Medicines: `MedicineSource` (SEED/ADMIN/PHARMACY), `MedicineStatus` (APPROVED / PENDING "To review" / REJECTED /
  MERGED; `isSellable()` = APPROVED or PENDING), `Medicine.getFormLabel()` (the free text for OTHER),
  `MedicineView` (+ "Added by"). `MedicineService.label` = "Napa 500 mg Tablet".
- Pharmacy side: InventoryFrame's medicine autocomplete = APPROVED + the pharmacy's OWN pending medicines
  (`MedicineDao.suggest(text, ownerId, 8)`) and ALWAYS a last row `+ Add "<text>" as a new medicine…`
  (`AutoComplete.setActionRow`) → `AddMedicineFrame(owner, typed, onSaved)` (460x520, DISPOSE_ON_CLOSE, owner disabled
  until it closes; "napa 500mg" prefills brand "napa" + strength "500mg", `MedicineService.splitTypedText`).
  Before saving `MedicineService.findSimilar` (SearchEngine.normalize + Levenshtein: same form, same strength
  ignoring spaces/case, brand within the spelling tolerance or the same generic) → JOptionPane "These look similar.
  Use one of them instead?" with "Use selected" / "No, add as new". `addByPharmacy` never creates an exact duplicate
  (same brand + strength + form, ignoring case/extra spaces: the existing one – a merged one's target – is used);
  new ones are source PHARMACY, status PENDING, created_by = owner, admins notified INFO "<pharmacy> added a new
  medicine: <label>. Please review it." (LINK_MEDICINE), audit MEDICINE_ADDED. "Edit medicine" (InventoryFrame) =
  `AddMedicineFrame(owner, pending, onSaved)` → `updateMyPending` (only the pharmacy that added it, only while
  PENDING: `MedicineService.canPharmacyEdit`). A batch of a REJECTED medicine shows "Choose correct medicine" →
  `MergeMedicineFrame(owner, m, true, onDone)` → `moveMyStock` (that pharmacy's batches + reservations).
- Patients: PENDING medicines are searchable and sellable at once; SearchFrame / PharmacyDetailFrame show
  `UITheme.newMedicineHtml()` "New – not yet reviewed". Search catalog = `MedicineDao.findSearchable()` (APPROVED,
  PENDING, MERGED); a MERGED match is replaced by its `merged_into_id` target (`SearchService.resolveMerged`);
  REJECTED never shows. `InventoryDao.findStockedPharmacies` only returns stock of APPROVED/PENDING medicines;
  `ReservationService.getOffer/reserve` refuse other medicines (running reservations stay valid).
- Admin review (MedicineCatalogFrame; `MedicineCatalogFrame(MedicineStatus)` filtered, `MedicineCatalogFrame(int)`
  selects one): cmbStatus All / "To review (n)" / Approved / Rejected / Merged, cmbSource All / Seed / Admin / Pharmacy,
  tblMedicines ID | Brand | Generic | Strength | Form | Manufacturer | Rx | Added by | Status (PENDING rows
  `ROW_LOW_STOCK` yellow, status coloured `UITheme.medicineStatusColor`). `approve`, `approveWithChanges` (the form's
  values, one transaction), `reject(id, reason ≥ 5)`, `merge(duplicateId, correctId)` via `MergeMedicineFrame`
  (640x480, searchable table of APPROVED medicines, similar ones first): ONE IMMEDIATE transaction moves inventory
  (same pharmacy + batch_no ignoring case → `InventoryDao.addUnitsFrom` adds quantity AND reserved_qty, reservations
  and stock_movements re-pointed, ADJUST movement, extra row deleted), reservations and search_logs, re-points older
  merges, status MERGED + merged_into_id. Review updates use `MedicineDao.review(..)` (only from PENDING). Each
  notifies the creator (INFO, LINK_MEDICINE), audits MEDICINE_APPROVED / _REJECTED / _MERGED, clears the cache.
  AdminDashboardFrame card "Medicines to review (n)" → `MedicineCatalogFrame(MedicineStatus.PENDING)`.
- Currencies: `util.CurrencyUtil` – `allCurrencies(first…)` labels "BDT – Bangladeshi Taka" (given ones, then BDT, USD,
  EUR, GBP, INR, then all by code; `codeOf(label)`), `currencyForCountry` ("BD" → BDT, unknown → USD),
  `fractionDigits` (JPY 0, BHD 3), `round`, `hasAllowedDecimals`, `format` ("Tk 7.00", "£3.20", "¥450", "MYR 12.50";
  symbols Tk $ € £ ₹ ¥ Rs), `formatApprox` (whole units from 10: "≈ Tk 480"), `formatTotals` per currency
  ("Tk 3,978.00 · $120.00"), `times`, `parseAmount`, `fromDb`.
- Pharmacy currency: registration sets it from the place's country (RegisterFrame unchanged); PharmacyProfileFrame
  cmbDefaultCurrency (`PharmacyService.updateProfile(…, currency)`); InventoryFrame "Price / unit *" txtPrice +
  cmbCurrency per batch (closed combo shows the code), price > 0 with at most the currency's decimals
  (`InventoryService.addBatch/updateBatch(…, price, currency, …)`), Price column formatted, footer stock value per currency.
- Patient currency: `UserService.displayCurrency(user)` = preferred_currency, else the country's (`countryCurrency`);
  ProfileFrame cmbPreferredCurrency (`updateMyProfile(name, phone, place, currency)`; the country's own currency is
  stored as NULL).
- Rates: `engine.ExchangeRateService` (implements `SearchEngine.Converter`): GET https://open.er-api.com/v6/latest/USD
  (no key; `Fetcher` injectable for tests), `parse` (org.json, BigDecimal), at most once per day (`needsUpdate`: online
  rates 24+ h old); HTTP 429 / offline keeps the saved rates; every rate saved (ONLINE), MANUAL rows kept unless
  replaceManual (`save(parsed, true)`); `saveManualIfMissing` (demo data) / `setManualRate` (admin only, audit
  EXCHANGE_RATE_SET; USD always 1; no screen uses it); `convert(amount, from, to)` = amount / rate[from] × rate[to], null when a rate is
  missing (never guessed); 60-s in-memory cache cleared on every save (and AvailabilityCache.clear()).
  `ExchangeRateJob` "Exchange rates": `JobScheduler.every(job, 90 s, 24 h)` – at start-up it only downloads when the
  rates are old. Credit "Rates by Exchange Rate API" (`CREDIT_TEXT`, link `CREDIT_URL` via `MapUtil.browse` /
  `MapUtil.linkLabel`) + the rates date wherever a converted price is shown. There is NO admin screen for exchange
  rates (the ExchangeRatesFrame was removed on request): rates come only from the job and the demo data.
- Search: `findStockedPharmacies` groups by pharmacy AND currency (MIN(price) per currency, never across currencies)
  and merges per pharmacy (`PharmacyStock.getLowestByCurrency`); `SearchEngine.applyPrices(rows, patientCurrency,
  converter)` converts each and picks the cheapest, sets `comparablePrice` (null = no rate); Sort PRICE compares it,
  rows without a rate last, footer "Some prices could not be compared (no exchange rate)". Price cells
  "£3.20 (≈ Tk 480)" when the currency is not the patient's. `SearchService.search(…, displayCurrency)`.
- Reservations save unit_price + currency; `ReservationView.getAmount()` (BigDecimal) / `getAmountText()`; the total
  ("Total to pay at the pharmacy") is in the batch's currency with "≈ <patient currency>" under it
  (PharmacyDetailFrame, ReservationConfirmFrame, MyReservationsFrame); PharmacyReservationsFrame shows its own currency.
  The admin dashboard and reports have no money totals (if one is added: group by currency or convert with "≈").
- Demo data (`DemoDataSeeder.load` adds only what is missing, `isComplete()`): manual rates BDT 122, USD 1, EUR 0.92,
  GBP 0.78, INR 88.50; pharmacy13 Westminster Chemist (London, GBP) and pharmacy14 Park Street Pharmacy (Kolkata, INR),
  verified, 24 h, with stock; 3 PENDING medicines: "Napa 500mg" (Green Life, same batch number as its Napa 500 mg →
  demo Merge), Maxpro 20 mg (Medi Home), Deslor 5 mg (Popular Medicine Corner).
- .form files: every form's .form and initComponents are kept identical. Fix 3 found that LoginFrame,
  PharmacyProfileFrame, RegisterFrame, SearchFrame and VerificationFrame .forms were stale since Fix 1/2 and
  regenerated them from the Java code (no layout change).

## Theme (`UITheme`)

| Token        | Value     |
|--------------|-----------|
| Sidebar (logo navy) | `#0F2A3D` |
| Accent (logo dark teal) | `#0F766E` |
| Teal (logo)  | `#14B8A6` |
| Orange (logo) | `#F97316` |
| Background   | `#F4F6F9` |
| Success      | `#2E9E5B` |
| Warning      | `#E0A100` |
| Danger       | `#D64545` |

Fonts: Segoe UI 13 (body), 16 bold (heading), 22 bold (title).
`UITheme.install()` sets FlatLaf Light with these colours (called once in `Main`).
Helpers: `styleFrame` (window icon + background), `styleTitle`, `styleHeading`, `styleMuted`,
`styleStatus`, `styleError`, `stylePrimaryButton`, `styleSuccessButton`, `styleWarningButton`,
`styleDangerButton`, `styleLinkButton`, `styleCard` (white panel, light border),
`styleWarningNote` (yellow box #FFF6DA / #F0D98A / #6D5300), `styleInfoNote` (light green box),
`createLogoIcon(size)` / `createLogoImage(size)` (the logo `resources/logo.png` scaled: window icons, sidebar, login, toasts, tray), `styleSummaryCard(card, colour)` (dashboard
card), `hex(colour)` ("#RRGGBB" for HTML text). Selected JTabbedPane tabs use the accent
green. Placeholders: `field.putClientProperty("JTextField.placeholderText", "...")`.

Tables (`TableUtil`): row height 28, bold header, accent selection, ID column narrow
(50 px) and centered, read-only model, row sorter.

## Current state

- `medix.Main` opens `LoginFrame` (400x560). "Create one" → `RegisterFrame` (Patient /
  Pharmacy tabs) → back to `LoginFrame` with the email filled in.
- Login opens `SearchFrame` / `InventoryFrame` / `AdminDashboardFrame` by role (AdminHomeFrame was removed).
- Sidebar menus (`SidebarUtil`): PATIENT Search Medicine, My Reservations, Notifications,
  Profile; PHARMACY Inventory, Reservations, Alerts, Pharmacy Profile; ADMIN Dashboard,
  Pharmacy Verification, Medicine Catalog, Reports (n), Users, Audit Log, Notifications; all end with Logout.
  Every item has a frame now (a `null` would show "… is coming in a later part."):
  Search Medicine → SearchFrame, My Reservations → MyReservationsFrame, Notifications → NotificationFrame,
  Profile → ProfileFrame, Inventory → InventoryFrame, Reservations (pharmacy) → PharmacyReservationsFrame,
  Alerts → AlertsFrame, Pharmacy Profile → PharmacyProfileFrame, Dashboard → AdminDashboardFrame,
  Pharmacy Verification → VerificationFrame, Medicine Catalog → MedicineCatalogFrame,
  Reports → ReportsFrame, Users → UsersFrame, Audit Log → AuditLogFrame.
- Admin frames use `lblTitle` + `lblSubtitle` at the top instead of `lblWelcome`.
- `medix.engine`: `GeoUtil`, `SearchEngine`, `AvailabilityCache`, `OpeningHours` (done);
  `JobScheduler` + `Jobs` and the six job classes (Part 8).
