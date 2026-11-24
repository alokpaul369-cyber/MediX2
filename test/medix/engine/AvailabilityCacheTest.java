package medix.engine;

import java.util.ArrayList;
import medix.model.PharmacyStock;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class AvailabilityCacheTest {

    @After
    public void clear() {
        AvailabilityCache.clear();
    }

    @Test
    public void keyIncludesMedicineAreaAndRadius() {
        assertEquals("5:23.75,90.37:5.0", AvailabilityCache.key(5, 23.7461, 90.3742, 5.0));
        assertEquals("5:23.75,90.37:any", AvailabilityCache.key(5, 23.7461, 90.3742, null));
    }

    @Test
    public void keyRoundsThePointToTwoDecimals() {
        // two points about 300 m apart share the cached rows; London and Dhaka do not
        assertEquals(AvailabilityCache.key(5, 23.7461, 90.3742, 5.0), AvailabilityCache.key(5, 23.7489, 90.3711, 5.0));
        assertEquals("5:51.51,-0.13:25.0", AvailabilityCache.key(5, 51.5072, -0.1276, 25.0));
    }

    @Test
    public void invalidateRemovesOnlyThatMedicine() {
        AvailabilityCache.put(AvailabilityCache.key(5, 23.7461, 90.3742, 5.0), new ArrayList<PharmacyStock>());
        AvailabilityCache.put(AvailabilityCache.key(5, 22.3338, 91.8344, null), new ArrayList<PharmacyStock>());
        AvailabilityCache.put(AvailabilityCache.key(55, 23.7461, 90.3742, 5.0), new ArrayList<PharmacyStock>());
        AvailabilityCache.invalidateMedicine(5);
        assertNull(AvailabilityCache.get(AvailabilityCache.key(5, 23.7461, 90.3742, 5.0)));
        assertNull(AvailabilityCache.get(AvailabilityCache.key(5, 22.3338, 91.8344, null)));
        assertNotNull("medicine 55 must stay", AvailabilityCache.get(AvailabilityCache.key(55, 23.7461, 90.3742, 5.0)));
    }

    @Test
    public void keepsAtMost200LeastRecentlyUsedEntries() {
        for (int i = 0; i < AvailabilityCache.MAX_ENTRIES; i++) {
            AvailabilityCache.put(AvailabilityCache.key(i, 23.7461, 90.3742, 5.0), new ArrayList<PharmacyStock>());
        }
        AvailabilityCache.get(AvailabilityCache.key(0, 23.7461, 90.3742, 5.0)); // 0 is now recently used
        AvailabilityCache.put(AvailabilityCache.key(999, 23.7461, 90.3742, 5.0), new ArrayList<PharmacyStock>());
        assertEquals(AvailabilityCache.MAX_ENTRIES, AvailabilityCache.size());
        assertNotNull(AvailabilityCache.get(AvailabilityCache.key(0, 23.7461, 90.3742, 5.0)));
        assertNull("the least recently used entry is dropped", AvailabilityCache.get(AvailabilityCache.key(1, 23.7461, 90.3742, 5.0)));
    }
}
