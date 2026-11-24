package medix.engine;

import java.util.List;
import java.util.Locale;
import medix.model.Area;

/**
 * Geography helpers for any place on Earth: straight-line distance (haversine), the bounding box
 * used to pre-filter pharmacies in SQL, coordinate checks, rounding and distance text.
 */
public final class GeoUtil {

    private static final double EARTH_RADIUS_KM = 6371.0;
    /** Kilometres per degree of latitude (and of longitude at the equator). */
    public static final double KM_PER_DEGREE = 111.0;
    /** Coordinates are typed / stored with at most this many decimals (about 10 cm). */
    public static final int MAX_DECIMALS = 6;

    private GeoUtil() {
    }

    /** Distance in kilometres between (lat1, lon1) and (lat2, lon2), given in degrees. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static boolean isValidLatitude(double lat) {
        return !Double.isNaN(lat) && lat >= -90 && lat <= 90;
    }

    public static boolean isValidLongitude(double lon) {
        return !Double.isNaN(lon) && lon >= -180 && lon <= 180;
    }

    /** Rounded to 2 decimals (about 1 km): what MediX stores about a patient's location or a search. */
    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Rounded to 6 decimals (full precision for a pharmacy, which is a public shop). */
    public static double round6(double value) {
        return Math.round(value * 1000000.0) / 1000000.0;
    }

    /**
     * The rectangle around a point that contains every point within `km`: latitude ± km/111,
     * longitude ± km/(111 × cos(lat)). The longitude limits are not used (filterLongitude false)
     * near the poles or when the box would cross the 180° line; the exact distance is checked in
     * Java afterwards anyway.
     */
    public static BoundingBox boundingBox(double lat, double lon, double km) {
        double dLat = km / KM_PER_DEGREE;
        double minLat = Math.max(-90, lat - dLat);
        double maxLat = Math.min(90, lat + dLat);
        double cos = Math.cos(Math.toRadians(lat));
        if (Math.abs(lat) + dLat >= 89 || cos < 0.01) {
            return new BoundingBox(minLat, maxLat, -180, 180, false);
        }
        double dLon = km / (KM_PER_DEGREE * cos);
        if (lon - dLon < -180 || lon + dLon > 180) {
            return new BoundingBox(minLat, maxLat, -180, 180, false); // crosses the 180° line
        }
        return new BoundingBox(minLat, maxLat, lon - dLon, lon + dLon, true);
    }

    /** Latitude / longitude limits for the SQL pre-filter of a radius search. */
    public static final class BoundingBox {
        public final double minLat;
        public final double maxLat;
        public final double minLon;
        public final double maxLon;
        /** false: only the latitude limits apply (poles, 180° line). */
        public final boolean filterLongitude;

        public BoundingBox(double minLat, double maxLat, double minLon, double maxLon, boolean filterLongitude) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
            this.filterLongitude = filterLongitude;
        }
    }

    /** The area whose centre is closest to the point, or null if the list is empty. */
    public static Area nearestArea(List<Area> areas, double lat, double lon) {
        Area best = null;
        double bestKm = Double.MAX_VALUE;
        for (Area a : areas) {
            double km = distanceKm(lat, lon, a.getLatitude(), a.getLongitude());
            if (km < bestKm) {
                bestKm = km;
                best = a;
            }
        }
        return best;
    }

    /** Coordinate with 4 decimals, e.g. "23.7461". */
    public static String formatCoordinate(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    /** Coordinate with up to 6 decimals and no trailing zeros, e.g. "23.746102", "-0.1276". */
    public static String formatCoordinateFull(double value) {
        String s = String.format(Locale.US, "%.6f", value);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Search results: "650 m" under 1 km, "2.6 km" up to 100 km, "245 km" above. */
    public static String formatKm(double km) {
        if (km < 1) {
            return Math.round(km * 1000) + " m";
        }
        if (km <= 100) {
            return String.format(Locale.US, "%.1f km", km);
        }
        return String.format(Locale.US, "%.0f km", km);
    }

    /** Same as {@link #formatKm(double)}. */
    public static String formatDistance(double km) {
        return formatKm(km);
    }
}
