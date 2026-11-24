package medix.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import medix.dao.AreaDao;
import medix.engine.GeoUtil;
import medix.engine.GeocodingService;
import medix.model.Area;
import medix.model.Place;
import medix.model.PlaceSource;
import medix.util.AppPaths;
import medix.util.ErrorLog;
import medix.util.Session;

/**
 * Places anywhere on Earth: search (map service first, saved places when offline), reverse
 * geocoding, saving the places users choose in areas (no duplicates) and the user's recent places.
 * Network calls block – call them from a SwingWorker.
 */
public class LocationService {

    /** Two places closer than this (degrees, about 50 m) are the same saved place. */
    public static final double SAME_PLACE_DEGREES = 0.0005;
    public static final int MAX_RECENT = 5;
    public static final String MSG_OFFLINE = "Can't reach the map service. Check your internet, choose a saved place, "
            + "or type the latitude and longitude.";

    /**
     * Recent places per user, in data/ next to the database (their ids are areas rows of THIS database).
     * Not java.util.prefs: on Java 8 / Windows it prints a "Could not open/create prefs root node …
     * error code 5" warning for every normal (non-admin) user.
     */
    public static final String RECENT_FILE = "recent-places.properties";

    private final AreaDao areaDao = new AreaDao();
    private final GeocodingService geocoding = new GeocodingService();

    /** Places found for a text; offline = the map service could not be reached (saved places only). */
    public static final class PlaceSearch {
        public final List<Place> places;
        public final boolean offline;

        public PlaceSearch(List<Place> places, boolean offline) {
            this.places = places;
            this.offline = offline;
        }
    }

    /**
     * Up to 5 places for the text. Online results are marked with the id of an identical saved
     * place when there is one; without a connection the saved places that match are returned.
     */
    public PlaceSearch search(String text) throws SQLException {
        try {
            List<Place> found = geocoding.search(text);
            for (Place p : found) {
                Area saved = findSaved(p);
                if (saved != null) {
                    p.setSavedId(saved.getId());
                }
            }
            if (found.isEmpty()) {
                return new PlaceSearch(searchSaved(text), false);
            }
            return new PlaceSearch(found, false);
        } catch (IOException e) {
            ErrorLog.log("Place search failed (map service)", e);
            return new PlaceSearch(searchSaved(text), true);
        }
    }

    /** Saved places whose name contains the text (works offline). */
    public List<Place> searchSaved(String text) throws SQLException {
        List<Place> places = new ArrayList<Place>();
        for (Area a : areaDao.search(text, GeocodingService.MAX_RESULTS)) {
            places.add(Place.fromArea(a));
        }
        return places;
    }

    /**
     * The name of the place at the point (not saved yet), or null when the map service has none or
     * cannot be reached; then a saved place within about 1 km is used if there is one.
     */
    public Place reverse(double lat, double lon) throws SQLException {
        try {
            Place p = geocoding.reverse(lat, lon);
            if (p != null) {
                p.setSource(PlaceSource.MANUAL);
                return p;
            }
        } catch (IOException e) {
            ErrorLog.log("Reverse geocoding failed (map service)", e);
        }
        Area near = areaDao.findNear(lat, lon, 0.01);
        if (near == null) {
            return null;
        }
        Place p = new Place(near.getDisplayName(), lat, lon, near.getCity(), near.getCountry(), near.getCountryCode());
        p.setSource(PlaceSource.MANUAL);
        return p;
    }

    /** A typed or clicked point without a name yet: "23.746100, 90.374200". */
    public static Place point(double lat, double lon) {
        Place p = new Place(GeoUtil.formatCoordinateFull(lat) + ", " + GeoUtil.formatCoordinateFull(lon), lat, lon,
                null, null, null);
        p.setSource(PlaceSource.MANUAL);
        return p;
    }

