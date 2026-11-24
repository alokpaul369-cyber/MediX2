package medix.util;

import java.util.regex.Pattern;

/**
 * Input checks shared by the forms and the services.
 */
public final class ValidationUtil {

    public static final int MIN_PASSWORD_LENGTH = 6;

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    /** Bangladeshi mobile: 01 + operator digit 3-9 + 8 digits. */
    private static final Pattern BD_MOBILE = Pattern.compile("^01[3-9]\\d{8}$");
    /** International: + country code and number, 8-15 digits in total (E.164). */
    private static final Pattern INTERNATIONAL = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    /** Typed coordinate: up to 3 digits before and 6 after the decimal point. */
    private static final Pattern COORDINATE = Pattern.compile("^[+-]?\\d{1,3}(\\.\\d{1,6})?$");
    /** Shown when a phone number is not accepted. */
    public static final String MSG_PHONE = "Enter a mobile number like 01712345678, or an international number "
            + "like +44 7700 900123.";
    private static final Pattern LICENSE_NO = Pattern.compile("^[A-Za-z0-9-]{6,30}$");

    private ValidationUtil() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Trimmed text, or null if blank. */
    public static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL.matcher(email.trim()).matches();
    }

    /** Lower-case, trimmed email for storing and comparing. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /** 01XXXXXXXXX; dashes and spaces are allowed and ignored, e.g. 01712-345678. */
    public static boolean isValidBdMobile(String phone) {
        return phone != null && BD_MOBILE.matcher(normalizePhone(phone)).matches();
    }

    /**
     * A Bangladeshi mobile (01XXXXXXXXX) or an international number +<country code><number> with
     * 8-15 digits, e.g. +44 7700 900123 or +1 (212) 555-0100. Spaces, dashes, dots and brackets are
     * allowed and removed before saving (normalizePhone).
     */
    public static boolean isValidPhone(String phone) {
        if (phone == null) {
            return false;
        }
        String p = normalizePhone(phone);
        return BD_MOBILE.matcher(p).matches() || INTERNATIONAL.matcher(p).matches();
    }

    /** Phone without spaces, dashes, dots and brackets, e.g. "01712345678", "+447700900123". */
    public static String normalizePhone(String phone) {
        return phone == null ? null : phone.replaceAll("[\\s\\-.()]", "").trim();
    }

    /**
     * A typed latitude (-90..90, latitude true) or longitude (-180..180) with up to 6 decimals; null
     * when the text is not such a number. A comma is accepted as the decimal point.
     */
    public static Double parseCoordinate(String text, boolean latitude) {
        if (isBlank(text)) {
            return null;
        }
        String t = text.trim().replace(',', '.');
        if (!COORDINATE.matcher(t).matches()) {
            return null;
        }
        double v = Double.parseDouble(t);
        double max = latitude ? 90 : 180;
        return v >= -max && v <= max ? v : null;
    }

    /**
     * Privacy mask: "01819123552" → "01819-…552" (first 5 and last 3 digits). Short or empty
     * numbers become "…".
     */
    public static String maskPhone(String phone) {
        String p = normalizePhone(phone);
        if (p == null || p.length() < 9) {
            return "…";
        }
        return p.substring(0, 5) + "-…" + p.substring(p.length() - 3);
    }

    public static boolean isValidLicenseNo(String licenseNo) {
        return licenseNo != null && LICENSE_NO.matcher(licenseNo.trim()).matches();
    }

    public static boolean isValidPassword(String password) {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH;
    }

    /** Parses a decimal number; returns null for blank or invalid text. */
    public static Double parseDouble(String text) {
        if (isBlank(text)) {
            return null;
        }
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
