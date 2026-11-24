package medix.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import medix.model.Area;
import medix.model.PlaceSource;
import medix.util.DateUtil;

/**
 * Data access for the areas table: saved places anywhere in the world.
 */
public class AreaDao extends BaseDao<Area> {

    @Override
    protected String tableName() {
        return "areas";
    }

    @Override
    protected Area mapRow(ResultSet rs) throws SQLException {
        Area a = new Area();
        a.setId(rs.getInt("id"));
        a.setName(rs.getString("name"));
        a.setDisplayName(rs.getString("display_name"));
        a.setCity(rs.getString("city"));
        a.setCountry(rs.getString("country"));
        a.setCountryCode(rs.getString("country_code"));
        a.setLatitude(rs.getDouble("latitude"));
        a.setLongitude(rs.getDouble("longitude"));
        a.setSource(getEnum(rs, "source", PlaceSource.class));
        a.setCreatedAt(rs.getString("created_at"));
        return a;
    }

    public int insert(Area a) throws SQLException {
        if (a.getCreatedAt() == null) {
            a.setCreatedAt(DateUtil.now());
        }
        if (a.getSource() == null) {
            a.setSource(PlaceSource.MANUAL);
        }
        int id = executeInsert(
                "INSERT INTO areas (name, display_name, city, country, country_code, latitude, longitude, source, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.getName(), a.getDisplayName(), a.getCity(), a.getCountry(), a.getCountryCode(), a.getLatitude(),
                a.getLongitude(), a.getSource(), a.getCreatedAt());
        a.setId(id);
        return id;
    }

    public boolean update(Area a) throws SQLException {
        return executeUpdate(
                "UPDATE areas SET name = ?, display_name = ?, city = ?, country = ?, country_code = ?, latitude = ?, "
                + "longitude = ?, source = ? WHERE id = ?",
                a.getName(), a.getDisplayName(), a.getCity(), a.getCountry(), a.getCountryCode(), a.getLatitude(),
                a.getLongitude(), a.getSource(), a.getId()) > 0;
    }

    /** First place with this short name; a seeded Dhaka area wins when names repeat. */
    public Area findByName(String name) throws SQLException {
        return findOne("SELECT * FROM areas WHERE name = ? COLLATE NOCASE "
                + "ORDER BY CASE source WHEN 'SEED' THEN 0 ELSE 1 END, id LIMIT 1", name);
    }

    /** The place with exactly this display name (ignoring case), or null. */
    public Area findByDisplayName(String displayName) throws SQLException {
        return findOne("SELECT * FROM areas WHERE display_name = ? COLLATE NOCASE LIMIT 1", displayName);
    }

    /** The closest saved place whose coordinates are both within `tolerance` degrees, or null. */
    public Area findNear(double lat, double lon, double tolerance) throws SQLException {
        return findOne("SELECT * FROM areas WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? "
                + "ORDER BY ABS(latitude - ?) + ABS(longitude - ?) LIMIT 1",
                lat - tolerance, lat + tolerance, lon - tolerance, lon + tolerance, lat, lon);
    }

    /** Saved places whose name or display name contains the text (offline place search). */
    public List<Area> search(String text, int limit) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return findList("SELECT * FROM areas WHERE name LIKE ? OR display_name LIKE ? "
                + "ORDER BY CASE WHEN name LIKE ? THEN 0 ELSE 1 END, display_name LIMIT ?",
                like, like, (text == null ? "" : text.trim()) + "%", limit);
    }

    /** All saved places sorted by display name. */
    public List<Area> findAllByName() throws SQLException {
        return findList("SELECT * FROM areas ORDER BY display_name");
    }
}
