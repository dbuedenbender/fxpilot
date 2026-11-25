package io.github.dbuedenbender.fxpilot.modulardemo.widgets;

import javafx.scene.control.Button;

/** Custom control whose package is exported to FxPilot, so {@code fx_property name=serial} can read it. */
public class SerialButton extends Button {

    private final String serial;

    public SerialButton(String text, String serial) {
        super(text);
        this.serial = serial;
    }

    public String getSerial() {
        return serial;
    }
}
