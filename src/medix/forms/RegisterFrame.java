package medix.forms;

import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import medix.engine.GeoUtil;
import medix.model.Pharmacy;
import medix.model.Place;
import medix.service.AuthException;
import medix.service.AuthService;
import medix.service.LocationService;
import medix.util.DateUtil;
import medix.util.ErrorLog;
import medix.util.FileStorageUtil;
import medix.util.PharmacyLocationFields;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * Sign-up for patients and pharmacies anywhere in the world. The two JTabbedPane tabs are panels
 * inside this one JFrame Form. Locations come from LocationPickerFrame, "Find from address"
 * (OpenStreetMap, saved places offline) or typed coordinates.
 */
public class RegisterFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Create Account";
    private static final String TEXT_PATIENT = "Create Account";
    private static final String TEXT_PHARMACY = "Register Pharmacy";
    /** Width of lblFoundLocation (the pharmacy tab content width). */
    private static final int FOUND_WIDTH = 500;

    private final AuthService authService = new AuthService();
    private final LocationService locationService = new LocationService();
    private File licenseFile;
    private File lastFolder;
    /** The patient's chosen location (saved place), or null. */
    private Place patientLocation;
    /** Address / coordinates / Find from address / Pick on map of the Pharmacy tab. */
    private PharmacyLocationFields pharmacyLocation;

    public RegisterFrame() {
        initComponents();
        applyTheme();
        pharmacyLocation = new PharmacyLocationFields(this, TITLE, txtAddress, txtLatitude, txtLongitude,
                lblFoundLocation, btnFindFromAddress, FOUND_WIDTH);
        updateRegisterButton();
        pack();
        setLocationRelativeTo(null);
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleHeading(lblHeading);
        UITheme.styleCard(pnlPatient);
        UITheme.styleCard(pnlPharmacy);
        UITheme.styleInfoNote(lblPatientNote);
        lblPatientNote.setText(UITheme.wrapHtml(lblPatientNote.getText(), 500));
        UITheme.styleWarningNote(lblPharmacyNote);
        UITheme.stylePrimaryButton(btnRegister);

        // keep the button the same width for both texts
        btnRegister.setText(TEXT_PHARMACY);
        Dimension size = btnRegister.getPreferredSize();
        btnRegister.setPreferredSize(new Dimension(size.width + 16, Math.max(size.height, 34)));
        btnCancel.setPreferredSize(new Dimension(btnCancel.getPreferredSize().width + 16, Math.max(size.height, 34)));

        placeholder(txtPatientPhone, "");
        placeholder(txtPharmacyPhone, "");
        placeholder(txtPatientEmail, "you@example.com");
        placeholder(txtPharmacyEmail, "pharmacy@example.com");
        placeholder(txtLicenseNo, "e.g. DL-12345678");
        placeholder(txtOpeningHours, "8:00 AM – 11:00 PM");
        placeholder(txtLicenseFile, "No file chosen");
        placeholder(txtLocation, "Click Choose…");
        txtAddress.setFont(UITheme.FONT_BODY);
    }

    private static void placeholder(JComponent field, String text) {
        field.putClientProperty("JTextField.placeholderText", text);
    }

    private boolean isPharmacyTab() {
        return tabRegister.getSelectedComponent() == pnlPharmacy;
    }

    private void updateRegisterButton() {
        btnRegister.setText(isPharmacyTab() ? TEXT_PHARMACY : TEXT_PATIENT);
    }

    private void backToLogin(String email) {
        new LoginFrame(email).setVisible(true);
        dispose();
    }

    // ---------------------------------------------------------------- patient

    private void choosePatientLocation() {
        new LocationPickerFrame(this, place -> {
            patientLocation = place;
            txtLocation.setText(place.getDisplayName());
            txtLocation.setCaretPosition(0);
            txtLocation.setToolTipText(place.getDisplayName());
        }, null, null, patientLocation).setVisible(true);
    }

    private void registerPatient() {
        if (!validatePatient()) {
            return;
        }
        String email = ValidationUtil.normalizeEmail(txtPatientEmail.getText());
        try {
            authService.registerPatient(txtFullName.getText(), email, txtPatientPhone.getText(),
                    new String(txtPatientPassword.getPassword()), patientLocation);
            JOptionPane.showMessageDialog(this, "Your account was created. You can log in now.",
                    TITLE, JOptionPane.INFORMATION_MESSAGE);
            backToLogin(email);
        } catch (AuthException e) {
            showWarning(e.getMessage());
        } catch (SQLException e) {
            showError("Could not create the account:\n" + ErrorLog.describe(e));
        }
    }

    private boolean validatePatient() {
        if (ValidationUtil.isBlank(txtFullName.getText())) {
            return fail("Enter your full name.", txtFullName);
        }
        if (!checkPhone(txtPatientPhone)) {
            return false;
        }
        if (!checkEmail(txtPatientEmail)) {
            return false;
        }
        if (patientLocation == null) {
            return fail("Choose your location: click Choose… and search for your area or city.", btnChooseLocation);
        }
        return checkPasswords(txtPatientPassword, txtPatientConfirm);
    }

    // ---------------------------------------------------------------- pharmacy

    private void registerPharmacy() {
        if (!validatePharmacy()) {
            return;
        }
        String email = ValidationUtil.normalizeEmail(txtPharmacyEmail.getText());
        String[] hours = DateUtil.parseOpeningHours(txtOpeningHours.getText());
        double lat = pharmacyLocation.getLatitude();
        double lon = pharmacyLocation.getLongitude();
        try {
            // the place found for these coordinates, or the point itself (saved as a MANUAL place)
            Place place = locationService.save(pharmacyLocation.placeFor(lat, lon));
            Pharmacy p = new Pharmacy();
            p.setName(txtPharmacyName.getText().trim());
            p.setLicenseNo(txtLicenseNo.getText().trim());
            p.setAddress(txtAddress.getText().trim());
            p.setAreaId(place.getSavedId());
            p.setLatitude(GeoUtil.round6(lat)); // a shop is public: full precision
            p.setLongitude(GeoUtil.round6(lon));
            p.setOpenTime(hours[0]);
            p.setCloseTime(hours[1]);
            authService.registerPharmacy(txtOwnerName.getText(), email, txtPharmacyPhone.getText(),
                    new String(txtPharmacyPassword.getPassword()), p, licenseFile);
            JOptionPane.showMessageDialog(this,
                    "Registration sent. You can log in after an admin verifies your license.",
                    TITLE, JOptionPane.INFORMATION_MESSAGE);
            backToLogin(email);
        } catch (AuthException e) {
            showWarning(e.getMessage());
        } catch (SQLException | IOException e) {
            showError("Could not register the pharmacy:\n" + ErrorLog.describe(e));
        }
    }

    private boolean validatePharmacy() {
        if (ValidationUtil.isBlank(txtPharmacyName.getText())) {
            return fail("Enter the pharmacy name.", txtPharmacyName);
        }
        String license = txtLicenseNo.getText().trim();
        if (license.isEmpty()) {
            return fail("Enter the drug license number.", txtLicenseNo);
        }
        if (!ValidationUtil.isValidLicenseNo(license)) {
            return fail("The drug license number must be 6-30 letters, digits or dashes.", txtLicenseNo);
        }
        try {
            if (authService.isLicenseRegistered(license)) {
                return fail("This drug license number is already registered.", txtLicenseNo);
            }
        } catch (SQLException e) {
            showError("Could not check the license number:\n" + ErrorLog.describe(e));
            return false;
        }
        if (ValidationUtil.isBlank(txtOwnerName.getText())) {
            return fail("Enter the owner / pharmacist name.", txtOwnerName);
        }
        if (!checkPhone(txtPharmacyPhone) || !checkEmail(txtPharmacyEmail)) {
            return false;
        }
        if (DateUtil.parseOpeningHours(txtOpeningHours.getText()) == null) {
            return fail("Write opening hours like 8:00 AM – 11:00 PM.", txtOpeningHours);
        }
        if (!checkPasswords(txtPharmacyPassword, txtPharmacyConfirm)) {
            return false;
        }
        if (ValidationUtil.isBlank(txtAddress.getText())) {
            return fail("Enter the pharmacy address.", txtAddress);
        }
        if (!pharmacyLocation.check()) {
            return false;
        }
        String fileProblem = FileStorageUtil.checkLicenseFile(licenseFile);
        if (fileProblem != null) {
            return fail(fileProblem, btnBrowse);
        }
        return true;
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
        licenseFile = file;
        txtLicenseFile.setText(file.getAbsolutePath());
        txtLicenseFile.setCaretPosition(0);
    }

    // ---------------------------------------------------------------- shared checks

    private boolean checkPhone(javax.swing.JTextField field) {
        if (ValidationUtil.isBlank(field.getText())) {
            return fail("Enter the phone number.", field);
        }
        if (!ValidationUtil.isValidPhone(field.getText())) {
            return fail(ValidationUtil.MSG_PHONE, field);
        }
        return true;
    }

    private boolean checkEmail(javax.swing.JTextField field) {
        String email = field.getText().trim();
        if (email.isEmpty()) {
            return fail("Enter the email address.", field);
        }
        if (!ValidationUtil.isValidEmail(email)) {
            return fail("Enter a valid email address, like name@example.com.", field);
        }
        try {
            if (authService.isEmailRegistered(email)) {
                return fail("This email is already registered. Log in or use another email.", field);
            }
        } catch (SQLException e) {
            showError("Could not check the email:\n" + ErrorLog.describe(e));
            return false;
        }
        return true;
    }

    private boolean checkPasswords(JPasswordField password, JPasswordField confirm) {
        String pw = new String(password.getPassword());
        if (pw.isEmpty()) {
            return fail("Enter a password.", password);
        }
        if (!ValidationUtil.isValidPassword(pw)) {
            return fail("The password must be at least " + ValidationUtil.MIN_PASSWORD_LENGTH + " characters.", password);
        }
        String again = new String(confirm.getPassword());
        if (again.isEmpty()) {
            return fail("Confirm the password.", confirm);
        }
        if (!pw.equals(again)) {
            return fail("The passwords do not match.", confirm);
        }
        return true;
    }

    /** Shows the problem and puts the cursor in the field. Always returns false. */
    private boolean fail(String message, JComponent field) {
        showWarning(message);
        // after the dialog has handed focus back, so this request wins
        SwingUtilities.invokeLater(field::requestFocusInWindow);
        return false;
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

        lblHeading = new javax.swing.JLabel();
        tabRegister = new javax.swing.JTabbedPane();
        pnlPatient = new javax.swing.JPanel();
        lblFullName = new javax.swing.JLabel();
        txtFullName = new javax.swing.JTextField();
        lblPatientPhone = new javax.swing.JLabel();
        txtPatientPhone = new javax.swing.JTextField();
        lblPatientEmail = new javax.swing.JLabel();
        txtPatientEmail = new javax.swing.JTextField();
        lblLocation = new javax.swing.JLabel();
        txtLocation = new javax.swing.JTextField();
        btnChooseLocation = new javax.swing.JButton();
        lblPatientPassword = new javax.swing.JLabel();
        txtPatientPassword = new javax.swing.JPasswordField();
        lblPatientConfirm = new javax.swing.JLabel();
        txtPatientConfirm = new javax.swing.JPasswordField();
        lblPatientNote = new javax.swing.JLabel();
        pnlPharmacy = new javax.swing.JPanel();
        lblPharmacyName = new javax.swing.JLabel();
        txtPharmacyName = new javax.swing.JTextField();
        lblLicenseNo = new javax.swing.JLabel();
        txtLicenseNo = new javax.swing.JTextField();
        lblOwnerName = new javax.swing.JLabel();
        txtOwnerName = new javax.swing.JTextField();
        lblPharmacyPhone = new javax.swing.JLabel();
        txtPharmacyPhone = new javax.swing.JTextField();
        lblPharmacyEmail = new javax.swing.JLabel();
        txtPharmacyEmail = new javax.swing.JTextField();
        lblOpeningHours = new javax.swing.JLabel();
        txtOpeningHours = new javax.swing.JTextField();
        lblPharmacyPassword = new javax.swing.JLabel();
        txtPharmacyPassword = new javax.swing.JPasswordField();
        lblPharmacyConfirm = new javax.swing.JLabel();
        txtPharmacyConfirm = new javax.swing.JPasswordField();
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
        btnBrowse = new javax.swing.JButton();
        lblPharmacyNote = new javax.swing.JLabel();
        btnCancel = new javax.swing.JButton();
        btnRegister = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("MediX – Create Account");
        setResizable(false);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
        });

        lblHeading.setText("Create your MediX account");

        tabRegister.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                tabRegisterStateChanged(evt);
            }
        });

        lblFullName.setText("Full name *");

        lblPatientPhone.setText("Phone *");

        lblPatientEmail.setText("Email *");

        lblLocation.setText("Your location *");

        txtLocation.setEditable(false);

        btnChooseLocation.setText("Choose…");
        btnChooseLocation.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnChooseLocationActionPerformed(evt);
            }
        });

        lblPatientPassword.setText("Password *");

        lblPatientConfirm.setText("Confirm password *");

        lblPatientNote.setText("MediX saves your location rounded to about 1 km and uses it only to sort pharmacies by distance.");

        javax.swing.GroupLayout pnlPatientLayout = new javax.swing.GroupLayout(pnlPatient);
        pnlPatient.setLayout(pnlPatientLayout);
        pnlPatientLayout.setHorizontalGroup(
            pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPatientLayout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addGroup(pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(pnlPatientLayout.createSequentialGroup()
                        .addGroup(pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblFullName)
                            .addComponent(txtFullName, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
                            .addComponent(lblPatientEmail)
                            .addComponent(txtPatientEmail, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
                            .addComponent(lblPatientPassword)
                            .addComponent(txtPatientPassword, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE))
                        .addGap(20, 20, 20)
                        .addGroup(pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblPatientPhone)
                            .addComponent(txtPatientPhone, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
                            .addComponent(lblLocation)
                            .addGroup(pnlPatientLayout.createSequentialGroup()
                                .addComponent(txtLocation, javax.swing.GroupLayout.DEFAULT_SIZE, 170, Short.MAX_VALUE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnChooseLocation))
                            .addComponent(lblPatientConfirm)
                            .addComponent(txtPatientConfirm, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)))
                    .addComponent(lblPatientNote, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(20, 20, 20))
        );
        pnlPatientLayout.setVerticalGroup(
            pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPatientLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblFullName)
                    .addComponent(lblPatientPhone))
                .addGap(4, 4, 4)
                .addGroup(pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtFullName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtPatientPhone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addGroup(pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblPatientEmail)
                    .addComponent(lblLocation))
                .addGap(4, 4, 4)
                .addGroup(pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtPatientEmail, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtLocation, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnChooseLocation))
                .addGap(10, 10, 10)
                .addGroup(pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblPatientPassword)
                    .addComponent(lblPatientConfirm))
                .addGap(4, 4, 4)
                .addGroup(pnlPatientLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtPatientPassword, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtPatientConfirm, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(16, 16, 16)
                .addComponent(lblPatientNote)
                .addGap(16, 16, Short.MAX_VALUE))
        );

        tabRegister.addTab("Patient", pnlPatient);

        lblPharmacyName.setText("Pharmacy name *");

        lblLicenseNo.setText("Drug license number *");

        lblOwnerName.setText("Owner / pharmacist name *");

        lblPharmacyPhone.setText("Phone *");

        lblPharmacyEmail.setText("Email *");

        lblOpeningHours.setText("Opening hours");

        txtOpeningHours.setText("8:00 AM – 11:00 PM");

        lblPharmacyPassword.setText("Password *");

        lblPharmacyConfirm.setText("Confirm password *");

        lblAddress.setText("Address *");

        txtAddress.setColumns(20);
        txtAddress.setLineWrap(true);
        txtAddress.setRows(2);
        txtAddress.setWrapStyleWord(true);

        scrAddress.setViewportView(txtAddress);

        lblLatitude.setText("Latitude");

        lblLongitude.setText("Longitude");

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

        lblLicenseFile.setText("License document (PDF / image) *");

        txtLicenseFile.setEditable(false);

        btnBrowse.setText("Browse...");
        btnBrowse.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnBrowseActionPerformed(evt);
            }
        });

        lblPharmacyNote.setText("<html>Your pharmacy stays hidden from patients until an admin<br>verifies your license.</html>");

        javax.swing.GroupLayout pnlPharmacyLayout = new javax.swing.GroupLayout(pnlPharmacy);
        pnlPharmacy.setLayout(pnlPharmacyLayout);
        pnlPharmacyLayout.setHorizontalGroup(
            pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPharmacyLayout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(pnlPharmacyLayout.createSequentialGroup()
                        .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblPharmacyName)
                            .addComponent(txtPharmacyName, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
                            .addComponent(lblOwnerName)
                            .addComponent(txtOwnerName, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
                            .addComponent(lblPharmacyEmail)
                            .addComponent(txtPharmacyEmail, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
                            .addComponent(lblPharmacyPassword)
                            .addComponent(txtPharmacyPassword, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE))
                        .addGap(20, 20, 20)
                        .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblLicenseNo)
                            .addComponent(txtLicenseNo, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
                            .addComponent(lblPharmacyPhone)
                            .addComponent(txtPharmacyPhone, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
                            .addComponent(lblOpeningHours)
                            .addComponent(txtOpeningHours, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
                            .addComponent(lblPharmacyConfirm)
                            .addComponent(txtPharmacyConfirm, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)))
                    .addComponent(lblAddress)
                    .addComponent(scrAddress, javax.swing.GroupLayout.DEFAULT_SIZE, 520, Short.MAX_VALUE)
                    .addGroup(pnlPharmacyLayout.createSequentialGroup()
                        .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblLatitude)
                            .addComponent(txtLatitude, javax.swing.GroupLayout.DEFAULT_SIZE, 130, Short.MAX_VALUE))
                        .addGap(12, 12, 12)
                        .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblLongitude)
                            .addComponent(txtLongitude, javax.swing.GroupLayout.DEFAULT_SIZE, 130, Short.MAX_VALUE))
                        .addGap(12, 12, 12)
                        .addComponent(btnFindFromAddress)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnPickOnMap))
                    .addComponent(lblFoundLocation, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblLicenseFile)
                    .addGroup(pnlPharmacyLayout.createSequentialGroup()
                        .addComponent(txtLicenseFile, javax.swing.GroupLayout.DEFAULT_SIZE, 400, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnBrowse))
                    .addComponent(lblPharmacyNote, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(20, 20, 20))
        );
        pnlPharmacyLayout.setVerticalGroup(
            pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlPharmacyLayout.createSequentialGroup()
                .addGap(16, 16, 16)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblPharmacyName)
                    .addComponent(lblLicenseNo))
                .addGap(4, 4, 4)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtPharmacyName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtLicenseNo, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblOwnerName)
                    .addComponent(lblPharmacyPhone))
                .addGap(4, 4, 4)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtOwnerName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtPharmacyPhone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblPharmacyEmail)
                    .addComponent(lblOpeningHours))
                .addGap(4, 4, 4)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtPharmacyEmail, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtOpeningHours, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblPharmacyPassword)
                    .addComponent(lblPharmacyConfirm))
                .addGap(4, 4, 4)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtPharmacyPassword, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtPharmacyConfirm, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addComponent(lblAddress)
                .addGap(4, 4, 4)
                .addComponent(scrAddress, javax.swing.GroupLayout.PREFERRED_SIZE, 48, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblLatitude)
                    .addComponent(lblLongitude))
                .addGap(4, 4, 4)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtLatitude, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(txtLongitude, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnFindFromAddress)
                    .addComponent(btnPickOnMap))
                .addGap(4, 4, 4)
                .addComponent(lblFoundLocation)
                .addGap(10, 10, 10)
                .addComponent(lblLicenseFile)
                .addGap(4, 4, 4)
                .addGroup(pnlPharmacyLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtLicenseFile, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnBrowse))
                .addGap(14, 14, 14)
                .addComponent(lblPharmacyNote)
                .addGap(16, 16, Short.MAX_VALUE))
        );

        tabRegister.addTab("Pharmacy", pnlPharmacy);

        btnCancel.setText("Cancel");
        btnCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelActionPerformed(evt);
            }
        });

        btnRegister.setText("Create Account");
        btnRegister.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRegisterActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblHeading)
                    .addComponent(tabRegister, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addComponent(btnCancel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnRegister)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(lblHeading)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(tabRegister, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnCancel)
                    .addComponent(btnRegister))
                .addContainerGap())
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void formWindowClosing(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosing
        new LoginFrame().setVisible(true);
    }//GEN-LAST:event_formWindowClosing

    private void tabRegisterStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_tabRegisterStateChanged
        updateRegisterButton();
    }//GEN-LAST:event_tabRegisterStateChanged

    private void btnChooseLocationActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnChooseLocationActionPerformed
        choosePatientLocation();
    }//GEN-LAST:event_btnChooseLocationActionPerformed

    private void btnFindFromAddressActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnFindFromAddressActionPerformed
        pharmacyLocation.findFromAddress();
    }//GEN-LAST:event_btnFindFromAddressActionPerformed

    private void btnPickOnMapActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnPickOnMapActionPerformed
        pharmacyLocation.pickOnMap();
    }//GEN-LAST:event_btnPickOnMapActionPerformed

    private void btnBrowseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnBrowseActionPerformed
        chooseLicenseFile();
    }//GEN-LAST:event_btnBrowseActionPerformed

    private void btnCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelActionPerformed
        backToLogin(null);
    }//GEN-LAST:event_btnCancelActionPerformed

    private void btnRegisterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRegisterActionPerformed
        if (isPharmacyTab()) {
            registerPharmacy();
        } else {
            registerPatient();
        }
    }//GEN-LAST:event_btnRegisterActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnBrowse;
    private javax.swing.JButton btnCancel;
    private javax.swing.JButton btnChooseLocation;
    private javax.swing.JButton btnFindFromAddress;
    private javax.swing.JButton btnPickOnMap;
    private javax.swing.JButton btnRegister;
    private javax.swing.JLabel lblAddress;
    private javax.swing.JLabel lblFoundLocation;
    private javax.swing.JLabel lblFullName;
    private javax.swing.JLabel lblHeading;
    private javax.swing.JLabel lblLatitude;
    private javax.swing.JLabel lblLicenseFile;
    private javax.swing.JLabel lblLicenseNo;
    private javax.swing.JLabel lblLocation;
    private javax.swing.JLabel lblLongitude;
    private javax.swing.JLabel lblOpeningHours;
    private javax.swing.JLabel lblOwnerName;
    private javax.swing.JLabel lblPatientConfirm;
    private javax.swing.JLabel lblPatientEmail;
    private javax.swing.JLabel lblPatientNote;
    private javax.swing.JLabel lblPatientPassword;
    private javax.swing.JLabel lblPatientPhone;
    private javax.swing.JLabel lblPharmacyConfirm;
    private javax.swing.JLabel lblPharmacyEmail;
    private javax.swing.JLabel lblPharmacyName;
    private javax.swing.JLabel lblPharmacyNote;
    private javax.swing.JLabel lblPharmacyPassword;
    private javax.swing.JLabel lblPharmacyPhone;
    private javax.swing.JPanel pnlPatient;
    private javax.swing.JPanel pnlPharmacy;
    private javax.swing.JScrollPane scrAddress;
    private javax.swing.JTabbedPane tabRegister;
    private javax.swing.JTextArea txtAddress;
    private javax.swing.JTextField txtFullName;
    private javax.swing.JTextField txtLatitude;
    private javax.swing.JTextField txtLicenseFile;
    private javax.swing.JTextField txtLicenseNo;
    private javax.swing.JTextField txtLocation;
    private javax.swing.JTextField txtLongitude;
    private javax.swing.JTextField txtOpeningHours;
    private javax.swing.JTextField txtOwnerName;
    private javax.swing.JPasswordField txtPatientConfirm;
    private javax.swing.JTextField txtPatientEmail;
    private javax.swing.JPasswordField txtPatientPassword;
    private javax.swing.JTextField txtPatientPhone;
    private javax.swing.JPasswordField txtPharmacyConfirm;
    private javax.swing.JTextField txtPharmacyEmail;
    private javax.swing.JTextField txtPharmacyName;
    private javax.swing.JPasswordField txtPharmacyPassword;
    private javax.swing.JTextField txtPharmacyPhone;
    // End of variables declaration//GEN-END:variables
}
