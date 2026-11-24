package medix.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import medix.dao.ExchangeRateDao;
import medix.dao.UserDao;
import medix.model.ExchangeRate;
import medix.model.RateSource;
import medix.service.DatabaseService;
import medix.service.ServiceException;
import medix.util.DBConnection;
import medix.util.Session;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exchange rates with a saved sample answer of open.er-api.com (no real network calls) and a
 * temporary database.
 */
public class ExchangeRateServiceTest {

    private static String sample;

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        sample = resource("er_api_latest_usd.json");
    }

    @Before
    public void emptyRates() throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM exchange_rates");
        }
        ExchangeRateService.clearCache();
        Session.login(new UserDao().findById(1), null); // the seeded admin
    }

    @Test
    public void parsesTheSavedAnswer() throws Exception {
        ExchangeRateService.Parsed p = ExchangeRateService.parse(sample);
        assertEquals(10, p.getRates().size());
        assertEquals(new BigDecimal("121.84"), p.getRates().get("BDT"));
        assertEquals(new BigDecimal("0.7654"), p.getRates().get("GBP"));
        assertEquals(0, BigDecimal.ONE.compareTo(p.getRates().get("USD")));
        assertEquals("Thu, 05 Nov 2026 00:02:31 +0000", p.getLastUpdateUtc());
        try {
            ExchangeRateService.parse("{\"result\":\"error\",\"error-type\":\"unsupported-code\"}");
            fail("an error answer is not used");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("unsupported-code"));
        }
    }

    @Test
    public void downloadsOnceAndConverts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ExchangeRateService service = new ExchangeRateService(url -> {
            calls.incrementAndGet();
            assertEquals(ExchangeRateService.API_URL, url);
            return sample;
        });
        assertTrue(service.needsUpdate());
        ExchangeRateService.UpdateResult r = service.updateOnline(false, false);
        assertTrue(r.isFetched());
        assertEquals(10, r.getSaved());
        assertEquals(RateSource.ONLINE, new ExchangeRateDao().findByCode("GBP").getSource());
        assertFalse("fresh rates are not downloaded again the same day", service.needsUpdate());
        assertFalse(service.updateOnline(false, false).isFetched());
        assertEquals(0, service.runScheduled());
        assertEquals("only one request", 1, calls.get());

        // £3.20 → Tk: 3.20 / 0.7654 × 121.84 = 509.39…
        BigDecimal tk = service.convert(new BigDecimal("3.20"), "GBP", "BDT");
        assertEquals(509.39, tk.doubleValue(), 0.01);
        assertEquals("amount / rate[from] × rate[to]", 3.20 / 0.7654 * 121.84, tk.doubleValue(), 1e-9);
        assertEquals("same currency: unchanged", new BigDecimal("7.00"), service.convert(new BigDecimal("7.00"), "BDT", "BDT"));
        assertEquals(0, new BigDecimal("121.84").compareTo(service.convert(BigDecimal.ONE, "USD", "BDT")));
    }

    @Test
    public void aMissingRateGivesNull() throws Exception {
        ExchangeRateService service = new ExchangeRateService(url -> sample);
        assertNull("no rates saved yet", service.convert(BigDecimal.TEN, "GBP", "BDT"));
        service.updateOnline(false, false);
        assertNull("THB is not in the sample", service.convert(BigDecimal.TEN, "THB", "BDT"));
        assertNull(service.convert(BigDecimal.TEN, "BDT", "THB"));
        assertNull(service.convert(null, "BDT", "GBP"));
    }

    @Test
    public void manualRatesAreKeptUnlessReplaced() throws Exception {
        ExchangeRateService service = new ExchangeRateService(url -> sample);
        service.setManualRate("BDT", new BigDecimal("122.00"));
        service.setManualRate("THB", new BigDecimal("32.50"));
        ExchangeRateService.UpdateResult r = service.updateOnline(true, false);
        assertEquals(1, r.getKeptManual());
        ExchangeRate bdt = new ExchangeRateDao().findByCode("BDT");
        assertEquals(RateSource.MANUAL, bdt.getSource());
        assertEquals(0, new BigDecimal("122.00").compareTo(bdt.getRatePerUsd()));
        assertEquals("a currency the API does not send stays", RateSource.MANUAL,
                new ExchangeRateDao().findByCode("THB").getSource());

        // the admin ticks "Replace manual rates" (direct save: the 1-hour limit of Update now is not tested here)
        service.save(ExchangeRateService.parse(sample), true);
        bdt = new ExchangeRateDao().findByCode("BDT");
        assertEquals(RateSource.ONLINE, bdt.getSource());
        assertEquals(0, new BigDecimal("121.84").compareTo(bdt.getRatePerUsd()));
    }

    @Test
    public void offlineOrBusyKeepsTheSavedRates() throws Exception {
        ExchangeRateService.clearCache();
        new ExchangeRateService(url -> sample).setManualRate("GBP", new BigDecimal("0.78"));
        ExchangeRateService busy = new ExchangeRateService(url -> {
            throw new ExchangeRateService.RateLimitException();
        });
        assertEquals("HTTP 429: nothing saved, no failure", 0, busy.runScheduled());
        ExchangeRateService offline = new ExchangeRateService(url -> {
            throw new IOException(ExchangeRateService.MSG_OFFLINE);
        });
        assertEquals(0, offline.runScheduled());
        assertEquals(0, new BigDecimal("0.78").compareTo(new ExchangeRateDao().findByCode("GBP").getRatePerUsd()));
    }

    @Test
    public void onlyAnAdminSetsRatesAndUsdStaysOne() throws Exception {
        ExchangeRateService service = new ExchangeRateService(url -> sample);
        try {
            service.setManualRate("USD", new BigDecimal("2"));
            fail("USD is the base");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("USD"));
        }
        Session.logout();
        try {
            service.setManualRate("GBP", new BigDecimal("0.8"));
            fail("not logged in as admin");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("admin"));
        }
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = ExchangeRateServiceTest.class.getResourceAsStream(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
