package com.stock.analyzer.service;

import com.google.gson.*;
import com.stock.analyzer.model.dto.Stock;
import com.stock.analyzer.model.dto.StockGraphState;
import com.stock.analyzer.model.dto.StocksGraphRequestBody;
import com.stock.analyzer.model.dto.StocksSearchRequestBody;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Stream;

public class StockGraphServiceImpl implements StockGraphService {


    private final String searchApiUrl;
    private final String getGraphUrl;
    private final Gson gson;
    private final String criticsTemplate;
    private final String getKeysUrl;

    public StockGraphServiceImpl(String searchApiUrl, String getGraphUrl, String getKeysUrl, String getKeysTemplate) {
        this.searchApiUrl = searchApiUrl;
        this.getGraphUrl = getGraphUrl;
        this.getKeysUrl = getKeysUrl;
        this.criticsTemplate = getKeysTemplate;
        this.gson = new Gson();
    }

    @Override
    public StockGraphState graphStockData(Stock stock) {
        try {
            var requestBody = new StocksSearchRequestBody(stock.ticker_symbol());
            var requestBodyString = this.gson.toJson(requestBody, StocksSearchRequestBody.class);
            JsonObject element = SendPost(this.searchApiUrl, requestBodyString);
            if (element == null) {
                System.out.println("Something null with stock " + stock.ticker_symbol());
                return null;
            }
            JsonObject data = element.getAsJsonArray("data").get(0).getAsJsonObject();
            String ticker = data.getAsJsonPrimitive("ticker").getAsString();
            if (!ticker.equals(stock.ticker_symbol()) || !stock.exchange_symbol().equals(data.getAsJsonPrimitive("exchange").getAsString())) {
                System.out.println("Something wrong with stock " + stock.ticker_symbol());
                return null;
            }

            String KID = data.getAsJsonPrimitive("KID").getAsString();

            var ratings = GetGraphForType(KID, "fest_est_ar_avg_no").map(JsonPrimitive::getAsDouble).toList();
            var currentLow = 0;//GetGraphForType(KID, "fest_estpt_low").map(JsonPrimitive::getAsDouble).toList();
            var avgs = GetGraphForType(KID, "fest_estpt").map(JsonPrimitive::getAsDouble).toList();
            var epss = GetGraphForType(KID, "fest_esteps_ntm").map(JsonPrimitive::getAsDouble).toList();
            List<Double> caps = GetGraphForType(KID, "f_mkt").map(JsonPrimitive::getAsDouble).toList();
            var currentHigh = 0;//GetGraphForType(KID, "fest_estpt_high").map(JsonPrimitive::getAsDouble).toList();
            var critics = 0;//GetCritics(KID);

            StocksGraphRequestBody candles = new StocksGraphRequestBody(KID, "p_candle_range", 1200);
            requestBodyString = this.gson.toJson(candles, StocksGraphRequestBody.class);
            var stockResponse = SendPost(this.getGraphUrl, requestBodyString).getAsJsonObject("graph");
            var volumePricings = stockResponse.getAsJsonArray("volume").asList().stream().map(JsonElement::getAsDouble).toList();
            var datePricings = stockResponse.getAsJsonArray("date").asList().stream().map(JsonElement::getAsString).toList();
            var closePricings = stockResponse.getAsJsonArray("close").asList().stream().map(JsonElement::getAsDouble).toList();
            double avg20 = closePricings.subList(closePricings.size() - 20, closePricings.size()).stream().mapToDouble(Double::doubleValue).average().orElse(-1);
            //double avg150 = closePricings.subList(closePricings.size() - 150, closePricings.size()).stream().mapToDouble(Double::doubleValue).average().orElse(-1);
            var currentClose = closePricings.get(closePricings.size() - 1);
            var size = Math.min(caps.size(), Math.min(ratings.size(), Math.min(Math.min(Math.min(avgs.size(), closePricings.size()), datePricings.size()), epss.size())));


            return new StockGraphState(stock, ratings, currentLow, currentHigh, currentClose,
                    closePricings.subList(closePricings.size() - size, closePricings.size()),
                    /*volumePricings.subList(volumePricings.size() - size, volumePricings.size())*/ null,
                    avgs.subList(avgs.size() - size, avgs.size()),
                    datePricings.subList(datePricings.size() - size, datePricings.size()),
                    epss.subList(epss.size() - size, epss.size()),
                    caps.subList(caps.size() - size, caps.size()));//.subList(datePricings.size() - size, datePricings.size()));
            //return new StockGraphState(stock, currentRating, currentLow, currentAvg, currentHigh, currentClose, avg20, avg150, critics);
        } catch (Exception e) {
            System.err.println("Error during graphing" + e.getMessage());
            return null;
        }
    }

    private Stream<JsonPrimitive> GetGraphForType(String KID, String type) throws IOException, InterruptedException {
        StocksGraphRequestBody rating = new StocksGraphRequestBody(KID, type, 1200);
        String requestBodyString = this.gson.toJson(rating, StocksGraphRequestBody.class);
        var result = SendPost(this.getGraphUrl, requestBodyString);
        if (result == null) {
            System.out.println("null when attempting to get " + requestBodyString);
            throw new NullPointerException("null when attempting to get " + requestBodyString);
        }
        var value = result.getAsJsonObject("graph").getAsJsonArray("value");
        if (value == null) {
            System.err.println("bad data for " + requestBodyString);
            throw new NullPointerException("null when attempting to get " + requestBodyString);
        }
        return value.asList().stream().map(JsonElement::getAsJsonPrimitive);
    }

    private JsonObject SendPost(String url, String bodyString) throws IOException, InterruptedException {
        HttpRequest request;
        HttpResponse<String> response;
        HttpRequest.Builder requestBuilder;
        requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyString));


        request = requestBuilder.build();
        response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.out.println("got status " + response.statusCode() + " for " + bodyString);
            return null;
        }

        return new JsonParser().parse(response.body()).getAsJsonObject();
    }

    private int GetCritics(String KID) throws IOException, InterruptedException {
        String body = this.criticsTemplate.replace("{KID}", KID);
        return this.SendPost(this.getKeysUrl, body).getAsJsonObject("KID").getAsJsonObject(KID)
                .getAsJsonObject().asMap().values().stream()
                .mapToInt(key -> key.getAsJsonObject().getAsJsonPrimitive("value").getAsInt())
                .sum();

    }
}

