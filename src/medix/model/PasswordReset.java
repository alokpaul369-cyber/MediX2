package medix.model;

/**
 * Row of the password_resets table: one "Forgot password?" code. Only a salted hash of the
 * 6-digit code is stored, never the code itself.
 */
public class PasswordReset {

    private int id;
    private int userId;
    private String codeHash;
    private String codeSalt;
    private String createdAt;
    private String expiresAt;
    private int attempts;
    private boolean used;
    private ResetChannel sentVia;

    public PasswordReset() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public String getCodeSalt() {
        return codeSalt;
    }

    public void setCodeSalt(String codeSalt) {
        this.codeSalt = codeSalt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** Wrong codes typed so far (the code is cancelled at 5). */
    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    /** True once the password was reset with it, a newer code was sent or it was cancelled. */
    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    public ResetChannel getSentVia() {
        return sentVia;
    }

    public void setSentVia(ResetChannel sentVia) {
        this.sentVia = sentVia;
    }
}
