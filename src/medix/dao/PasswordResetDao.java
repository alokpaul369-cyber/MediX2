package medix.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import medix.model.PasswordReset;
import medix.model.ResetChannel;

/**
 * Data access for the password_resets table.
 */
public class PasswordResetDao extends BaseDao<PasswordReset> {

    @Override
    protected String tableName() {
        return "password_resets";
    }

    @Override
    protected PasswordReset mapRow(ResultSet rs) throws SQLException {
        PasswordReset p = new PasswordReset();
        p.setId(rs.getInt("id"));
        p.setUserId(rs.getInt("user_id"));
        p.setCodeHash(rs.getString("code_hash"));
        p.setCodeSalt(rs.getString("code_salt"));
        p.setCreatedAt(rs.getString("created_at"));
        p.setExpiresAt(rs.getString("expires_at"));
        p.setAttempts(rs.getInt("attempts"));
        p.setUsed(getBoolean(rs, "used"));
        p.setSentVia(getEnum(rs, "sent_via", ResetChannel.class));
        return p;
    }

    /** Insert on the caller's connection (request transaction). createdAt / expiresAt must be set. */
    public int insert(Connection con, PasswordReset p) throws SQLException {
        int id = executeInsert(con,
                "INSERT INTO password_resets (user_id, code_hash, code_salt, created_at, expires_at, attempts, used, "
                + "sent_via) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                p.getUserId(), p.getCodeHash(), p.getCodeSalt(), p.getCreatedAt(), p.getExpiresAt(), p.getAttempts(),
                p.isUsed(), p.getSentVia());
        p.setId(id);
        return id;
    }

    public boolean update(PasswordReset p) throws SQLException {
        return executeUpdate("UPDATE password_resets SET user_id = ?, code_hash = ?, code_salt = ?, expires_at = ?, "
                + "attempts = ?, used = ?, sent_via = ? WHERE id = ?",
                p.getUserId(), p.getCodeHash(), p.getCodeSalt(), p.getExpiresAt(), p.getAttempts(), p.isUsed(),
                p.getSentVia(), p.getId()) > 0;
    }

    /** Codes of the user created at or after the date-time (rate limits). */
    public int countSince(Connection con, int userId, String since) throws SQLException {
        return queryInt(con, "SELECT COUNT(*) FROM password_resets WHERE user_id = ? AND created_at >= ?", userId, since);
    }

    /** created_at of the user's newest code, or null. */
    public PasswordReset findNewest(Connection con, int userId) throws SQLException {
        return findOne(con, "SELECT * FROM password_resets WHERE user_id = ? ORDER BY id DESC LIMIT 1", userId);
    }

    /** The user's newest code that is not used yet (it may be expired), or null. */
    public PasswordReset findLatestUnused(Connection con, int userId) throws SQLException {
        return findOne(con, "SELECT * FROM password_resets WHERE user_id = ? AND used = 0 ORDER BY id DESC LIMIT 1",
                userId);
    }

    /** Marks every unused code of the user as used (a newer code was sent, the password was reset). */
    public int useAll(Connection con, int userId) throws SQLException {
        return executeUpdate(con, "UPDATE password_resets SET used = 1 WHERE user_id = ? AND used = 0", userId);
    }

    /** One more wrong try; at `limit` tries the code is cancelled (used = 1). Returns the new count. */
    public int addAttempt(Connection con, int id, int limit) throws SQLException {
        executeUpdate(con, "UPDATE password_resets SET attempts = attempts + 1, "
                + "used = CASE WHEN attempts + 1 >= ? THEN 1 ELSE used END WHERE id = ?", limit, id);
        return queryInt(con, "SELECT attempts FROM password_resets WHERE id = ?", id);
    }

    public boolean markUsed(Connection con, int id) throws SQLException {
        return executeUpdate(con, "UPDATE password_resets SET used = 1 WHERE id = ? AND used = 0", id) > 0;
    }
}
