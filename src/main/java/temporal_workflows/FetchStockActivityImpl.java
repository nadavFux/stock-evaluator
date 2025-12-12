package temporal_workflows;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.DTO.BaseStock;
import common.DTO.Root;
import common.SectorToStocksConverter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class FetchStockActivityImpl implements FetchStockActivity {
    private final String baseUrl;
    private final Map<String, String> baseHeaders;
    private final String replacementTemplate;

    public FetchStockActivityImpl(String baseUrl, String replacementTemplate, Map<String, String> baseHeaders) {
        this.baseUrl = baseUrl;
        this.baseHeaders = baseHeaders;
        this.replacementTemplate = replacementTemplate;
    }

    @Override
    public List<BaseStock> fetchStockData(int sector) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(this.baseUrl.replace(this.replacementTemplate, String.valueOf(sector))))
                .method("GET", HttpRequest.BodyPublishers.noBody());
        AddHeadersToRequestBuilder(requestBuilder);


        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.out.println("got not 200 in fetch for " + sector);
            return null;
        }
        var body = response.body();
        JsonObject element = JsonParser.parseString(body).getAsJsonObject();
        JsonObject data = element.getAsJsonObject().getAsJsonObject("data");
        data.remove("raw");

        var rootValue = new Gson().fromJson(data, Root.class);
        return SectorToStocksConverter.Convert(rootValue);
    }

    private void AddHeadersToRequestBuilder(HttpRequest.Builder requestBuilder) {
        for (Map.Entry<String, String> entry : this.baseHeaders.entrySet()) {
            requestBuilder.header(entry.getKey(), entry.getValue());
        }
    }
}
