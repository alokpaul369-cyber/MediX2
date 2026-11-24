# MediX – Medicine Availability Finder

MediX is a Java 8 Swing desktop app (a NetBeans Ant project with an SQLite database). It started in Dhaka and works anywhere on Earth.
Pharmacies keep a live inventory of their medicines: quantity, batch, price and expiry date.
Patients search for a medicine by name, see which nearby pharmacies have it (sorted by distance),
and reserve units for 30–60 minutes before they travel. Admins verify pharmacy licenses, handle
complaints and keep an eye on the platform.

This is the desktop version of the system described in our project report. The table under
[Architecture](#architecture-report--desktop-version) shows how each part of the report maps to this app.

## The three roles

| Role | Who | What they can do |
|------|-----|------------------|
| **Patient** | Member of the public | Search medicines (spelling mistakes allowed), filter by area and distance, sort by distance, price or stock, see a pharmacy's details and map, reserve up to 10 units (max 3 active reservations), cancel, report a problem, get notifications, edit the profile, change the password, delete the search history |
| **Pharmacy** | Pharmacy owner (one account = one pharmacy) | Register with a drug license, manage stock batches (add, stock in, sell, adjust, confirm all stock) priced in any currency, add a medicine that is not in the catalog and sell it at once, confirm / prepare / hand over / extend / cancel reservations, see alerts (low stock, expiry, stale stock), edit the pharmacy profile |
| **Admin** | Platform admin | Dashboard with charts, verify / reject / suspend / reactivate pharmacies after checking the license, manage the medicine catalog and review medicines added by pharmacies (approve, edit, merge, reject), uphold or dismiss reports, enable / disable users, add admins, read and export the audit log |

## Features

- **Anywhere on Earth**: patients and pharmacies choose their location by searching a place
  (OpenStreetMap), clicking a map, or typing coordinates. Places are saved and reused, and saved places
  and typed coordinates work offline. Phone numbers can be Bangladeshi (01…) or international (+44…).
- **Forgot password?**: a 6-digit code by e-mail (expires in 15 minutes, 5 tries, at most 1 per minute and
  3 per hour). Without e-mail settings it runs in demo mode and shows the code on screen. Admins can also
  set a temporary password that must be changed at the next login.
- **Any medicine**: a pharmacy types a medicine that is not in its list and picks "+ Add … as a new medicine…".
  Similar medicines are shown first ("napa 500mg" finds Napa 500 mg) and exact duplicates are never created.
  The new medicine can be sold straight away (patients see "New – not yet reviewed"); an admin approves it,
  corrects it, merges it into the right medicine (stock, reservations and searches move with it, the same batch
  becomes one batch) or rejects it (its stock disappears from search).
- **Any currency**: every batch has its own currency (the pharmacy's default follows its country). Patients see the
  price in the pharmacy's currency with "≈" their own currency, and sorting by price converts first. Rates come
  once a day from Exchange Rate API (open.er-api.com, no key); the demo data adds offline rates; without a rate a price
  is never guessed. Money is calculated with BigDecimal.
- **Search** with exact, starts-with, word, generic-name and fuzzy (Levenshtein) matching and
  "Did you mean …?". Results are ranked by distance (Haversine), then trust score, then price.
  Expired batches and unverified pharmacies are never shown. Pharmacies whose stock is more than 24 hours old are flagged.
- **Reservations**: one transaction per change, first-expiring batch first (FEFO), reserved units are
  protected from sales, codes like `MX-1101-0042`, holds of 30/45/60 minutes that expire automatically.
- **Trust score** (0–100): 80 + completed pickups (up to +15) − 5 per out-of-stock cancel
  − 10 per upheld report − 5 for stale stock. It is recalculated every night.
- **Background jobs**: reservation expiry (every minute), low stock (every 15 minutes), stale stock
  (every hour), expiry sweep (08:00), trust scores (02:00) and search-log clean-up after 90 days (03:00).
  Each run is retried 3 times and written to `job_runs`.
- **Notifications**: inbox screens, pop-up toasts, Windows tray messages and an unread count in the sidebar.
- **Admin dashboard**: summary cards, "most searched" and "searches per day" charts (JFreeChart, exportable as
  PNG), possible shortages (searched but found nowhere) and the newest open reports.
- **Audit log** of every important action (login, stock change, verification, report decision …)
  with filters and CSV export.
- **Demo data**: one click on the login screen fills an empty database with realistic data.
- **Privacy**: searches store only the area, never exact coordinates. They are deleted after 90 days, or earlier
  when the patient asks.

## Screens (JFrame Forms in `src/medix/forms`)

| Screen | Who | Purpose |
|--------|-----|---------|
| LoginFrame | everyone | Log in, create an account, "Forgot password?", load demo data |
| ForgotPasswordFrame | everyone | Ask for a 6-digit reset code by e-mail |
| ResetPasswordFrame | everyone | Enter the code and a new password ("Resend code" after 60 s) |
| ChangePasswordFrame | everyone | Forced new password after an admin reset |
| RegisterFrame | everyone | Patient tab, Pharmacy tab (with license upload) |
| SearchFrame | patient | Search medicines nearby |
| PharmacyDetailFrame | patient | Pharmacy card, batches, reserve |
| ReservationConfirmFrame | patient | Reservation code and hold time |
| MyReservationsFrame | patient | Own reservations, cancel, report |
| ReportFrame | patient | Report a problem with a reservation |
| ProfileFrame | patient | Details, password, delete search history |
| NotificationFrame | patient, admin | Notification inbox |
| InventoryFrame | pharmacy | Stock batches |
| StockDialogFrame | pharmacy | Stock in / sold / adjust |
| PharmacyReservationsFrame | pharmacy | Reservations of the pharmacy |
| AlertsFrame | pharmacy | Alerts + background job status |
| PharmacyProfileFrame | pharmacy | Pharmacy details, new license |
| AdminDashboardFrame | admin | Cards, charts, shortages, open reports |
| VerificationFrame | admin | License check, approve / reject / suspend |
| MedicineCatalogFrame | admin | Medicine catalog |
| ReportsFrame | admin | Uphold / dismiss reports, go to suspend |
| UsersFrame | admin | Enable / disable users, reset a password (temporary password) |
| AddAdminFrame | admin | Create another admin |
| AuditLogFrame | admin | Audit log with filters and CSV export |
| LocationPickerFrame | everyone | Choose a place anywhere: search, map, coordinates, recent places |
| ToastFrame | everyone | Pop-up notification (bottom right) |

Every screen opens in the NetBeans Design view. A patient cannot open pharmacy or admin screens, and the
other roles are restricted in the same way. If a screen is opened by the wrong role, it closes, the user is
sent home and `ACCESS_DENIED` is written to the audit log. On top of that, every service checks the
logged-in role and only returns the logged-in pharmacy's own stock and reservations.

## Demo logins

Click **Load demo data** on the login screen (it asks first and does nothing if the demo accounts already exist).

| Role | Email | Password |
|------|-------|----------|
| Admin | `admin@medix.com` | `admin123` (always there) |
| Patient | `patient@medix.com` (Dhanmondi) | `demo123` |
| Pharmacy | `pharmacy1@medix.com` (Green Life Pharmacy, Dhanmondi, open 24 hours) | `demo123` |

The demo data adds the following:
- 5 more patients: `patient2`–`patient6@medix.com`.
- 12 fictional pharmacies, `pharmacy1`–`pharmacy12@medix.com`, all with password `demo123`:
  - 9 are verified, 2 are pending and 1 is suspended.
  - City Drug Store was verified more than 12 months ago, so it shows "Re-verify".
  - Al-Madina Drug House has stock that was not updated for 2 days.
  - Each pharmacy has a generated license image.
- 8–20 stock batches per verified pharmacy, including some low-stock, expiring and expired batches. Nobody in
  Uttara stocks Insulet R, so it appears as a shortage.
- About 1,200 searches over 14 days.
- Reservations in every status.
- 3 reports (open, upheld, dismissed).
- Background job history, notifications and audit entries.
- Fix 3 (also added to demo data loaded earlier – click "Load demo data" again, only what is missing is added):
  - `pharmacy13@medix.com` Westminster Chemist in London (prices in GBP) and `pharmacy14@medix.com` Park Street
    Pharmacy in Kolkata (prices in INR), verified and open 24 hours.
  - 3 medicines added by pharmacies that wait for review: "Napa 500mg" (an obvious duplicate of Napa 500 mg with the
    same batch number – try Merge), Maxpro 20 mg and Deslor 5 mg.
  - Manual exchange rates for BDT, USD, EUR, GBP and INR, so prices convert without internet.

## Architecture (report → desktop version)

| In the report (web system) | In this desktop version |
|---|---|
| Spring Boot REST services | Plain Java service classes in `medix.service` (forms call services, services call DAOs) |
| PostgreSQL | SQLite file `data/medix.db` through the `sqlite-jdbc` driver, plain JDBC DAOs in `medix.dao` |
| Redis cache | `medix.engine.AvailabilityCache`: an in-memory LRU map with a 60-second expiry, cleared when stock changes |
| Browser Geolocation API + maps | Places are found with OpenStreetMap Nominatim (`GeocodingService`) or picked on an OpenStreetMap map (JXMapViewer2 in `LocationPickerFrame`) or typed as coordinates, and saved in `areas`. Search uses a bounding box in SQL, then the Haversine distance (`GeoUtil`). "Open map" opens OpenStreetMap in the browser |
| Quartz / Spring `@Scheduled` | `medix.engine.JobScheduler` (one `ScheduledExecutorService`, retries, `job_runs` history) |
| React web pages | NetBeans JFrame Forms (Swing + FlatLaf look and feel) |
| JWT login sessions | `AuthService` + `Session` (salted SHA-256 passwords) |
| Push / e-mail notifications | `notifications` table, toasts and Windows tray messages |

Packages: `medix` (Main), `medix.forms` (screens), `medix.model` (POJOs, enums), `medix.dao` (one DAO per table),
`medix.service` (business rules, transactions), `medix.engine` (search, distance, cache, jobs),
`medix.util` (database connection, session, theme, tables, demo data, error log …).

## Database: 14 tables

The first column of every table is `id INTEGER PRIMARY KEY AUTOINCREMENT`, and every foreign key column has
an index. At start-up MediX checks this and prints:

```
Schema check: 14/14 tables use id INTEGER PRIMARY KEY AUTOINCREMENT
Schema check: 24/24 foreign key columns have an index
```

| # | Table | Columns |
|---|-------|---------|
| 1 | `areas` | saved places anywhere on Earth: id, name, display_name (unique), city, country, country_code, latitude, longitude, source (SEED / GEOCODED / MANUAL), created_at |
| 2 | `users` | id, full_name, email (unique), phone, password_hash, salt, role (PATIENT / PHARMACY / ADMIN), area_id, is_active, created_at, latitude, longitude (rounded to about 1 km), location_name, must_change_password (1 after an admin reset) |
| 3 | `pharmacies` | id, owner_id (unique), name, license_no (unique), license_doc_path, address, area_id, latitude, longitude, phone, open_time, close_time, status (PENDING / VERIFIED / REJECTED / SUSPENDED), trust_score, verified_by, verified_at, reject_reason, created_at, stale_penalty, currency |
| 4 | `medicines` | id, brand_name, generic_name, strength, form (TABLET … PATCH / OTHER), form_other, manufacturer, rx_required, source (SEED / ADMIN / PHARMACY), status (APPROVED / PENDING / REJECTED / MERGED), created_by, created_at, reviewed_by, reviewed_at, review_note, merged_into_id; unique (brand_name, strength, form, form_other) |
| 5 | `inventory` | id, pharmacy_id, medicine_id, batch_no, quantity, reserved_qty, price, currency, expiry_date, low_stock_threshold, updated_at; unique (pharmacy_id, medicine_id, batch_no); reserved_qty ≤ quantity |
| 6 | `stock_movements` | id, inventory_id, change_qty, reason (ADD / SALE / ADJUST / EXPIRED / RESERVED / RELEASED / PICKED_UP), user_id, note, created_at |
| 7 | `reservations` | id, code (unique), patient_id, pharmacy_id, medicine_id, inventory_id, quantity, status (PENDING / CONFIRMED / READY / COMPLETED / CANCELLED / EXPIRED), created_at, expires_at, updated_at, note, extended, cancelled_by, cancel_reason, unit_price, currency |
| 8 | `notifications` | id, user_id, type (LOW_STOCK / EXPIRY / RESERVATION / STALE / VERIFICATION / REPORT / INFO), message, created_at, is_read, link_entity, link_id |
| 9 | `search_logs` | id, user_id, query, medicine_id, area_id, results_count, created_at, latitude, longitude (rounded to about 1 km) |
| 10 | `reports` | id, reporter_id, pharmacy_id, reservation_id, reason (WRONG_STOCK / RESERVATION_NOT_HONOURED / WRONG_PRICE / FAKE_PHARMACY / OTHER), details, status (OPEN / RESOLVED / DISMISSED), admin_note, created_at, resolved_at |
| 11 | `audit_logs` | id, user_id, action, entity, entity_id, details, created_at |
| 12 | `job_runs` | id, job_name, started_at, finished_at, status (OK / FAILED), items_affected, message |
| 13 | `password_resets` | id, user_id, code_hash, code_salt (only a salted hash of the 6-digit code, never the code), created_at, expires_at, attempts, used, sent_via (EMAIL / DEMO) |
| 14 | `exchange_rates` | id, currency_code (unique), rate_per_usd (units per 1 US dollar), source (ONLINE / MANUAL), updated_at |

Dates are stored as text (`yyyy-MM-dd HH:mm:ss`), money as REAL next to its currency code (BigDecimal in Java), and enums by name with a CHECK
constraint. Any change to stock or reservations runs in one `BEGIN IMMEDIATE` transaction.

## Requirements

- JDK 8 (1.8). The jars also run on newer Java versions.
- NetBeans with Java SE support (tested with NetBeans 8.2). Newer Apache NetBeans also works if a JDK 8 platform is
  selected under Project Properties → Libraries → Java Platform.

All libraries are already in `lib/`:

| Jar | Purpose |
|-----|---------|
| `sqlite-jdbc-3.54.1.0.jar` | SQLite JDBC driver |
| `jfreechart-1.5.6.jar` | Dashboard charts |
| `jcalendar-1.4.jar` | `JDateChooser` |
| `flatlaf-3.7.2.jar` | Look and feel |
| `json-20260814.jar` | org.json, reads the place-search answers |
| `javax.mail-1.6.2.jar` | JavaMail, sends the password reset codes |
| `jxmapviewer2-2.8.1.jar`, `commons-logging-1.2.jar` | The map in the location picker (and its logging dependency) |
| `junit-4.13.2.jar`, `hamcrest-core-1.3.jar` | Tests only (not shipped) |

## Build and run in NetBeans

1. **File → Open Project…**, select the `MediX` folder.
2. Right-click the project → **Clean and Build**. This creates `dist/MediX.jar`, with the libraries in `dist/lib/`
   and `Main-Class: medix.Main`.
3. Press **F6** (Run Project). The *MediX – Login* window appears.
4. Click **Load demo data**, then log in with one of the demo logins above.

From a terminal: `ant clean jar`, then `java -jar dist/MediX.jar`.

## One runnable jar, run.bat and MediX.exe

```bash
ant package-fat
```

This builds `dist/MediX-all.jar` (MediX and every library in one file) and copies `run.bat` next to it.
Double-click `run.bat`, or run `java -jar dist/MediX-all.jar`. The database is created in a `data` folder next to the jar.

**MediX.exe with Launch4j** (free, https://launch4j.sourceforge.net):

1. Run `ant package-fat` first.
2. Start Launch4j. On the **Basic** tab:
   - *Output file*: `…\MediX\dist\MediX.exe`
   - *Jar*: `…\MediX\dist\MediX-all.jar`
   - *Icon*: optional, needs an `.ico` file
   - *Change dir*: `.` (so `data\` is created next to the exe)
3. On the **Header** tab choose *GUI*.
4. On the **JRE** tab set *Min JRE version* to `1.8.0`. Optionally add a download URL (for example
   `https://adoptium.net/`) that is shown when no Java is installed.
5. On the **Version Info** tab you may fill in "MediX – Medicine Availability Finder".
6. Save the configuration (for example `launch4j-medix.xml`) and click the gear icon (**Build wrapper**).
7. `dist\MediX.exe` starts MediX like the jar does. The exe still needs Java 8 or newer on the computer.

Careful: **Clean and Build deletes `dist/`**, including `dist/data` if you ran the jar or exe from there.

## Tests

Right-click the project → **Test** (Alt+F6), or run `ant test`. There are 23 JUnit 4 test classes (147 tests); none of them use the network or send e-mail.
Each class runs in its own JVM with a temporary database file, never `data/medix.db`.

| Test class | Covers |
|------------|--------|
| `PasswordUtilTest` | salt, hash, verify |
| `GeoUtilTest` | Haversine distance, bounding box (poles, 180° line), distance text |
| `SearchEngineTest` | exact, starts-with, generic name, fuzzy, radius, sorting, stale stock |
| `SearchServiceTest` | expired batches hidden, unverified pharmacies hidden, cache, search log, London vs Dhaka, Fiji across the 180° line |
| `ReservationServiceTest` | reserve (FEFO, never split), cancel, cannot over-reserve (also two at once), max 3 active |
| `PharmacyReservationServiceTest` | confirm → ready → pick up, extend, cancel, trust formula |
| `JobsTest` | reservation expiry, alerts, retries, job history |
| `InventoryServiceTest` | reserved units cannot be sold or adjusted away, own stock only |
| `TrustScoreServiceTest` | base 80, bonus cap, penalties, 30-day window, clamp |
| `ReportServiceTest` | uphold lowers trust, dismiss, handled once |
| `PasswordResetServiceTest` | code hashing, 15-minute expiry, 5 tries, 1 per minute / 3 per hour, same answer for unknown e-mails, admin reset + forced change |
| `GeocodingServiceTest` | reading saved OpenStreetMap answers (search, reverse, open sea) |
| `MigrationRunnerTest` | an old Dhaka-only database is migrated without losing rows or foreign keys |
| `AdminServiceTest`, `UserServiceTest` | dashboard numbers, users, profile, audit filters |
| `DemoDataSeederTest` | demo data + the 14/14 schema check |
| `CurrencyUtilTest` | "Tk 7.00", "¥450" (no decimals), 3 decimals for BHD, BD → BDT, GB → GBP, JP → JPY |
| `ExchangeRateServiceTest` | a saved Exchange Rate API answer, conversion, missing rate = null, manual rates kept, HTTP 429 / offline |
| `MedicineServiceTest` | "napa 500mg" finds Napa 500 mg, no exact duplicates, merge (stock, same batch, reservations), reject hides stock |
| `AvailabilityCacheTest`, `OpeningHoursTest`, `ValidationUtilTest` | helpers |

## Your e-mail for the map service

OpenStreetMap's free services ask every app to identify itself. Open `data/config.properties` (MediX creates it
on the first start) and replace the placeholder with your own address:

```
contact.email=you@your-university.edu
```

Until then, MediX identifies itself without an address, because the services refuse the example.com placeholder.

## E-mail for "Forgot password?"

Without e-mail settings MediX runs in **demo mode**: the reset code appears on screen, which is handy for a
presentation. To send real e-mails with Gmail:

1. Turn on 2-Step Verification for the Gmail account, then create an **app password** at
   https://myaccount.google.com/apppasswords (your normal password does not work).
2. Copy `data/mail.properties.example` to `data/mail.properties` and fill in `smtp.username` and
   `smtp.password` (the app password).
3. Restart MediX. The "Demo mode" note on the Reset Password screen disappears.

`data/mail.properties` is in `.gitignore`: never commit it. If sending fails, the user sees "Couldn't send the
email. Try again later or ask an admin to reset your password." and the details go to `data/error.log`.

## Where the data lives

- Database: `data/medix.db`. It sits next to the project when MediX is run from NetBeans, and next to the jar or exe
  otherwise.
- License documents: `data/licenses/`.
- Error details: `data/error.log`. Screens show short, friendly messages; stack traces only go to this file.
- To start again with an empty database, close MediX and delete `data/medix.db`.

## Known limitations

- "Open now" uses the computer's time zone, not the pharmacy's. A pharmacy in another time zone can show
  as open or closed at the wrong hours.
- Address search and the map need the internet. Saved places, recent places and typed coordinates work offline.
- Place names come from OpenStreetMap: a new or small street may be missing, so use "Pick on map" then.
- Older Java 8 updates (such as 8u111) do not trust OpenStreetMap's certificate by themselves, so MediX adds that
  one public root certificate at start-up. Newer Java versions do not need this.
- The Recent places list is stored in `data/recent-places.properties` (per MediX user), not in the database.

## Disclaimer

MediX is a university project. The pharmacies, licenses, people and stock in the demo data are fictional, and the
license images are samples, not real documents. MediX shows the stock that pharmacies report. It helps patients
find and hold medicine, but it does not replace a doctor's advice or a call to the pharmacy. Stock can change at any
moment, so in an emergency always call the pharmacy before you travel. Prescription medicines still need a valid
prescription, and the pharmacist may refuse without one.

Project rules for developers are in `CLAUDE.md`.
