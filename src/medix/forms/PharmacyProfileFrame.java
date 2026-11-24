package medix.forms;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import medix.model.Pharmacy;
import medix.model.PharmacyDetails;
import medix.service.LocationService;
import medix.service.PharmacyService;
import medix.service.ServiceException;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.FileStorageUtil;
import medix.util.PharmacyLocationFields;
import medix.util.SidebarUtil;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * The pharmacy edits its own details (same card layout as the Pharmacy tab of RegisterFrame).
 * License number and email are read-only. A new license document notifies the admins.
 */
public class PharmacyProfileFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Pharmacy Profile";
    /** Width of lblFoundLocation (the card content width). */
    private static final int FOUND_WIDTH = 560;

    private final PharmacyService pharmacyService = new PharmacyService();
    private final LocationService locationService = new LocationService();
    /** Address / coordinates / Find from address / Pick on map. */
    private PharmacyLocationFields location;
    /** A new license document chosen with Browse..., uploaded on Save. */
    private File newLicenseFile;
    private File lastFolder;

    public PharmacyProfileFrame() {
        initComponents();
        applyTheme();
        location = new PharmacyLocationFields(this, TITLE, txtAddress, txtLatitude, txtLongitude, lblFoundLocation,
                btnFindFromAddress, FOUND_WIDTH);
        SidebarUtil.attach(this, pnlSidebar, SidebarUtil.PHARMACY_PROFILE);
        load();
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleTitle(lblTitle);
        UITheme.styleMuted(lblSubtitle);
        UITheme.styleCard(pnlProfile);
        UITheme.styleInfoNote(lblStatusNote);
        UITheme.stylePrimaryButton(btnSave);
        txtAddress.setFont(UITheme.FONT_BODY);
        txtPhone.putClientProperty("JTextField.placeholderText", "");
        txtOpeningHours.putClientProperty("JTextField.placeholderText", "8:00 AM – 11:00 PM");
        txtLicenseNo.setToolTipText("The license number cannot be changed. Contact the admin.");
        txtEmail.setToolTipText("The login email cannot be changed.");
    }

    /** Fills the form from the database (also used by "Undo changes"). */
    private void load() {
        try {
            PharmacyDetails d = pharmacyService.getMyPharmacy();
            Pharmacy p = d.getPharmacy();
            txtPharmacyName.setText(p.getName());
            txtLicenseNo.setText(p.getLicenseNo());
            txtOwnerName.setText(d.getOwner() == null ? "" : d.getOwner().getFullName());
            txtPhone.setText(p.getPhone() == null ? "" : p.getPhone());
            txtEmail.setText(d.getOwner() == null ? "" : d.getOwner().getEmail());
            txtOpeningHours.setText(DateUtil.formatTime12(p.getOpenTime()) + " – " + DateUtil.formatTime12(p.getCloseTime()));
            txtAddress.setText(p.getAddress());
            if (p.getLatitude() != null && p.getLongitude() != null) {
                location.show(locationService.getSaved(p.getAreaId()), p.getLatitude(), p.getLongitude());
            }
            newLicenseFile = null;
            txtLicenseFile.setText(p.getLicenseDocPath() == null ? "" : p.getLicenseDocPath());
            txtLicenseFile.setCaretPosition(0);
            lblStatusNote.setText(PharmacyService.statusLine(p));
            cmbDefaultCurrency.setModel(new javax.swing.DefaultComboBoxModel<String>(
                    medix.util.CurrencyUtil.allCurrencies(p.getCurrency()).toArray(new String[0])));
            cmbDefaultCurrency.setSelectedItem(medix.util.CurrencyUtil.label(p.getCurrency()));
            cmbDefaultCurrency.setToolTipText("New batches are priced in this currency (each batch can still use another).");
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not load your pharmacy:\n" + ErrorLog.describe(e));
        }
    }

    private void save() {
        if (ValidationUtil.isBlank(txtPharmacyName.getText())) {
            fail("Enter the pharmacy name.", txtPharmacyName);
            return;
        }
        if (ValidationUtil.isBlank(txtOwnerName.getText())) {
            fail("Enter the owner / pharmacist name.", txtOwnerName);
            return;
        }
        if (!ValidationUtil.isValidPhone(txtPhone.getText())) {
            fail(ValidationUtil.MSG_PHONE, txtPhone);
            return;
        }
        String[] hours = DateUtil.parseOpeningHours(txtOpeningHours.getText());
        if (hours == null) {
            fail("Write opening hours like 8:00 AM – 11:00 PM.", txtOpeningHours);
            return;
        }
        if (ValidationUtil.isBlank(txtAddress.getText())) {
            fail("Enter the pharmacy address.", txtAddress);
            return;
        }
        if (!location.check()) {
            return;
        }
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        try {
            boolean uploaded = pharmacyService.updateProfile(txtPharmacyName.getText(), txtOwnerName.getText(),
                    txtPhone.getText(), hours[0], hours[1], txtAddress.getText(), lat, lon, location.placeFor(lat, lon),
                    newLicenseFile, cmbDefaultCurrency.getSelectedItem() == null ? null
                    : medix.util.CurrencyUtil.codeOf(cmbDefaultCurrency.getSelectedItem().toString()));
            load();
            showInfo("Your profile was saved." + (uploaded ? "\nThe admins were told about the new license document." : ""));
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException | IOException e) {
            showError("Could not save the profile:\n" + ErrorLog.describe(e));
        }
    }


    private void chooseLicenseFile() {
        JFileChooser chooser = new JFileChooser(lastFolder);
        chooser.setDialogTitle("MediX – Choose license document");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("PDF or image (*.pdf, *.png, *.jpg, *.jpeg)",
                FileStorageUtil.LICENSE_EXTENSIONS));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        lastFolder = file.getParentFile();
        String problem = FileStorageUtil.checkLicenseFile(file);
        if (problem != null) {
            showWarning(problem);
            return;
        }
        newLicenseFile = file;
        txtLicenseFile.setText(file.getAbsolutePath() + "  (new – uploaded when you save)");
        txtLicenseFile.setCaretPosition(0);
    }

    private void viewLicense() {
        try {
            pharmacyService.openLicense();
        } catch (ServiceException e) {
            showWarning(e.getMessage());
        } catch (SQLException | IOException e) {
            showError("Could not open the license file:\n" + ErrorLog.describe(e));
        }
    }

    private void fail(String message, JComponent field) {
        showWarning(message);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
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
        pnlProfile = new javax.swing.JPanel();
        lblPharmacyName = new javax.swing.JLabel();
        txtPharmacyName = new javax.swing.JTextField();
        lblLicenseNo = new javax.swing.JLabel();
        txtLicenseNo = new javax.swing.JTextField();
        lblOwnerName = new javax.swing.JLabel();
        txtOwnerName = new javax.swing.JTextField();
        lblPhone = new javax.swing.JLabel();
        txtPhone = new javax.swing.JTextField();
        lblEmail = new javax.swing.JLabel();
        txtEmail = new javax.swing.JTextField();
        lblOpeningHours = new javax.swing.JLabel();
        txtOpeningHours = new javax.swing.JTextField();
        lblAddress = new javax.swing.JLabel();
        scrAddress = new javax.swing.JScrollPane();
        txtAddress = new javax.swing.JTextArea();
        lblLatitude = new javax.swing.JLabel();
        txtLatitude = new javax.swing.JTextField();
        lblLongitude = new javax.swing.JLabel();
        txtLongitude = new javax.swing.JTextField();
        btnFindFromAddress = new javax.swing.JButton();
        btnPickOnMap = new javax.swing.JButton();
        lblFoundLocation = new javax.swing.JLabel();
        lblLicenseFile = new javax.swing.JLabel();
        txtLicenseFile = new javax.swing.JTextField();
        btnViewLicense = new javax.swing.JButton();
        btnBrowse = new javax.swing.JButton();
        lblStatusNote = new javax.swing.JLabel();
        lblDefaultCurrency = new javax.swing.JLabel();
        cmbDefaultCurrency = new javax.swing.JComboBox<>();
        btnUndo = new javax.swing.JButton();
        btnSave = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("MediX – Pharmacy Profile");
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

        lblTitle.setText("Pharmacy Profile");

        lblSubtitle.setText("Keep your details correct so patients can find you.");

        lblPharmacyName.setText("Pharmacy name *");

        lblLicenseNo.setText("Drug license number");

        txtLicenseNo.setEditable(false);

        lblOwnerName.setText("Owner / pharmacist name *");

        lblPhone.setText("Phone *");

        lblEmail.setText("Email");

        txtEmail.setEditable(false);

        lblOpeningHours.setText("Opening hours");

        lblAddress.setText("Address *");

        txtAddress.setColumns(20);
        txtAddress.setLineWrap(true);
        txtAddress.setRows(2);
        txtAddress.setWrapStyleWord(true);
        scrAddress.setViewportView(txtAddress);

        lblLatitude.setText("Latitude *");

        lblLongitude.setText("Longitude *");

        btnFindFromAddress.setText("Find from address");
        btnFindFromAddress.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnFindFromAddressActionPerformed(evt);
            }
        });

        btnPickOnMap.setText("Pick on map");
        btnPickOnMap.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnPickOnMapActionPerformed(evt);
            }
        });

        lblFoundLocation.setText(" ");

        lblLicenseFile.setText("License document");

        txtLicenseFile.setEditable(false);

        btnViewLicense.setText("View");
        btnViewLicense.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnViewLicenseActionPerformed(evt);
            }
        });

        btnBrowse.setText("Browse...");
        btnBrowse.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnBrowseActionPerformed(evt);
            }
        });

        lblStatusNote.setText("Status");

        lblDefaultCurrency.setText("Default currency");

        btnUndo.setText("Undo changes");
        btnUndo.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnUndoActionPerformed(evt);
            }
        });

        btnSave.setText("Save changes");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout pnlProfileLayout = new javax.swing.GroupLayout(pnlProfile);
        pnlProfile.setLayout(pnlProfileLayout);
        pnlProfileLayout.setHorizontalGroup(
            pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlProfileLayout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(pnlProfileLayout.createSequentialGroup()
                        .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblPharmacyName)
                            .addComponent(txtPharmacyName)
                            .addComponent(lblOwnerName)
                            .addComponent(txtOwnerName)
                            .addComponent(lblEmail)
                            .addComponent(txtEmail))
                        .addGap(20, 20, 20)
                        .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblLicenseNo)
                            .addComponent(txtLicenseNo)
                            .addComponent(lblPhone)
                            .addComponent(txtPhone)
                            .addComponent(lblOpeningHours)
                            .addComponent(txtOpeningHours)))
                    .addComponent(lblAddress)
                    .addComponent(scrAddress)
                    .addGroup(pnlProfileLayout.createSequentialGroup()
                        .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblLatitude)
                            .addComponent(txtLatitude))
                        .addGap(12, 12, 12)
                        .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblLongitude)
                            .addComponent(txtLongitude))
                        .addGap(12, 12, 12)
                        .addComponent(btnFindFromAddress)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnPickOnMap))
                    .addComponent(lblFoundLocation, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblLicenseFile)
                    .addGroup(pnlProfileLayout.createSequentialGroup()
                        .addComponent(txtLicenseFile)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnViewLicense)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnBrowse))
                    .addComponent(lblStatusNote, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(pnlProfileLayout.createSequentialGroup()
                        .addComponent(lblDefaultCurrency)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(cmbDefaultCurrency, javax.swing.GroupLayout.PREFERRED_SIZE, 250, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(btnUndo)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnSave)))
                .addGap(20, 20, 20))
        );
        pnlProfileLayout.setVerticalGroup(
            pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlProfileLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblPharmacyName)
                    .addComponent(lblLicenseNo))
                .addGap(4, 4, 4)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtPharmacyName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtLicenseNo, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblOwnerName)
                    .addComponent(lblPhone))
                .addGap(4, 4, 4)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtOwnerName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtPhone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblEmail)
                    .addComponent(lblOpeningHours))
                .addGap(4, 4, 4)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtEmail, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtOpeningHours, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addComponent(lblAddress)
                .addGap(4, 4, 4)
                .addComponent(scrAddress, javax.swing.GroupLayout.PREFERRED_SIZE, 48, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblLatitude)
                    .addComponent(lblLongitude))
                .addGap(4, 4, 4)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtLatitude, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtLongitude, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnFindFromAddress)
                    .addComponent(btnPickOnMap))
                .addGap(4, 4, 4)
                .addComponent(lblFoundLocation)
                .addGap(10, 10, 10)
                .addComponent(lblLicenseFile)
                .addGap(4, 4, 4)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtLicenseFile, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnViewLicense)
                    .addComponent(btnBrowse))
                .addGap(14, 14, 14)
                .addComponent(lblStatusNote)
                .addGap(16, 16, 16)
                .addGroup(pnlProfileLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblDefaultCurrency)
                    .addComponent(cmbDefaultCurrency, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnUndo)
                    .addComponent(btnSave))
                .addGap(16, 16, 16))
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(pnlSidebar, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(24, 24, 24)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblTitle)
                    .addComponent(lblSubtitle)
                    .addComponent(pnlProfile, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
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
                .addComponent(pnlProfile, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(20, 20, Short.MAX_VALUE))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void btnFindFromAddressActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnFindFromAddressActionPerformed
        location.findFromAddress();
    }//GEN-LAST:event_btnFindFromAddressActionPerformed

    private void btnPickOnMapActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnPickOnMapActionPerformed
        location.pickOnMap();
    }//GEN-LAST:event_btnPickOnMapActionPerformed

    private void btnViewLicenseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnViewLicenseActionPerformed
        viewLicense();
    }//GEN-LAST:event_btnViewLicenseActionPerformed

    private void btnBrowseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnBrowseActionPerformed
        chooseLicenseFile();
    }//GEN-LAST:event_btnBrowseActionPerformed

    private void btnUndoActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUndoActionPerformed
        load();
    }//GEN-LAST:event_btnUndoActionPerformed

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
        save();
    }//GEN-LAST:event_btnSaveActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnBrowse;
    private javax.swing.JButton btnFindFromAddress;
    private javax.swing.JButton btnPickOnMap;
    private javax.swing.JButton btnSave;
    private javax.swing.JButton btnUndo;
    private javax.swing.JButton btnViewLicense;
    private javax.swing.JComboBox<String> cmbDefaultCurrency;
    private javax.swing.JLabel lblAddress;
    private javax.swing.JLabel lblDefaultCurrency;
    private javax.swing.JLabel lblEmail;
    private javax.swing.JLabel lblFoundLocation;
    private javax.swing.JLabel lblLatitude;
    private javax.swing.JLabel lblLicenseFile;
    private javax.swing.JLabel lblLicenseNo;
    private javax.swing.JLabel lblLongitude;
    private javax.swing.JLabel lblOpeningHours;
    private javax.swing.JLabel lblOwnerName;
    private javax.swing.JLabel lblPharmacyName;
    private javax.swing.JLabel lblPhone;
    private javax.swing.JLabel lblStatusNote;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlProfile;
    private javax.swing.JPanel pnlSidebar;
    private javax.swing.JScrollPane scrAddress;
    private javax.swing.JTextArea txtAddress;
    private javax.swing.JTextField txtEmail;
    private javax.swing.JTextField txtLatitude;
    private javax.swing.JTextField txtLicenseFile;
    private javax.swing.JTextField txtLicenseNo;
    private javax.swing.JTextField txtLongitude;
    private javax.swing.JTextField txtOpeningHours;
    private javax.swing.JTextField txtOwnerName;
    private javax.swing.JTextField txtPharmacyName;
    private javax.swing.JTextField txtPhone;
    // End of variables declaration//GEN-END:variables
}
