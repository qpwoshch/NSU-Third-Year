package org.example.API;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public class Place {
    private String API = Dotenv.configure().load().get("graphhopeer");
    private static final String websiteUrl = "https://graphhopper.com/api/1/geocode";
    private int timeout = 5000;

    public Map<Integer, Map<String, String>> search(String request) {
        Map<Integer, Map<String, String>> locations = new HashMap<>();
        try {
            String urlString = websiteUrl + "?q=" + URLEncoder.encode(request, "UTF-8") + "&key=" + API;
            HttpURLConnection connection = getConnect(urlString);
            readAnswer(connection, locations);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return locations;
    }

    private HttpURLConnection getConnect(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        return connection;
    }

    private void readAnswer(HttpURLConnection connection, Map<Integer, Map<String, String>> locations) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        JSONObject jsonResponse = new JSONObject(response.toString());
        JSONArray hits = jsonResponse.getJSONArray("hits");
        System.out.println(hits.length());
        int maxSizeOfLocations = 10;
        for (int i = 0; i < hits.length() && i < maxSizeOfLocations; i++) {
            JSONObject hit = hits.getJSONObject(i);
            Map<String, String> location = new HashMap<>();
            location.put("Название", hit.getString("name"));
            location.put("Город", hit.getString("city"));
            location.put("Тип", hit.getString("osm_value"));
            location.put("Ширина", String.valueOf(hit.getJSONObject("point").getDouble("lat")));
            location.put("Долгота", String.valueOf(hit.getJSONObject("point").getDouble("lng")));
            locations.put(i, location);
        }
    }
}
