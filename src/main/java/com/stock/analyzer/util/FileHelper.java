package com.stock.analyzer.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.stock.analyzer.model.dto.StockGraphState;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

public class FileHelper {
    public static void SaveResults(List<StockGraphState> finalResults, String fileName) {
        Gson gson = new GsonBuilder()
                /*.setExclusionStrategies(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes f) {
                        return f.getName().equals("closePrices") || f.getName().equals("volumes"); // Omit this field
                    }

                    @Override
                    public boolean shouldSkipClass(Class<?> clazz) {
                        return false;
                    }
                })*/
                .setPrettyPrinting().create();

        try (FileWriter writer = new FileWriter(fileName)) {
            gson.toJson(finalResults, writer);
            System.out.println("Object written to file as JSON.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<StockGraphState> ReadResults(String fileName) throws IOException, ClassNotFoundException {
        Gson gson = new Gson();
        Type listType = new TypeToken<List<StockGraphState>>() {
        }.getType();
        try (FileReader reader = new FileReader(fileName)) {
            return gson.fromJson(reader, listType);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}

