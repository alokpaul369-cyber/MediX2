package medix.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import medix.model.Place;
import medix.util.AppConfig;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Place search and reverse geocoding with OpenStreetMap Nominatim (free, no API key).
 * Usage policy: an identifying User-Agent, at most 1 request per second (enforced here), never
 * on every key press (callers only search on a button click or Enter), and results cached for
 * the session. Calls block: run them in a SwingWorker. Network problems throw IOException.
 */
public class GeocodingService {

    public static final String SEARCH_URL = "https://nominatim.openstreetmap.org/search";
    public static final String REVERSE_URL = "https://nominatim.openstreetmap.org/reverse";
    public static final int TIMEOUT_MILLIS = 8000;
    public static final int MAX_RESULTS = 5;
    private static final long MIN_INTERVAL_MILLIS = 1000;
    private static final int CACHE_SIZE = 200;

    private static long lastRequestAt;
    private static final Map<String, List<Place>> SEARCH_CACHE = lruCache();
    private static final Map<String, Place> REVERSE_CACHE = lruCache();

    /**
     * "MediX/1.0 (university project; you@uni.edu)" with the contact.email from data/config.properties.
     * While it is still the example.com placeholder the address is left out: Nominatim refuses
     * requests that carry a placeholder address (HTTP 403).
     */
    public static String userAgent() {
        return AppConfig.hasRealContactEmail()
                ? "MediX/1.0 (university project; " + AppConfig.contactEmail() + ")"
                : "MediX/1.0 (university project)";
    }

    /** Up to 5 places for the text (address, area, city, country …); empty when nothing matches. */
    public List<Place> search(String text) throws IOException {
        String query = text == null ? "" : text.trim();
        if (query.isEmpty()) {
            return new ArrayList<Place>();
        }
        String key = query.toLowerCase(Locale.ROOT);
        synchronized (SEARCH_CACHE) {
            List<Place> hit = SEARCH_CACHE.get(key);
            if (hit != null) {
                return new ArrayList<Place>(hit);
            }
        }
        List<Place> places = parseSearch(get(SEARCH_URL + "?q=" + URLEncoder.encode(query, "UTF-8")
                + "&format=jsonv2&addressdetails=1&limit=" + MAX_RESULTS + "&accept-language=en"));
        synchronized (SEARCH_CACHE) {
            SEARCH_CACHE.put(key, new ArrayList<Place>(places));
        }
        return places;
    }

    /** The place at the point (street, area, city …), or null when there is nothing (e.g. open sea). */
    public Place reverse(double lat, double lon) throws IOException {
        String key = String.format(Locale.ROOT, "%.5f,%.5f", lat, lon);
        synchronized (REVERSE_CACHE) {
            if (REVERSE_CACHE.containsKey(key)) {
                return REVERSE_CACHE.get(key);
            }
        }
        Place place = parseReverse(get(String.format(Locale.ROOT,
                "%s?lat=%.6f&lon=%.6f&format=jsonv2&addressdetails=1&accept-language=en", REVERSE_URL, lat, lon)));
        if (place != null) {
            // the user's point, not the centre of the street / building Nominatim returns
            place.setLatitude(lat);
            place.setLongitude(lon);
        }
        synchronized (REVERSE_CACHE) {
            REVERSE_CACHE.put(key, place);
        }
        return place;
    }

    // ---------------------------------------------------------------- parsing (tested with saved responses)

    /** A /search?format=jsonv2 response (a JSON array). */
    public static List<Place> parseSearch(String json) throws IOException {
        List<Place> places = new ArrayList<Place>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && places.size() < MAX_RESULTS; i++) {
                Place p = parsePlace(array.getJSONObject(i));
                if (p != null) {
                    places.add(p);
                }
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from the map service", e);
        }
        return places;
    }

    /** A /reverse?format=jsonv2 response (one object, or {"error": …} when nothing is there). */
    public static Place parseReverse(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            return o.has("error") ? null : parsePlace(o);
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from the map service", e);
        }
    }

    /** One result: display_name, lat / lon (strings in Nominatim), city and country from "address". */
    static Place parsePlace(JSONObject o) {
        String displayName = o.optString("display_name", "").trim();
        double lat = parseCoordinate(o.opt("lat"));
        double lon = parseCoordinate(o.opt("lon"));
        if (displayName.isEmpty() || !GeoUtil.isValidLatitude(lat) || !GeoUtil.isValidLongitude(lon)) {
            return null;
        }
        JSONObject address = o.optJSONObject("address");
        String city = null;
        String country = null;
        String countryCode = null;
        if (address != null) {
            for (String field : new String[]{"city", "town", "village", "suburb", "county", "state"}) {
                String value = address.optString(field, "").trim();
                if (!value.isEmpty()) {
                    city = value;
                    break;
                }
            }
            country = emptyToNull(address.optString("country", ""));
            String cc = emptyToNull(address.optString("country_code", ""));
            countryCode = cc == null ? null : cc.toUpperCase(Locale.ROOT);
        }
        return new Place(displayName, lat, lon, city, country, countryCode);
    }

    private static double parseCoordinate(Object value) {
        if (value == null) {
            return Double.NaN;
        }
        try {
            return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    // ---------------------------------------------------------------- HTTP

    private String get(String url) throws IOException {
        throttle();
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(TIMEOUT_MILLIS);
        con.setReadTimeout(TIMEOUT_MILLIS);
        con.setRequestProperty("User-Agent", userAgent());
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Accept-Language", "en");
        try {
            int status = con.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("The map service answered HTTP " + status);
            }
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            con.disconnect();
        }
    }

    /** At most one request per second for the whole app (Nominatim usage policy). */
    private static void throttle() throws IOException {
        synchronized (GeocodingService.class) {
            long wait = lastRequestAt + MIN_INTERVAL_MILLIS - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Cancelled");
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    private static <V> Map<String, V> lruCache() {
        return Collections.synchronizedMap(new LinkedHashMap<String, V>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > CACHE_SIZE;
            }
        });
    }
}
