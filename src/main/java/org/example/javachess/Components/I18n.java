package org.example.javachess.Components;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/** UI strings (Italian) from {@code /i18n/messages.properties}; FXML files use the same bundle via {@code %key}. */
public final class I18n {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("i18n.messages", Locale.ITALIAN);

    private I18n() {
    }

    public static ResourceBundle bundle() {
        return BUNDLE;
    }

    /**
     * Returns the string for {@code key} with {@code {0}}, {@code {1}}... replaced by the arguments.
     * Plain replacement (not {@link java.text.MessageFormat}) so Italian apostrophes need no escaping.
     */
    public static String t(String key, Object... args) {
        String value;
        try {
            value = BUNDLE.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
        for (int i = 0; i < args.length; i++) {
            value = value.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return value;
    }
}
