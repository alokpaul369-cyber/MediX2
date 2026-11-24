package medix.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Currencies (ISO 4217 codes such as "BDT", "GBP") with java.util.Currency (Java 8).
 *
 * Money is always a BigDecimal in code (never float or double); the database keeps REAL columns
 * next to a currency column, and {@link #fromDb(double, String)} turns a stored value back into an
 * exact amount rounded to the currency's decimals (JPY 0, BDT 2, BHD 3).
 */
public final class CurrencyUtil {

    public static final String BDT = "BDT";
    public static final String USD = "USD";
    /** Shown right after the pharmacy's / user's own currency in every currency list. */
    public static final String[] COMMON = {BDT, USD, "EUR", "GBP", "INR"};

    private static final Map<String, String> SYMBOLS = new LinkedHashMap<String, String>();

    static {
        SYMBOLS.put(BDT, "Tk ");
        SYMBOLS.put(USD, "$");
        SYMBOLS.put("EUR", "€");
        SYMBOLS.put("GBP", "£");
        SYMBOLS.put("INR", "₹");
        SYMBOLS.put("JPY", "¥");
        SYMBOLS.put("PKR", "Rs ");
    }

    private CurrencyUtil() {
    }

    // ---------------------------------------------------------------- lists

    /**
     * Every currency Java knows, as codes: the given ones first (e.g. the pharmacy's currency),
     * then BDT, USD, EUR, GBP, INR, then all others sorted by code. Null / unknown codes are skipped.
     */
    public static List<String> allCurrencyCodes(String... first) {
        Set<String> codes = new LinkedHashSet<String>();
        if (first != null) {
            for (String c : first) {
                if (isValidCode(c)) {
                    codes.add(c.toUpperCase(Locale.ROOT));
                }
            }
        }
        for (String c : COMMON) {
            codes.add(c);
        }
        TreeMap<String, Currency> sorted = new TreeMap<String, Currency>();
        for (Currency c : Currency.getAvailableCurrencies()) {
            sorted.put(c.getCurrencyCode(), c);
        }
        codes.addAll(sorted.keySet());
        return new ArrayList<String>(codes);
    }

    /** Same order as {@link #allCurrencyCodes}, as labels "BDT – Bangladeshi Taka" (for combo boxes). */
    public static List<String> allCurrencies(String... first) {
        List<String> labels = new ArrayList<String>();
        for (String code : allCurrencyCodes(first)) {
            labels.add(label(code));
        }
        return labels;
    }

    /** "BDT – Bangladeshi Taka" (English name from java.util.Currency). */
    public static String label(String code) {
        String c = code == null ? "" : code.toUpperCase(Locale.ROOT);
        try {
            return c + " – " + Currency.getInstance(c).getDisplayName(Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            return c;
        }
    }

    /** The code at the start of a {@link #label} ("BDT – Bangladeshi Taka" → "BDT"); null for null. */
    public static String codeOf(String labelOrCode) {
        if (labelOrCode == null) {
            return null;
        }
        String t = labelOrCode.trim();
        return t.length() >= 3 ? t.substring(0, 3).toUpperCase(Locale.ROOT) : t.toUpperCase(Locale.ROOT);
    }

    public static boolean isValidCode(String code) {
        if (code == null || code.trim().length() != 3) {
            return false;
        }
        try {
            Currency.getInstance(code.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The currency of a country (ISO 3166 code, "BD" → BDT, "GB" → GBP); USD when unknown. */
    public static String currencyForCountry(String countryCode) {
        if (countryCode == null || countryCode.trim().length() != 2) {
            return USD;
        }
        try {
            Currency c = Currency.getInstance(new Locale("", countryCode.trim().toUpperCase(Locale.ROOT)));
            return c == null ? USD : c.getCurrencyCode();
        } catch (IllegalArgumentException e) {
            return USD;
        }
    }

    // ---------------------------------------------------------------- amounts

    /** Decimals of the currency (JPY 0, BDT 2, BHD 3); 2 for codes without a fixed number. */
    public static int fractionDigits(String code) {
        try {
            int d = Currency.getInstance(code.toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
            return d < 0 ? 2 : d;
        } catch (IllegalArgumentException | NullPointerException e) {
            return 2;
        }
    }

    /** Rounded half-up to the currency's decimals. */
    public static BigDecimal round(BigDecimal amount, String code) {
        return amount.setScale(fractionDigits(code), RoundingMode.HALF_UP);
    }

    /** A REAL column value as an exact amount (e.g. 1.2 → 1.20 for BDT). */
    public static BigDecimal fromDb(double value, String code) {
        return round(BigDecimal.valueOf(value), code);
    }

    /** Nullable REAL column. */
    public static BigDecimal fromDb(Double value, String code) {
        return value == null ? null : fromDb(value.doubleValue(), code);
    }

    /** quantity × unit price, rounded to the currency. */
    public static BigDecimal times(BigDecimal unitPrice, int quantity, String code) {
        return round(unitPrice.multiply(BigDecimal.valueOf(quantity)), code);
    }

    /** "1.20" → 1.20, "1,250.5" → 1250.5; null when it is not a number. */
    public static BigDecimal parseAmount(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().replace(",", "");
        if (t.isEmpty() || !t.matches("\\d*(\\.\\d*)?") || t.equals(".")) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True when the amount has no more decimals than the currency allows (1.205 is wrong for BDT). */
    public static boolean hasAllowedDecimals(BigDecimal amount, String code) {
        return amount.stripTrailingZeros().scale() <= fractionDigits(code);
    }

    /** Amount for a text field: "1.20", "450" (JPY), no grouping. */
    public static String plain(BigDecimal amount, String code) {
        return round(amount, code).toPlainString();
    }

    // ---------------------------------------------------------------- formatting

    /** Short symbol: "Tk ", "$", "€", "£", "₹", "¥", "Rs ", otherwise the code and a space ("MYR "). */
    public static String symbol(String code) {
        String c = code == null ? "" : code.toUpperCase(Locale.ROOT);
        String s = SYMBOLS.get(c);
        return s != null ? s : c + " ";
    }

    /** "Tk 7.00", "£3.20", "¥450", "BHD 1.250", "MYR 12.50" (grouped: "Tk 3,978.00"). */
    public static String format(BigDecimal amount, String code) {
        if (amount == null) {
            return "–";
        }
        return format(amount, code, fractionDigits(code));
    }

    /**
     * An approximate (converted) amount: whole units from 10 up ("≈ Tk 480", "≈ Tk 23"), otherwise
     * the currency's decimals ("≈ £3.20"). Callers add the "≈".
     */
    public static String formatApprox(BigDecimal amount, String code) {
        if (amount == null) {
            return "–";
        }
        int digits = amount.abs().compareTo(BigDecimal.TEN) >= 0 ? 0 : fractionDigits(code);
        return format(amount, code, digits);
    }

    private static String format(BigDecimal amount, String code, int digits) {
        BigDecimal r = amount.setScale(digits, RoundingMode.HALF_UP);
        DecimalFormat df = new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + zeros(digits),
                DecimalFormatSymbols.getInstance(Locale.US));
        String sign = r.signum() < 0 ? "-" : "";
        return sign + symbol(code) + df.format(r.abs());
    }

    private static String zeros(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('0');
        }
        return sb.toString();
    }

    /** Adds an amount to its currency's total (keeps the first-seen order). */
    public static void addTo(Map<String, BigDecimal> totals, String code, BigDecimal amount) {
        BigDecimal old = totals.get(code);
        totals.put(code, old == null ? amount : old.add(amount));
    }

    /**
     * Totals per currency, never added together: "Tk 3,978.00 · $120.00". BDT first, then the
     * others by code. "Tk 0.00" when there is nothing.
     */
    public static String formatTotals(Map<String, BigDecimal> totals) {
        if (totals.isEmpty()) {
            return format(BigDecimal.ZERO, BDT);
        }
        TreeMap<String, BigDecimal> sorted = new TreeMap<String, BigDecimal>(totals);
        StringBuilder sb = new StringBuilder();
        if (sorted.containsKey(BDT)) {
            sb.append(format(sorted.remove(BDT), BDT));
        }
        for (Map.Entry<String, BigDecimal> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(format(e.getValue(), e.getKey()));
        }
        return sb.toString();
    }

    /**
     * "£6.40" or, when it is not the patient's currency, "£6.40 (≈ Tk 960)"; converted may be null
     * (no exchange rate) and then only the own amount is shown.
     */
    public static String withApprox(BigDecimal amount, String code, BigDecimal converted, String patientCurrency) {
        String own = format(amount, code);
        if (code == null || code.equals(patientCurrency) || converted == null) {
            return own;
        }
        return own + " (≈ " + formatApprox(converted, patientCurrency) + ")";
    }
}
