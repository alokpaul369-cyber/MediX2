package medix.forms;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import medix.model.Medicine;
import medix.model.Role;
import medix.service.MedicineService;
import medix.service.ServiceException;
import medix.util.ErrorLog;
import medix.util.FormUtil;
import medix.util.SidebarUtil;
import medix.util.TableUtil;
import medix.util.UITheme;

/**
 * Small picker for the correct medicine, with a searchable table of APPROVED medicines (the most
 * similar ones first and marked "similar").
 * <ul>
 *   <li>Admin (merge): the PENDING duplicate is merged into the chosen medicine
 *       (MedicineService.merge – stock, reservations and searches move with it).</li>
 *   <li>Pharmacy (move): the pharmacy's batches of a REJECTED medicine move to the chosen one
 *       (MedicineService.moveMyStock).</li>
 * </ul>
 * The owner frame is disabled until this window closes. onDone receives the summary.
 */
public class MergeMedicineFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Choose the Correct Medicine";

    private final MedicineService medicineService = new MedicineService();
    private final Medicine source;
    private final boolean moveMode;
    private final Consumer<String> onDone;
    private DefaultTableModel tableModel;
    private List<Medicine> targets = new ArrayList<Medicine>();
    private final Set<Integer> similarIds = new HashSet<Integer>();
    private List<Medicine> rows = new ArrayList<Medicine>();

    /** Admin: merge the pending duplicate. */
    public MergeMedicineFrame(JFrame owner, Medicine duplicate, Consumer<String> onDone) {
        this(owner, duplicate, false, onDone);
    }

    /** moveMode = a pharmacy moves its stock of a rejected medicine. */
    public MergeMedicineFrame(final JFrame owner, Medicine medicine, boolean moveMode, Consumer<String> onDone) {
        initComponents();
        this.source = medicine;
        this.moveMode = moveMode;
        this.onDone = onDone;
        if (!SidebarUtil.guard(this, moveMode ? Role.PHARMACY : Role.ADMIN)) {
            return;
        }
        applyTheme();
        setupTable();
        loadTargets();
        FormUtil.onTextChange(txtSearch, this::showRows);
        showRows();
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
        SwingUtilities.invokeLater(txtSearch::requestFocusInWindow);
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleHeading(lblHeading);
        UITheme.styleMuted(lblInfo);
        UITheme.styleMuted(lblCount);
        UITheme.stylePrimaryButton(btnMerge);
        String name = MedicineService.label(source);
        if (moveMode) {
            setTitle("MediX – Choose the Correct Medicine");
            lblHeading.setText("Move your stock of " + name);
            lblInfo.setText(UITheme.wrapHtml("The admin rejected <b>" + escape(name) + "</b>. Choose the correct medicine: "
                    + "your batches and reservations of it move there and can be found by patients again.", 560));
            btnMerge.setText("Move to selected");
        } else {
            lblHeading.setText("Merge " + name + " into…");
            lblInfo.setText(UITheme.wrapHtml("Choose the correct medicine. All stock, reservations and searches of <b>"
                    + escape(name) + "</b> move to it; the same batch at a pharmacy becomes one batch.", 560));
            btnMerge.setText("Merge into selected");
        }
        txtSearch.putClientProperty("JTextField.placeholderText", "Brand or generic name");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void setupTable() {
        tableModel = TableUtil.setupTable(tblMedicines, "ID", "Brand", "Generic", "Strength", "Form", "Manufacturer", "Match");
        TableUtil.setColumnWidth(tblMedicines, 1, 95);
        TableUtil.setColumnWidth(tblMedicines, 2, 130);
        TableUtil.setColumnWidth(tblMedicines, 3, 80);
        TableUtil.setColumnWidth(tblMedicines, 4, 70);
        TableUtil.setColumnWidth(tblMedicines, 5, 110);
        TableUtil.setColumnWidth(tblMedicines, 6, 60);
        TableUtil.setRowBackground(tblMedicines,
                modelRow -> similarIds.contains(rows.get(modelRow).getId()) ? UITheme.NOTE_INFO_BG : null);
        tblMedicines.getSelectionModel().addListSelectionListener(e -> btnMerge.setEnabled(tblMedicines.getSelectedRow() >= 0));
    }

    /** Approved medicines, the similar ones first. */
    private void loadTargets() {
        try {
            List<Medicine> all = medicineService.getMergeTargets(source.getId());
            List<Medicine> similar = MedicineService.findSimilar(source, all);
            similarIds.clear();
            targets = new ArrayList<Medicine>(similar);
            for (Medicine m : similar) {
                similarIds.add(m.getId());
            }
            for (Medicine m : all) {
                if (!similarIds.contains(m.getId())) {
                    targets.add(m);
                }
            }
        } catch (SQLException e) {
            showError("Could not load the medicines:\n" + ErrorLog.describe(e));
        }
    }

    private void showRows() {
        String text = txtSearch.getText().trim().toLowerCase();
        rows = new ArrayList<Medicine>();
        for (Medicine m : targets) {
            if (text.isEmpty() || (m.getBrandName() + " " + m.getGenericName()).toLowerCase().contains(text)) {
                rows.add(m);
            }
        }
        TableUtil.clear(tableModel);
        for (Medicine m : rows) {
            tableModel.addRow(new Object[]{m.getId(), m.getBrandName(), m.getGenericName(), m.getStrength(),
                m.getFormLabel(), m.getManufacturer() == null ? "" : m.getManufacturer(),
                similarIds.contains(m.getId()) ? "similar" : ""});
        }
        lblCount.setText(rows.size() + " approved medicine(s)" + (similarIds.isEmpty() ? "" : " · similar ones first (green)"));
        if (!rows.isEmpty() && similarIds.contains(rows.get(0).getId())) {
            tblMedicines.setRowSelectionInterval(0, 0);
        }
        btnMerge.setEnabled(tblMedicines.getSelectedRow() >= 0);
    }

    private void mergeSelected() {
        Integer id = TableUtil.getSelectedId(tblMedicines);
        if (id == null) {
            JOptionPane.showMessageDialog(this, "Select the correct medicine in the table.", TITLE, JOptionPane.WARNING_MESSAGE);
            return;
        }
        Medicine target = null;
        for (Medicine m : rows) {
            if (m.getId() == id) {
                target = m;
            }
        }
        String from = MedicineService.label(source);
        String to = MedicineService.label(target);
        String question = moveMode ? "Move your batches of " + from + " to " + to + "?"
                : "Merge " + from + " into " + to + "?\n\nAll its stock, reservations and searches move to " + to
                + ".\nThis cannot be undone.";
        if (JOptionPane.showConfirmDialog(this, question, TITLE, JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            String summary = moveMode ? medicineService.moveMyStock(source.getId(), id)
                    : medicineService.merge(source.getId(), id);
            dispose();
            if (onDone != null) {
                onDone.accept(summary);
            }
        } catch (ServiceException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), TITLE, JOptionPane.WARNING_MESSAGE);
        } catch (SQLException e) {
            showError("Could not save:\n" + ErrorLog.describe(e));
        }
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

        lblHeading = new javax.swing.JLabel();
        lblInfo = new javax.swing.JLabel();
        lblSearch = new javax.swing.JLabel();
        txtSearch = new javax.swing.JTextField();
        scrMedicines = new javax.swing.JScrollPane();
        tblMedicines = new javax.swing.JTable();
        lblCount = new javax.swing.JLabel();
        btnCancel = new javax.swing.JButton();
        btnMerge = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("MediX – Merge Medicine");
        setPreferredSize(new java.awt.Dimension(640, 480));

        lblHeading.setText("Merge into…");

        lblInfo.setText("Choose the correct medicine.");

        lblSearch.setText("Search");

        scrMedicines.setViewportView(tblMedicines);

        lblCount.setText("0 approved medicines");

        btnCancel.setText("Cancel");
        btnCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelActionPerformed(evt);
            }
        });

        btnMerge.setText("Merge into selected");
        btnMerge.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnMergeActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblHeading, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblInfo, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblSearch)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(txtSearch, javax.swing.GroupLayout.DEFAULT_SIZE, 554, Short.MAX_VALUE))
                    .addComponent(scrMedicines, javax.swing.GroupLayout.DEFAULT_SIZE, 600, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblCount)
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(btnCancel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnMerge)))
                .addGap(20, 20, 20))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(lblHeading)
                .addGap(6, 6, 6)
                .addComponent(lblInfo)
                .addGap(12, 12, 12)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblSearch)
                    .addComponent(txtSearch, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addComponent(scrMedicines, javax.swing.GroupLayout.DEFAULT_SIZE, 300, Short.MAX_VALUE)
                .addGap(12, 12, 12)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblCount)
                    .addComponent(btnCancel)
                    .addComponent(btnMerge))
                .addGap(16, 16, 16))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void btnCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelActionPerformed
        dispose();
    }//GEN-LAST:event_btnCancelActionPerformed

    private void btnMergeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnMergeActionPerformed
        mergeSelected();
    }//GEN-LAST:event_btnMergeActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCancel;
    private javax.swing.JButton btnMerge;
    private javax.swing.JLabel lblCount;
    private javax.swing.JLabel lblHeading;
    private javax.swing.JLabel lblInfo;
    private javax.swing.JLabel lblSearch;
    private javax.swing.JScrollPane scrMedicines;
    private javax.swing.JTable tblMedicines;
    private javax.swing.JTextField txtSearch;
    // End of variables declaration//GEN-END:variables
}
