package medix.forms;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.MouseInputListener;
import javax.swing.table.DefaultTableModel;
import medix.engine.GeoUtil;
import medix.engine.GeocodingService;
import medix.model.Place;
import medix.service.LocationService;
import medix.util.AppPaths;
import medix.util.ErrorLog;
import medix.util.TableUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;
import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.OSMTileFactoryInfo;
import org.jxmapviewer.cache.FileBasedLocalCache;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;
import org.jxmapviewer.painter.CompoundPainter;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.DefaultWaypoint;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.Waypoint;
import org.jxmapviewer.viewer.WaypointPainter;

/**
 * Pop-up window to choose a place anywhere on Earth: search (OpenStreetMap Nominatim, saved places
 * when offline), click the map (JXMapViewer2 + OpenStreetMap tiles), type coordinates or pick a
 * recent place. The window that opened it is disabled until it closes; the chosen place is saved
 * in areas and handed back through {@link PlacePickedListener}.
 */
public class LocationPickerFrame extends javax.swing.JFrame {

    /** Receives the chosen place (already saved: getSavedId() is set). */
    public interface PlacePickedListener {
        void placePicked(Place place);
    }

    private static final String TITLE = "MediX – Choose Location";
    private static final String TILE_PROBE = "https://tile.openstreetmap.org/0/0/0.png";
    private static final String ATTRIBUTION = "© OpenStreetMap contributors";
    /** JXMapViewer zoom (1 = closest … 17): a city, the whole world. */
    private static final int ZOOM_CITY = 6;
    private static final int ZOOM_WORLD = 16;
    private static final int RESULT_TEXT_WIDTH = 360;

    private final LocationService locationService = new LocationService();
    private final JFrame owner;
    private final PlacePickedListener listener;
    private DefaultTableModel resultModel;
    private List<Place> results = new ArrayList<Place>();
    private List<Place> recent = new ArrayList<Place>();
    private Place chosen;
    /** Increases with every new point, so a slow reverse lookup for an older click is ignored. */
    private int pointRequest;
    private JXMapViewer map;
    private final WaypointPainter<Waypoint> markerPainter = new WaypointPainter<Waypoint>();

    /** Opens the picker over `owner` (disabled until the picker closes). */
    public LocationPickerFrame(JFrame owner, PlacePickedListener listener) {
        this(owner, listener, null, null, null);
    }

