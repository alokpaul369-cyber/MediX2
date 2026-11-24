package medix.forms;

import java.awt.Component;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import medix.engine.ExchangeRateService;
import medix.engine.GeoUtil;
import medix.engine.OpeningHours;
import medix.engine.SearchEngine;
import medix.model.Medicine;
import medix.model.PharmacyStock;
import medix.model.Place;
import medix.model.SearchResult;
import medix.model.User;
import medix.service.LocationService;
import medix.service.MedicineService;
import medix.service.SearchService;
import medix.util.AutoComplete;
import medix.util.CurrencyUtil;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.MapUtil;
import medix.util.Session;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;

/**
 * Patient home: search a medicine (brand, generic or misspelled) and see verified pharmacies
 * that have it, nearest first. Stale stock (24+ hours) is yellow with ⚠.
 */
public class SearchFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Search Medicine";
    /** cmbWithin items and their values (null = any distance, sorted by distance). */
    private static final String[] RADIUS_LABELS = {"1 km", "2 km", "5 km", "10 km", "25 km", "50 km", "100 km",
        "Any distance"};
    private static final Double[] RADIUS_VALUES = {1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0, null};
    private static final int DEFAULT_RADIUS = 2; // 5 km
    private static final String CHOOSE_ANOTHER = "Choose another location…";
    private static final int COL_OPEN = 8;

    private final SearchService searchService = new SearchService();
    private final ExchangeRateService rateService = new ExchangeRateService();
    private final LocationService locationService = new LocationService();
    /** The places of cmbNear in item order (the last item, "Choose another location…", has none). */
    private List<Place> nearPlaces = new ArrayList<Place>();
    /** The place searched from. */
    private Place origin;
    private boolean updatingNear;
    private DefaultTableModel tableModel;
    private AutoComplete<Medicine> queryAutoComplete;
    /** Medicine picked in the suggestions (null when the text was typed freely). */
    private Medicine selectedMedicine;
    /** The medicine of the last search, re-used when the area or distance changes. */
    private Medicine lastMedicine;
    private SearchResult lastResult;
    /** Rows in table-model order. */
    private List<PharmacyStock> rows = new ArrayList<PharmacyStock>();
    private boolean settingUp = true;

    public SearchFrame() {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.PATIENT_SEARCH);
        setupControls();
        setupTable();
        showEmptyState();
        settingUp = false;
        SwingUtilities.invokeLater(txtQuery::requestFocusInWindow);
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleCard(pnlSearch);
        UITheme.stylePrimaryButton(btnSearch);
        UITheme.stylePrimaryButton(btnViewReserve);
        UITheme.styleHeading(lblMedicineName);
        UITheme.styleMuted(lblMedicineInfo);
        UITheme.styleMuted(lblStatus);
        lblRx.setOpaque(true);
        lblRx.setBackground(UITheme.DANGER);
        lblRx.setForeground(UITheme.WHITE);
        lblRx.setFont(UITheme.FONT_BODY_BOLD);
        lblRx.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        lblDidYouMean.setFont(UITheme.FONT_BODY_BOLD);
        lblDidYouMean.setForeground(UITheme.ORANGE);
        lblFooterNote.setForeground(UITheme.NOTE_WARNING_TEXT);
        UITheme.styleLinkButton(btnRatesCredit);
        btnRatesCredit.setToolTipText(ExchangeRateService.CREDIT_URL);
        btnRatesCredit.setVisible(false);
        UITheme.styleMuted(lblAlternatives);
        txtQuery.putClientProperty("JTextField.placeholderText", "e.g. Seclo, Napa or Paracetamol");
        getRootPane().setDefaultButton(btnSearch);
    }

    private void setupControls() {
        fillNear(null);
        cmbWithin.setModel(new DefaultComboBoxModel<String>(RADIUS_LABELS));
        cmbWithin.setSelectedIndex(DEFAULT_RADIUS);
        cmbSort.setModel(new DefaultComboBoxModel<SearchEngine.Sort>(SearchEngine.Sort.values()));
        queryAutoComplete = AutoComplete.attach(txtQuery, SearchService.MIN_SUGGEST_LETTERS, searchService::suggest,
                MedicineService::suggestionLabel, m -> {
                    selectedMedicine = m;
                    if (m != null) {
                        runSearch();
                    }
                });
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblResults,
                "ID", "Pharmacy", "Area", "Distance", "Available", "Price", "Updated", "Trust", "Open now");
        TableUtil.setColumnWidth(tblResults, 1, 180);
        TableUtil.setColumnWidth(tblResults, 2, 120);
        int[] widths = {0, 0, 0, 75, 70, 150, 80, 55, 80};
        for (int col = 3; col <= COL_OPEN; col++) {
            TableUtil.setColumnWidth(tblResults, col, widths[col]);
            TableUtil.centerColumn(tblResults, col);
        }
        tblResults.getColumnModel().getColumn(COL_OPEN).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                if (!selected) {
                    setForeground("Open".equals(value) ? UITheme.SUCCESS : UITheme.TEXT_MUTED);
                }
                setFont(UITheme.FONT_BODY_BOLD);
                return this;
            }
        });
        TableUtil.setRowBackground(tblResults,
                modelRow -> SearchEngine.isStale(rows.get(modelRow)) ? UITheme.ROW_LOW_STOCK : null);
        tblResults.getSelectionModel().addListSelectionListener(e -> updateButtons());
    }

    // ---------------------------------------------------------------- search

    /**
     * cmbNear: "My location (Dhanmondi, Dhaka)", the recent places, "Choose another location…".
     * select = the place to select (null = my location, else the first item).
     */
    private void fillNear(Place select) {
        nearPlaces = new ArrayList<Place>();
        List<String> labels = new ArrayList<String>();
        Place mine = myLocation();
        if (mine != null) {
            nearPlaces.add(mine);
            labels.add("My location (" + mine.getShortName() + ")");
        }
        for (Place p : locationService.getRecent()) {
            if (mine == null || mine.getSavedId() == null || !mine.getSavedId().equals(p.getSavedId())) {
                nearPlaces.add(p);
                labels.add(p.getShortName());
            }
        }
        if (select != null && select.getSavedId() != null) {
            boolean listed = false;
            for (Place p : nearPlaces) {
                listed |= select.getSavedId().equals(p.getSavedId());
            }
            if (!listed) {
                nearPlaces.add(select);
                labels.add(select.getShortName());
            }
        }
        labels.add(CHOOSE_ANOTHER);
        updatingNear = true;
        cmbNear.setModel(new DefaultComboBoxModel<String>(labels.toArray(new String[0])));
        int index = 0;
        for (int i = 0; select != null && i < nearPlaces.size(); i++) {
            if (select.getSavedId() != null && select.getSavedId().equals(nearPlaces.get(i).getSavedId())) {
                index = i;
            }
        }
        cmbNear.setSelectedIndex(nearPlaces.isEmpty() ? -1 : index);
        cmbNear.setToolTipText(nearPlaces.isEmpty() ? null : nearPlaces.get(index).getDisplayName());
        origin = nearPlaces.isEmpty() ? null : nearPlaces.get(index);
        updatingNear = false;
    }

    /** The patient's own location (rounded to about 1 km), or null when none is saved. */
    private Place myLocation() {
        User me = Session.getUser();
        if (me == null) {
            return null;
        }
        try {
            Place saved = locationService.getSaved(me.getAreaId());
            if (me.getLatitude() == null || me.getLongitude() == null) {
                return saved;
            }
            Place p = new Place(saved == null ? me.getLocationName() : saved.getDisplayName(), me.getLatitude(),
                    me.getLongitude(), saved == null ? null : saved.getCity(), saved == null ? null : saved.getCountry(),
                    saved == null ? null : saved.getCountryCode());
            p.setSavedId(me.getAreaId());
            return p;
        } catch (SQLException e) {
            ErrorLog.log("Could not read the user's location", e);
            return null;
        }
    }

    /** cmbNear changed: search again from the place, or open the picker for "Choose another location…". */
    private void nearChanged() {
        if (updatingNear) {
            return;
        }
        int i = cmbNear.getSelectedIndex();
        if (i >= 0 && i < nearPlaces.size()) {
            origin = nearPlaces.get(i);
            cmbNear.setToolTipText(origin.getDisplayName());
            searchAgain();
            return;
        }
        // "Choose another location…": keep the current place selected until a new one is picked
        Place current = origin;
        fillNear(current);
        new LocationPickerFrame(this, place -> {
            fillNear(place);
            searchAgain();
        }, null, null, current).setVisible(true);
    }

    private Double selectedRadius() {
        return RADIUS_VALUES[Math.max(0, cmbWithin.getSelectedIndex())];
    }

    private SearchEngine.Sort selectedSort() {
        return (SearchEngine.Sort) cmbSort.getSelectedItem();
    }

    /** A new search from the text box (or the picked suggestion). */
    private void runSearch() {
        String text = txtQuery.getText().trim();
        if (text.isEmpty() && selectedMedicine == null) {
            JOptionPane.showMessageDialog(this, "Type a medicine name, e.g. Napa or Paracetamol.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            SwingUtilities.invokeLater(txtQuery::requestFocusInWindow);
            return;
        }
        queryAutoComplete.hide();
        search(text, selectedMedicine);
    }

    private void search(String text, Medicine chosen) {
        if (origin == null) {
            JOptionPane.showMessageDialog(this, "Choose where to search from: pick \"" + CHOOSE_ANOTHER + "\" in Near.",
                    TITLE, JOptionPane.WARNING_MESSAGE);
            SwingUtilities.invokeLater(cmbNear::requestFocusInWindow);
            return;
        }
        try {
            lastResult = searchService.search(text, chosen, origin, selectedRadius(), selectedSort());
            lastMedicine = lastResult.getMedicine();
            showResult(lastResult);
        } catch (SQLException e) {
            showError("Could not search:\n" + ErrorLog.describe(e));
        }
    }

    /** The area or distance changed: search the same medicine again. */
    private void searchAgain() {
        if (!settingUp && lastMedicine != null) {
            search(txtQuery.getText().trim(), lastMedicine);
        }
    }

    // ---------------------------------------------------------------- showing results

    private void showEmptyState() {
        lblMedicineName.setText("Search for a medicine");
        lblRx.setVisible(false);
        lblStatus.setText(" ");
        lblMedicineInfo.setText("Type at least 2 letters for suggestions. A generic name or a misspelled name works too.");
        lblResultCount.setText(" ");
        lblDidYouMean.setText(" ");
        lblFooterNote.setText(" ");
        lblAlternatives.setText(" ");
        fillTable(new ArrayList<PharmacyStock>());
    }

    private void showResult(SearchResult r) {
        Medicine m = r.getMedicine();
        lblDidYouMean.setText(r.getDidYouMean() == null ? " " : "Did you mean " + r.getDidYouMean() + "?");
        lblStatus.setText("Searched in " + r.getElapsedMillis() + " ms" + (r.isCached() ? " (cached)" : "")
                + "  ·  from " + r.getOrigin().getShortName());
        if (m == null) {
            lblMedicineName.setText("No medicine found");
            lblRx.setVisible(false);
            lblMedicineInfo.setText("Nothing in the catalog matches “" + r.getQuery() + "”.");
            lblResultCount.setText("Check the spelling or search by the generic name (e.g. Omeprazole).");
            UITheme.styleStatus(lblResultCount, UITheme.DANGER);
            lblAlternatives.setText(" ");
            fillTable(new ArrayList<PharmacyStock>());
            return;
        }
        lblMedicineName.setText(m.isPending() ? "<html>" + MedicineService.label(m) + UITheme.newMedicineHtml() + "</html>"
                : MedicineService.label(m));
        lblRx.setVisible(m.isRxRequired());
        lblMedicineInfo.setText(m.getGenericName()
                + (m.getManufacturer() == null ? "" : "  ·  " + m.getManufacturer())
                + (m.isRxRequired() ? "  ·  Bring your prescription" : ""));
        int n = r.getRows().size();
        String within = r.getRadiusKm() == null ? "" : " within " + km(r.getRadiusKm());
        String shortName = m.getBrandName() + " " + m.getStrength();
        if (n > 0) {
            lblResultCount.setText(n + (n == 1 ? " pharmacy has it" : " pharmacies have it") + within);
            UITheme.styleStatus(lblResultCount, UITheme.SUCCESS);
        } else {
            lblResultCount.setText(r.getRadiusKm() == null
                    ? "No verified pharmacy has " + shortName + " in stock right now."
                    : "No verified pharmacy" + within + " of " + r.getOrigin().getName() + " has " + shortName
                    + " in stock. Try a bigger distance.");
            UITheme.styleStatus(lblResultCount, UITheme.DANGER);
        }
        lblAlternatives.setText(alternativesText(r));
        // "other brands in stock" is an answer, so it stands out; "also matches" is a hint
        boolean answer = r.getRows().isEmpty() && !r.getAlternatives().isEmpty();
        lblAlternatives.setFont(answer ? UITheme.FONT_BODY_BOLD : UITheme.FONT_BODY);
        lblAlternatives.setForeground(answer ? UITheme.ACCENT : UITheme.TEXT_MUTED);
        fillTable(r.getRows());
    }

    private String alternativesText(SearchResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.getRows().isEmpty() && !r.getAlternatives().isEmpty()) {
            sb.append("Same medicine, other brands: ");
            for (int i = 0; i < r.getAlternatives().size(); i++) {
                SearchResult.Alternative a = r.getAlternatives().get(i);
                Medicine m = a.getMedicine();
                sb.append(i == 0 ? "" : ",  ").append(m.getBrandName()).append(' ').append(m.getStrength())
                        .append(" (").append(a.getPharmacies()).append(a.getPharmacies() == 1 ? " pharmacy" : " pharmacies")
                        .append(", nearest ").append(GeoUtil.formatKm(a.getNearestKm())).append(')');
            }
        } else if (!r.getOtherMatches().isEmpty() && selectedMedicine == null) {
            sb.append("Also matches “").append(r.getQuery()).append("”: ");
            for (int i = 0; i < r.getOtherMatches().size() && i < 5; i++) {
                sb.append(i == 0 ? "" : ",  ").append(MedicineService.label(r.getOtherMatches().get(i)));
            }
            sb.append("  –  pick one from the suggestions to search it.");
        }
        return sb.length() == 0 ? " " : sb.toString();
    }

    private void fillTable(List<PharmacyStock> list) {
        rows = new ArrayList<PharmacyStock>(list);
        TableUtil.clear(tableModel);
        boolean anyStale = false;
        for (PharmacyStock s : rows) {
            boolean stale = SearchEngine.isStale(s);
            anyStale |= stale;
            tableModel.addRow(new Object[]{s.getPharmacyId(), s.getPharmacyName(), s.getAreaName(),
                GeoUtil.formatKm(s.getDistanceKm()), s.getAvailable(), priceText(s),
                stale ? "<html>" + DateUtil.ago(s.getLastUpdated()) + " " + UITheme.warnHtml() + "</html>"
                        : DateUtil.ago(s.getLastUpdated()),
                s.getTrustScore(), OpeningHours.isOpenNow(s.getOpenTime(), s.getCloseTime()) ? "Open" : "Closed"});
        }
        List<String> notes = new ArrayList<String>();
        if (anyStale) {
            notes.add(UITheme.warnHtml() + " Stock not updated for over 24 hours. Call the pharmacy first.");
        }
        if (lastResult != null && lastResult.isSomeNotComparable() && selectedSort() == SearchEngine.Sort.PRICE) {
            notes.add("Some prices could not be compared (no exchange rate).");
        }
        lblFooterNote.setText(notes.isEmpty() ? " " : "<html>" + String.join("&nbsp;&nbsp;·&nbsp;&nbsp;", notes) + "</html>");
        boolean converted = lastResult != null && !rows.isEmpty() && lastResult.hasForeignPrices();
        btnRatesCredit.setVisible(converted);
        if (converted) {
            btnRatesCredit.setText(rateService.creditLine());
        }
        updateButtons();
    }

    /** "Tk 7.00", or "£3.20 (≈ Tk 480)" when the pharmacy's currency is not the patient's. */
    private String priceText(PharmacyStock s) {
        if (s.getLowestPrice() == null) {
            return "–";
        }
        String own = CurrencyUtil.format(s.getLowestPrice(), s.getCurrency());
        String mine = lastResult == null ? s.getCurrency() : lastResult.getDisplayCurrency();
        if (s.getCurrency().equals(mine)) {
            return own;
        }
        return s.getComparablePrice() == null ? own
                : own + " (≈ " + CurrencyUtil.formatApprox(s.getComparablePrice(), mine) + ")";
    }

    /** Sort changed: re-order the rows already shown (no new search). */
    private void resort() {
        if (!settingUp && lastResult != null) {
            SearchEngine.sort(rows, selectedSort());
            fillTable(rows);
        }
    }

    private void updateButtons() {
        boolean selected = tblResults.getSelectedRow() >= 0;
        btnOpenMap.setEnabled(selected);
        btnViewReserve.setEnabled(selected);
    }

    private PharmacyStock selectedRow() {
        int viewRow = tblResults.getSelectedRow();
        return viewRow < 0 ? null : rows.get(tblResults.convertRowIndexToModel(viewRow));
    }

    private static String km(double radius) {
        return (radius == Math.rint(radius) ? String.valueOf((int) radius) : String.valueOf(radius)) + " km";
    }

    // ---------------------------------------------------------------- map

    private void openMap() {
        PharmacyStock p = selectedRow();
        if (p == null) {
            return;
        }
        String where = p.getPharmacyName() + " is at " + GeoUtil.formatCoordinate(p.getLatitude()) + ", "
                + GeoUtil.formatCoordinate(p.getLongitude())
                + (p.getAddress() == null ? "" : "\n" + p.getAddress())
                + (p.getPhone() == null ? "" : "\nPhone: " + p.getPhone());
        MapUtil.openMapOrExplain(this, btnOpenMap, TITLE, p.getLatitude(), p.getLongitude(), where);
    }

    /** Opens the pharmacy detail window; after a reservation the results are refreshed. */
    private void viewAndReserve() {
        PharmacyStock p = selectedRow();
        if (p == null || lastMedicine == null || lastResult == null) {
            return;
        }
        Place from = lastResult.getOrigin();
        new PharmacyDetailFrame(p.getPharmacyId(), lastMedicine.getId(), from.getLatitude(), from.getLongitude(),
                this::refreshAfterReservation).setVisible(true);
    }

    /** The reservation changed "Available": search the same medicine again (the cache was cleared). */
    private void refreshAfterReservation() {
        if (lastMedicine != null) {
            search(txtQuery.getText().trim(), lastMedicine);
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.ERROR_MESSAGE);
    }

    private void btnRatesCreditActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRatesCreditActionPerformed
        MapUtil.browse(this, ExchangeRateService.CREDIT_URL);
    }//GEN-LAST:event_btnRatesCreditActionPerformed

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        pnlSidebar = new javax.swing.JPanel();
        lblTitle = new javax.swing.JLabel();
        lblSubtitle = new javax.swing.JLabel();
        pnlSearch = new javax.swing.JPanel();
        lblQuery = new javax.swing.JLabel();
        txtQuery = new javax.swing.JTextField();
        lblLocation = new javax.swing.JLabel();
        cmbNear = new javax.swing.JComboBox<>();
        lblRadius = new javax.swing.JLabel();
        cmbWithin = new javax.swing.JComboBox<>();
        lblSort = new javax.swing.JLabel();
        cmbSort = new javax.swing.JComboBox<>();
        btnSearch = new javax.swing.JButton();
        lblMedicineName = new javax.swing.JLabel();
        lblRx = new javax.swing.JLabel();
        lblStatus = new javax.swing.JLabel();
        lblMedicineInfo = new javax.swing.JLabel();
        lblResultCount = new javax.swing.JLabel();
        lblDidYouMean = new javax.swing.JLabel();
        scrResults = new javax.swing.JScrollPane();
        tblResults = new javax.swing.JTable();
        lblFooterNote = new javax.swing.JLabel();
        btnRatesCredit = new javax.swing.JButton();
        btnOpenMap = new javax.swing.JButton();
        btnViewReserve = new javax.swing.JButton();
        lblAlternatives = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Search Medicine");
        setPreferredSize(new java.awt.Dimension(1200, 720));

        javax.swing.GroupLayout pnlSidebarLayout = new javax.swing.GroupLayout(pnlSidebar);
        pnlSidebar.setLayout(pnlSidebarLayout);
        pnlSidebarLayout.setHorizontalGroup(
            pnlSidebarLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 170, Short.MAX_VALUE)
        );
        pnlSidebarLayout.setVerticalGroup(
            pnlSidebarLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 720, Short.MAX_VALUE)
        );

        lblTitle.setText("Find a medicine");

        lblSubtitle.setText("Search by brand or generic name. Only verified pharmacies with stock are shown.");

        lblQuery.setText("Medicine");

        lblLocation.setText("Near");

        cmbNear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbNearActionPerformed(evt);
            }
        });

        lblRadius.setText("Within");

        cmbWithin.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbWithinActionPerformed(evt);
            }
        });

        lblSort.setText("Sort by");

        cmbSort.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbSortActionPerformed(evt);
            }
        });

        btnSearch.setText("Search");
        btnSearch.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSearchActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlSearchLayout = new javax.swing.GroupLayout(pnlSearch);
        pnlSearch.setLayout(pnlSearchLayout);
        pnlSearchLayout.setHorizontalGroup(
            pnlSearchLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlSearchLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlSearchLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblQuery)
                    .addComponent(txtQuery, javax.swing.GroupLayout.DEFAULT_SIZE, 380, Short.MAX_VALUE))
                .addGap(12, 12, 12)
                .addGroup(pnlSearchLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblLocation)
                    .addComponent(cmbNear, javax.swing.GroupLayout.PREFERRED_SIZE, 240, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(12, 12, 12)
                .addGroup(pnlSearchLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblRadius)
                    .addComponent(cmbWithin, javax.swing.GroupLayout.PREFERRED_SIZE, 110, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(12, 12, 12)
                .addGroup(pnlSearchLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblSort)
                    .addComponent(cmbSort, javax.swing.GroupLayout.PREFERRED_SIZE, 120, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(12, 12, 12)
                .addComponent(btnSearch, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(16, 16, 16))
        );
        pnlSearchLayout.setVerticalGroup(
            pnlSearchLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlSearchLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addGroup(pnlSearchLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblQuery)
                    .addComponent(lblLocation)
                    .addComponent(lblRadius)
                    .addComponent(lblSort))
                .addGap(4, 4, 4)
                .addGroup(pnlSearchLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtQuery, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(cmbNear, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(cmbWithin, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(cmbSort, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnSearch))
                .addGap(14, 14, 14))
        );

        lblMedicineName.setText("Search for a medicine");

        lblRx.setText("Prescription");

        lblStatus.setText(" ");

        lblMedicineInfo.setText(" ");

        lblResultCount.setText(" ");

        lblDidYouMean.setText(" ");

        scrResults.setViewportView(tblResults);

        lblFooterNote.setText(" ");

        btnRatesCredit.setText("Rates by Exchange Rate API");
        btnRatesCredit.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRatesCreditActionPerformed(evt);
            }
        });

        btnOpenMap.setText("Open map");
        btnOpenMap.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnOpenMapActionPerformed(evt);
            }
        });

        btnViewReserve.setText("View & reserve");
        btnViewReserve.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnViewReserveActionPerformed(evt);
            }
        });

        lblAlternatives.setText(" ");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(pnlSidebar, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(24, 24, 24)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblTitle)
                    .addComponent(lblSubtitle)
                    .addComponent(pnlSearch, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblMedicineName)
                        .addGap(10, 10, 10)
                        .addComponent(lblRx)
                        .addGap(16, 16, Short.MAX_VALUE)
                        .addComponent(lblStatus))
                    .addComponent(lblMedicineInfo, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblResultCount)
                        .addGap(16, 16, 16)
                        .addComponent(lblDidYouMean))
                    .addComponent(scrResults, javax.swing.GroupLayout.DEFAULT_SIZE, 940, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblFooterNote, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(12, 12, 12)
                        .addComponent(btnRatesCredit)
                        .addGap(12, 12, 12)
                        .addComponent(btnOpenMap)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnViewReserve))
                    .addComponent(lblAlternatives, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(24, 24, 24))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(pnlSidebar, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addGroup(layout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addComponent(lblTitle)
                .addGap(4, 4, 4)
                .addComponent(lblSubtitle)
                .addGap(14, 14, 14)
                .addComponent(pnlSearch, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(16, 16, 16)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblMedicineName)
                    .addComponent(lblRx)
                    .addComponent(lblStatus))
                .addGap(2, 2, 2)
                .addComponent(lblMedicineInfo)
                .addGap(6, 6, 6)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblResultCount)
                    .addComponent(lblDidYouMean))
                .addGap(8, 8, 8)
                .addComponent(scrResults, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(10, 10, 10)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblFooterNote)
                    .addComponent(btnRatesCredit)
                    .addComponent(btnOpenMap)
                    .addComponent(btnViewReserve))
                .addGap(6, 6, 6)
                .addComponent(lblAlternatives)
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cmbNearActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbNearActionPerformed
        nearChanged();
    }//GEN-LAST:event_cmbNearActionPerformed

    private void cmbWithinActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbWithinActionPerformed
        searchAgain();
    }//GEN-LAST:event_cmbWithinActionPerformed

    private void cmbSortActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbSortActionPerformed
        resort();
    }//GEN-LAST:event_cmbSortActionPerformed

    private void btnSearchActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSearchActionPerformed
        runSearch();
    }//GEN-LAST:event_btnSearchActionPerformed

    private void btnOpenMapActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnOpenMapActionPerformed
        openMap();
    }//GEN-LAST:event_btnOpenMapActionPerformed

    private void btnViewReserveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnViewReserveActionPerformed
        viewAndReserve();
    }//GEN-LAST:event_btnViewReserveActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnOpenMap;
    private javax.swing.JButton btnRatesCredit;
    private javax.swing.JButton btnSearch;
    private javax.swing.JButton btnViewReserve;
    private javax.swing.JComboBox<String> cmbNear;
    private javax.swing.JComboBox<medix.engine.SearchEngine.Sort> cmbSort;
    private javax.swing.JComboBox<String> cmbWithin;
    private javax.swing.JLabel lblAlternatives;
    private javax.swing.JLabel lblDidYouMean;
    private javax.swing.JLabel lblFooterNote;
    private javax.swing.JLabel lblLocation;
    private javax.swing.JLabel lblMedicineInfo;
    private javax.swing.JLabel lblMedicineName;
    private javax.swing.JLabel lblQuery;
    private javax.swing.JLabel lblRadius;
    private javax.swing.JLabel lblResultCount;
    private javax.swing.JLabel lblRx;
    private javax.swing.JLabel lblSort;
    private javax.swing.JLabel lblStatus;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlSearch;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrResults;
    private javax.swing.JTable tblResults;
    private javax.swing.JTextField txtQuery;
    // End of variables declaration//GEN-END:variables
}
