package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import medix.dao.AreaDao;
import medix.dao.PharmacyDao;
import medix.dao.UserDao;
import medix.model.Area;
import medix.model.Pharmacy;
import medix.model.PharmacyStatus;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.PasswordUtil;
import medix.util.Session;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * "Forgot password?" in DEMO MODE (no data/mail.properties in the temporary folder, so no e-mail is
 * sent) with a clock the test moves forward; plus the admin reset / must_change_password flow.
 */
public class PasswordResetServiceTest {

    private static final String PASSWORD = "secret1";

    private static Area dhanmondi;
    private static int seq;

    /** A clock that only moves when the test says so. */
    private static final class TestClock extends Clock {
        long millis = System.currentTimeMillis();

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        void plusSeconds(long seconds) {
            millis += seconds * 1000;
        }
    }

    private TestClock clock;
    private PasswordResetService service;
    private User user;

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
        dhanmondi = new AreaDao().findByName("Dhanmondi");
    }

    @Before
    public void freshUserAndClock() throws Exception {
        PasswordResetService.clearRequestLimits();
        // the database limits look at real created_at times: start each test a day later
        clock = new TestClock();
        clock.millis += 86400000L * (++seq);
        service = new PasswordResetService(clock, new EmailService());
        user = new AuthService().registerPatient("Reset Test " + seq, "reset" + seq + "@test.com", "01712345678",
                PASSWORD, dhanmondi.getId());
        Session.logout();
    }

    @Test
    public void demoModeShowsTheCodeButStoresOnlyASaltedHash() throws Exception {
        assertTrue("no data/mail.properties in the test folder", service.isDemoMode());
        PasswordResetService.Request r = service.requestCode(user.getEmail());
        assertEquals(PasswordResetService.MSG_SENT, r.message);
        assertTrue(r.demoMode);
        assertTrue(r.demoCode.matches("\\d{6}"));
        assertEquals(1, count("SELECT COUNT(*) FROM password_resets WHERE user_id = " + user.getId()
                + " AND sent_via = 'DEMO' AND used = 0"));
        String hash = text("SELECT code_hash FROM password_resets WHERE user_id = " + user.getId());
        String salt = text("SELECT code_salt FROM password_resets WHERE user_id = " + user.getId());
        assertNotEquals("never the plain code", r.demoCode, hash);
        assertFalse(hash.contains(r.demoCode));
        assertTrue(PasswordUtil.verify(r.demoCode, salt, hash));
        assertTrue("expires 15 minutes later", text("SELECT expires_at FROM password_resets WHERE user_id = "
                + user.getId()).compareTo(text("SELECT created_at FROM password_resets WHERE user_id = " + user.getId())) > 0);
    }

    @Test
    public void theRightCodeChangesThePasswordInOneGo() throws Exception {
        String code = service.requestCode(user.getEmail()).demoCode;
        service.resetPassword(user.getEmail(), code, "brandnew1", "brandnew1");
        assertEquals(user.getId(), new AuthService().login(user.getEmail(), "brandnew1").getId());
        Session.logout();
        try {
            new AuthService().login(user.getEmail(), PASSWORD);
            fail("the old password no longer works");
        } catch (AuthException expected) {
            assertEquals(AuthService.MSG_WRONG_LOGIN, expected.getMessage());
        }
        assertEquals("code used", 0, count("SELECT COUNT(*) FROM password_resets WHERE used = 0 AND user_id = " + user.getId()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_RESET' AND user_id = " + user.getId()));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE user_id = " + user.getId()
                + " AND message LIKE 'Your password was changed on %If this wasn''t you, contact the admin.'"));
        try {
            service.resetPassword(user.getEmail(), code, "another1", "another1");
            fail("a code works only once");
        } catch (ServiceException expected) {
            assertEquals(PasswordResetService.MSG_NO_CODE, expected.getMessage());
        }
    }

    @Test
    public void unknownAndDisabledEmailsGetTheSameAnswerAndNoCode() throws Exception {
        PasswordResetService.Request unknown = service.requestCode("nobody-" + seq + "@test.com");
        assertEquals(PasswordResetService.MSG_SENT, unknown.message);
        assertNull(unknown.demoCode);
        new UserDao().setActive(user.getId(), false);
        PasswordResetService.Request disabled = service.requestCode(user.getEmail());
        assertEquals(PasswordResetService.MSG_SENT, disabled.message);
        assertNull("a disabled account never gets a code", disabled.demoCode);
        assertEquals(0, count("SELECT COUNT(*) FROM password_resets WHERE user_id = " + user.getId()));
        // the limits apply to unknown addresses too, so they cannot be told apart
        try {
            service.requestCode("nobody-" + seq + "@test.com");
            fail("60-second limit");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().startsWith("Please wait"));
        }
    }

    @Test
    public void codesExpireAfter15Minutes() throws Exception {
        String code = service.requestCode(user.getEmail()).demoCode;
        clock.plusSeconds(15 * 60 - 5);
        // still valid just before: a wrong password check proves the code was accepted
        try {
            service.resetPassword(user.getEmail(), code, PASSWORD, PASSWORD);
            fail("the old password is refused");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().startsWith("Choose a new password"));
        }
        clock.plusSeconds(10);
        try {
            service.resetPassword(user.getEmail(), code, "brandnew1", "brandnew1");
            fail("expired");
        } catch (ServiceException expected) {
            assertEquals(PasswordResetService.MSG_EXPIRED, expected.getMessage());
        }
    }

    @Test
    public void fiveWrongCodesCancelTheCode() throws Exception {
        String code = service.requestCode(user.getEmail()).demoCode;
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 4; i++) {
            try {
                service.resetPassword(user.getEmail(), wrong, "brandnew1", "brandnew1");
                fail("wrong code");
            } catch (ServiceException expected) {
                int left = 5 - i;
                assertEquals("The code is not correct. " + left + (left == 1 ? " try" : " tries") + " left.",
                        expected.getMessage());
            }
        }
        try {
            service.resetPassword(user.getEmail(), wrong, "brandnew1", "brandnew1");
            fail("fifth wrong code");
        } catch (ServiceException expected) {
            assertEquals(PasswordResetService.MSG_TOO_MANY_WRONG, expected.getMessage());
        }
        try {
            service.resetPassword(user.getEmail(), code, "brandnew1", "brandnew1");
            fail("even the right code is cancelled now");
        } catch (ServiceException expected) {
            assertEquals(PasswordResetService.MSG_TOO_MANY_WRONG, expected.getMessage());
        }
        assertEquals(5, count("SELECT attempts FROM password_resets WHERE user_id = " + user.getId()));
    }

    @Test
    public void oneCodePerMinuteAndThreePerHour() throws Exception {
        String first = service.requestCode(user.getEmail()).demoCode;
        clock.plusSeconds(30);
        try {
            service.requestCode(user.getEmail());
            fail("60-second limit");
        } catch (ServiceException expected) {
            assertEquals("Please wait 30 seconds before asking for another code.", expected.getMessage());
        }
        clock.plusSeconds(31);
        String second = service.requestCode(user.getEmail()).demoCode;
        assertEquals("a new code cancels the older one", 1, count("SELECT COUNT(*) FROM password_resets WHERE used = 0 "
                + "AND user_id = " + user.getId()));
        if (!first.equals(second)) {
            try {
                service.resetPassword(user.getEmail(), first, "brandnew1", "brandnew1");
                fail("the first code was replaced");
            } catch (ServiceException expected) {
                assertTrue(expected.getMessage().startsWith("The code is not correct"));
            }
        }
        clock.plusSeconds(61);
        service.requestCode(user.getEmail());
        clock.plusSeconds(61);
        try {
            service.requestCode(user.getEmail());
            fail("3 per hour");
        } catch (ServiceException expected) {
            assertEquals(PasswordResetService.MSG_HOURLY, expected.getMessage());
        }
        clock.plusSeconds(3600);
        assertTrue(service.requestCode(user.getEmail()).demoCode.matches("\\d{6}"));
    }

    @Test
    public void passwordRulesAreChecked() throws Exception {
        String code = service.requestCode(user.getEmail()).demoCode;
        try {
            service.resetPassword(user.getEmail(), code, "short", "short");
            fail("too short");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("at least 6"));
        }
        try {
            service.resetPassword(user.getEmail(), code, "brandnew1", "brandnew2");
            fail("confirmation differs");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("do not match"));
        }
        try {
            service.resetPassword(user.getEmail(), "12a456", "brandnew1", "brandnew1");
            fail("not 6 digits");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("6-digit"));
        }
        assertEquals("rule failures do not count as wrong codes", 0,
                count("SELECT attempts FROM password_resets WHERE user_id = " + user.getId()));
    }

    @Test
    public void adminResetForcesAPasswordChangeAtTheNextLogin() throws Exception {
        UserService users = new UserService();
        Session.login(new UserDao().findById(1), null); // seeded admin
        String temporary = users.adminResetPassword(user.getId());
        assertEquals(8, temporary.length());
        assertTrue(temporary.matches("[A-Za-z0-9]{8}") && temporary.matches(".*\\d.*"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'ADMIN_PASSWORD_RESET' AND user_id = 1 "
                + "AND entity_id = " + user.getId()));
        Session.logout();

        User loggedIn = new AuthService().login(user.getEmail(), temporary);
        assertTrue("LoginFrame opens ChangePasswordFrame", loggedIn.isMustChangePassword());
        try {
            users.completeForcedChange(temporary, temporary);
            fail("the temporary password cannot be kept");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("temporary"));
        }
        users.completeForcedChange("mychoice1", "mychoice1");
        assertFalse(new UserDao().findById(user.getId()).isMustChangePassword());
        assertFalse(Session.getUser().isMustChangePassword());
        Session.logout();
        assertFalse(new AuthService().login(user.getEmail(), "mychoice1").isMustChangePassword());
        Session.logout();

        Session.login(user, null);
        try {
            users.adminResetPassword(1);
            fail("only an admin resets passwords");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("admin"));
        }
        Session.logout();
    }

    @Test
    public void pharmacyLoginMessagesStillApplyAfterAReset() throws Exception {
        User owner = new User();
        owner.setFullName("Pending Owner " + seq);
        owner.setEmail("pending" + seq + "@test.com");
        owner.setPhone("01711111111");
        owner.setSalt("c2FsdA==");
        owner.setPasswordHash("x");
        owner.setRole(medix.model.Role.PHARMACY);
        owner.setActive(true);
        new UserDao().insert(owner);
        Pharmacy p = new Pharmacy();
        p.setOwnerId(owner.getId());
        p.setName("Pending Pharma " + seq);
        p.setLicenseNo("DL-PR-00" + seq);
        p.setAddress("Road 1, Dhanmondi");
        p.setAreaId(dhanmondi.getId());
        p.setOpenTime("08:00");
        p.setCloseTime("22:00");
        p.setStatus(PharmacyStatus.PENDING);
        new PharmacyDao().insert(p);

        Session.login(new UserDao().findById(1), null);
        String temporary = new UserService().adminResetPassword(owner.getId());
        Session.logout();
        try {
            new AuthService().login(owner.getEmail(), temporary);
            fail("a pending pharmacy still cannot log in");
        } catch (AuthException expected) {
            assertEquals(AuthService.MSG_PENDING, expected.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int count(String sql) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection c = DBConnection.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
