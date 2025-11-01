package org.example.GUI;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ShowLocations {
    private Stage stage;
    private Map<Integer, Map<String, String>> locations;
    private final ButtonFromLocations listenerForButton;

    public ShowLocations(Map<Integer, Map<String, String>> locations, Stage stage, ButtonFromLocations listener) {
        this.listenerForButton = listener;
        this.stage = stage;
        this.locations = locations;
    }

    public void show() {
        VBox vbox = new VBox(10);
        for (Map.Entry<Integer, Map<String, String>> location : locations.entrySet()) {
            Map<String, String> dataOfLocation = location.getValue();
            String data = "Название: " + dataOfLocation.get("Название") + "\nГород: " + dataOfLocation.get("Город") + "\nТип: " + dataOfLocation.get("Тип") + "\nШирина: " + dataOfLocation.get("Ширина") + "\nДолгота: " + dataOfLocation.get("Долгота");
            Button locationButton = new Button(data);
            locationButton.setOnAction(e -> listenerForButton.SearchInfo(dataOfLocation));
            vbox.getChildren().add(locationButton);
        }

        Scene scene = new Scene(vbox, 450, 600);
        stage.setScene(scene);
        stage.setTitle("Локации");
        stage.show();
    }
}
