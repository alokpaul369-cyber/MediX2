package medix.model;

/**
 * Where a saved place (areas row) came from: areas.source.
 * Stored in the database as {@link #name()}; the CHECK constraint in schema.sql lists the same values.
 */
public enum PlaceSource {
    /** One of the 10 Dhaka areas created on the first start. */
    SEED,
    /** Found by the map service (OpenStreetMap Nominatim). */
    GEOCODED,
    /** Typed coordinates or a point clicked on the map that the map service could not name. */
    MANUAL
}
