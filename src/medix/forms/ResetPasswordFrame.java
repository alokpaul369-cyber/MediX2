package medix.forms;

import java.awt.Cursor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import medix.service.PasswordResetService;
import medix.service.ServiceException;
import medix.util.ErrorLog;
import medix.util.UITheme;
import medix.util.ValidationUtil;

/**
 * "Forgot password?" step 2: the 6-digit code from the e-mail + the new password. "Resend code"
 * waits 60 seconds (countdown with a javax.swing.Timer). On success the login screen opens with
 * the e-mail filled in.
 */
public class ResetPasswordFrame extends javax.swing.JFrame {

    private static final String TITLE = "MediX – New Password";

    private final PasswordResetService resetService = new PasswordResetService();
    private final Timer countdown;
    private int secondsLeft;
    private char echoChar;

    /** email = the address the code was sent to (normalised). */
    public ResetPasswordFrame(String email) {
        initComponents();
        applyTheme();
        txtEmail.setText(email);
        countdown = new Timer(1000, e -> tick());
        startCountdown();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                new LoginFrame(txtEmail.getText()).setVisible(true);
            }
        });
        getRootPane().setDefaultButton(btnResetPassword);
        SwingUtilities.invokeLater(txtCode::requestFocusInWindow);
    }

    private void applyTheme() {
        UITheme.styleFrame(this);
        UITheme.styleHeading(lblHeading);
        UITheme.styleMuted(lblInfo);
        if (resetService.isDemoMode()) {
            lblInfo.setText("Demo mode: the code was shown on screen. It expires in "
                    + PasswordResetService.CODE_MINUTES + " minutes.");
        }
        for (javax.swing.JLabel l : new javax.swing.JLabel[]{lblEmail, lblCode, lblNewPassword, lblConfirm}) {
            l.setFont(UITheme.FONT_BODY_BOLD);
        }
        txtEmail.setEditable(false);
        txtCode.setFont(UITheme.FONT_HEADING);
        txtCode.putClientProperty("JTextField.placeholderText", "123456");
        txtNewPassword.putClientProperty("JTextField.placeholderText", "At least " + ValidationUtil.MIN_PASSWORD_LENGTH
                + " characters");
        chkShowPassword.setOpaque(false);
        echoChar = txtNewPassword.getEchoChar();
        UITheme.stylePrimaryButton(btnResetPassword);
        UITheme.styleLinkButton(btnResendCode);
    }

    // ---------------------------------------------------------------- resend countdown

    private void startCountdown() {
        secondsLeft = PasswordResetService.MIN_SECONDS_BETWEEN;
        updateResendButton();
        countdown.restart();
    }

    private void tick() {
        secondsLeft--;
        if (secondsLeft <= 0) {
            countdown.stop();
        }
        updateResendButton();
    }

    private void updateResendButton() {
        btnResendCode.setEnabled(secondsLeft <= 0);
        btnResendCode.setText(secondsLeft > 0 ? "Resend code (" + secondsLeft + " s)" : "Resend code");
    }

    private void resendCode() {
        final String email = txtEmail.getText();
        btnResendCode.setEnabled(false);
        btnResendCode.setText("Sending…");
        new SwingWorker<PasswordResetService.Request, Void>() {
            @Override
            protected PasswordResetService.Request doInBackground() throws Exception {
                return resetService.requestCode(email);
            }

            @Override
            protected void done() {
                try {
                    PasswordResetService.Request r = get();
                    startCountdown();
                    txtCode.setText("");
                    JOptionPane.showMessageDialog(ResetPasswordFrame.this, r.message, TITLE,
                            JOptionPane.INFORMATION_MESSAGE);
                    if (r.demoCode != null) {
                        JOptionPane.showMessageDialog(ResetPasswordFrame.this, "Your new code:\n\n        " + r.demoCode
                                + "\n\nIt expires in " + PasswordResetService.CODE_MINUTES + " minutes.",
                                ForgotPasswordFrame.DEMO_TITLE, JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    secondsLeft = 0;
                    updateResendButton();
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    showWarning(cause instanceof ServiceException ? cause.getMessage()
                            : "Could not create the code:\n" + ErrorLog.describe(cause));
                }
            }
        }.execute();
    }

    // ---------------------------------------------------------------- reset

    private void resetPassword() {
        String code = txtCode.getText().trim().replace(" ", "");
        String password = new String(txtNewPassword.getPassword());
        if (!code.matches("\\d{6}")) {
            fail("Enter the 6-digit code from the email.", txtCode);
            return;
        }
        if (!ValidationUtil.isValidPassword(password)) {
            fail("The new password must be at least " + ValidationUtil.MIN_PASSWORD_LENGTH + " characters.",
                    txtNewPassword);
            return;
        }
        if (!password.equals(new String(txtConfirmPassword.getPassword()))) {
            fail("The new passwords do not match.", txtConfirmPassword);
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            resetService.resetPassword(txtEmail.getText(), code, password, new String(txtConfirmPassword.getPassword()));
            JOptionPane.showMessageDialog(this, "Password changed. You can log in now.", TITLE,
                    JOptionPane.INFORMATION_MESSAGE);
            new LoginFrame(txtEmail.getText()).setVisible(true);
            dispose();
        } catch (ServiceException e) {
            String m = e.getMessage();
            fail(m, m.contains("password") ? txtNewPassword : txtCode);
            if (m.contains("code") && !m.contains("tries left")) {
                txtCode.setText(""); // expired / cancelled: a new code is needed
            }
        } catch (java.sql.SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not change the password:\n" + ErrorLog.describe(e), TITLE,
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private void showPassword(boolean show) {
        txtNewPassword.setEchoChar(show ? (char) 0 : echoChar);
        txtConfirmPassword.setEchoChar(show ? (char) 0 : echoChar);
    }

    private void back() {
        new LoginFrame(txtEmail.getText()).setVisible(true);
        dispose();
    }

    @Override
    public void dispose() {
        countdown.stop();
        super.dispose();
    }

    private void fail(String message, JComponent field) {
        showWarning(message);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
    }

    private void showWarning(String message) {
        JOptionPane.showMessageDialog(this, message, TITLE, JOptionPane.WARNING_MESSAGE);
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
        lblCode = new javax.swing.JLabel();
        txtCode = new javax.swing.JTextField();
        btnResendCode = new javax.swing.JButton();
        lblNewPassword = new javax.swing.JLabel();
        txtNewPassword = new javax.swing.JPasswordField();
        lblConfirm = new javax.swing.JLabel();
        txtConfirmPassword = new javax.swing.JPasswordField();
        chkShowPassword = new javax.swing.JCheckBox();
        btnBack = new javax.swing.JButton();
        btnResetPassword = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("MediX – New Password");
        setPreferredSize(new java.awt.Dimension(420, 430));
        setResizable(false);

        lblHeading.setText("Enter the code and a new password");

        lblInfo.setText("The code is in the email we just sent. It expires in 15 minutes.");

        lblEmail.setText("Email");

        lblCode.setText("6-digit code");

        btnResendCode.setText("Resend code");
        btnResendCode.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnResendCodeActionPerformed(evt);
            }
        });

        lblNewPassword.setText("New password");

        lblConfirm.setText("Repeat the new password");

        chkShowPassword.setText("Show passwords");
        chkShowPassword.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                chkShowPasswordActionPerformed(evt);
            }
        });

        btnBack.setText("Back");
        btnBack.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnBackActionPerformed(evt);
            }
        });

        btnResetPassword.setText("Reset password");
        btnResetPassword.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnResetPasswordActionPerformed(evt);
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
                    .addComponent(txtEmail, javax.swing.GroupLayout.DEFAULT_SIZE, 360, Short.MAX_VALUE)
                    .addComponent(lblCode)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(txtCode, javax.swing.GroupLayout.DEFAULT_SIZE, 150, Short.MAX_VALUE)
                        .addGap(12, 12, 12)
                        .addComponent(btnResendCode)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addComponent(lblNewPassword)
                    .addComponent(txtNewPassword, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblConfirm)
                    .addComponent(txtConfirmPassword, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(chkShowPassword)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addComponent(btnBack)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnResetPassword)))
                .addGap(24, 24, 24))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addComponent(lblHeading)
                .addGap(4, 4, 4)
                .addComponent(lblInfo)
                .addGap(14, 14, 14)
                .addComponent(lblEmail)
                .addGap(4, 4, 4)
                .addComponent(txtEmail, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lblCode)
                .addGap(4, 4, 4)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtCode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnResendCode))
                .addGap(10, 10, 10)
                .addComponent(lblNewPassword)
                .addGap(4, 4, 4)
                .addComponent(txtNewPassword, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(10, 10, 10)
                .addComponent(lblConfirm)
                .addGap(4, 4, 4)
                .addComponent(txtConfirmPassword, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(6, 6, 6)
                .addComponent(chkShowPassword)
                .addGap(14, 14, Short.MAX_VALUE)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnBack)
                    .addComponent(btnResetPassword))
                .addGap(18, 18, 18))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void btnResendCodeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnResendCodeActionPerformed
        resendCode();
    }//GEN-LAST:event_btnResendCodeActionPerformed

    private void chkShowPasswordActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_chkShowPasswordActionPerformed
        showPassword(chkShowPassword.isSelected());
    }//GEN-LAST:event_chkShowPasswordActionPerformed

    private void btnBackActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnBackActionPerformed
        back();
    }//GEN-LAST:event_btnBackActionPerformed

    private void btnResetPasswordActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnResetPasswordActionPerformed
        resetPassword();
    }//GEN-LAST:event_btnResetPasswordActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnBack;
    private javax.swing.JButton btnResendCode;
    private javax.swing.JButton btnResetPassword;
    private javax.swing.JCheckBox chkShowPassword;
    private javax.swing.JLabel lblCode;
    private javax.swing.JLabel lblConfirm;
    private javax.swing.JLabel lblEmail;
    private javax.swing.JLabel lblHeading;
    private javax.swing.JLabel lblInfo;
    private javax.swing.JLabel lblNewPassword;
    private javax.swing.JTextField txtCode;
    private javax.swing.JPasswordField txtConfirmPassword;
    private javax.swing.JTextField txtEmail;
    private javax.swing.JPasswordField txtNewPassword;
    // End of variables declaration//GEN-END:variables
}
