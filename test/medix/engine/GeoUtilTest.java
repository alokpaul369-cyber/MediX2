package medix.engine;

import java.util.Arrays;
import medix.model.Area;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeoUtilTest {

    private static final double DHANMONDI_LAT = 23.7461;
    private static final double DHANMONDI_LON = 90.3742;
    private static final double GULSHAN1_LAT = 23.7806;
    private static final double GULSHAN1_LON = 90.4160;

    @Test
    public void dhanmondiToGulshan1IsAbout5Km() {
        double km = GeoUtil.distanceKm(DHANMONDI_LAT, DHANMONDI_LON, GULSHAN1_LAT, GULSHAN1_LON);
        // straight line between the two area centres: about 5.7 km
        assertTrue("was " + km, km > 5.0 && km < 6.5);
    }

    @Test
    public void distanceIsSymmetricAndZeroForTheSamePoint() {
        double there = GeoUtil.distanceKm(DHANMONDI_LAT, DHANMONDI_LON, GULSHAN1_LAT, GULSHAN1_LON);
        double back = GeoUtil.distanceKm(GULSHAN1_LAT, GULSHAN1_LON, DHANMONDI_LAT, DHANMONDI_LON);
        assertEquals(there, back, 1e-9);
        assertEquals(0.0, GeoUtil.distanceKm(DHANMONDI_LAT, DHANMONDI_LON, DHANMONDI_LAT, DHANMONDI_LON), 1e-9);
    }

    @Test
    public void distancesAcrossTheWorld() {
        // Dhaka – London about 8,000 km; across the 180° line in Fiji only about 21 km
        double dhakaLondon = GeoUtil.distanceKm(DHANMONDI_LAT, DHANMONDI_LON, 51.5072, -0.1276);
        assertTrue("was " + dhakaLondon, dhakaLondon > 7900 && dhakaLondon < 8100);
        double fiji = GeoUtil.distanceKm(-17.7, 179.9, -17.7, -179.9);
        assertTrue("was " + fiji, fiji > 20 && fiji < 22);
    }

    @Test
    public void distanceText() {
        assertEquals("650 m", GeoUtil.formatKm(0.65));
        assertEquals("2.6 km", GeoUtil.formatKm(2.6449));
        assertEquals("100.0 km", GeoUtil.formatKm(100));
        assertEquals("245 km", GeoUtil.formatKm(245.4));
    }

    @Test
    public void coordinatesAnywhereOnEarth() {
        assertTrue(GeoUtil.isValidLatitude(-90) && GeoUtil.isValidLatitude(90) && GeoUtil.isValidLatitude(51.5));
        assertFalse(GeoUtil.isValidLatitude(90.0001));
        assertTrue(GeoUtil.isValidLongitude(-180) && GeoUtil.isValidLongitude(180) && GeoUtil.isValidLongitude(-0.1276));
        assertFalse(GeoUtil.isValidLongitude(-180.5));
        assertFalse(GeoUtil.isValidLatitude(Double.NaN));
        assertEquals(23.75, GeoUtil.round2(23.7461), 1e-9);
        assertEquals(-0.13, GeoUtil.round2(-0.1276), 1e-9);
        assertEquals("23.7461", GeoUtil.formatCoordinateFull(23.746100));
        assertEquals("-0.127612", GeoUtil.formatCoordinateFull(-0.1276123));
    }

    @Test
    public void boundingBoxContainsTheCircle() {
        GeoUtil.BoundingBox box = GeoUtil.boundingBox(51.51, -0.13, 5);
        assertTrue(box.filterLongitude);
        assertEquals(51.51 - 5 / 111.0, box.minLat, 1e-9);
        assertEquals(51.51 + 5 / 111.0, box.maxLat, 1e-9);
        // at London's latitude a degree of longitude is only ~69 km, so the box is wider than tall
        assertTrue(box.maxLon - box.minLon > box.maxLat - box.minLat);
        assertTrue(box.minLon < -0.1276 && -0.1276 < box.maxLon);
    }

    @Test
    public void boundingBoxAcrossThe180Line() {
        // Fiji: the box around 179.9 would cross into -179.x, so only latitude is filtered
        GeoUtil.BoundingBox east = GeoUtil.boundingBox(-17.7, 179.9, 25);
        assertFalse(east.filterLongitude);
        GeoUtil.BoundingBox west = GeoUtil.boundingBox(-17.7, -179.9, 25);
        assertFalse(west.filterLongitude);
        // a small box well away from the line still filters longitude
        assertTrue(GeoUtil.boundingBox(-17.7, 178.4, 5).filterLongitude);
    }

    @Test
    public void boundingBoxNearThePoles() {
        GeoUtil.BoundingBox north = GeoUtil.boundingBox(89.5, 10, 100);
        assertFalse(north.filterLongitude);
        assertEquals(90, north.maxLat, 1e-9);
    }

    @Test
    public void nearestArea() {
        Area dhanmondi = area(1, "Dhanmondi", DHANMONDI_LAT, DHANMONDI_LON);
        Area gulshan = area(5, "Gulshan 1", GULSHAN1_LAT, GULSHAN1_LON);
        assertEquals("Gulshan 1", GeoUtil.nearestArea(Arrays.asList(dhanmondi, gulshan), 23.7790, 90.4100).getName());
    }

    private static Area area(int id, String name, double lat, double lon) {
        Area a = new Area();
        a.setId(id);
        a.setName(name);
        a.setLatitude(lat);
        a.setLongitude(lon);
        return a;
    }
}
