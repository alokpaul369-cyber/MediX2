package medix.util;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Currency formatting, decimals and the currency of a country (java.util.Currency on Java 8).
 */
public class CurrencyUtilTest {

    @Test
    public void formatsWithShortSymbolsAndTheCurrencysDecimals() {
        assertEquals("Tk 7.00", CurrencyUtil.format(new BigDecimal("7"), "BDT"));
        assertEquals("£3.20", CurrencyUtil.format(new BigDecimal("3.2"), "GBP"));
        assertEquals("$120.00", CurrencyUtil.format(new BigDecimal("120"), "USD"));
        assertEquals("€0.99", CurrencyUtil.format(new BigDecimal("0.99"), "EUR"));
        assertEquals("₹45.50", CurrencyUtil.format(new BigDecimal("45.5"), "INR"));
        assertEquals("Rs 12.00", CurrencyUtil.format(new BigDecimal("12"), "PKR"));
        assertEquals("other codes are written out", "MYR 12.50", CurrencyUtil.format(new BigDecimal("12.5"), "MYR"));
        assertEquals("grouped", "Tk 3,978.00", CurrencyUtil.format(new BigDecimal("3978"), "BDT"));
    }

    @Test
    public void yenHasNoDecimalsAndBahrainiDinarThree() {
        assertEquals("¥450", CurrencyUtil.format(new BigDecimal("450"), "JPY"));
        assertEquals("rounded half up", "¥451", CurrencyUtil.format(new BigDecimal("450.5"), "JPY"));
        assertEquals("BHD 1.250", CurrencyUtil.format(new BigDecimal("1.25"), "BHD"));
        assertEquals(0, CurrencyUtil.fractionDigits("JPY"));
        assertEquals(3, CurrencyUtil.fractionDigits("BHD"));
        assertEquals(2, CurrencyUtil.fractionDigits("BDT"));
        assertTrue(CurrencyUtil.hasAllowedDecimals(new BigDecimal("1.20"), "BDT"));
        assertFalse(CurrencyUtil.hasAllowedDecimals(new BigDecimal("1.205"), "BDT"));
        assertFalse(CurrencyUtil.hasAllowedDecimals(new BigDecimal("450.5"), "JPY"));
        assertTrue("450.00 is still 450", CurrencyUtil.hasAllowedDecimals(new BigDecimal("450.00"), "JPY"));
        assertTrue(CurrencyUtil.hasAllowedDecimals(new BigDecimal("1.255"), "BHD"));
    }

    @Test
    public void currencyOfACountry() {
        assertEquals("BDT", CurrencyUtil.currencyForCountry("BD"));
        assertEquals("GBP", CurrencyUtil.currencyForCountry("GB"));
        assertEquals("JPY", CurrencyUtil.currencyForCountry("JP"));
        assertEquals("INR", CurrencyUtil.currencyForCountry("in"));
        assertEquals("unknown → USD", "USD", CurrencyUtil.currencyForCountry("ZZ"));
        assertEquals("USD", CurrencyUtil.currencyForCountry(null));
    }

    @Test
    public void listStartsWithTheOwnCurrencyThenTheCommonOnes() {
        List<String> codes = CurrencyUtil.allCurrencyCodes("GBP");
        assertEquals("GBP", codes.get(0));
        assertEquals("BDT", codes.get(1));
        assertEquals("USD", codes.get(2));
        assertEquals("EUR", codes.get(3));
        assertEquals("INR", codes.get(4));
        assertEquals("no duplicates", codes.size(), new java.util.HashSet<String>(codes).size());
        assertTrue(codes.size() > 100);
        assertEquals("BDT – Bangladeshi Taka", CurrencyUtil.label("BDT"));
        assertEquals("BDT", CurrencyUtil.codeOf("BDT – Bangladeshi Taka"));
    }

    @Test
    public void totalsPerCurrencyAreNeverAddedTogether() {
        Map<String, BigDecimal> totals = new LinkedHashMap<String, BigDecimal>();
        CurrencyUtil.addTo(totals, "USD", new BigDecimal("100"));
        CurrencyUtil.addTo(totals, "BDT", new BigDecimal("3000"));
        CurrencyUtil.addTo(totals, "USD", new BigDecimal("20"));
        CurrencyUtil.addTo(totals, "BDT", new BigDecimal("978"));
        assertEquals("Tk 3,978.00 · $120.00", CurrencyUtil.formatTotals(totals));
    }

    @Test
    public void parsesAmountsAndApproximations() {
        assertEquals(new BigDecimal("1.20"), CurrencyUtil.parseAmount(" 1.20 "));
        assertEquals(new BigDecimal("1250.5"), CurrencyUtil.parseAmount("1,250.5"));
        assertNull(CurrencyUtil.parseAmount("abc"));
        assertNull(CurrencyUtil.parseAmount("-3"));
        assertEquals("≈ amounts from 10 are whole", "Tk 480", CurrencyUtil.formatApprox(new BigDecimal("479.6"), "BDT"));
        assertEquals("£3.20", CurrencyUtil.formatApprox(new BigDecimal("3.2"), "GBP"));
        assertEquals("Tk 14.00", CurrencyUtil.format(CurrencyUtil.times(new BigDecimal("7"), 2, "BDT"), "BDT"));
        assertEquals("REAL from the database", new BigDecimal("1.20"), CurrencyUtil.fromDb(1.2, "BDT"));
    }
}
