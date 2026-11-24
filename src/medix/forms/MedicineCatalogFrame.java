package medix.forms;

import java.awt.Component;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.model.MedicineSource;
import medix.model.MedicineStatus;
import medix.model.MedicineView;
import medix.service.MedicineService;
import medix.service.ServiceException;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.FormUtil;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * Admin: the medicine catalog. Select a row to edit or delete it; Clear to add a new one.
 * Medicines added by pharmacies wait "To review" (light yellow rows): Approve, Edit &amp; approve
 * (fix typos in the form first), Merge into the correct medicine (MergeMedicineFrame) or Reject
 * with a reason.
 */
public class MedicineCatalogFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Medicine Catalog";
    private static final int COL_STATUS = 8;
    /** cmbStatus items (index 1 gets the count): All, To review (n), Approved, Rejected, Merged. */
    private static final MedicineStatus[] STATUS_FILTERS = {null, MedicineStatus.PENDING, MedicineStatus.APPROVED,
        MedicineStatus.REJECTED, MedicineStatus.MERGED};
    private static final MedicineSource[] SOURCE_FILTERS = {null, MedicineSource.SEED, MedicineSource.ADMIN,
        MedicineSource.PHARMACY};

    private final MedicineService medicineService = new MedicineService();
    private DefaultTableModel tableModel;
    private final Map<Integer, MedicineView> shownById = new HashMap<Integer, MedicineView>();
    private List<MedicineView> rows = new ArrayList<MedicineView>();
    /** The medicine in the form, or null when adding a new one. */
    private Medicine editing;
    private boolean loadingFilters;

    public MedicineCatalogFrame() {
        this((MedicineStatus) null);
    }

    /** Opens the catalog filtered to a status (PENDING = "To review", from the dashboard card). */
    public MedicineCatalogFrame(MedicineStatus statusFilter) {
        initComponents();
        applyTheme();
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.ADMIN_CATALOG);
        cmbForm.setModel(new DefaultComboBoxModel<MedicineForm>(MedicineService.getForms()));
        cmbSource.setModel(new DefaultComboBoxModel<String>(new String[]{"All", "Seed", "Admin", "Pharmacy"}));
        setupTable();
        loadStatusFilter(statusFilter);
        FormUtil.onTextChange(txtSearch, this::reload);
        reload();
        clearForm();
    }

    /** Opens the catalog with one medicine selected (from a "Please review it" notification). */
    public MedicineCatalogFrame(int medicineId) {
        this((MedicineStatus) null);
        if (TableUtil.selectId(tblMedicines, medicineId)) {
            tblMedicines.scrollRectToVisible(tblMedicines.getCellRect(tblMedicines.getSelectedRow(), 0, true));
        }
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleCard(pnlForm);
        UITheme.styleHeading(lblFormTitle);
        UITheme.styleHeading(lblReview);
        UITheme.styleMuted(lblReviewInfo);
        UITheme.styleMuted(lblCount);
        chkRxRequired.setOpaque(false);
        UITheme.stylePrimaryButton(btnSave);
        UITheme.stylePrimaryButton(btnUpdate);
        UITheme.styleDangerButton(btnDelete);
        UITheme.styleSuccessButton(btnApprove);
        UITheme.styleSuccessButton(btnEditApprove);
        UITheme.styleWarningButton(btnMerge);
        UITheme.styleDangerButton(btnReject);
        txtSearch.putClientProperty("JTextField.placeholderText", "Brand or generic name");
        txtBrand.putClientProperty("JTextField.placeholderText", "e.g. Napa");
        txtGeneric.putClientProperty("JTextField.placeholderText", "e.g. Paracetamol");
        txtStrength.putClientProperty("JTextField.placeholderText", "e.g. 500 mg, 250 mg/5 ml, 0.1%");
        txtFormOther.putClientProperty("JTextField.placeholderText", "Type the form");
        txtManufacturer.putClientProperty("JTextField.placeholderText", "e.g. Beximco Pharmaceuticals");
        btnEditApprove.setToolTipText("Saves the changes in the form, then approves the medicine.");
        btnMerge.setToolTipText("It is a duplicate: move its stock to the correct medicine.");
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblMedicines, "ID", "Brand", "Generic", "Strength", "Form", "Manufacturer",
                "Rx", "Added by", "Status");
        TableUtil.setColumnWidth(tblMedicines, 1, 95);
        TableUtil.setColumnWidth(tblMedicines, 2, 120);
        TableUtil.setColumnWidth(tblMedicines, 3, 85);
        TableUtil.setColumnWidth(tblMedicines, 4, 70);
        TableUtil.setColumnWidth(tblMedicines, 5, 110);
        TableUtil.setColumnWidth(tblMedicines, 6, 35);
        TableUtil.centerColumn(tblMedicines, 6);
        TableUtil.setColumnWidth(tblMedicines, 7, 105);
        TableUtil.setColumnWidth(tblMedicines, COL_STATUS, 100);
        tblMedicines.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                MedicineStatus status = rows.get(table.convertRowIndexToModel(row)).getMedicine().getStatus();
                setFont(UITheme.FONT_BODY_BOLD);
                if (!selected) {
                    setForeground(UITheme.medicineStatusColor(status));
                }
                return this;
            }
        });
        TableUtil.setRowBackground(tblMedicines,
                modelRow -> rows.get(modelRow).getMedicine().isPending() ? UITheme.ROW_LOW_STOCK : null);
        tblMedicines.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Integer id = TableUtil.getSelectedId(tblMedicines);
                if (id != null && shownById.containsKey(id)) {
                    fillForm(shownById.get(id));
                }
            }
        });
    }

    /** cmbStatus with "To review (n)"; keeps (or sets) the selection. */
    private void loadStatusFilter(MedicineStatus select) {
        int toReview = 0;
        try {
            toReview = medicineService.countToReview();
        } catch (SQLException e) {
            ErrorLog.log("Could not count medicines to review", e);
        }
        int index = select == null ? Math.max(0, cmbStatus.getSelectedIndex()) : indexOf(select);
        loadingFilters = true;
        try {
            cmbStatus.setModel(new DefaultComboBoxModel<String>(new String[]{"All", "To review (" + toReview + ")",
                "Approved", "Rejected", "Merged"}));
            cmbStatus.setSelectedIndex(index);
        } finally {
            loadingFilters = false;
        }
    }

    private static int indexOf(MedicineStatus status) {
        for (int i = 0; i < STATUS_FILTERS.length; i++) {
            if (STATUS_FILTERS[i] == status) {
                return i;
            }
        }
        return 0;
    }

    private void reload() {
        Integer keep = editing == null ? null : editing.getId();
        MedicineStatus status = STATUS_FILTERS[Math.max(0, cmbStatus.getSelectedIndex())];
        MedicineSource source = SOURCE_FILTERS[Math.max(0, cmbSource.getSelectedIndex())];
        try {
            rows = medicineService.searchViews(txtSearch.getText(), status, source);
        } catch (SQLException e) {
            rows = new ArrayList<MedicineView>();
            showError("Could not load the medicines:\n" + ErrorLog.describe(e));
        }
        shownById.clear();
        TableUtil.clear(tableModel);
        int pending = 0;
        for (MedicineView v : rows) {
            Medicine m = v.getMedicine();
            shownById.put(m.getId(), v);
            pending += m.isPending() ? 1 : 0;
            tableModel.addRow(new Object[]{m.getId(), m.getBrandName(), m.getGenericName(), m.getStrength(),
                m.getFormLabel(), m.getManufacturer() == null ? "" : m.getManufacturer(), m.isRxRequired() ? "Yes" : "No",
                v.getAddedBy(), m.getStatus()});
        }
        lblCount.setText(rows.size() + (rows.size() == 1 ? " medicine" : " medicines")
                + (ValidationUtil.isBlank(txtSearch.getText()) && status == null && source == null ? "" : " found")
                + (pending > 0 ? "  ·  " + pending + " to review (light yellow)" : ""));
        if (keep != null && !TableUtil.selectId(tblMedicines, keep)) {
            clearForm();
        }
    }

    private void fillForm(MedicineView v) {
        Medicine m = v.getMedicine();
        editing = m;
        txtBrand.setText(m.getBrandName());
        txtGeneric.setText(m.getGenericName());
        txtStrength.setText(m.getStrength());
        cmbForm.setSelectedItem(m.getForm());
        txtFormOther.setText(m.getFormOther());
        updateFormOther();
        txtManufacturer.setText(m.getManufacturer() == null ? "" : m.getManufacturer());
        chkRxRequired.setSelected(m.isRxRequired());
        lblFormTitle.setText("Edit medicine #" + m.getId());
        lblReviewInfo.setText(UITheme.wrapHtml(reviewText(v), 250));
        updateButtons();
    }

    private static String reviewText(MedicineView v) {
        Medicine m = v.getMedicine();
        String added = "Added by " + v.getAddedBy() + " on " + DateUtil.toDisplayDate(m.getCreatedAt());
        if (m.isPending()) {
            return added + ". Waiting for review.";
        }
        if (m.getReviewedAt() == null) {
            return added + ".";
        }
        return added + ". " + m.getStatus() + " on " + DateUtil.toDisplayDate(m.getReviewedAt())
                + (m.getReviewNote() == null ? "." : ": " + m.getReviewNote());
    }

    private void clearForm() {
        editing = null;
        tblMedicines.clearSelection();
        txtBrand.setText("");
        txtGeneric.setText("");
        txtStrength.setText("");
        cmbForm.setSelectedItem(MedicineForm.TABLET);
        txtFormOther.setText("");
        updateFormOther();
        txtManufacturer.setText("");
        chkRxRequired.setSelected(false);
        lblFormTitle.setText("Add a new medicine");
        lblReviewInfo.setText(UITheme.wrapHtml("Select a light yellow row (added by a pharmacy) to review it.", 250));
        updateButtons();
        SwingUtilities.invokeLater(txtBrand::requestFocusInWindow);
    }

    private void updateFormOther() {
        boolean other = cmbForm.getSelectedItem() == MedicineForm.OTHER;
        txtFormOther.setVisible(other);
        pnlForm.revalidate();
    }

    private void updateButtons() {
        boolean merged = editing != null && editing.getStatus() == MedicineStatus.MERGED;
        boolean pending = editing != null && editing.isPending();
        btnSave.setEnabled(editing == null);
        btnUpdate.setEnabled(editing != null && !merged);
        btnDelete.setEnabled(editing != null);
        btnApprove.setEnabled(pending);
        btnEditApprove.setEnabled(pending);
        btnMerge.setEnabled(pending);
        btnReject.setEnabled(pending);
    }

    /** Reads the form into a Medicine; returns null (after showing the problem) if a field is missing. */
    private Medicine readForm() {
        if (ValidationUtil.isBlank(txtBrand.getText())) {
            return fail("Enter the brand name.", txtBrand);
        }
        if (ValidationUtil.isBlank(txtGeneric.getText())) {
            return fail("Enter the generic name.", txtGeneric);
        }
        if (ValidationUtil.isBlank(txtStrength.getText())) {
            return fail("Enter the strength, e.g. 500 mg, 250 mg/5 ml or 0.1%.", txtStrength);
        }
        if (cmbForm.getSelectedItem() == null) {
            return fail("Choose the form.", cmbForm);
        }
        MedicineForm form = (MedicineForm) cmbForm.getSelectedItem();
        if (form == MedicineForm.OTHER && ValidationUtil.isBlank(txtFormOther.getText())) {
            return fail("Write the form (e.g. Mouthwash).", txtFormOther);
        }
        Medicine m = new Medicine();
        m.setBrandName(txtBrand.getText());
        m.setGenericName(txtGeneric.getText());
        m.setStrength(txtStrength.getText());
        m.setForm(form);
        m.setFormOther(form == MedicineForm.OTHER ? txtFormOther.getText() : "");
        m.setManufacturer(txtManufacturer.getText());
        m.setRxRequired(chkRxRequired.isSelected());
        return m;
    }

    private void save() {
        Medicine m = readForm();
        if (m == null) {
            return;
        }
        try {
            int id = medicineService.add(m);
            showInfo(MedicineService.label(m) + " was added to the catalog.");
            txtSearch.setText("");
            clearForm();
            reload();
            TableUtil.selectId(tblMedicines, id);
        } catch (ServiceException e) {
            fail(e.getMessage(), txtBrand);
        } catch (SQLException e) {
            showError("Could not save the medicine:\n" + ErrorLog.describe(e));
        }
    }

    private void update() {
        Medicine m = readForm();
        if (m == null) {
            return;
        }
        m.setId(editing.getId());
        try {
            medicineService.update(m);
            reload();
            showInfo(MedicineService.label(m) + " was updated.");
        } catch (ServiceException e) {
            fail(e.getMessage(), txtBrand);
        } catch (SQLException e) {
            showError("Could not update the medicine:\n" + ErrorLog.describe(e));
        }
    }

    private void delete() {
        String name = MedicineService.label(editing);
        if (JOptionPane.showConfirmDialog(this, "Delete " + name + " from the catalog?", TITLE,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            medicineService.delete(editing.getId());
            clearForm();
            reload();
            showInfo(name + " was deleted.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not delete the medicine:\n" + ErrorLog.describe(e));
        }
    }

    // ---------------------------------------------------------------- review

    private void approve() {
        String name = MedicineService.label(editing);
        if (JOptionPane.showConfirmDialog(this, "Approve " + name + "?\nEvery pharmacy can then choose it.", TITLE,
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            medicineService.approve(editing.getId());
            afterReview(name + " was approved. The pharmacy was notified.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not approve the medicine:\n" + ErrorLog.describe(e));
        }
    }

    private void editAndApprove() {
        Medicine m = readForm();
        if (m == null) {
            return;
        }
        m.setId(editing.getId());
        try {
            medicineService.approveWithChanges(m);
            afterReview(MedicineService.label(m) + " was saved and approved. The pharmacy was notified.");
        } catch (ServiceException e) {
            fail(e.getMessage(), txtBrand);
        } catch (SQLException e) {
            showError("Could not approve the medicine:\n" + ErrorLog.describe(e));
        }
    }

    private void merge() {
        final Medicine duplicate = editing;
        new MergeMedicineFrame(this, duplicate, summary -> afterReview(MedicineService.label(duplicate)
                + " was merged (" + summary + "). The pharmacy was notified.")).setVisible(true);
    }

    private void reject() {
        String name = MedicineService.label(editing);
        String reason = JOptionPane.showInputDialog(this, "Why is " + name + " rejected?\n"
                + "The pharmacy sees this reason. Its stock is hidden from patient search.", TITLE,
                JOptionPane.QUESTION_MESSAGE);
        if (reason == null) {
            return;
        }
        try {
            medicineService.reject(editing.getId(), reason);
            afterReview(name + " was rejected. The pharmacy was notified.");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not reject the medicine:\n" + ErrorLog.describe(e));
        }
    }

    private void afterReview(String message) {
        int id = editing == null ? 0 : editing.getId();
        loadStatusFilter(null);
        reload();
        if (!TableUtil.selectId(tblMedicines, id)) {
            clearForm();
        }
        showInfo(message);
    }

    private Medicine fail(String message, JComponent field) {
        showWarning(message);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
        return null;
    }

    private void showInfo(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.INFORMATION_MESSAGE);
    }

    private void showWarning(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.ERROR_MESSAGE);
    }

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
        pnlForm = new javax.swing.JPanel();
        lblFormTitle = new javax.swing.JLabel();
        lblBrand = new javax.swing.JLabel();
        txtBrand = new javax.swing.JTextField();
        lblGeneric = new javax.swing.JLabel();
        txtGeneric = new javax.swing.JTextField();
        lblStrength = new javax.swing.JLabel();
        txtStrength = new javax.swing.JTextField();
        lblForm = new javax.swing.JLabel();
        cmbForm = new javax.swing.JComboBox<>();
        txtFormOther = new javax.swing.JTextField();
        lblManufacturer = new javax.swing.JLabel();
        txtManufacturer = new javax.swing.JTextField();
        chkRxRequired = new javax.swing.JCheckBox();
        btnSave = new javax.swing.JButton();
        btnUpdate = new javax.swing.JButton();
        btnDelete = new javax.swing.JButton();
        btnClear = new javax.swing.JButton();
        lblReview = new javax.swing.JLabel();
        lblReviewInfo = new javax.swing.JLabel();
        btnApprove = new javax.swing.JButton();
        btnEditApprove = new javax.swing.JButton();
        btnMerge = new javax.swing.JButton();
        btnReject = new javax.swing.JButton();
        lblSearch = new javax.swing.JLabel();
        txtSearch = new javax.swing.JTextField();
        lblStatus = new javax.swing.JLabel();
        cmbStatus = new javax.swing.JComboBox<>();
        lblSource = new javax.swing.JLabel();
        cmbSource = new javax.swing.JComboBox<>();
        scrMedicines = new javax.swing.JScrollPane();
        tblMedicines = new javax.swing.JTable();
        lblCount = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Medicine Catalog");
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

        lblTitle.setText("Medicine Catalog");

        lblSubtitle.setText("Medicines that pharmacies can add to their inventory. Pharmacies can add new ones; review them here.");

        lblFormTitle.setText("Add or edit a medicine");

        lblBrand.setText("Brand name *");

        lblGeneric.setText("Generic name *");

        lblStrength.setText("Strength *");

        lblForm.setText("Form *");

        cmbForm.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbFormActionPerformed(evt);
            }
        });

        lblManufacturer.setText("Manufacturer");

        chkRxRequired.setText("Prescription required (Rx)");

        btnSave.setText("Save");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        btnUpdate.setText("Update");
        btnUpdate.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnUpdateActionPerformed(evt);
            }
        });

        btnDelete.setText("Delete");
        btnDelete.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDeleteActionPerformed(evt);
            }
        });

        btnClear.setText("Clear");
        btnClear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearActionPerformed(evt);
            }
        });

        lblReview.setText("Review");

        lblReviewInfo.setText("Select a light yellow row to review it.");

        btnApprove.setText("Approve");
        btnApprove.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnApproveActionPerformed(evt);
            }
        });

        btnEditApprove.setText("Edit & approve");
        btnEditApprove.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnEditApproveActionPerformed(evt);
            }
        });

        btnMerge.setText("Merge...");
        btnMerge.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnMergeActionPerformed(evt);
            }
        });

        btnReject.setText("Reject...");
        btnReject.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRejectActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlFormLayout = new javax.swing.GroupLayout(pnlForm);
        pnlForm.setLayout(pnlFormLayout);
        pnlFormLayout.setHorizontalGroup(
            pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlFormLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblFormTitle)
                    .addComponent(lblBrand)
                    .addComponent(txtBrand, javax.swing.GroupLayout.DEFAULT_SIZE, 268, Short.MAX_VALUE)
                    .addComponent(lblGeneric)
                    .addComponent(txtGeneric, javax.swing.GroupLayout.DEFAULT_SIZE, 268, Short.MAX_VALUE)
                    .addComponent(lblStrength)
                    .addComponent(txtStrength, javax.swing.GroupLayout.DEFAULT_SIZE, 268, Short.MAX_VALUE)
                    .addComponent(lblForm)
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addComponent(cmbForm, javax.swing.GroupLayout.PREFERRED_SIZE, 130, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(txtFormOther, javax.swing.GroupLayout.DEFAULT_SIZE, 132, Short.MAX_VALUE))
                    .addComponent(lblManufacturer)
                    .addComponent(txtManufacturer, javax.swing.GroupLayout.DEFAULT_SIZE, 268, Short.MAX_VALUE)
                    .addComponent(chkRxRequired)
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addComponent(btnSave, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnUpdate, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addComponent(btnDelete, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnClear, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addComponent(lblReview)
                    .addComponent(lblReviewInfo, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addComponent(btnApprove, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnEditApprove, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(pnlFormLayout.createSequentialGroup()
                        .addComponent(btnMerge, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnReject, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                .addGap(16, 16, 16))
        );
        pnlFormLayout.setVerticalGroup(
            pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlFormLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addComponent(lblFormTitle)
                .addGap(10, 10, 10)
                .addComponent(lblBrand)
                .addGap(2, 2, 2)
                .addComponent(txtBrand, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblGeneric)
                .addGap(2, 2, 2)
                .addComponent(txtGeneric, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblStrength)
                .addGap(2, 2, 2)
                .addComponent(txtStrength, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblForm)
                .addGap(2, 2, 2)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(cmbForm, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtFormOther, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(8, 8, 8)
                .addComponent(lblManufacturer)
                .addGap(2, 2, 2)
                .addComponent(txtManufacturer, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(chkRxRequired)
                .addGap(12, 12, 12)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnSave)
                    .addComponent(btnUpdate))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnDelete)
                    .addComponent(btnClear))
                .addGap(18, 18, 18)
                .addComponent(lblReview)
                .addGap(4, 4, 4)
                .addComponent(lblReviewInfo)
                .addGap(8, 8, 8)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnApprove)
                    .addComponent(btnEditApprove))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(pnlFormLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnMerge)
                    .addComponent(btnReject))
                .addGap(14, 14, Short.MAX_VALUE))
        );

        lblSearch.setText("Search");

        lblStatus.setText("Status");

        cmbStatus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbStatusActionPerformed(evt);
            }
        });

        lblSource.setText("Added by");

        cmbSource.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbSourceActionPerformed(evt);
            }
        });

        scrMedicines.setViewportView(tblMedicines);

        lblCount.setText("0 medicines");

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
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(pnlForm, javax.swing.GroupLayout.PREFERRED_SIZE, 300, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(20, 20, 20)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(lblSearch)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(txtSearch, javax.swing.GroupLayout.DEFAULT_SIZE, 230, Short.MAX_VALUE)
                                .addGap(16, 16, 16)
                                .addComponent(lblStatus)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(cmbStatus, javax.swing.GroupLayout.PREFERRED_SIZE, 140, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(16, 16, 16)
                                .addComponent(lblSource)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(cmbSource, javax.swing.GroupLayout.PREFERRED_SIZE, 110, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addComponent(scrMedicines, javax.swing.GroupLayout.DEFAULT_SIZE, 642, Short.MAX_VALUE)
                            .addComponent(lblCount))))
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
                .addGap(16, 16, 16)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(pnlForm, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(lblSearch)
                            .addComponent(txtSearch, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(lblStatus)
                            .addComponent(cmbStatus, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(lblSource)
                            .addComponent(cmbSource, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(10, 10, 10)
                        .addComponent(scrMedicines, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGap(8, 8, 8)
                        .addComponent(lblCount)))
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cmbFormActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbFormActionPerformed
        updateFormOther();
    }//GEN-LAST:event_cmbFormActionPerformed

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
        save();
    }//GEN-LAST:event_btnSaveActionPerformed

    private void btnUpdateActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUpdateActionPerformed
        update();
    }//GEN-LAST:event_btnUpdateActionPerformed

    private void btnDeleteActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDeleteActionPerformed
        delete();
    }//GEN-LAST:event_btnDeleteActionPerformed

    private void btnClearActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearActionPerformed
        clearForm();
    }//GEN-LAST:event_btnClearActionPerformed

    private void btnApproveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnApproveActionPerformed
        approve();
    }//GEN-LAST:event_btnApproveActionPerformed

    private void btnEditApproveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnEditApproveActionPerformed
        editAndApprove();
    }//GEN-LAST:event_btnEditApproveActionPerformed

    private void btnMergeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnMergeActionPerformed
        merge();
    }//GEN-LAST:event_btnMergeActionPerformed

    private void btnRejectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRejectActionPerformed
        reject();
    }//GEN-LAST:event_btnRejectActionPerformed

    private void cmbStatusActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbStatusActionPerformed
        if (!loadingFilters && tableModel != null) {
            reload();
        }
    }//GEN-LAST:event_cmbStatusActionPerformed

    private void cmbSourceActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbSourceActionPerformed
        if (tableModel != null) {
            reload();
        }
    }//GEN-LAST:event_cmbSourceActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnApprove;
    private javax.swing.JButton btnClear;
    private javax.swing.JButton btnDelete;
    private javax.swing.JButton btnEditApprove;
    private javax.swing.JButton btnMerge;
    private javax.swing.JButton btnReject;
    private javax.swing.JButton btnSave;
    private javax.swing.JButton btnUpdate;
    private javax.swing.JCheckBox chkRxRequired;
    private javax.swing.JComboBox<medix.model.MedicineForm> cmbForm;
    private javax.swing.JComboBox<String> cmbSource;
    private javax.swing.JComboBox<String> cmbStatus;
    private javax.swing.JLabel lblBrand;
    private javax.swing.JLabel lblCount;
    private javax.swing.JLabel lblForm;
    private javax.swing.JLabel lblFormTitle;
    private javax.swing.JLabel lblGeneric;
    private javax.swing.JLabel lblManufacturer;
    private javax.swing.JLabel lblReview;
    private javax.swing.JLabel lblReviewInfo;
    private javax.swing.JLabel lblSearch;
    private javax.swing.JLabel lblSource;
    private javax.swing.JLabel lblStatus;
    private javax.swing.JLabel lblStrength;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlForm;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrMedicines;
    private javax.swing.JTable tblMedicines;
    private javax.swing.JTextField txtBrand;
    private javax.swing.JTextField txtFormOther;
    private javax.swing.JTextField txtGeneric;
    private javax.swing.JTextField txtManufacturer;
    private javax.swing.JTextField txtSearch;
    private javax.swing.JTextField txtStrength;
    // End of variables declaration//GEN-END:variables
}
