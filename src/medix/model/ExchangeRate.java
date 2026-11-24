package medix.model;

import java.math.BigDecimal;

/**
 * Row of the exchange_rates table: how many units of a currency one US dollar buys.
 */
public class ExchangeRate {

    private int id;
    private String currencyCode;
    private BigDecimal ratePerUsd;
    private RateSource source;
    private String updatedAt;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    /** ISO 4217 code, e.g. "BDT". */
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public BigDecimal getRatePerUsd() {
        return ratePerUsd;
    }

    public void setRatePerUsd(BigDecimal ratePerUsd) {
        this.ratePerUsd = ratePerUsd;
    }

    public RateSource getSource() {
        return source;
    }

    public void setSource(RateSource source) {
        this.source = source;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return currencyCode + " " + ratePerUsd;
    }
}
