package medix.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import medix.util.AppPaths;
import medix.util.ErrorLog;

/**
 * Sends e-mail through SMTP with JavaMail (javax.mail 1.6.2). Settings are read from
 * data/mail.properties on every send (see data/mail.properties.example):
 * mail.enabled, smtp.host, smtp.port, smtp.starttls, smtp.username, smtp.password, from.name.
 * Without the file, or with mail.enabled=false, MediX runs in DEMO MODE: no e-mail is sent and the
 * password reset code is shown on screen. Sending blocks – call it from a SwingWorker.
 */
public class EmailService {

    public static final String FILE_NAME = "mail.properties";
    private static final int TIMEOUT_MILLIS = 15000;

    public static File getFile() {
        return new File(AppPaths.getDataDir(), FILE_NAME);
    }

    /** True when data/mail.properties exists, says mail.enabled=true and has a host and user name. */
    public boolean isEnabled() {
        Properties p = load();
        return p != null && "true".equalsIgnoreCase(p.getProperty("mail.enabled", "").trim())
                && !p.getProperty("smtp.host", "").trim().isEmpty()
                && !p.getProperty("smtp.username", "").trim().isEmpty();
    }

    /** Sends a plain-text e-mail. Throws MessagingException when SMTP fails (wrong password, offline …). */
    public void send(String to, String subject, String body) throws MessagingException {
        Properties settings = load();
        if (settings == null) {
            throw new MessagingException("E-mail is not set up (data/" + FILE_NAME + " is missing)");
        }
        final String username = settings.getProperty("smtp.username", "").trim();
        final String password = settings.getProperty("smtp.password", "");
        Properties props = new Properties();
        props.put("mail.smtp.host", settings.getProperty("smtp.host", "").trim());
        props.put("mail.smtp.port", settings.getProperty("smtp.port", "587").trim());
        props.put("mail.smtp.auth", "true");
        boolean starttls = !"false".equalsIgnoreCase(settings.getProperty("smtp.starttls", "true").trim());
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        props.put("mail.smtp.starttls.required", String.valueOf(starttls));
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MILLIS));
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
        MimeMessage message = new MimeMessage(session);
        try {
            message.setFrom(new InternetAddress(username, settings.getProperty("from.name", "MediX").trim(), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            message.setFrom(new InternetAddress(username));
        }
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        Transport.send(message);
    }

    /** data/mail.properties, or null when it does not exist / cannot be read. */
    private static Properties load() {
        File file = getFile();
        if (!file.isFile()) {
            return null;
        }
        Properties p = new Properties();
        try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            p.load(in);
            return p;
        } catch (IOException e) {
            ErrorLog.log("Could not read " + file.getAbsolutePath(), e);
            return null;
        }
    }
}
