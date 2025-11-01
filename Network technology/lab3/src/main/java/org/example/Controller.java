package org.example;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.example.API.Info;
import org.example.API.InterestingPlaces;
import org.example.API.Place;
import org.example.API.Weather;
import org.example.GUI.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.lang.Double.parseDouble;


public class Controller implements ButtonFromMenu, ButtonFromLocations, ButtonFromShowInfo {
    private Stage primaryStage;
    private Place place = new Place();
    private Weather weather = new Weather();
    private InterestingPlaces interestingPlaces = new InterestingPlaces();
    private Info info = new Info();

    public void startGUI(Stage primaryStage) {
        this.primaryStage = primaryStage;
        showMenu();
    }

    @Override
    public void showMenu() {
        new Menu(primaryStage, this).show();
    }

    @Override
    public void ExitSelected() {
        primaryStage.close();
    }

    @Override
    public void SearchLocation(String request) {
        CompletableFuture<Map<Integer, Map<String, String>>> locations = CompletableFuture.supplyAsync(() -> place.search(request));
        locations.thenAccept(locationsList -> {
            Platform.runLater(() -> {
                new ShowLocations(locationsList, primaryStage, this).show();
            });
        });
    }

    @Override
    public void SearchInfo(Map<String, String> dataOfLocation) {
        CompletableFuture<String> weatherInPlace = CompletableFuture.supplyAsync(() -> weather.getWeather(parseDouble(dataOfLocation.get("Ширина")), parseDouble(dataOfLocation.get("Долгота"))));
        CompletableFuture<List<String>> interestingPlacesAround = CompletableFuture.supplyAsync(() -> interestingPlaces.getPlaces(parseDouble(dataOfLocation.get("Ширина")), parseDouble(dataOfLocation.get("Долгота"))));
//        interestingPlacesAround.thenAccept(pl -> {
//            weatherInPlace.thenAccept(weather -> {
//                Platform.runLater(() -> {
//                    new ShowInfo(primaryStage, weather, pl, this, dataOfLocation.get("Название")).show();
//                });
//            });
//        });
                Map<String, String> placeAndInfo = new HashMap<>();
        CompletableFuture.allOf(interestingPlacesAround).thenRun(() -> {
            try {
                List<CompletableFuture<Void>> checkEnd = new ArrayList<>();
                List<String> placesResult = interestingPlacesAround.get();
                for (String place : placesResult) {
                    CompletableFuture<String> information = CompletableFuture.supplyAsync(() -> info.getInfo(place));
                    checkEnd.add(information.thenAccept(locate -> {
                        placeAndInfo.put(place, locate);
                    }));
                }
                CompletableFuture.allOf(checkEnd.toArray(new CompletableFuture[0])).thenRun(() -> {
                    weatherInPlace.thenAccept(weather -> {
                        Platform.runLater(() -> {
                            new ShowInfo(primaryStage, weather, placeAndInfo, this,dataOfLocation.get("Название")).show();

                        });
                    });
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

}
