package medix.service;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import medix.dao.AuditLogDao;
import medix.dao.NotificationDao;
import medix.dao.PasswordResetDao;
import medix.dao.UserDao;
import medix.model.AuditLog;
import medix.model.Notification;
import medix.model.NotificationType;
import medix.model.PasswordReset;
import medix.model.ResetChannel;
import medix.model.User;
import medix.util.DBConnection;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.PasswordUtil;
import medix.util.ValidationUtil;

/**
 * "Forgot password?": a 6-digit code (SecureRandom) is sent by e-mail (EmailService) – or shown on
 * screen in DEMO MODE when e-mail is not set up – and only its salted SHA-256 hash is stored.
 * The code expires after 15 minutes and is cancelled after 5 wrong tries. At most 1 code per
 * 60 seconds and 3 per hour per e-mail address; a new code cancels the older ones.
 * The answer to a request is the same whether or not the e-mail belongs to an account.
 * Network and SMTP calls block: call requestCode from a SwingWorker.
 */
public class PasswordResetService {

    public static final int CODE_MINUTES = 15;
    public static final int MAX_ATTEMPTS = 5;
    public static final int MIN_SECONDS_BETWEEN = 60;
    public static final int MAX_PER_HOUR = 3;
    public static final String MSG_SENT = "If an account exists for this email, a 6-digit code has been sent.\n"
            + "It expires in " + CODE_MINUTES + " minutes.";
    public static final String MSG_SEND_FAILED = "Couldn't send the email. Try again later or ask an admin to reset "
            + "your password.";
    public static final String MSG_TOO_MANY_WRONG = "Too many wrong codes. Request a new code.";
    public static final String MSG_EXPIRED = "This code has expired. Request a new code.";
    public static final String MSG_NO_CODE = "This code is no longer valid. Request a new code.";
    public static final String MSG_HOURLY = "You asked for " + MAX_PER_HOUR + " codes in the last hour. "
            + "Please try again later.";
    public static final String EMAIL_SUBJECT = "MediX password reset code";

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Request times per e-mail address (known or not), so the limits look the same for every address. */
    private static final Map<String, Deque<Long>> REQUESTS = new HashMap<String, Deque<Long>>();

    private final Clock clock;
    private final EmailService emailService;
    private final UserDao userDao = new UserDao();
    private final PasswordResetDao resetDao = new PasswordResetDao();
    private final AuditLogDao auditLogDao = new AuditLogDao();
    private final NotificationDao notificationDao = new NotificationDao();
    private final AuditService auditService = new AuditService();

    /** What the screen shows after a request. demoCode is set only in demo mode for a real account. */
    public static final class Request {
        public final String message;
        public final boolean demoMode;
        public final String demoCode;

        Request(String message, boolean demoMode, String demoCode) {
            this.message = message;
            this.demoMode = demoMode;
            this.demoCode = demoCode;
        }
    }

    public PasswordResetService() {
        this(Clock.systemDefaultZone(), new EmailService());
    }

    /** Tests: a fixed / moving clock and an e-mail service (demo mode when it is not enabled). */
    PasswordResetService(Clock clock, EmailService emailService) {
        this.clock = clock;
        this.emailService = emailService;
    }

    /** True when codes are shown on screen because data/mail.properties is missing or disabled. */
    public boolean isDemoMode() {
        return !emailService.isEnabled();
    }

    // ---------------------------------------------------------------- request a code

