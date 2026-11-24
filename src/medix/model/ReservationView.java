package medix.model;

import java.math.BigDecimal;
import medix.util.CurrencyUtil;

/**
 * A reservation with the names needed on screen (medicine, pharmacy) and its unit price + currency
 * (saved when reserving; for rows older than Fix 3 the batch's price). Not a table: ReservationDao
 * builds it with a JOIN.
 */
public class ReservationView {

    private final Reservation reservation;
    private final String medicineLabel;
    private final String medicineShort;
    private final String pharmacyName;
    private final String pharmacyPhone;
    private final int pharmacyOwnerId;
    private final BigDecimal unitPrice;
    private final String currency;
    private final String patientName;
    private final String patientPhone;

    public ReservationView(Reservation reservation, String medicineLabel, String medicineShort, String pharmacyName,
            String pharmacyPhone, int pharmacyOwnerId, BigDecimal unitPrice, String currency, String patientName,
            String patientPhone) {
        this.reservation = reservation;
        this.medicineLabel = medicineLabel;
        this.medicineShort = medicineShort;
        this.pharmacyName = pharmacyName;
        this.pharmacyPhone = pharmacyPhone;
        this.pharmacyOwnerId = pharmacyOwnerId;
        this.unitPrice = unitPrice;
        this.currency = currency == null ? CurrencyUtil.BDT : currency;
        this.patientName = patientName;
        this.patientPhone = patientPhone;
    }

    public String getPatientName() {
        return patientName;
    }

    /** The patient's full phone number; show it masked until the reservation is confirmed. */
    public String getPatientPhone() {
        return patientPhone;
    }

    public Reservation getReservation() {
        return reservation;
    }

    /** e.g. "Seclo 20 mg Capsule". */
    public String getMedicineLabel() {
        return medicineLabel;
    }

    /** e.g. "Seclo 20 mg" (used in messages). */
    public String getMedicineShort() {
        return medicineShort;
    }

    public String getPharmacyName() {
        return pharmacyName;
    }

    public String getPharmacyPhone() {
        return pharmacyPhone;
    }

    public int getPharmacyOwnerId() {
        return pharmacyOwnerId;
    }

    /** Price per unit when reserved; null when unknown (very old row whose batch was deleted). */
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /** Currency of the unit price and amount (the batch's currency). */
    public String getCurrency() {
        return currency;
    }

    /** quantity × unit price in getCurrency(), or null when the price is unknown. */
    public BigDecimal getAmount() {
        return unitPrice == null ? null : CurrencyUtil.times(unitPrice, reservation.getQuantity(), currency);
    }

    /** "Tk 14.00", or "–" when the price is unknown. */
    public String getAmountText() {
        return unitPrice == null ? "–" : CurrencyUtil.format(getAmount(), currency);
    }

    @Override
    public String toString() {
        return reservation.getCode();
    }
}
