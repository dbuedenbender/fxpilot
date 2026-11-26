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

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Map;
import java.util.TreeMap;

/**
 * Dashboard tab: charts (read with fx_chart), a canvas to draw on (fx_canvas, fx_robot_drag) and the
 * activity list.
 */
final class DashboardView {

    private final ActivityLog log;
    private final ObservableList<Customer> customers;
    private final PieChart countries = new PieChart();

    DashboardView(ActivityLog log, ObservableList<Customer> customers) {
        this.log = log;
        this.customers = customers;
    }

    Node build() {
        CategoryAxis months = new CategoryAxis();
        NumberAxis euros = new NumberAxis();
        euros.setLabel("EUR");
        BarChart<String, Number> revenue = new BarChart<>(months, euros);
        revenue.setId("revenueChart");
        revenue.setTitle("Revenue");
        revenue.setAnimated(false);
        revenue.setLegendVisible(false);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("2026");
        int[] values = {12_400, 9_800, 15_100, 13_700, 17_900, 16_200};
        String[] names = {"Jan", "Feb", "Mar", "Apr", "May", "Jun"};
        for (int i = 0; i < values.length; i++) {
            series.getData().add(new XYChart.Data<>(names[i], values[i]));
        }
        revenue.getData().add(series);
        revenue.setPrefSize(360, 240);

        countries.setId("countryChart");
        countries.setTitle("Customers by country");
        countries.setAnimated(false);
        countries.setPrefSize(300, 240);
        customers.addListener((ListChangeListener<Customer>) c -> updateCountries());
        updateCountries();

        Canvas canvas = new Canvas(300, 120);
        canvas.setId("signatureCanvas");
        GraphicsContext g = canvas.getGraphicsContext2D();
        clear(canvas);
        canvas.setOnMousePressed(e -> {
            g.beginPath();
            g.moveTo(e.getX(), e.getY());
        });
        canvas.setOnMouseDragged(e -> {
            g.lineTo(e.getX(), e.getY());
            g.stroke();
        });
        canvas.setOnMouseReleased(e -> log.info("Signature drawn"));
        canvas.setOnMouseClicked(e -> log.info(String.format("Canvas clicked at %.0f,%.0f", e.getX(), e.getY())));
        Button clearSignature = new Button("Clear signature");
        clearSignature.setId("clearSignatureButton");
        clearSignature.setOnAction(e -> {
            clear(canvas);
            log.info("Signature cleared");
        });

        ListView<String> activity = new ListView<>(log.entries());
        activity.setId("activityList");
        activity.setPrefHeight(150);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(12));
        grid.add(revenue, 0, 0);
        grid.add(countries, 1, 0);
        grid.add(new TitledPane("Signature", new VBox(6, canvas, clearSignature)), 0, 1);
        grid.add(new TitledPane("Activity", activity), 1, 1);
        Label hint = new Label("Charts are read-only; the activity list shows every action of this demo.");
        grid.add(hint, 0, 2, 2, 1);
        return grid;
    }

    private void updateCountries() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Customer c : customers) {
            String country = c.getCountry() == null || c.getCountry().isEmpty() ? "?" : c.getCountry();
            counts.merge(country, 1, Integer::sum);
        }
        countries.getData().clear();
        counts.forEach((country, count) -> countries.getData().add(new PieChart.Data(country, count)));
    }

    private static void clear(Canvas canvas) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.setStroke(Color.web("#1565c0"));
        g.setLineWidth(2);
    }
}
