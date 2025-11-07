package org.example.GUI;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Map;

public class ShowInfo {
    private Stage stage;
    private String weather;
//    private List<String> placesAndInfo;
    private Map<String, String> placesAndInfo;
    private final ButtonFromShowInfo listenerForButton;
    private String place;

    public ShowInfo(Stage stage, String weather, Map<String, String> placesAndInfo, ButtonFromShowInfo listener, String place) {
        this.listenerForButton = listener;
        this.stage = stage;
        this.placesAndInfo = placesAndInfo;
        this.weather = weather;
        this.place = place;
    }

    public void show() {
        VBox vbox = new VBox(10);
        Label placeLabel = new Label("Место: " + place);
        placeLabel.setStyle("-fx-font-size: 18px;");
        Label weatherLabel = new Label("Погода: " + weather);

        Label nearbyPlacesLabel = new Label("Места рядом:");
        vbox.getChildren().addAll(placeLabel, weatherLabel, nearbyPlacesLabel);

        for (Map.Entry<String, String> entry : placesAndInfo.entrySet()) {
            Label placeInfoLabel = new Label(entry.getKey() + ": " + entry.getValue());
            placeInfoLabel.setWrapText(true);
            vbox.getChildren().add(placeInfoLabel);
        }
//        for (int i = 0; i < placesAndInfo.size(); i++) {
//            Label placeInfoLabel = new Label(placesAndInfo.get(i));
//            vbox.getChildren().add(placeInfoLabel);
//        }

        Button menuButton = new Button("Вернуться в меню");
        menuButton.setOnAction(e -> listenerForButton.showMenu());

        vbox.getChildren().add(menuButton);

        Scene scene = new Scene(vbox, 400, 600);
        stage.setScene(scene);
        stage.setTitle("Информация о месте");
        stage.show();
    }
}
