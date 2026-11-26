/*
 * Copyright 2026 FxPilot contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dbuedenbender.fxpilot.demo;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalDate;

/** Customer row of the demo; JavaFX properties so the table updates when a row is edited. */
public final class Customer {

    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty email = new SimpleStringProperty();
    private final StringProperty country = new SimpleStringProperty();
    private final StringProperty type = new SimpleStringProperty();
    private final StringProperty priority = new SimpleStringProperty("Normal");
    private final BooleanProperty active = new SimpleBooleanProperty();
    private final DoubleProperty creditLimit = new SimpleDoubleProperty();
    private final IntegerProperty discount = new SimpleIntegerProperty();
    private final ObjectProperty<LocalDate> since = new SimpleObjectProperty<>();

    public Customer(String name, String email, String country, String type, boolean active, double creditLimit) {
        this.name.set(name);
        this.email.set(email);
        this.country.set(country);
        this.type.set(type);
        this.active.set(active);
        this.creditLimit.set(creditLimit);
        this.since.set(LocalDate.of(2024, 1, 15));
    }

    public StringProperty nameProperty() {
        return name;
    }

    public StringProperty emailProperty() {
        return email;
    }

    public StringProperty countryProperty() {
        return country;
    }

    public StringProperty typeProperty() {
        return type;
    }

    public StringProperty priorityProperty() {
        return priority;
    }

    public BooleanProperty activeProperty() {
        return active;
    }

    public DoubleProperty creditLimitProperty() {
        return creditLimit;
    }

    public IntegerProperty discountProperty() {
        return discount;
    }

    public ObjectProperty<LocalDate> sinceProperty() {
        return since;
    }

    public String getName() {
        return name.get();
    }

    public String getCountry() {
        return country.get();
    }

    /** Text shown in lists and used by fx_select on the ComboBox of the order dialog. */
    @Override
    public String toString() {
        return name.get();
    }
}
