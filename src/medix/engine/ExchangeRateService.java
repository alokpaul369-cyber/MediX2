package medix.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import medix.dao.ExchangeRateDao;
import medix.model.ExchangeRate;
import medix.model.RateSource;
import medix.model.Role;
import medix.service.AuditService;
import medix.service.ServiceException;
import medix.util.CurrencyUtil;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.Session;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Exchange rates for showing prices in the patient's currency and sorting by price.
 *
 * Online source: Exchange Rate API's free endpoint {@value #API_URL} (no key; rates change once a
 * day). It is fetched at most once a day (ExchangeRateJob: at start-up when the saved rates are older
 * than 24 hours, then daily); HTTP 429 or no internet keeps the saved rates. Every rate is saved in
 * exchange_rates (source ONLINE). MANUAL rates (the demo data's offline rates) are kept. The terms ask for the credit {@value #CREDIT_TEXT} wherever converted prices are shown.
 *
 * convert(amount, from, to) = amount / rate[from] × rate[to] (rates are units per 1 USD). A missing
 * rate gives null: a price is never converted with a guessed rate.
 */
public class ExchangeRateService implements SearchEngine.Converter {

    public static final String API_URL = "https://open.er-api.com/v6/latest/USD";
    public static final String CREDIT_TEXT = "Rates by Exchange Rate API";
    public static final String CREDIT_URL = "https://www.exchangerate-api.com";
    public static final int MAX_AGE_HOURS = 24;
    /** "Update now" right after a fetch is refused: the rates only change once a day. */
    public static final int MIN_MINUTES_BETWEEN_FETCHES = 60;
    public static final int TIMEOUT_MILLIS = 8000;
    public static final String MSG_OFFLINE = "Can't reach the exchange rate service. The saved rates are used.";
    public static final String MSG_BUSY = "The exchange rate service is busy (HTTP 429). The saved rates are used.";

    /** Downloads a URL (replaced in tests: no real network calls). */
    public interface Fetcher {
        String get(String url) throws IOException;
    }

    /** An HTTP 429 answer. */
    public static class RateLimitException extends IOException {
        private static final long serialVersionUID = 1L;

        public RateLimitException() {
            super(MSG_BUSY);
        }
    }

    /** A parsed /v6/latest/USD answer. */
    public static final class Parsed {
        private final Map<String, BigDecimal> rates;
        private final String lastUpdateUtc;

        Parsed(Map<String, BigDecimal> rates, String lastUpdateUtc) {
            this.rates = rates;
            this.lastUpdateUtc = lastUpdateUtc;
        }

        /** Units per 1 USD by code, in the order of the answer. */
        public Map<String, BigDecimal> getRates() {
            return rates;
        }

        /** e.g. "Fri, 06 Nov 2026 00:02:31 +0000". */
        public String getLastUpdateUtc() {
            return lastUpdateUtc;
        }
    }

    /** What an online update did. */
    public static final class UpdateResult {
        private final int saved;
        private final int keptManual;
        private final boolean fetched;
        private final String message;

        UpdateResult(int saved, int keptManual, boolean fetched, String message) {
            this.saved = saved;
            this.keptManual = keptManual;
            this.fetched = fetched;
            this.message = message;
        }

        public int getSaved() {
            return saved;
        }

        public int getKeptManual() {
            return keptManual;
        }

        /** False when nothing was downloaded (rates still fresh). */
        public boolean isFetched() {
            return fetched;
        }

        public String getMessage() {
            return message;
        }
    }

    private static final Object CACHE_LOCK = new Object();
    private static Map<String, BigDecimal> cache;
    private static long cacheLoadedAt;
    private static final long CACHE_MILLIS = 60000;

    private final ExchangeRateDao dao = new ExchangeRateDao();
    private final Fetcher fetcher;

    public ExchangeRateService() {
        this(ExchangeRateService::httpGet);
    }

    public ExchangeRateService(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    // ---------------------------------------------------------------- reading

    public List<ExchangeRate> getAll() throws SQLException {
        return dao.findAll();
    }

    /** Saved rates by code (cached for a minute; every save clears the cache). */
    public Map<String, BigDecimal> rates() {
        synchronized (CACHE_LOCK) {
            if (cache == null || System.currentTimeMillis() - cacheLoadedAt > CACHE_MILLIS) {
                Map<String, BigDecimal> m = new HashMap<String, BigDecimal>();
                try {
                    for (ExchangeRate r : dao.findAll()) {
                        m.put(r.getCurrencyCode(), r.getRatePerUsd());
                    }
                } catch (SQLException e) {
                    ErrorLog.log("Could not read the exchange rates", e);
                }
                cache = Collections.unmodifiableMap(m);
                cacheLoadedAt = System.currentTimeMillis();
            }
            return cache;
        }
    }

    public static void clearCache() {
        synchronized (CACHE_LOCK) {
            cache = null;
        }
    }

    /**
     * amount in `from` → amount in `to` (not rounded; format it for display). Same currency: the
     * amount itself. Null when a rate is missing or an argument is null.
     */
    @Override
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null || from == null || to == null) {
            return null;
        }
        if (from.equalsIgnoreCase(to)) {
            return amount;
        }
        Map<String, BigDecimal> r = rates();
        BigDecimal rateFrom = rate(r, from);
        BigDecimal rateTo = rate(r, to);
        if (rateFrom == null || rateTo == null || rateFrom.signum() <= 0) {
            return null;
        }
        return amount.multiply(rateTo).divide(rateFrom, MathContext.DECIMAL64);
    }

    private static BigDecimal rate(Map<String, BigDecimal> r, String code) {
        BigDecimal v = r.get(code.toUpperCase(Locale.ROOT));
        if (v == null && CurrencyUtil.USD.equalsIgnoreCase(code)) {
            return BigDecimal.ONE; // the base currency
        }
        return v;
    }

    /** Newest updated_at of any rate ("rates updated …"); null when there are no rates. */
    public String ratesDate() {
        try {
            return dao.newestUpdate(null);
        } catch (SQLException e) {
            ErrorLog.log("Could not read the exchange rates date", e);
            return null;
        }
    }

    /** "Rates by Exchange Rate API · updated 06 Nov 2026" (or "no rates saved yet"). */
    public String creditLine() {
        String date = ratesDate();
        return CREDIT_TEXT + " · " + (date == null ? "no rates saved yet" : "updated " + DateUtil.toDisplayDate(date));
    }

    /** True when there are no online rates or the newest is MAX_AGE_HOURS old. */
    public boolean needsUpdate() throws SQLException {
        String newest = dao.newestUpdate(RateSource.ONLINE);
        return newest == null || DateUtil.isOlderThanHours(newest, MAX_AGE_HOURS);
    }

    // ---------------------------------------------------------------- online update

    /** A /v6/latest/USD answer → rates. IOException when it is not a success answer. */
    public static Parsed parse(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            if (!"success".equals(o.optString("result"))) {
                throw new IOException("The exchange rate service answered: " + o.optString("error-type", "error"));
            }
            if (!"USD".equals(o.optString("base_code", "USD"))) {
                throw new IOException("Unexpected base currency " + o.optString("base_code"));
            }
            JSONObject rates = o.getJSONObject("rates");
            Map<String, BigDecimal> map = new LinkedHashMap<String, BigDecimal>();
            Iterator<String> keys = rates.keys();
            while (keys.hasNext()) {
                String code = keys.next();
                if (code.length() != 3) {
                    continue;
                }
                BigDecimal value = new BigDecimal(rates.get(code).toString());
                if (value.signum() > 0) {
                    map.put(code.toUpperCase(Locale.ROOT), value);
                }
            }
            if (map.isEmpty()) {
                throw new IOException("The exchange rate service sent no rates.");
            }
            return new Parsed(map, o.optString("time_last_update_utc", null));
        } catch (JSONException | NumberFormatException e) {
            throw new IOException("Unexpected answer from the exchange rate service", e);
        }
    }

    /**
     * Downloads and saves the rates. Without force nothing is downloaded while the online rates
     * are under 24 hours old. With force (admin "Update now") it is refused within an hour of the
     * last download. replaceManual = also overwrite rates an admin typed.
     */
    public UpdateResult updateOnline(boolean force, boolean replaceManual) throws IOException, SQLException {
        String newest = dao.newestUpdate(RateSource.ONLINE);
        if (!force && newest != null && !DateUtil.isOlderThanHours(newest, MAX_AGE_HOURS)) {
            return new UpdateResult(0, 0, false, "The rates are less than " + MAX_AGE_HOURS + " hours old.");
        }
        if (force && newest != null && minutesSince(newest) < MIN_MINUTES_BETWEEN_FETCHES) {
            return new UpdateResult(0, 0, false, "The rates were downloaded " + DateUtil.ago(newest)
                    + " ago. They change once a day, so they were not downloaded again.");
        }
        Parsed parsed = parse(fetcher.get(API_URL));
        return save(parsed, replaceManual);
    }

    /** Saves parsed rates in one transaction; MANUAL rows stay unless replaceManual. */
    UpdateResult save(Parsed parsed, boolean replaceManual) throws SQLException {
        int saved = 0;
        int kept = 0;
        String now = DateUtil.now();
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                for (Map.Entry<String, BigDecimal> e : parsed.getRates().entrySet()) {
                    ExchangeRate old = dao.findByCode(con, e.getKey());
                    if (old != null && old.getSource() == RateSource.MANUAL && !replaceManual) {
                        kept++;
                        continue;
                    }
                    ExchangeRate r = old != null ? old : new ExchangeRate();
                    r.setCurrencyCode(e.getKey());
                    r.setRatePerUsd(e.getValue());
                    r.setSource(RateSource.ONLINE);
                    r.setUpdatedAt(now);
                    if (old == null) {
                        dao.insert(con, r);
                    } else {
                        dao.update(con, r);
                    }
                    saved++;
                }
                con.commit();
            } catch (SQLException | RuntimeException ex) {
                con.rollback();
                throw ex;
            }
        }
        clearCache();
        AvailabilityCache.clear(); // search rows carry converted prices
        return new UpdateResult(saved, kept, true, saved + " rate(s) saved"
                + (kept > 0 ? ", " + kept + " manual rate(s) kept" : "") + ".");
    }

    /** ExchangeRateJob: downloads when the rates are old; offline / HTTP 429 keeps the saved rates. */
    public int runScheduled() throws SQLException {
        if (!needsUpdate()) {
            return 0;
        }
        try {
            return updateOnline(false, false).getSaved();
        } catch (IOException e) {
            ErrorLog.log("Exchange rate update skipped", e);
            return 0;
        }
    }

    // ---------------------------------------------------------------- manual rates

    /**
     * Admin: saves a rate by hand (source MANUAL, kept by the online update). USD is the base and
     * must stay 1. Writes audit EXCHANGE_RATE_SET.
     */
    public void setManualRate(String code, BigDecimal ratePerUsd) throws ServiceException, SQLException {
        requireAdmin();
        saveManual(code, ratePerUsd);
        new AuditService().log("EXCHANGE_RATE_SET", "exchange_rates", null,
                "1 USD = " + ratePerUsd.stripTrailingZeros().toPlainString() + " " + code.toUpperCase(Locale.ROOT));
    }

    /** Same without the admin check and audit (demo data). Returns false when the rate already exists. */
    public boolean saveManualIfMissing(String code, BigDecimal ratePerUsd) throws ServiceException, SQLException {
        if (dao.findByCode(code) != null) {
            return false;
        }
        saveManual(code, ratePerUsd);
        return true;
    }

    private void saveManual(String code, BigDecimal ratePerUsd) throws ServiceException, SQLException {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CurrencyUtil.isValidCode(c)) {
            throw new ServiceException("Choose a currency from the list.");
        }
        if (ratePerUsd == null || ratePerUsd.signum() <= 0) {
            throw new ServiceException("The rate must be more than 0.");
        }
        if (CurrencyUtil.USD.equals(c) && ratePerUsd.compareTo(BigDecimal.ONE) != 0) {
            throw new ServiceException("USD is the base currency: its rate is always 1.");
        }
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                ExchangeRate old = dao.findByCode(con, c);
                ExchangeRate r = old != null ? old : new ExchangeRate();
                r.setCurrencyCode(c);
                r.setRatePerUsd(ratePerUsd);
                r.setSource(RateSource.MANUAL);
                r.setUpdatedAt(DateUtil.now());
                if (old == null) {
                    dao.insert(con, r);
                } else {
                    dao.update(con, r);
                }
                con.commit();
            } catch (SQLException | RuntimeException ex) {
                con.rollback();
                throw ex;
            }
        }
        clearCache();
        AvailabilityCache.clear();
    }

    private static void requireAdmin() throws ServiceException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new ServiceException("Only an admin can change exchange rates.");
        }
    }

    private static long minutesSince(String dateTime) {
        java.util.Date d = DateUtil.parseDateTime(dateTime);
        return d == null ? Long.MAX_VALUE : (System.currentTimeMillis() - d.getTime()) / 60000;
    }

    // ---------------------------------------------------------------- HTTP

    private static String httpGet(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(TIMEOUT_MILLIS);
        con.setReadTimeout(TIMEOUT_MILLIS);
        con.setRequestProperty("User-Agent", "MediX/1.0 (university project)");
        con.setRequestProperty("Accept", "application/json");
        try {
            int status;
            try {
                status = con.getResponseCode();
            } catch (IOException e) {
                throw new IOException(MSG_OFFLINE, e);
            }
            if (status == 429) {
                throw new RateLimitException();
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("The exchange rate service answered HTTP " + status + ". The saved rates are used.");
            }
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            con.disconnect();
        }
    }
}
