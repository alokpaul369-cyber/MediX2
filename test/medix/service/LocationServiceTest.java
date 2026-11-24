package medix.service;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import medix.model.Place;
import medix.util.AppPaths;
import medix.util.Session;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Recent places are kept in data/recent-places.properties (not java.util.prefs / the registry).
 */
public class LocationServiceTest {

    @BeforeClass
    public static void createDatabase() throws Exception {
        File dir = Files.createTempDirectory("medix-test").toFile();
        System.setProperty("user.dir", dir.getAbsolutePath());
        new DatabaseService().initialize();
    }

    @Test
    public void recentPlacesAreStoredInTheDataFolderNewestFirstMaxFive() throws Exception {
        Session.logout(); // guest list
        LocationService locations = new LocationService();
        for (int id = 1; id <= 7; id++) {
            locations.remember(locations.getSaved(id));
        }
        locations.remember(locations.getSaved(4)); // chosen again: moves to the top
        List<Place> recent = locations.getRecent();
        assertEquals(5, recent.size());
        assertEquals(Integer.valueOf(4), recent.get(0).getSavedId());
        assertEquals(Integer.valueOf(7), recent.get(1).getSavedId());
        assertEquals(Integer.valueOf(6), recent.get(2).getSavedId());
        assertTrue(new File(AppPaths.getDataDir(), LocationService.RECENT_FILE).isFile());
        // a new service instance reads the same file
        assertEquals(Integer.valueOf(4), new LocationService().getRecent().get(0).getSavedId());
    }
}
