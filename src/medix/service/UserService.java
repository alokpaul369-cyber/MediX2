package medix.service;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import medix.dao.AreaDao;
import medix.dao.PasswordResetDao;
import medix.dao.UserDao;
import medix.engine.GeoUtil;
import medix.model.Area;
import medix.model.NotificationType;
import medix.model.Place;
import medix.model.Role;
import medix.model.User;
import medix.model.UserDetails;
import medix.util.CurrencyUtil;
import medix.util.DBConnection;
import medix.util.PasswordUtil;
import medix.util.Session;
import medix.util.ValidationUtil;

/**
 * Accounts: the admin Users screen (list, disable / enable) and the logged-in user's own
 * profile (name, phone, area, password).
 */
public class UserService {

    private final UserDao userDao = new UserDao();
    private final LocationService locationService = new LocationService();
    private final PasswordResetDao passwordResetDao = new PasswordResetDao();
    private final AuditService auditService = new AuditService();
    private final NotificationService notificationService = new NotificationService();

    // ---------------------------------------------------------------- admin

    /** Users with the role (null = all) whose name or email contains the text. */
    public List<UserDetails> getUsers(Role role, String text) throws ServiceException, SQLException {
        requireAdmin();
        return userDao.findDetails(role, text);
    }

    /**
     * Disables (cannot log in) or enables an account. An admin cannot disable themself
     * or the last active admin.
     */
    public void setActive(int userId, boolean active) throws ServiceException, SQLException {
        requireAdmin();
        User u = userDao.findById(userId);
        if (u == null) {
            throw new ServiceException("This user no longer exists.");
        }
        if (!active && Session.getUserId() != null && Session.getUserId() == userId) {
            throw new ServiceException("You cannot disable your own account.");
        }
        if (!active && u.getRole() == Role.ADMIN && u.isActive() && userDao.countActiveByRole(Role.ADMIN) <= 1) {
            throw new ServiceException("The last active admin cannot be disabled.");
        }
        if (!userDao.setActive(userId, active)) {
            throw new ServiceException(u.getFullName() + " is already " + (active ? "enabled." : "disabled."));
        }
        if (active) {
            notificationService.notifyUser(userId, NotificationType.INFO, "Your account was enabled again by the admin.");
        }
        auditService.log(active ? "USER_ENABLED" : "USER_DISABLED", "users", userId,
                u.getFullName() + " (" + u.getEmail() + ", " + u.getRole().name() + ")");
    }

    // ---------------------------------------------------------------- own profile

    /** The logged-in user, read fresh from the database. */
    public User getMe() throws ServiceException, SQLException {
        User u = userDao.findById(requireLogin());
        if (u == null) {
            throw new ServiceException("Your account no longer exists.");
        }
        return u;
    }

    // ---------------------------------------------------------------- display currency (Fix 3)

    /** The currency of the user's country (from the saved place); BDT when unknown. */
    public String countryCurrency(User u) {
        if (u != null && u.getAreaId() != null) {
            try {
                Area a = new AreaDao().findById(u.getAreaId());
                if (a != null && a.getCountryCode() != null) {
                    return CurrencyUtil.currencyForCountry(a.getCountryCode());
                }
            } catch (SQLException e) {
                medix.util.ErrorLog.log("Could not read the user's country", e);
            }
        }
        return CurrencyUtil.BDT;
    }

    /** The currency prices are converted to for this user: preferred_currency, else the country's. */
    public String displayCurrency(User u) {
        if (u != null && CurrencyUtil.isValidCode(u.getPreferredCurrency())) {
            return u.getPreferredCurrency();
        }
        return countryCurrency(u);
    }

    /** Same for the logged-in user (BDT when nobody is logged in). */
    public String myDisplayCurrency() {
        return displayCurrency(Session.getUser());
    }

    /** Saves name, phone and home location (rounded to about 1 km) of the logged-in user (Session is refreshed). */
    public void updateMyProfile(String fullName, String phone, Place location) throws ServiceException, SQLException {
        User me = Session.getUser();
        updateMyProfile(fullName, phone, location, me == null ? null : me.getPreferredCurrency());
    }

    /**
     * Same plus the display currency (ISO code; null = the currency of the user's country, which is
     * also stored as null so it follows a new location).
     */
    public void updateMyProfile(String fullName, String phone, Place location, String preferredCurrency)
            throws ServiceException, SQLException {
        if (preferredCurrency != null && !CurrencyUtil.isValidCode(preferredCurrency)) {
            throw new ServiceException("Choose a currency from the list.");
        }
        int me = requireLogin();
        String name = ValidationUtil.trimToNull(fullName);
        if (name == null) {
            throw new ServiceException("Name is required.");
        }
        if (name.length() > 80) {
            throw new ServiceException("Please keep the name under 80 characters.");
        }
        if (!ValidationUtil.isValidPhone(phone)) {
            throw new ServiceException(ValidationUtil.MSG_PHONE);
        }
        if (location == null) {
            throw new ServiceException("Choose your location.");
        }
        Place saved = locationService.save(location);
        userDao.updateProfile(me, name, ValidationUtil.normalizePhone(phone), saved.getSavedId(),
                GeoUtil.round2(saved.getLatitude()), GeoUtil.round2(saved.getLongitude()), saved.getShortName());
        String countryDefault = saved.getCountryCode() == null ? CurrencyUtil.BDT
                : CurrencyUtil.currencyForCountry(saved.getCountryCode());
        String keep = preferredCurrency == null || preferredCurrency.equalsIgnoreCase(countryDefault) ? null
                : preferredCurrency.toUpperCase(java.util.Locale.ROOT);
        userDao.updatePreferredCurrency(me, keep);
        refreshSession(me);
        auditService.log("PROFILE_UPDATED", "users", me, name);
    }