    /**
     * @param search  text to show in the search box (e.g. the typed address), or null
     * @param found   places to list right away (e.g. several matches for an address), or null
     * @param current the place chosen so far, shown on the map, or null
     */
    public LocationPickerFrame(JFrame owner, PlacePickedListener listener, String search, List<Place> found,
            Place current) {
        initComponents();
        this.owner = owner;
        this.listener = listener;
        applyTheme();
        setupResults();
        setupMap();
        loadRecent();
        if (search != null) {
            txtPlaceSearch.setText(search);
        }
        if (found != null && !found.isEmpty()) {
            showResults(found);
            setStatus(found.size() + " places match. Choose the right one.", false);
        }
        choose(current, true);
        if (owner != null) {
            owner.setEnabled(false);
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    owner.setEnabled(true);
                    owner.toFront();
                }
            });
        }
        getRootPane().setDefaultButton(btnUseThisLocation);
        SwingUtilities.invokeLater(txtPlaceSearch::requestFocusInWindow);
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleHeading(lblSearch);
        UITheme.styleHeading(lblCoordinates);
        UITheme.styleHeading(lblRecent);
        UITheme.styleMuted(lblStatus);
        UITheme.styleMuted(lblMapHint);
        lblChosen.setFont(UITheme.FONT_BODY_BOLD);
        lblChosen.setForeground(UITheme.SIDEBAR);
        UITheme.styleCard(pnlMap);
        txtPlaceSearch.putClientProperty("JTextField.placeholderText", "e.g. Dhanmondi, Dhaka  ·  Agrabad, Chattogram  ·  London");
        txtLatitude.putClientProperty("JTextField.placeholderText", "-90 to 90");
        txtLongitude.putClientProperty("JTextField.placeholderText", "-180 to 180");
        UITheme.stylePrimaryButton(btnFind);
        UITheme.stylePrimaryButton(btnUseThisLocation);
        lstRecent.setFont(UITheme.FONT_BODY);
    }

    private void setupResults() {
        resultModel = TableUtil.setupTable(tblPlaceResults, "ID", "Place", "Country", "Latitude", "Longitude");
        TableUtil.setColumnWidth(tblPlaceResults, 1, 190);
        TableUtil.setColumnWidth(tblPlaceResults, 2, 90);
        TableUtil.setColumnWidth(tblPlaceResults, 3, 70);
        TableUtil.setColumnWidth(tblPlaceResults, 4, 70);
        tblPlaceResults.getSelectionModel().addListSelectionListener(e -> {
            int row = tblPlaceResults.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0) {
                choose(results.get(tblPlaceResults.convertRowIndexToModel(row)), true);
            }
        });
        lstRecent.addListSelectionListener(e -> {
            int i = lstRecent.getSelectedIndex();
            if (!e.getValueIsAdjusting() && i >= 0 && i < recent.size()) {
                choose(recent.get(i), true);
            }
        });
    }

    private void loadRecent() {
        recent = locationService.getRecent();
        DefaultListModel<String> model = new DefaultListModel<String>();
        for (Place p : recent) {
            model.addElement(p.getDisplayName());
        }
        if (recent.isEmpty()) {
            model.addElement("No places chosen yet");
            lstRecent.setEnabled(false);
        }
        lstRecent.setModel(model);
    }

    // ---------------------------------------------------------------- map (JXMapViewer2)

    /** Adds the OpenStreetMap map to pnlMap; without a connection "Map not available offline". */
    private void setupMap() {
        try {
            map = new JXMapViewer();
            DefaultTileFactory tiles = new DefaultTileFactory(new OSMTileFactoryInfo());
            tiles.setUserAgent(GeocodingService.userAgent()); // OSM tile usage policy
            tiles.setThreadPoolSize(2);
            tiles.setLocalCache(new FileBasedLocalCache(new File(AppPaths.getDataDir(), "tile-cache"), false));
            map.setTileFactory(tiles);
            map.setZoom(ZOOM_WORLD);
            map.setAddressLocation(new GeoPosition(20, 0));
            MouseInputListener pan = new PanMouseInputListener(map);
            map.addMouseListener(pan);
            map.addMouseMotionListener(pan);
            map.addMouseWheelListener(new ZoomMouseWheelListenerCursor(map));
            map.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                        GeoPosition gp = map.convertPointToGeoPosition(e.getPoint());
                        pointChosen(gp.getLatitude(), gp.getLongitude(), false);
                    }
                }
            });
            List<Painter<JXMapViewer>> painters = new ArrayList<Painter<JXMapViewer>>();
            painters.add(markerPainter);
            painters.add(LocationPickerFrame::paintAttribution);
            map.setOverlayPainter(new CompoundPainter<JXMapViewer>(painters));
            pnlMap.setLayout(new BorderLayout());
            pnlMap.add(map, BorderLayout.CENTER);
            checkTilesOnline();
        } catch (RuntimeException | LinkageError e) {
            ErrorLog.log("The map could not be created", e);
            mapUnavailable();
        }
    }

    /** One quick request to the tile server; hides the map when it cannot be reached. */
    private void checkTilesOnline() {
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    HttpURLConnection con = (HttpURLConnection) new URL(TILE_PROBE).openConnection();
                    con.setRequestMethod("HEAD");
                    con.setRequestProperty("User-Agent", GeocodingService.userAgent());
                    con.setConnectTimeout(GeocodingService.TIMEOUT_MILLIS);
                    con.setReadTimeout(GeocodingService.TIMEOUT_MILLIS);
                    return con.getResponseCode() == HttpURLConnection.HTTP_OK;
                } catch (Exception e) {
                    ErrorLog.log("Map tiles cannot be loaded", e);
                    return false;
                }
            }

            @Override
            protected void done() {
                try {
                    if (!get()) {
                        mapUnavailable();
                    }
                } catch (Exception e) {
                    mapUnavailable();
                }
            }
        }.execute();
    }

    private void mapUnavailable() {
        map = null;
        pnlMap.removeAll();
        pnlMap.setLayout(new BorderLayout());
        JLabel offline = new JLabel("Map not available offline", SwingConstants.CENTER);
        UITheme.styleMuted(offline);
        offline.setFont(UITheme.FONT_HEADING);
        pnlMap.add(offline, BorderLayout.CENTER);
        pnlMap.revalidate();
        pnlMap.repaint();
        lblMapHint.setText(UITheme.wrapHtml("Search for a place or type the latitude and longitude.", 300));
    }

    /** "© OpenStreetMap contributors" in the bottom-right corner (required by the tile licence). */
    private static void paintAttribution(Graphics2D g, JXMapViewer viewer, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(UITheme.FONT_BODY.deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(ATTRIBUTION) + 10;
        int h = fm.getHeight() + 2;
        g2.setColor(new Color(255, 255, 255, 210));
        g2.fillRect(width - w, height - h, w, h);
        g2.setColor(UITheme.TEXT);
        g2.drawString(ATTRIBUTION, width - w + 5, height - fm.getDescent() - 2);
        g2.dispose();
    }

    /** Moves the map to the place and shows the marker there. */
    private void showOnMap(Place p, boolean zoomIn) {
        if (map == null) {
            return;
        }
        if (p == null) {
            markerPainter.setWaypoints(Collections.<Waypoint>emptySet());
        } else {
            GeoPosition gp = new GeoPosition(p.getLatitude(), p.getLongitude());
            markerPainter.setWaypoints(Collections.<Waypoint>singleton(new DefaultWaypoint(gp)));
            if (zoomIn && map.getZoom() > ZOOM_CITY) {
                map.setZoom(ZOOM_CITY);
            }
            map.setAddressLocation(gp);
        }
        map.repaint();
    }

    // ---------------------------------------------------------------- choosing

    /** A search result, recent or initial place was chosen (null = nothing). */
    private void choose(Place p, boolean moveMap) {
        chosen = p;
        if (p != null) {
            txtLatitude.setText(GeoUtil.formatCoordinateFull(p.getLatitude()));
            txtLongitude.setText(GeoUtil.formatCoordinateFull(p.getLongitude()));
            if (moveMap) {
                showOnMap(p, true);
            }
        }
        updateChosen();
    }

    private void updateChosen() {
        if (chosen == null) {
            lblChosen.setText("Chosen: nothing yet – search, click the map or type coordinates.");
        } else {
            lblChosen.setText(UITheme.wrapHtml("Chosen: " + escape(chosen.getDisplayName()) + " ("
                    + chosen.formatCoordinates() + ")", 560));
        }
        btnUseThisLocation.setEnabled(chosen != null);
    }

    /** A clicked or typed point: chosen at once, then named by reverse geocoding in the background. */
    private void pointChosen(double lat, double lon, boolean moveMap) {
        final double la = GeoUtil.round6(lat);
        final double lo = GeoUtil.round6(lon);
        tblPlaceResults.clearSelection();
        lstRecent.clearSelection();
        choose(LocationService.point(la, lo), false);
        showOnMap(chosen, moveMap);
        setStatus("Finding the place name…", false);
        final int request = ++pointRequest;
        new SwingWorker<Place, Void>() {
            @Override
            protected Place doInBackground() throws Exception {
                return locationService.reverse(la, lo);
            }

            @Override
            protected void done() {
                if (request != pointRequest) {
                    return; // the user chose another point meanwhile
                }
                try {
                    Place named = get();
                    if (named != null) {
                        choose(named, false);
                        setStatus(" ", false);
                    } else {
                        setStatus("No place name found for this point. You can still use it.", false);
                    }
                } catch (Exception e) {
                    ErrorLog.log("Reverse geocoding failed", e);
                    setStatus(LocationService.MSG_OFFLINE, true);
                }
            }
        }.execute();
    }

    private void search() {
        final String text = txtPlaceSearch.getText().trim();
        if (text.isEmpty()) {
            fail("Type a place to search for, e.g. Dhanmondi, Dhaka or London.", txtPlaceSearch);
            return;
        }
        btnFind.setEnabled(false);
        txtPlaceSearch.setEnabled(false);
        setStatus("Searching…", false);
        new SwingWorker<LocationService.PlaceSearch, Void>() {
            @Override
            protected LocationService.PlaceSearch doInBackground() throws Exception {
                return locationService.search(text);
            }

            @Override
            protected void done() {
                btnFind.setEnabled(true);
                txtPlaceSearch.setEnabled(true);
                try {
                    LocationService.PlaceSearch found = get();
                    showResults(found.places);
                    if (found.offline) {
                        setStatus(LocationService.MSG_OFFLINE, true);
                    } else if (found.places.isEmpty()) {
                        setStatus("No place found. Try a shorter name (area, city, country), click the map "
                                + "or type the coordinates.", true);
                    } else {
                        setStatus(found.places.size() == 1 ? "1 place found." : found.places.size() + " places found.",
                                false);
                    }
                    if (found.places.size() == 1) {
                        tblPlaceResults.setRowSelectionInterval(0, 0);
                    }
                } catch (Exception e) {
                    setStatus(ErrorLog.describe(e), true);
                }
            }
        }.execute();
    }

    private void showResults(List<Place> places) {
        results = new ArrayList<Place>(places);
        TableUtil.clear(resultModel);
        for (Place p : results) {
            resultModel.addRow(new Object[]{p.getSavedId() == null ? "—" : String.valueOf(p.getSavedId()),
                p.getDisplayName(), p.getCountry() == null ? "" : p.getCountry(),
                GeoUtil.formatCoordinate(p.getLatitude()), GeoUtil.formatCoordinate(p.getLongitude())});
        }
    }

    private void useCoordinates() {
        Double lat = ValidationUtil.parseCoordinate(txtLatitude.getText(), true);
        if (lat == null) {
            fail("Latitude must be a number from -90 to 90 with up to 6 decimals, e.g. 23.7461.", txtLatitude);
            return;
        }
        Double lon = ValidationUtil.parseCoordinate(txtLongitude.getText(), false);
        if (lon == null) {
            fail("Longitude must be a number from -180 to 180 with up to 6 decimals, e.g. 90.3742.", txtLongitude);
            return;
        }
        pointChosen(lat, lon, true);
    }

    /** Saves the chosen place, remembers it as recent, hands it back and closes. */
    private void useThisLocation() {
        if (chosen == null) {
            return;
        }
        try {
            Place saved = locationService.save(chosen);
            locationService.remember(saved);
            dispose();
            if (listener != null) {
                listener.placePicked(saved);
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not save the place:\n" + ErrorLog.describe(e), TITLE,
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void setStatus(String text, boolean warning) {
        if (warning) {
            UITheme.styleStatus(lblStatus, UITheme.ORANGE);
        } else {
            UITheme.styleMuted(lblStatus);
        }
        lblStatus.setText(UITheme.wrapHtml(escape(text), RESULT_TEXT_WIDTH));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void fail(String message, JComponent field) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.WARNING_MESSAGE);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        lblSearch = new javax.swing.JLabel();
        txtPlaceSearch = new javax.swing.JTextField();
        btnFind = new javax.swing.JButton();
        scrPlaceResults = new javax.swing.JScrollPane();
        tblPlaceResults = new javax.swing.JTable();
        lblStatus = new javax.swing.JLabel();
        lblCoordinates = new javax.swing.JLabel();
        lblLatitude = new javax.swing.JLabel();
        txtLatitude = new javax.swing.JTextField();
        lblLongitude = new javax.swing.JLabel();
        txtLongitude = new javax.swing.JTextField();
        btnUseCoordinates = new javax.swing.JButton();
        lblRecent = new javax.swing.JLabel();
        scrRecent = new javax.swing.JScrollPane();
        lstRecent = new javax.swing.JList<>();
        pnlMap = new javax.swing.JPanel();
        lblMapHint = new javax.swing.JLabel();
        lblChosen = new javax.swing.JLabel();
        btnCancel = new javax.swing.JButton();
        btnUseThisLocation = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("MediX – Choose Location");
        setPreferredSize(new java.awt.Dimension(780, 580));

        lblSearch.setText("Search for a place");

        txtPlaceSearch.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtPlaceSearchActionPerformed(evt);
            }
        });

        btnFind.setText("Find");
        btnFind.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnFindActionPerformed(evt);
            }
        });

        scrPlaceResults.setViewportView(tblPlaceResults);

        lblStatus.setText(" ");

        lblCoordinates.setText("Or type the coordinates");

        lblLatitude.setText("Latitude");

        lblLongitude.setText("Longitude");

        btnUseCoordinates.setText("Go");
        btnUseCoordinates.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnUseCoordinatesActionPerformed(evt);
            }
        });

        lblRecent.setText("Recent places");

        scrRecent.setViewportView(lstRecent);

        javax.swing.GroupLayout pnlMapLayout = new javax.swing.GroupLayout(pnlMap);
        pnlMap.setLayout(pnlMapLayout);
        pnlMapLayout.setHorizontalGroup(
            pnlMapLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 340, Short.MAX_VALUE)
        );
        pnlMapLayout.setVerticalGroup(
            pnlMapLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 380, Short.MAX_VALUE)
        );

        lblMapHint.setText("Click the map to choose a point. Scroll to zoom, drag to move.");

        lblChosen.setText("Chosen: nothing yet");

        btnCancel.setText("Cancel");
        btnCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelActionPerformed(evt);
            }
        });

        btnUseThisLocation.setText("Use this location");
        btnUseThisLocation.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnUseThisLocationActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblSearch)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(txtPlaceSearch, javax.swing.GroupLayout.DEFAULT_SIZE, 300, Short.MAX_VALUE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnFind))
                            .addComponent(scrPlaceResults, javax.swing.GroupLayout.DEFAULT_SIZE, 390, Short.MAX_VALUE)
                            .addComponent(lblStatus, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblCoordinates)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(lblLatitude)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtLatitude, javax.swing.GroupLayout.DEFAULT_SIZE, 90, Short.MAX_VALUE)
                                .addGap(10, 10, 10)
                                .addComponent(lblLongitude)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtLongitude, javax.swing.GroupLayout.DEFAULT_SIZE, 90, Short.MAX_VALUE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnUseCoordinates))
                            .addComponent(lblRecent)
                            .addComponent(scrRecent, javax.swing.GroupLayout.DEFAULT_SIZE, 390, Short.MAX_VALUE))
                        .addGap(16, 16, 16)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(pnlMap, javax.swing.GroupLayout.DEFAULT_SIZE, 340, Short.MAX_VALUE)
                            .addComponent(lblMapHint, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                    .addComponent(lblChosen, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addComponent(btnCancel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnUseThisLocation)))
                .addGap(16, 16, 16))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblSearch)
                        .addGap(6, 6, 6)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(txtPlaceSearch, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(btnFind))
                        .addGap(8, 8, 8)
                        .addComponent(scrPlaceResults, javax.swing.GroupLayout.DEFAULT_SIZE, 150, Short.MAX_VALUE)
                        .addGap(4, 4, 4)
                        .addComponent(lblStatus)
                        .addGap(10, 10, 10)
                        .addComponent(lblCoordinates)
                        .addGap(6, 6, 6)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(lblLatitude)
                            .addComponent(txtLatitude, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(lblLongitude)
                            .addComponent(txtLongitude, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(btnUseCoordinates))
                        .addGap(10, 10, 10)
                        .addComponent(lblRecent)
                        .addGap(6, 6, 6)
                        .addComponent(scrRecent, javax.swing.GroupLayout.PREFERRED_SIZE, 96, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(pnlMap, javax.swing.GroupLayout.DEFAULT_SIZE, 380, Short.MAX_VALUE)
                        .addGap(4, 4, 4)
                        .addComponent(lblMapHint)))
                .addGap(12, 12, 12)
                .addComponent(lblChosen)
                .addGap(10, 10, 10)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnCancel)
                    .addComponent(btnUseThisLocation))
                .addGap(14, 14, 14))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void txtPlaceSearchActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtPlaceSearchActionPerformed
        search();
    }//GEN-LAST:event_txtPlaceSearchActionPerformed

    private void btnFindActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnFindActionPerformed
        search();
    }//GEN-LAST:event_btnFindActionPerformed

    private void btnUseCoordinatesActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUseCoordinatesActionPerformed
        useCoordinates();
    }//GEN-LAST:event_btnUseCoordinatesActionPerformed

    private void btnCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelActionPerformed
        dispose();
    }//GEN-LAST:event_btnCancelActionPerformed

    private void btnUseThisLocationActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUseThisLocationActionPerformed
        useThisLocation();
    }//GEN-LAST:event_btnUseThisLocationActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCancel;
    private javax.swing.JButton btnFind;
    private javax.swing.JButton btnUseCoordinates;
    private javax.swing.JButton btnUseThisLocation;
    private javax.swing.JLabel lblChosen;
    private javax.swing.JLabel lblCoordinates;
    private javax.swing.JLabel lblLatitude;
    private javax.swing.JLabel lblLongitude;
    private javax.swing.JLabel lblMapHint;
    private javax.swing.JLabel lblRecent;
    private javax.swing.JLabel lblSearch;
    private javax.swing.JLabel lblStatus;
    private javax.swing.JList<String> lstRecent;
    private javax.swing.JPanel pnlMap;
    private javax.swing.JScrollPane scrPlaceResults;
    private javax.swing.JScrollPane scrRecent;
    private javax.swing.JTable tblPlaceResults;
    private javax.swing.JTextField txtLatitude;
    private javax.swing.JTextField txtLongitude;
    private javax.swing.JTextField txtPlaceSearch;
    // End of variables declaration//GEN-END:variables
}