    /**
     * Creates and sends a code. Unknown and disabled accounts get the same answer (MSG_SENT) but no
     * code. ServiceException for an invalid address, the 60-second / hourly limits and SMTP failures.
     */
    public Request requestCode(String email) throws ServiceException, SQLException {
        String address = ValidationUtil.normalizeEmail(email);
        if (!ValidationUtil.isValidEmail(address)) {
            throw new ServiceException("Enter a valid email address, like name@example.com.");
        }
        checkAndRecordLimits(address);
        boolean demo = isDemoMode();
        User user = userDao.findByEmail(address);
        if (user == null || !user.isActive()) {
            return new Request(MSG_SENT, demo, null); // no code, same answer
        }

        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
        PasswordReset reset = new PasswordReset();
        reset.setUserId(user.getId());
        reset.setCodeSalt(PasswordUtil.generateSalt());
        reset.setCodeHash(PasswordUtil.hash(code, reset.getCodeSalt()));
        reset.setCreatedAt(format(now()));
        reset.setExpiresAt(format(now() + CODE_MINUTES * 60000L));
        reset.setSentVia(demo ? ResetChannel.DEMO : ResetChannel.EMAIL);
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                // the database limits also hold after a restart
                PasswordReset newest = resetDao.findNewest(con, user.getId());
                if (newest != null && secondsSince(newest.getCreatedAt()) < MIN_SECONDS_BETWEEN) {
                    throw new ServiceException(waitMessage(MIN_SECONDS_BETWEEN - secondsSince(newest.getCreatedAt())));
                }
                if (resetDao.countSince(con, user.getId(), format(now() - 3600000L)) >= MAX_PER_HOUR) {
                    throw new ServiceException(MSG_HOURLY);
                }
                resetDao.useAll(con, user.getId()); // a new code cancels the older ones
                resetDao.insert(con, reset);
                con.commit();
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
        if (!demo) {
            try {
                emailService.send(user.getEmail(), EMAIL_SUBJECT, emailBody(user, code));
            } catch (Exception e) {
                ErrorLog.log("Password reset e-mail to user #" + user.getId() + " failed", e);
                try (Connection con = DBConnection.getConnection()) {
                    resetDao.markUsed(con, reset.getId()); // never valid: it was not delivered
                }
                throw new ServiceException(MSG_SEND_FAILED);
            }
        }
        auditService.logAs(user.getId(), "PASSWORD_RESET_REQUESTED", "users", user.getId(),
                "Code sent via " + reset.getSentVia().name());
        return new Request(MSG_SENT, demo, demo ? code : null);
    }

    static String emailBody(User user, String code) {
        return "Hello " + user.getFullName() + ",\n\n"
                + "Your MediX password reset code is:\n\n"
                + "    " + code + "\n\n"
                + "It expires in " + CODE_MINUTES + " minutes.\n\n"
                + "If you didn't ask for this, ignore this email. Your password stays the same.\n\n"
                + "– MediX";
    }

    /** 1 per 60 seconds and 3 per hour for every address, known or not (no account enumeration). */
    private void checkAndRecordLimits(String address) throws ServiceException {
        long now = now();
        synchronized (REQUESTS) {
            Deque<Long> times = REQUESTS.get(address);
            if (times == null) {
                times = new ArrayDeque<Long>();
                REQUESTS.put(address, times);
            }
            while (!times.isEmpty() && now - times.peekFirst() >= 3600000L) {
                times.pollFirst();
            }
            if (!times.isEmpty() && now - times.peekLast() < MIN_SECONDS_BETWEEN * 1000L) {
                throw new ServiceException(waitMessage(MIN_SECONDS_BETWEEN - (now - times.peekLast()) / 1000));
            }
            if (times.size() >= MAX_PER_HOUR) {
                throw new ServiceException(MSG_HOURLY);
            }
            times.addLast(now);
        }
    }

    private static String waitMessage(long seconds) {
        long s = Math.max(1, seconds);
        return "Please wait " + s + (s == 1 ? " second" : " seconds") + " before asking for another code.";
    }

    /** Tests only: forget the in-memory request times. */
    static void clearRequestLimits() {
        synchronized (REQUESTS) {
            REQUESTS.clear();
        }
    }

    // ---------------------------------------------------------------- reset the password