    /** Changes the logged-in user's password after checking the current one. */
    public void changeMyPassword(String current, String newPassword, String confirm) throws ServiceException, SQLException {
        User u = getMe();
        if (current == null || current.isEmpty() || !PasswordUtil.verify(current, u.getSalt(), u.getPasswordHash())) {
            throw new ServiceException("The current password is not correct.");
        }
        if (!ValidationUtil.isValidPassword(newPassword)) {
            throw new ServiceException("The new password must be at least " + ValidationUtil.MIN_PASSWORD_LENGTH
                    + " characters.");
        }
        if (!newPassword.equals(confirm)) {
            throw new ServiceException("The new passwords do not match.");
        }
        if (newPassword.equals(current)) {
            throw new ServiceException("The new password is the same as the current one.");
        }
        String salt = PasswordUtil.generateSalt();
        userDao.updatePassword(u.getId(), PasswordUtil.hash(newPassword, salt), salt);
        refreshSession(u.getId());
        auditService.log("PASSWORD_CHANGED", "users", u.getId(), null);
    }

    // ---------------------------------------------------------------- admin reset + forced change

    /** Letters and digits that cannot be confused (no 0/O, 1/l/I). */
    private static final String TEMP_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    public static final int TEMP_PASSWORD_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Admin only (backup when e-mail does not work): sets a random 8-character temporary password,
     * must_change_password = 1, cancels open reset codes and writes ADMIN_PASSWORD_RESET. Returns the
     * temporary password, shown once to the admin. An admin resets their own password in Profile.
     */
    public String adminResetPassword(int userId) throws ServiceException, SQLException {
        requireAdmin();
        User u = userDao.findById(userId);
        if (u == null) {
            throw new ServiceException("This user no longer exists.");
        }
        if (Session.getUserId() != null && Session.getUserId() == userId) {
            throw new ServiceException("You cannot reset your own password here. Use Forgot password? on the "
                    + "login screen instead.");
        }
        String temporary = temporaryPassword();
        String salt = PasswordUtil.generateSalt();
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                userDao.updatePassword(con, userId, PasswordUtil.hash(temporary, salt), salt, true);
                passwordResetDao.useAll(con, userId);
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        auditService.log("ADMIN_PASSWORD_RESET", "users", userId,
                u.getFullName() + " (" + u.getEmail() + ") must choose a new password at the next login");
        return temporary;
    }

    /** 8 random characters with at least one letter and one digit. */
    static String temporaryPassword() {
        while (true) {
            StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
            for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
                sb.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
            }
            String s = sb.toString();
            if (s.matches(".*\\d.*") && s.matches(".*[A-Za-z].*")) {
                return s;
            }
        }
    }

    /**
     * ChangePasswordFrame after an admin reset: the logged-in user (must_change_password = 1) chooses
     * a new password; it must differ from the temporary one. Clears the flag and refreshes Session.
     */
    public void completeForcedChange(String newPassword, String confirm) throws ServiceException, SQLException {
        User u = getMe();
        if (!ValidationUtil.isValidPassword(newPassword)) {
            throw new ServiceException("The new password must be at least " + ValidationUtil.MIN_PASSWORD_LENGTH
                    + " characters.");
        }
        if (!newPassword.equals(confirm)) {
            throw new ServiceException("The new passwords do not match.");
        }
        if (PasswordUtil.verify(newPassword, u.getSalt(), u.getPasswordHash())) {
            throw new ServiceException("Choose a new password, not the temporary one.");
        }
        String salt = PasswordUtil.generateSalt();
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                userDao.updatePassword(con, u.getId(), PasswordUtil.hash(newPassword, salt), salt, false);
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        refreshSession(u.getId());
        auditService.log("PASSWORD_CHANGED", "users", u.getId(), "New password after an admin reset");
    }

    private void refreshSession(int userId) throws SQLException {
        User fresh = userDao.findById(userId);
        if (fresh != null) {
            Session.login(fresh, Session.getPharmacy());
        }
    }

    private static int requireLogin() throws ServiceException {
        Integer me = Session.getUserId();
        if (me == null) {
            throw new ServiceException("Please log in first.");
        }
        return me;
    }

    private static void requireAdmin() throws ServiceException {
        if (!Session.hasRole(Role.ADMIN)) {
            throw new ServiceException("Only an admin can do this.");
        }
    }
}
