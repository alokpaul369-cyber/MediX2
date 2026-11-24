package medix.model;

/**
 * How a password reset code reached the user: password_resets.sent_via.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 */
public enum ResetChannel {
    /** Sent by e-mail (data/mail.properties is set up). */
    EMAIL,
    /** Demo mode: e-mail is not set up, the code was shown on screen. */
    DEMO
}