    /**
     * Checks the code against the user's latest unused code and sets the new password. In ONE
     * transaction: new salt + hash, code used, must_change_password = 0, audit PASSWORD_RESET and a
     * notification. A wrong code counts a try (cancelled at 5).
     */
    public void resetPassword(String email, String code, String newPassword, String confirm)
            throws ServiceException, SQLException {
        String c = code == null ? "" : code.trim().replace(" ", "");
        if (!c.matches("\\d{6}")) {
            throw new ServiceException("Enter the 6-digit code from the email.");
        }
        if (!ValidationUtil.isValidPassword(newPassword)) {
            throw new ServiceException("The new password must be at least " + ValidationUtil.MIN_PASSWORD_LENGTH
                    + " characters.");
        }
        if (!newPassword.equals(confirm)) {
            throw new ServiceException("The new passwords do not match.");
        }
        User user = userDao.findByEmail(ValidationUtil.normalizeEmail(email));
        if (user == null || !user.isActive()) {
            throw new ServiceException("The code is not correct.");
        }
        String wrong = null;
        try (Connection con = DBConnection.getTransactionConnection()) {
            con.setAutoCommit(false);
            try {
                PasswordReset reset = resetDao.findLatestUnused(con, user.getId());
                if (reset == null) {
                    PasswordReset newest = resetDao.findNewest(con, user.getId());
                    throw new ServiceException(newest != null && newest.getAttempts() >= MAX_ATTEMPTS
                            ? MSG_TOO_MANY_WRONG : MSG_NO_CODE);
                }
                if (isExpired(reset)) {
                    resetDao.markUsed(con, reset.getId());
                    con.commit();
                    throw new ServiceException(MSG_EXPIRED);
                }
                if (!PasswordUtil.verify(c, reset.getCodeSalt(), reset.getCodeHash())) {
                    int attempts = resetDao.addAttempt(con, reset.getId(), MAX_ATTEMPTS);
                    con.commit();
                    int left = MAX_ATTEMPTS - attempts;
                    wrong = left <= 0 ? MSG_TOO_MANY_WRONG
                            : "The code is not correct. " + left + (left == 1 ? " try" : " tries") + " left.";
                } else {
                    if (PasswordUtil.verify(newPassword, user.getSalt(), user.getPasswordHash())) {
                        throw new ServiceException("Choose a new password, not the one you had before.");
                    }
                    String salt = PasswordUtil.generateSalt();
                    userDao.updatePassword(con, user.getId(), PasswordUtil.hash(newPassword, salt), salt, false);
                    resetDao.markUsed(con, reset.getId());
                    resetDao.useAll(con, user.getId());
                    AuditLog audit = new AuditLog();
                    audit.setUserId(user.getId());
                    audit.setAction("PASSWORD_RESET");
                    audit.setEntity("users");
                    audit.setEntityId(user.getId());
                    audit.setDetails("Password reset with a code sent via " + reset.getSentVia().name());
                    auditLogDao.insert(con, audit);
                    Notification n = new Notification();
                    n.setUserId(user.getId());
                    n.setType(NotificationType.INFO);
                    n.setMessage("Your password was changed on " + DateUtil.toDisplay(format(now()))
                            + ". If this wasn't you, contact the admin.");
                    notificationDao.insert(con, n);
                    con.commit();
                }
            } catch (ServiceException | SQLException | RuntimeException e) {
                con.rollback(); // nothing to undo when the expired code was already committed
                throw e;
            }
        }
        if (wrong != null) {
            throw new ServiceException(wrong);
        }
    }

    private boolean isExpired(PasswordReset reset) {
        Date expires = DateUtil.parseDateTime(reset.getExpiresAt());
        return expires == null || now() >= expires.getTime();
    }

    private long secondsSince(String dateTime) {
        Date d = DateUtil.parseDateTime(dateTime);
        return d == null ? Long.MAX_VALUE : (now() - d.getTime()) / 1000;
    }

    private long now() {
        return clock.millis();
    }

    private static String format(long millis) {
        return new SimpleDateFormat(DateUtil.DATE_TIME_PATTERN).format(new Date(millis));
    }
}
