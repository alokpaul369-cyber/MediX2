package medix.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One search result row: a VERIFIED pharmacy that has a medicine in stock.
 * Not a table: built by InventoryDao.findStockedPharmacies (one row per pharmacy,
 * summed over its non-expired batches). distanceKm is filled in by SearchEngine.
 */
public class PharmacyStock {

    private int pharmacyId;
    private String pharmacyName;
    private String areaName;
    /** Pharmacy coordinates; when missing, the centre of its area is used. */
    private double latitude;
    private double longitude;
    private String phone;
    private String address;
    private String openTime;
    private String closeTime;
    private int trustScore;
    /** SUM(quantity − reserved_qty) over non-expired batches. */
    private int available;
    /** Lowest price per currency among those batches (a pharmacy may price batches in several currencies). */
    private final Map<String, BigDecimal> lowestByCurrency = new LinkedHashMap<String, BigDecimal>();
    /** The lowest price shown, in its own currency (chosen by SearchEngine.applyPrices). */
    private BigDecimal lowestPrice;
    private String currency;
    /** lowestPrice in the patient's currency (null when no exchange rate); used to sort by price. */
    private BigDecimal comparablePrice;
    private String comparableCurrency;
    /** Newest updated_at among those batches. */
    private String lastUpdated;
    private double distanceKm;

    public int getPharmacyId() {
        return pharmacyId;
    }

    public void setPharmacyId(int pharmacyId) {
        this.pharmacyId = pharmacyId;
    }

    public String getPharmacyName() {
        return pharmacyName;
    }

    public void setPharmacyName(String pharmacyName) {
        this.pharmacyName = pharmacyName;
    }

    public String getAreaName() {
        return areaName;
    }

    public void setAreaName(String areaName) {
        this.areaName = areaName;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getOpenTime() {
        return openTime;
    }

    public void setOpenTime(String openTime) {
        this.openTime = openTime;
    }

    public String getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(String closeTime) {
        this.closeTime = closeTime;
    }

    public int getTrustScore() {
        return trustScore;
    }

    public void setTrustScore(int trustScore) {
        this.trustScore = trustScore;
    }

    public int getAvailable() {
        return available;
    }

    public void setAvailable(int available) {
        this.available = available;
    }

    public Map<String, BigDecimal> getLowestByCurrency() {
        return lowestByCurrency;
    }

    /** Keeps the lower price for the currency. */
    public void addPrice(String currencyCode, BigDecimal price) {
        BigDecimal old = lowestByCurrency.get(currencyCode);
        if (old == null || price.compareTo(old) < 0) {
            lowestByCurrency.put(currencyCode, price);
        }
    }

    public BigDecimal getLowestPrice() {
        return lowestPrice;
    }

    /** Sets the shown price (and, with a single currency, the per-currency list). */
    public void setLowestPrice(BigDecimal lowestPrice, String currency) {
        this.lowestPrice = lowestPrice;
        this.currency = currency;
        if (lowestByCurrency.isEmpty() && lowestPrice != null) {
            lowestByCurrency.put(currency, lowestPrice);
        }
    }

    /** Currency of lowestPrice (ISO code). */
    public String getCurrency() {
        return currency;
    }

    public BigDecimal getComparablePrice() {
        return comparablePrice;
    }

    public String getComparableCurrency() {
        return comparableCurrency;
    }

    public void setComparablePrice(BigDecimal comparablePrice, String comparableCurrency) {
        this.comparablePrice = comparablePrice;
        this.comparableCurrency = comparableCurrency;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }

    @Override
    public String toString() {
        return pharmacyName;
    }
}
