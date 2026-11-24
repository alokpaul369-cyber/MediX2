package medix.forms;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import medix.model.Medicine;
import medix.model.MedicineForm;
import medix.model.Role;
import medix.service.MedicineService;
import medix.service.ServiceException;
import medix.util.ErrorLog;
import medix.util.SidebarUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * Pharmacy: adds a medicine that is not in its list (from InventoryFrame's "+ Add … as a new
 * medicine"), or edits one it added that no admin has reviewed yet. Same fields as the admin's
 * Medicine Catalog. Before saving, similar medicines are listed ("These look similar. Use one of
 * them instead?"); an exact duplicate is never created, the existing one is used. The new medicine
 * is PENDING but can be sold straight away. The opener is disabled until this window closes;
 * onSaved receives the medicine to select.
 */
public class AddMedicineFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Add Medicine";
    private static final String USE_SELECTED = "Use selected";
    private static final String ADD_AS_NEW = "No, add as new";

    private final MedicineService medicineService = new MedicineService();
    private final Medicine editing;
    private final Consumer<Medicine> onSaved;

    /** Add: brand (and strength, e.g. "napa 500mg") prefilled from the typed text. */
    public AddMedicineFrame(JFrame owner, String typedText, Consumer<Medicine> onSaved) {
        this(owner, null, typedText, onSaved);
    }

    /** Edit a PENDING medicine this pharmacy added. */
    public AddMedicineFrame(JFrame owner, Medicine pending, Consumer<Medicine> onSaved) {
        this(owner, pending, null, onSaved);
    }

    private AddMedicineFrame(final JFrame owner, Medicine pending, String typedText, Consumer<Medicine> onSaved) {
        initComponents();
        this.editing = pending;
        this.onSaved = onSaved;
        if (!SidebarUtil.guard(this, Role.PHARMACY)) {
            return;
        }
        applyTheme();
        cmbForm.setModel(new DefaultComboBoxModel<MedicineForm>(MedicineService.getForms()));
        if (pending != null) {
            fill(pending);
        } else {
            String[] parts = MedicineService.splitTypedText(typedText);
            txtBrand.setText(parts[0]);
            txtStrength.setText(parts[1]);
            cmbForm.setSelectedItem(MedicineForm.TABLET);
        }
        updateFormOther();
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
        getRootPane().setDefaultButton(btnSave);
        SwingUtilities.invokeLater(() -> (txtBrand.getText().trim().isEmpty() ? txtBrand : txtGeneric).requestFocusInWindow());
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleCard(pnlCard);
        UITheme.styleHeading(lblHeading);
        UITheme.styleInfoNote(lblNote);
        chkRxRequired.setOpaque(false);
        UITheme.stylePrimaryButton(btnSave);
        txtBrand.putClientProperty("JTextField.placeholderText", "e.g. Napa");
        txtGeneric.putClientProperty("JTextField.placeholderText", "e.g. Paracetamol");
        txtStrength.putClientProperty("JTextField.placeholderText", "e.g. 500 mg, 250 mg/5 ml, 0.1%");
        txtFormOther.putClientProperty("JTextField.placeholderText", "Type the form");
        txtManufacturer.putClientProperty("JTextField.placeholderText", "e.g. Beximco Pharmaceuticals");
        if (editing != null) {
            setTitle("MediX – Edit Medicine");
            lblHeading.setText("Edit medicine #" + editing.getId());
            btnSave.setText("Save changes");
            lblNote.setText(UITheme.wrapHtml("You added this medicine and no admin has reviewed it yet, so you can still "
                    + "correct it. After the review only an admin can change it.", 350));
        } else {
            lblNote.setText(UITheme.wrapHtml("You can sell it straight away. Patients see it as "
                    + "<i>" + UITheme.NEW_MEDICINE_TEXT + "</i> until an admin checks it.", 350));
        }
    }

    private void fill(Medicine m) {
        txtBrand.setText(m.getBrandName());
        txtGeneric.setText(m.getGenericName());
        txtStrength.setText(m.getStrength());
        cmbForm.setSelectedItem(m.getForm());
        txtFormOther.setText(m.getFormOther());
        txtManufacturer.setText(m.getManufacturer() == null ? "" : m.getManufacturer());
        chkRxRequired.setSelected(m.isRxRequired());
    }

    private void updateFormOther() {
        txtFormOther.setVisible(cmbForm.getSelectedItem() == MedicineForm.OTHER);
        pnlCard.revalidate();
    }

    /** The form as a Medicine; null (after showing the first problem) when something is missing. */
    private Medicine readForm() {
        if (ValidationUtil.isBlank(txtBrand.getText())) {
            return fail("Enter the brand name.", txtBrand);
        }
        if (ValidationUtil.isBlank(txtGeneric.getText())) {
            return fail("Enter the generic name (the active ingredient), e.g. Paracetamol.", txtGeneric);
        }
        if (ValidationUtil.isBlank(txtStrength.getText())) {
            return fail("Enter the strength, e.g. 500 mg, 250 mg/5 ml or 0.1%.", txtStrength);
        }
        MedicineForm form = (MedicineForm) cmbForm.getSelectedItem();
        if (form == null) {
            return fail("Choose the form.", cmbForm);
        }
        if (form == MedicineForm.OTHER && ValidationUtil.isBlank(txtFormOther.getText())) {
            return fail("Write the form (e.g. Mouthwash).", txtFormOther);
        }
        Medicine m = new Medicine();
        if (editing != null) {
            m.setId(editing.getId());
        }
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
            if (editing != null) {
                medicineService.updateMyPending(m);
                finish(medicineService.findById(m.getId()));
                return;
            }
            List<Medicine> similar = medicineService.findSimilar(m);
            if (!similar.isEmpty()) {
                Medicine chosen = askSimilar(similar);
                if (chosen == CANCELLED) {
                    return;
                }
                if (chosen != null) {
                    finish(chosen);
                    return;
                }
            }
            MedicineService.AddResult result = medicineService.addByPharmacy(m);
            if (!result.isCreated()) {
                JOptionPane.showMessageDialog(this, MedicineService.label(result.getMedicine())
                        + " is already in the catalog, so it was chosen for you.", TITLE, JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, MedicineService.label(result.getMedicine())
                        + " was added. You can add a batch of it now.\nAn admin will review it.", TITLE,
                        JOptionPane.INFORMATION_MESSAGE);
            }
            finish(result.getMedicine());
        } catch (ServiceException e) {
            fail(e.getMessage(), txtBrand);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not save the medicine:\n" + ErrorLog.describe(e), TITLE,
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Marker for "the dialog was closed". */
    private static final Medicine CANCELLED = new Medicine();

    /** "These look similar. Use one of them instead?" – the chosen medicine, null = add as new, CANCELLED. */
    private Medicine askSimilar(List<Medicine> similar) {
        String[] labels = new String[similar.size()];
        for (int i = 0; i < labels.length; i++) {
            Medicine s = similar.get(i);
            labels[i] = MedicineService.suggestionLabel(s) + (s.isPending() ? "  (" + UITheme.NEW_MEDICINE_TEXT + ")" : "");
        }
        JList<String> list = new JList<String>(labels);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(6, labels.length));
        list.setFont(UITheme.FONT_BODY);
        JScrollPane scroll = new JScrollPane(list);
        Object[] message = {"These look similar. Use one of them instead?", scroll};
        Object[] options = {USE_SELECTED, ADD_AS_NEW};
        int answer = JOptionPane.showOptionDialog(this, message, TITLE, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer == 0) {
            return similar.get(Math.max(0, list.getSelectedIndex()));
        }
        return answer == 1 ? null : CANCELLED;
    }

    private void finish(Medicine m) {
        dispose();
        if (onSaved != null && m != null) {
            onSaved.accept(m);
        }
    }

    private Medicine fail(String message, JComponent field) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.WARNING_MESSAGE);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
        return null;
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        pnlCard = new javax.swing.JPanel();
        lblHeading = new javax.swing.JLabel();
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
        lblNote = new javax.swing.JLabel();
        btnCancel = new javax.swing.JButton();
        btnSave = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("MediX – Add Medicine");
        setPreferredSize(new java.awt.Dimension(460, 520));
        setResizable(false);

        lblHeading.setText("Add a new medicine");

        lblBrand.setText("Brand name *");

        lblGeneric.setText("Generic name *");

        lblStrength.setText("Strength * (e.g. 500 mg, 250 mg/5 ml, 0.1%)");

        lblForm.setText("Form *");

        cmbForm.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cmbFormActionPerformed(evt);
            }
        });

        lblManufacturer.setText("Manufacturer");

        chkRxRequired.setText("Prescription required (Rx)");

        lblNote.setText("You can sell it straight away. An admin reviews it.");

        javax.swing.GroupLayout pnlCardLayout = new javax.swing.GroupLayout(pnlCard);
        pnlCard.setLayout(pnlCardLayout);
        pnlCardLayout.setHorizontalGroup(
            pnlCardLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlCardLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblHeading)
                    .addComponent(lblBrand)
                    .addComponent(txtBrand, javax.swing.GroupLayout.DEFAULT_SIZE, 366, Short.MAX_VALUE)
                    .addComponent(lblGeneric)
                    .addComponent(txtGeneric, javax.swing.GroupLayout.DEFAULT_SIZE, 366, Short.MAX_VALUE)
                    .addComponent(lblStrength)
                    .addComponent(txtStrength, javax.swing.GroupLayout.DEFAULT_SIZE, 366, Short.MAX_VALUE)
                    .addComponent(lblForm)
                    .addGroup(pnlCardLayout.createSequentialGroup()
                        .addComponent(cmbForm, javax.swing.GroupLayout.PREFERRED_SIZE, 160, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(txtFormOther, javax.swing.GroupLayout.DEFAULT_SIZE, 200, Short.MAX_VALUE))
                    .addComponent(lblManufacturer)
                    .addComponent(txtManufacturer, javax.swing.GroupLayout.DEFAULT_SIZE, 366, Short.MAX_VALUE)
                    .addComponent(chkRxRequired)
                    .addComponent(lblNote, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(16, 16, 16))
        );
        pnlCardLayout.setVerticalGroup(
            pnlCardLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlCardLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addComponent(lblHeading)
                .addGap(12, 12, 12)
                .addComponent(lblBrand)
                .addGap(4, 4, 4)
                .addComponent(txtBrand, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblGeneric)
                .addGap(4, 4, 4)
                .addComponent(txtGeneric, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblStrength)
                .addGap(4, 4, 4)
                .addComponent(txtStrength, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(lblForm)
                .addGap(4, 4, 4)
                .addGroup(pnlCardLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(cmbForm, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtFormOther, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(8, 8, 8)
                .addComponent(lblManufacturer)
                .addGap(4, 4, 4)
                .addComponent(txtManufacturer, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(chkRxRequired)
                .addGap(10, 10, 10)
                .addComponent(lblNote)
                .addGap(14, 14, Short.MAX_VALUE))
        );

        btnCancel.setText("Cancel");
        btnCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelActionPerformed(evt);
            }
        });

        btnSave.setText("Add medicine");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(pnlCard, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(btnCancel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnSave)))
                .addGap(16, 16, 16))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addComponent(pnlCard, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(12, 12, 12)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnCancel)
                    .addComponent(btnSave))
                .addGap(16, 16, 16))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void cmbFormActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cmbFormActionPerformed
        updateFormOther();
    }//GEN-LAST:event_cmbFormActionPerformed

    private void btnCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelActionPerformed
        dispose();
    }//GEN-LAST:event_btnCancelActionPerformed

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
        save();
    }//GEN-LAST:event_btnSaveActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCancel;
    private javax.swing.JButton btnSave;
    private javax.swing.JCheckBox chkRxRequired;
    private javax.swing.JComboBox<medix.model.MedicineForm> cmbForm;
    private javax.swing.JLabel lblBrand;
    private javax.swing.JLabel lblForm;
    private javax.swing.JLabel lblGeneric;
    private javax.swing.JLabel lblHeading;
    private javax.swing.JLabel lblManufacturer;
    private javax.swing.JLabel lblNote;
    private javax.swing.JLabel lblStrength;
    private javax.swing.JPanel pnlCard;
    private javax.swing.JTextField txtBrand;
    private javax.swing.JTextField txtFormOther;
    private javax.swing.JTextField txtGeneric;
    private javax.swing.JTextField txtManufacturer;
    private javax.swing.JTextField txtStrength;
    // End of variables declaration//GEN-END:variables
}
