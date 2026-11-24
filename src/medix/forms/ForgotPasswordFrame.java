package medix.forms;

import java.awt.Cursor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import medix.service.PasswordResetService;
import medix.service.ServiceException;
import medix.util.ErrorLog;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * "Forgot password?" step 1: the user types their account e-mail and asks for a 6-digit code.
 * The answer is always the same, whether or not the e-mail has an account. In demo mode (no
 * data/mail.properties) the code is shown on screen. Then ResetPasswordFrame opens.
 */
public class ForgotPasswordFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – Reset Password";
    public static final String DEMO_TITLE = "Demo mode – email is not set up";

    private final PasswordResetService resetService = new PasswordResetService();

    public ForgotPasswordFrame() {
        this(null);
    }

    /** Opens with the email typed on the login screen. */
    public ForgotPasswordFrame(String email) {
        initComponents();
        applyTheme();
        if (email != null) {
            txtEmail.setText(email.trim());
        }
        // closing the window goes back to the login screen
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                new LoginFrame(txtEmail.getText().trim().isEmpty() ? null : txtEmail.getText().trim()).setVisible(true);
            }
        });
        getRootPane().setDefaultButton(btnSendCode);
        SwingUtilities.invokeLater(txtEmail::requestFocusInWindow);
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleHeading(lblHeading);
        UITheme.styleMuted(lblInfo);
        lblEmail.setFont(UITheme.FONT_BODY_BOLD);
        txtEmail.putClientProperty("JTextField.placeholderText", "you@example.com");
        UITheme.stylePrimaryButton(btnSendCode);
        UITheme.styleWarningNote(lblDemoMode);
        lblDemoMode.setText("Demo mode: codes appear on screen");
        lblDemoMode.setToolTipText("E-mail is not set up: copy data/mail.properties.example to data/mail.properties.");
        lblDemoMode.setVisible(resetService.isDemoMode());
    }

    private void sendCode() {
        final String email = txtEmail.getText().trim();
        if (!ValidationUtil.isValidEmail(email)) {
            JOptionPane.showMessageDialog(this, "Enter your account email, like name@example.com.", TITLE,
                    JOptionPane.WARNING_MESSAGE);
            SwingUtilities.invokeLater(txtEmail::requestFocusInWindow);
            return;
        }
        setBusy(true);
        new SwingWorker<PasswordResetService.Request, Void>() {
            @Override
            protected PasswordResetService.Request doInBackground() throws Exception {
                return resetService.requestCode(email);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    PasswordResetService.Request r = get();
                    JOptionPane.showMessageDialog(ForgotPasswordFrame.this, r.message, TITLE,
                            JOptionPane.INFORMATION_MESSAGE);
                    if (r.demoCode != null) {
                        JOptionPane.showMessageDialog(ForgotPasswordFrame.this, "E-mail is not set up, so here is the code"
                                + " that would have been sent:\n\n        " + r.demoCode + "\n\nIt expires in "
                                + PasswordResetService.CODE_MINUTES + " minutes.", DEMO_TITLE,
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                    new ResetPasswordFrame(ValidationUtil.normalizeEmail(email)).setVisible(true);
                    dispose();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String message = cause instanceof ServiceException ? cause.getMessage()
                            : "Could not create the code:\n" + ErrorLog.describe(cause);
                    JOptionPane.showMessageDialog(ForgotPasswordFrame.this, message, TITLE, JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        btnSendCode.setEnabled(!busy);
        btnSendCode.setText(busy ? "Sending…" : "Send code");
        txtEmail.setEnabled(!busy);
    }

    private void backToLogin() {
        String email = txtEmail.getText().trim();
        new LoginFrame(email.isEmpty() ? null : email).setVisible(true);
        dispose();
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
        lblEmail = new javax.swing.JLabel();
        txtEmail = new javax.swing.JTextField();
        lblDemoMode = new javax.swing.JLabel();
        btnBackToLogin = new javax.swing.JButton();
        btnSendCode = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("MediX – Reset Password");
        setPreferredSize(new java.awt.Dimension(420, 340));
        setResizable(false);

        lblHeading.setText("Reset your password");

        lblInfo.setText("Enter your account email. We'll send you a 6-digit code.");

        lblEmail.setText("Email");

        txtEmail.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtEmailActionPerformed(evt);
            }
        });

        lblDemoMode.setText("Demo mode: codes appear on screen");

        btnBackToLogin.setText("Back to login");
        btnBackToLogin.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnBackToLoginActionPerformed(evt);
            }
        });

        btnSendCode.setText("Send code");
        btnSendCode.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSendCodeActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(24, 24, 24)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(lblHeading, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblInfo, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblEmail)
                    .addComponent(txtEmail, javax.swing.GroupLayout.DEFAULT_SIZE, 340, Short.MAX_VALUE)
                    .addComponent(lblDemoMode, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addComponent(btnBackToLogin)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnSendCode)))
                .addGap(24, 24, 24))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(24, 24, 24)
                .addComponent(lblHeading)
                .addGap(6, 6, 6)
                .addComponent(lblInfo)
                .addGap(18, 18, 18)
                .addComponent(lblEmail)
                .addGap(4, 4, 4)
                .addComponent(txtEmail, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(16, 16, 16)
                .addComponent(lblDemoMode)
                .addGap(16, 16, Short.MAX_VALUE)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnBackToLogin)
                    .addComponent(btnSendCode))
                .addGap(20, 20, 20))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void txtEmailActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtEmailActionPerformed
        sendCode();
    }//GEN-LAST:event_txtEmailActionPerformed

    private void btnBackToLoginActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnBackToLoginActionPerformed
        backToLogin();
    }//GEN-LAST:event_btnBackToLoginActionPerformed

    private void btnSendCodeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendCodeActionPerformed
        sendCode();
    }//GEN-LAST:event_btnSendCodeActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnBackToLogin;
    private javax.swing.JButton btnSendCode;
    private javax.swing.JLabel lblDemoMode;
    private javax.swing.JLabel lblEmail;
    private javax.swing.JLabel lblHeading;
    private javax.swing.JLabel lblInfo;
    private javax.swing.JTextField txtEmail;
    // End of variables declaration//GEN-END:variables
}
