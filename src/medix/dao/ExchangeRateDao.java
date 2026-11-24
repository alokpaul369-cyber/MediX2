package medix.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.ExchangeRate;
import medix.model.RateSource;
import medix.util.DBConnection;

/**
 * Data access for the exchange_rates table (one row per currency, rate = units per 1 USD).
 */
public class ExchangeRateDao extends BaseDao<ExchangeRate> {

    @Override
    protected String tableName() {
        return "exchange_rates";
    }

    @Override
    protected ExchangeRate mapRow(ResultSet rs) throws SQLException {
        ExchangeRate r = new ExchangeRate();
        r.setId(rs.getInt("id"));
        r.setCurrencyCode(rs.getString("currency_code"));
        // a rate is not money: keep every digit the REAL column has
        r.setRatePerUsd(new BigDecimal(Double.toString(rs.getDouble("rate_per_usd"))));
        r.setSource(getEnum(rs, "source", RateSource.class));
        r.setUpdatedAt(rs.getString("updated_at"));
        return r;
    }

    /** All rates by currency code. */
    @Override
    public List<ExchangeRate> findAll() throws SQLException {
        return findList("SELECT * FROM exchange_rates ORDER BY currency_code");
    }

    public ExchangeRate findByCode(String code) throws SQLException {
        return findOne("SELECT * FROM exchange_rates WHERE currency_code = ?", code);
    }

    public ExchangeRate findByCode(Connection con, String code) throws SQLException {
        return findOne(con, "SELECT * FROM exchange_rates WHERE currency_code = ?", code);
    }

    public int insert(Connection con, ExchangeRate r) throws SQLException {
        int id = executeInsert(con, "INSERT INTO exchange_rates (currency_code, rate_per_usd, source, updated_at) "
                + "VALUES (?, ?, ?, ?)", r.getCurrencyCode(), r.getRatePerUsd(), r.getSource(), r.getUpdatedAt());
        r.setId(id);
        return id;
    }

    public int insert(ExchangeRate r) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return insert(con, r);
        }
    }

    /** New rate, source and time for an existing row. */
    public boolean update(Connection con, ExchangeRate r) throws SQLException {
        return executeUpdate(con, "UPDATE exchange_rates SET rate_per_usd = ?, source = ?, updated_at = ? WHERE id = ?",
                r.getRatePerUsd(), r.getSource(), r.getUpdatedAt(), r.getId()) > 0;
    }

    /** Newest updated_at of the rows with this source (null = any source); null when there is none. */
    public String newestUpdate(RateSource source) throws SQLException {
        try (Connection con = DBConnection.getConnection();
                java.sql.PreparedStatement ps = con.prepareStatement(
                        "SELECT MAX(updated_at) FROM exchange_rates WHERE (? IS NULL OR source = ?)")) {
            bind(ps, source, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
