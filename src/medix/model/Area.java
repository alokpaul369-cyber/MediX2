package medix.model;

/**
 * Row of the areas table: a saved place anywhere in the world. The 10 Dhaka areas are seeded
 * (source SEED); places that users choose are saved as GEOCODED (found by the map service) or
 * MANUAL (typed coordinates) and reused next time. Users, pharmacies and searches point here.
 */
public class Area {

    private int id;
    private String name;
    private String displayName;
    private String city;
    private String country;
    private String countryCode;
    private double latitude;
    private double longitude;
    private PlaceSource source = PlaceSource.SEED;
    private String createdAt;

    public Area() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    /** Short name, e.g. "Dhanmondi" (not unique). */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Full, unique name, e.g. "Dhanmondi, Dhaka, Bangladesh". */
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    /** ISO 3166-1 alpha-2, e.g. "BD", upper case; null when unknown. */
    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
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

    public PlaceSource getSource() {
        return source;
    }

    public void setSource(PlaceSource source) {
        this.source = source;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return displayName == null ? name : displayName;
    }
}
