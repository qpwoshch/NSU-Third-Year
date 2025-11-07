package org.example.API;

import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONArray;
import org.json.JSONObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class Info {
    private static final String WEBSITE_URL = "https://ru.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&exintro=&titles=";
    private int timeout = 150000;
    public String getInfo(String place) {
        try {
            String urlString = WEBSITE_URL + URLEncoder.encode(place, "UTF-8");
            HttpURLConnection connection = getConnect(urlString);
            return readAnswer(connection);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "Информация не найдена.";
    }

    private HttpURLConnection getConnect(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

        return connection;
    }

    private String readAnswer(HttpURLConnection connection) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        System.out.println("Response: " + response.toString());

        try {
            JSONObject jsonResponse = new JSONObject(response.toString());
            JSONObject pages = jsonResponse.getJSONObject("query").getJSONObject("pages");
            String pageId = pages.keys().next();
            JSONObject page = pages.getJSONObject(pageId);

            if (page.has("extract")) {
                String rawExtract = page.getString("extract");

                Document doc = Jsoup.parse(rawExtract);
                return doc.text();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "Информация не найдена.";
    }
}
