package medix.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Settings in data/config.properties (created with placeholders on first use). Today only
 * contact.email: the OpenStreetMap usage policy asks every app to identify itself with a way to
 * contact its author, so MediX sends it in the User-Agent of map and place-search requests.
 */
public final class AppConfig {

    public static final String FILE_NAME = "config.properties";
    public static final String CONTACT_EMAIL = "contact.email";
    public static final String PLACEHOLDER_EMAIL = "your.email@example.com";

    private static Properties properties;

    private AppConfig() {
    }

    public static File getFile() {
        return new File(AppPaths.getDataDir(), FILE_NAME);
    }

    /** The e-mail sent to the map service; the placeholder until the user edits the file. */
    public static String contactEmail() {
        String email = load().getProperty(CONTACT_EMAIL, PLACEHOLDER_EMAIL).trim();
        return email.isEmpty() ? PLACEHOLDER_EMAIL : email;
    }

    public static boolean hasRealContactEmail() {
        return !PLACEHOLDER_EMAIL.equals(contactEmail());
    }

    private static synchronized Properties load() {
        if (properties != null) {
            return properties;
        }
        properties = new Properties();
        File file = getFile();
        if (!file.exists()) {
            createDefault(file);
        }
        try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (IOException e) {
            ErrorLog.log("Could not read " + file.getAbsolutePath(), e);
        }
        return properties;
    }

    private static void createDefault(File file) {
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write("# MediX settings\r\n"
                    + "#\r\n"
                    + "# contact.email: your e-mail address. MediX sends it to the free OpenStreetMap services\r\n"
                    + "# (place search and map tiles) with every request, as their usage policy asks, so they can\r\n"
                    + "# contact you if something goes wrong. Replace the placeholder and restart MediX.\r\n"
                    + CONTACT_EMAIL + "=" + PLACEHOLDER_EMAIL + "\r\n");
        } catch (IOException e) {
            ErrorLog.log("Could not create " + file.getAbsolutePath(), e);
        }
    }
}