    /**
     * Saves the chosen place in areas (GEOCODED, or MANUAL for typed coordinates) unless the same
     * place is already saved (same display name, or coordinates within 0.0005°). Sets and returns
     * the place with its savedId.
     */
    public Place save(Place p) throws SQLException {
        if (p.getSavedId() != null && areaDao.findById(p.getSavedId()) != null) {
            return p;
        }
        if (!GeoUtil.isValidLatitude(p.getLatitude()) || !GeoUtil.isValidLongitude(p.getLongitude())) {
            throw new SQLException("Invalid coordinates " + p.getLatitude() + ", " + p.getLongitude());
        }
        Area saved = findSaved(p);
        if (saved == null) {
            Area a = new Area();
            a.setName(p.getName());
            a.setDisplayName(p.getDisplayName() == null ? point(p.getLatitude(), p.getLongitude()).getDisplayName()
                    : p.getDisplayName());
            a.setCity(p.getCity());
            a.setCountry(p.getCountry());
            a.setCountryCode(p.getCountryCode());
            a.setLatitude(GeoUtil.round6(p.getLatitude()));
            a.setLongitude(GeoUtil.round6(p.getLongitude()));
            a.setSource(p.getSource() == PlaceSource.MANUAL ? PlaceSource.MANUAL : PlaceSource.GEOCODED);
            areaDao.insert(a);
            saved = a;
        }
        p.setSavedId(saved.getId());
        return p;
    }

    /** The saved place with this id, or null. */
    public Place getSaved(Integer areaId) throws SQLException {
        if (areaId == null) {
            return null;
        }
        Area a = areaDao.findById(areaId);
        return a == null ? null : Place.fromArea(a);
    }

    private Area findSaved(Place p) throws SQLException {
        Area byName = p.getDisplayName() == null ? null : areaDao.findByDisplayName(p.getDisplayName());
        if (byName != null) {
            return byName;
        }
        return areaDao.findNear(p.getLatitude(), p.getLongitude(), SAME_PLACE_DEGREES);
    }

    // ---------------------------------------------------------------- recent places (data/recent-places.properties)

    /** The logged-in user's (or a guest's) last 5 chosen places, newest first. */
    public List<Place> getRecent() {
        List<Place> places = new ArrayList<Place>();
        String ids = readRecent().getProperty(recentKey(), "");
        for (String id : ids.split(",")) {
            try {
                if (!id.trim().isEmpty()) {
                    Place p = getSaved(Integer.parseInt(id.trim()));
                    if (p != null) {
                        places.add(p);
                    }
                }
            } catch (NumberFormatException | SQLException e) {
                ErrorLog.log("Recent place " + id + " could not be read", e);
            }
        }
        return places;
    }

    /** Puts a saved place at the top of the recent list (max 5). */
    public void remember(Place p) {
        if (p == null || p.getSavedId() == null) {
            return;
        }
        List<String> ids = new ArrayList<String>();
        ids.add(String.valueOf(p.getSavedId()));
        synchronized (LocationService.class) {
            Properties all = readRecent();
            for (String id : all.getProperty(recentKey(), "").split(",")) {
                String t = id.trim();
                if (!t.isEmpty() && !ids.contains(t) && ids.size() < MAX_RECENT) {
                    ids.add(t);
                }
            }
            all.setProperty(recentKey(), String.join(",", ids));
            try (OutputStream out = new FileOutputStream(recentFile())) {
                all.store(out, "MediX recent places (areas ids, newest first)");
            } catch (IOException e) {
                ErrorLog.log("Recent places could not be saved", e); // only a convenience list: never fail the caller
            }
        }
    }

    private static File recentFile() {
        return new File(AppPaths.getDataDir(), RECENT_FILE);
    }

    /** The whole file (empty when it does not exist yet or cannot be read). */
    private static synchronized Properties readRecent() {
        Properties p = new Properties();
        File f = recentFile();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                ErrorLog.log("Recent places could not be read", e);
            }
        }
        return p;
    }

    private static String recentKey() {
        Integer me = Session.getUserId();
        return me == null ? "guest" : "user-" + me;
    }
}
