package io.github.dbuedenbender.fxpilot.modulardemo.secret;

import javafx.scene.control.Label;

/**
 * Custom control in a package that is not exported. FxPilot still sees it as a Label (public JavaFX API),
 * but cannot call {@link #getCode()} by reflection.
 */
public class VaultLabel extends Label {

    private final String code;

    public VaultLabel(String text, String code) {
        super(text);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
