package medix.model;

/**
 * A place anywhere on Earth: a geocoding result, a typed / clicked point or a saved place
 * (savedId = its areas id once saved). Not a table; saved places are rows of areas.
 */
public class Place {

    private String displayName;
    private double latitude;
    private double longitude;
    private String city;
    private String country;
    private String countryCode;
    private Integer savedId;
    private PlaceSource source = PlaceSource.GEOCODED;

    public Place() {
    }

    public Place(String displayName, double latitude, double longitude, String city, String country, String countryCode) {
        this.displayName = displayName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.city = city;
        this.country = country;
        this.countryCode = countryCode;
    }

    /** A saved place (areas row). */
    public static Place fromArea(Area a) {
        Place p = new Place(a.getDisplayName() == null ? a.getName() : a.getDisplayName(), a.getLatitude(),
                a.getLongitude(), a.getCity(), a.getCountry(), a.getCountryCode());
        p.setSavedId(a.getId());
        p.setSource(a.getSource());
        return p;
    }

    /** "Dhanmondi, Dhaka, Bangladesh" */
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    /** The areas id once the place is saved; null for a new search result. */
    public Integer getSavedId() {
        return savedId;
    }

    public void setSavedId(Integer savedId) {
        this.savedId = savedId;
    }

    public PlaceSource getSource() {
        return source;
    }

    public void setSource(PlaceSource source) {
        this.source = source;
    }

    /** First part of the display name: "Dhanmondi" (or "23.7461, 90.3742" for a bare point). */
    public String getName() {
        if (displayName == null || displayName.trim().isEmpty()) {
            return formatCoordinates();
        }
        int comma = displayName.indexOf(',');
        return (comma > 0 ? displayName.substring(0, comma) : displayName).trim();
    }

    /**
     * A short label: "Dhanmondi, Dhaka", "London, United Kingdom", "Chattogram, Bangladesh".
     * Used for users.location_name and the "My location (…)" item.
     */
    public String getShortName() {
        String name = getName();
        String second = city != null && !city.trim().isEmpty() && !city.equalsIgnoreCase(name) ? city : country;
        return second == null || second.trim().isEmpty() || second.equalsIgnoreCase(name) ? name : name + ", " + second;
    }

    /** "23.7461, 90.3742" (4 decimals). */
    public String formatCoordinates() {
        return String.format(java.util.Locale.ROOT, "%.4f, %.4f", latitude, longitude);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
