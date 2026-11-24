package medix.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import medix.model.Place;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Parsing of real Nominatim answers saved in test/medix/engine/*.json (no network calls).
 */
public class GeocodingServiceTest {

    @BeforeClass
    public static void useTempFolder() throws IOException {
        // userAgent() reads data/config.properties: never the real data/ folder
        System.setProperty("user.dir", java.nio.file.Files.createTempDirectory("medix-test").toString());
    }

    @Test
    public void searchWithOneResult() throws Exception {
        List<Place> places = GeocodingService.parseSearch(resource("nominatim_search_chattogram.json"));
        assertEquals(1, places.size());
        Place p = places.get(0);
        assertTrue(p.getDisplayName().startsWith("Chattogram"));
        assertEquals("Chattogram", p.getCity());
        assertEquals("Bangladesh", p.getCountry());
        assertEquals("country codes are upper case", "BD", p.getCountryCode());
        assertEquals(22.33, p.getLatitude(), 0.01);
        assertEquals(91.83, p.getLongitude(), 0.01);
        assertEquals("Chattogram", p.getName());
        assertEquals("Chattogram, Bangladesh", p.getShortName());
        assertNull("not saved yet", p.getSavedId());
    }

    @Test
    public void searchWithSeveralResultsKeepsTheirOrder() throws Exception {
        List<Place> places = GeocodingService.parseSearch(resource("nominatim_search_springfield.json"));
        assertEquals(5, places.size());
        assertTrue(places.get(0).getDisplayName().contains("Illinois"));
        assertTrue(places.get(4).getDisplayName().contains("Oregon"));
        for (Place p : places) {
            assertEquals("Springfield", p.getCity());
            assertEquals("US", p.getCountryCode());
            assertTrue(p.getLongitude() < -60); // all in the USA
        }
    }

    @Test
    public void reverseGivesTheAddressAtThePoint() throws Exception {
        Place p = GeocodingService.parseReverse(resource("nominatim_reverse_london.json"));
        assertTrue(p.getDisplayName().contains("London"));
        assertEquals("United Kingdom", p.getCountry());
        assertEquals("GB", p.getCountryCode());
        assertEquals(51.507, p.getLatitude(), 0.01);
        assertEquals(-0.128, p.getLongitude(), 0.01);
    }

    @Test
    public void reverseOverTheSeaFindsNothing() throws Exception {
        assertNull(GeocodingService.parseReverse(resource("nominatim_reverse_sea.json")));
    }

    @Test
    public void emptyAndBrokenAnswers() throws Exception {
        assertEquals(0, GeocodingService.parseSearch("[]").size());
        // a result without coordinates or name is skipped
        assertEquals(0, GeocodingService.parseSearch("[{\"display_name\": \"x\"}, {\"lat\": \"1\", \"lon\": \"2\"}]").size());
        // city falls back to town, village … state
        Place p = GeocodingService.parseSearch("[{\"display_name\": \"Rabi, Fiji\", \"lat\": \"-16.5\", \"lon\": \"-179.97\","
                + " \"address\": {\"village\": \"Rabi\", \"state\": \"Northern\", \"country\": \"Fiji\", \"country_code\": \"fj\"}}]")
                .get(0);
        assertEquals("Rabi", p.getCity());
        assertEquals("FJ", p.getCountryCode());
        try {
            GeocodingService.parseSearch("<html>Too many requests</html>");
            fail("an HTML error page is not JSON");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("map service"));
        }
    }

    @Test
    public void userAgentIdentifiesMediX() {
        assertTrue(GeocodingService.userAgent().startsWith("MediX/1.0 (university project"));
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = GeocodingServiceTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Missing test resource " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
