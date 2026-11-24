package medix.util;

import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.JTextComponent;
import medix.engine.GeoUtil;
import medix.forms.LocationPickerFrame;
import medix.model.Place;
import medix.service.LocationService;

/**
 * The pharmacy location fields shared by RegisterFrame (Pharmacy tab) and PharmacyProfileFrame:
 * address + latitude / longitude + "Find from address" + "Pick on map" + the found-place label.
 * Find from address: map service first, saved places offline; 1 result fills the coordinates,
 * several open LocationPickerFrame, none explains what to try. Typed coordinates are looked up
 * (reverse geocoding) when a coordinate field loses the focus. Network calls run in SwingWorkers.
 */
public final class PharmacyLocationFields {

    public static final String MSG_ADDRESS_NOT_FOUND = "Address not found. Try a shorter address (area, city, country) "
            + "or click Pick on map.";
    public static final String MSG_COORDINATES = "Latitude must be from -90 to 90 and longitude from -180 to 180, "
            + "with up to 6 decimals (e.g. 23.746100 and 90.374200).";

    private final LocationService locationService = new LocationService();
    private final JFrame frame;
    private final String title;
    private final JTextComponent address;
    private final JTextComponent latitude;
    private final JTextComponent longitude;
    private final JLabel found;
    private final JButton findButton;
    private final int foundWidth;
    /** The place found for the current coordinates, or null. */
    private Place place;
    /** Increases with every lookup, so an older answer is ignored. */
    private int lookup;

    public PharmacyLocationFields(JFrame frame, String title, JTextComponent address, JTextComponent latitude,
            JTextComponent longitude, JLabel found, JButton findButton, int foundWidth) {
        this.frame = frame;
        this.title = title;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.found = found;
        this.findButton = findButton;
        this.foundWidth = foundWidth;
        UITheme.styleMuted(found);
        latitude.putClientProperty("JTextField.placeholderText", "e.g. 23.746100");
        longitude.putClientProperty("JTextField.placeholderText", "e.g. 90.374200");
        FocusAdapter typed = new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                lookUpTypedCoordinates();
            }
        };
        latitude.addFocusListener(typed);
        longitude.addFocusListener(typed);
    }

    /** Shows a known place (e.g. the saved one when the profile opens) without a lookup. */
    public void show(Place p, double lat, double lon) {
        lookup++;
        place = p;
        latitude.setText(GeoUtil.formatCoordinateFull(lat));
        longitude.setText(GeoUtil.formatCoordinateFull(lon));
        setFoundText(p == null ? " " : "Found: " + p.getDisplayName());
    }

    /** null when blank or invalid. */
    public Double getLatitude() {
        return ValidationUtil.parseCoordinate(latitude.getText(), true);
    }

    public Double getLongitude() {
        return ValidationUtil.parseCoordinate(longitude.getText(), false);
    }

    /** The place found for these coordinates, or the point itself (saved later as a MANUAL place). */
    public Place placeFor(double lat, double lon) {
        return matches(place, lat, lon) ? place : LocationService.point(lat, lon);
    }

    /**
     * Checks the coordinates; shows the problem and returns false. Both blank asks the user to
     * use Find from address / Pick on map.
     */
    public boolean check() {
        if (ValidationUtil.isBlank(latitude.getText()) && ValidationUtil.isBlank(longitude.getText())) {
            return fail("Set the pharmacy location: click Find from address or Pick on map, "
                    + "or type the latitude and longitude.", findButton);
        }
        if (getLatitude() == null) {
            return fail(MSG_COORDINATES, latitude);
        }
        if (getLongitude() == null) {
            return fail(MSG_COORDINATES, longitude);
        }
        return true;
    }

    public void findFromAddress() {
        final String text = address.getText().trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            fail("Type the address first, for example: House 12, Road 5, Dhanmondi, Dhaka.", address);
            return;
        }
        findButton.setEnabled(false);
        setFoundText("Searching…");
        new SwingWorker<LocationService.PlaceSearch, Void>() {
            @Override
            protected LocationService.PlaceSearch doInBackground() throws Exception {
                return locationService.search(text);
            }

            @Override
            protected void done() {
                findButton.setEnabled(true);
                try {
                    LocationService.PlaceSearch result = get();
                    if (result.places.isEmpty()) {
                        setFoundText(" ");
                        fail(result.offline ? LocationService.MSG_OFFLINE : MSG_ADDRESS_NOT_FOUND, address);
                    } else if (result.places.size() == 1) {
                        choose(result.places.get(0));
                    } else {
                        setFoundText(result.places.size() + " places match – choose one.");
                        new LocationPickerFrame(frame, PharmacyLocationFields.this::choose, text, result.places, null)
                                .setVisible(true);
                    }
                } catch (Exception e) {
                    setFoundText(" ");
                    JOptionPane.showMessageDialog(frame, "Could not search the address:\n" + ErrorLog.describe(e),
                            title, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** The map picker, starting at the current place / coordinates. */
    public void pickOnMap() {
        Double lat = getLatitude();
        Double lon = getLongitude();
        Place current = lat == null || lon == null ? place : placeFor(lat, lon);
        new LocationPickerFrame(frame, this::choose, address.getText().trim(), null, current).setVisible(true);
    }

    /** A place was chosen: coordinates with up to 6 decimals and its name under the row. */
    private void choose(Place p) {
        show(p, p.getLatitude(), p.getLongitude());
    }

    private void lookUpTypedCoordinates() {
        final Double lat = getLatitude();
        final Double lon = getLongitude();
        if (lat == null || lon == null || matches(place, lat, lon)) {
            return;
        }
        place = null;
        final int request = ++lookup;
        setFoundText("Finding the place for these coordinates…");
        new SwingWorker<Place, Void>() {
            @Override
            protected Place doInBackground() throws Exception {
                return locationService.reverse(lat, lon);
            }

            @Override
            protected void done() {
                if (request != lookup) {
                    return;
                }
                try {
                    Place named = get();
                    if (named == null) {
                        setFoundText("No place name found for these coordinates. Check them with Pick on map.");
                    } else {
                        place = named;
                        setFoundText("Found: " + named.getDisplayName());
                    }
                } catch (Exception e) {
                    setFoundText(LocationService.MSG_OFFLINE);
                }
            }
        }.execute();
    }

    /** True when the place is (almost) at the coordinates, i.e. it still describes them. */
    public static boolean matches(Place p, double lat, double lon) {
        return p != null && Math.abs(p.getLatitude() - lat) < LocationService.SAME_PLACE_DEGREES
                && Math.abs(p.getLongitude() - lon) < LocationService.SAME_PLACE_DEGREES;
    }

    private void setFoundText(String text) {
        found.setText(UITheme.wrapHtml(text.replace("&", "&amp;").replace("<", "&lt;"), foundWidth));
        found.setToolTipText(text.trim().isEmpty() ? null : text);
        Window w = SwingUtilities.getWindowAncestor(found);
        if (w != null) {
            w.validate();
        }
    }

    private boolean fail(String message, JComponent field) {
        JOptionPane.showMessageDialog(frame, message, title, JOptionPane.WARNING_MESSAGE);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
        return false;
    }
}
