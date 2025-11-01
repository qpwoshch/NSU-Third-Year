package org.example.GUI;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class Menu {
    private final Stage stage;
    private final ButtonFromMenu listenerForButton;

    public Menu(Stage stage, ButtonFromMenu listener) {
        this.stage = stage;
        this.listenerForButton = listener;
    }

    public void show() {
        Label label = new Label("Введите место для поиска:");
        TextField textField = new TextField();
        Button enter = new Button("Поиск");
        Button exit = new Button("Выход");
        enter.setOnAction(e -> listenerForButton.SearchLocation(textField.getText()));
        exit.setOnAction(e -> listenerForButton.ExitSelected());
        VBox vbox = new VBox(10, label, textField, enter, exit);
        vbox.setPadding(new Insets(20));
        vbox.setAlignment(Pos.CENTER);

        Scene scene = new Scene(vbox, 450, 600);

        stage.setScene(scene);
        stage.show();
    }
}
