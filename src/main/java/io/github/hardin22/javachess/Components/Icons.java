package io.github.hardin22.javachess.Components;

import org.kordamp.ikonli.javafx.FontIcon;

/** Feather icons (ikonli "fth-" literals) coloured by CSS through the {@code icon} style class. */
public final class Icons {

    private Icons() {
    }

    public static FontIcon of(String literal) {
        return of(literal, 20);
    }

    public static FontIcon of(String literal, int size) {
        FontIcon icon = new FontIcon(literal);
        icon.setIconSize(size);
        icon.getStyleClass().add("icon");
        return icon;
    }
}
