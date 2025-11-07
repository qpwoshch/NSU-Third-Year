package org.example.API;

import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;


public class Weather {
    private String API = Dotenv.configure().load().get("openweathermap");
    private static final String websiteURL = "http://api.openweathermap.org/data/2.5/weather";
    private int timeout = 5000;


    public String getWeather(double lat, double lng) {
        System.out.println(lat + " " + lng);
        try {
            String urlString = websiteURL + "?lat=" + lat + "&lon=" + lng + "&appid=" + API + "&units=metric";
            HttpURLConnection connection = getConnect(urlString);
            return readAnswer(connection);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return " ";
    }

    private HttpURLConnection getConnect(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        return connection;
    }

    private String readAnswer(HttpURLConnection connection) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        JSONObject jsonResponse = new JSONObject(response.toString());
        return jsonResponse.getJSONObject("main").getDouble("temp") + "°C " + "Влажность: " + jsonResponse.getJSONObject("main").getDouble("humidity") + "% " + "Осадки: " + jsonResponse.getJSONArray("weather").getJSONObject(0).getString("description");
    }

}
