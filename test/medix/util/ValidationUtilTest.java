package medix.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ValidationUtilTest {

    @Test
    public void masksPhoneUntilConfirmed() {
        assertEquals("01819-…552", ValidationUtil.maskPhone("01819123552"));
        assertEquals("01819-…552", ValidationUtil.maskPhone("01819-123552"));
        assertEquals("…", ValidationUtil.maskPhone(null));
        assertEquals("…", ValidationUtil.maskPhone("123"));
    }

    @Test
    public void bangladeshiMobileNumbers() {
        assertTrue(ValidationUtil.isValidBdMobile("01712345678"));
        assertTrue(ValidationUtil.isValidBdMobile("01712-345678"));
        assertFalse(ValidationUtil.isValidBdMobile("01212345678"));
        assertFalse(ValidationUtil.isValidBdMobile("0171234567"));
    }

    @Test
    public void phoneNumbersFromAnyCountry() {
        assertTrue(ValidationUtil.isValidPhone("01712345678"));
        assertTrue(ValidationUtil.isValidPhone("01712-345678"));
        assertTrue(ValidationUtil.isValidPhone("+44 7700 900123"));       // UK
        assertTrue(ValidationUtil.isValidPhone("+1 (212) 555-0100"));     // USA
        assertTrue(ValidationUtil.isValidPhone("+880 1712-345678"));      // Bangladesh, international
        assertTrue(ValidationUtil.isValidPhone("+6791234567"));           // Fiji, 10 digits
        assertFalse(ValidationUtil.isValidPhone("+12345"));                // too short
        assertFalse(ValidationUtil.isValidPhone("+1234567890123456"));     // 16 digits
        assertFalse(ValidationUtil.isValidPhone("+0123456789"));           // no country code starts with 0
        assertFalse(ValidationUtil.isValidPhone("7700 900123"));           // international needs +
        assertFalse(ValidationUtil.isValidPhone("+44 7700 9001x3"));
        assertFalse(ValidationUtil.isValidPhone(null));
        assertEquals("+447700900123", ValidationUtil.normalizePhone(" +44 7700-900.123 "));
        assertEquals("+12125550100", ValidationUtil.normalizePhone("+1 (212) 555-0100"));
    }

    @Test
    public void typedCoordinates() {
        assertEquals(23.7461, ValidationUtil.parseCoordinate("23.7461", true), 1e-9);
        assertEquals(-0.127612, ValidationUtil.parseCoordinate("-0.127612", false), 1e-9);
        assertEquals(51.5072, ValidationUtil.parseCoordinate(" 51,5072 ", true), 1e-9);  // comma as decimal point
        assertEquals(-180.0, ValidationUtil.parseCoordinate("-180", false), 1e-9);
        assertEquals(90.0, ValidationUtil.parseCoordinate("+90", true), 1e-9);
        assertEquals(null, ValidationUtil.parseCoordinate("90.5", true));          // latitude out of range
        assertEquals(null, ValidationUtil.parseCoordinate("181", false));          // longitude out of range
        assertEquals(null, ValidationUtil.parseCoordinate("23.1234567", true));    // 7 decimals
        assertEquals(null, ValidationUtil.parseCoordinate("abc", true));
        assertEquals(null, ValidationUtil.parseCoordinate("", false));
        assertEquals(null, ValidationUtil.parseCoordinate("1e2", false));
    }
}
