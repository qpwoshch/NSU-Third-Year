package org.example.API;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class InterestingPlaces {
    private static final String websiteURL = "http://overpass-api.de/api/interpreter";
    private int timeout = 5000;

    public List<String> getPlaces(double lat, double lng) {
        List<String> places = new ArrayList<>();
        String request = "[out:json];" +
                "node[\"amenity\"](around:1000," + lat + "," + lng + ");" +
                "node[\"tourism\"](around:1000," + lat + "," + lng + ");" +
                "node[\"shop\"](around:1000," + lat + "," + lng + ");" +
                "out body;";
        try {
            String urlString = websiteURL + "?data=" + URLEncoder.encode(request, "UTF-8");
            HttpURLConnection connection = getConnect(urlString);
            readAnswer(connection, places);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return places;
    }

    private HttpURLConnection getConnect(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        return connection;
    }

    private void readAnswer(HttpURLConnection connection, List<String> places) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        System.out.println("Response: " + response.toString());
        JSONObject jsonResponse = new JSONObject(response.toString());
        JSONArray nodes = jsonResponse.getJSONArray("elements");
        int maxElements = 5;
        for (int i = 0; i < nodes.length() && i < maxElements; i++) {
            JSONObject node = nodes.getJSONObject(i);
            places.add(node.has("tags") && node.getJSONObject("tags").has("name") ?
                    node.getJSONObject("tags").getString("name") : "Без названия");

        }
    }
}
