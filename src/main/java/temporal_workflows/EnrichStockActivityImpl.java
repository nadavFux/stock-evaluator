package temporal_workflows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.DTO.BaseStock;
import common.DTO.Stock;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class EnrichStockActivityImpl implements EnrichStockActivity {
    private final String baseUrl;
    private final String techBaseUrl;
    private final Map<String, String> identifierBaseHeaders;
    private final String fallBackAddition;
    private final Map<String, String> techBaseHeaders;

    public EnrichStockActivityImpl(String baseUrl, String techBaseUrl, Map<String, String> identifierBaseHeaders, String fallBackAddition, Map<String, String> techBaseHeaders) {
        this.baseUrl = baseUrl;
        this.techBaseUrl = techBaseUrl;
        this.identifierBaseHeaders = identifierBaseHeaders;
        this.fallBackAddition = fallBackAddition;
        this.techBaseHeaders = techBaseHeaders;
    }

    @Override
    public Stock enrichStockData(BaseStock stock) {

        Stock newStock = null;

        var result = getStockIdentifier(stock.ticker_symbol());
        if (result == null) {
            result = getStockIdentifier(this.fallBackAddition + stock.ticker_symbol());
        }
        try {
            if (result != null) {
                String identifier = result.getAsJsonPrimitive("isin").getAsString();
                String otherName = result.getAsJsonPrimitive("companyName").getAsString();
                var techResult = getStockTech(identifier);
                Double tech_asssessment = null;
                try {
                    tech_asssessment = techResult.getAsJsonObject("data").getAsJsonArray("indicators").asList()
                            .get(0).getAsJsonObject().getAsJsonPrimitive("technical_indicator").getAsDouble();
                } catch (Exception e) {
                    System.out.println("no tech for " + stock.ticker_symbol());
                }
                newStock = new Stock(stock.company_id(), stock.name(), stock.ticker_symbol(), stock.exchange_symbol()
                        , stock.filing_date(), stock.market_cap_before_filing_date(), stock.final_assessment(), stock.buying_recommendation(), identifier, otherName, tech_asssessment);
            } else {
                System.err.println("no isin for stock " + stock.ticker_symbol());
            }

            return newStock;
        } catch (Exception e) {
            System.err.println("Error during enriching" + e.getMessage());
            return new Stock(stock.company_id(), stock.name(), stock.ticker_symbol(), stock.exchange_symbol()
                    , stock.filing_date(), stock.market_cap_before_filing_date(), stock.final_assessment(), stock.buying_recommendation(), null, null, null);
        }
    }

    private JsonObject getStockIdentifier(String tickerSymbol) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(this.baseUrl + tickerSymbol))
                    .method("GET", HttpRequest.BodyPublishers.noBody());
            AddHeadersToRequestBuilder(requestBuilder, this.identifierBaseHeaders);

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonElement element = new JsonParser().parse(response.body());
            return element.getAsJsonObject().getAsJsonArray("data").get(0).getAsJsonObject();
        } catch (Exception e) {
            System.out.println("Error during stock identifier " + e.getMessage());
            return null;
        }
    }

    private JsonObject getStockTech(String identifier) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(this.techBaseUrl.replace("{id}", identifier)))
                .method("GET", HttpRequest.BodyPublishers.noBody());
        AddHeadersToRequestBuilder(requestBuilder, this.techBaseHeaders);


        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonElement element = new JsonParser().parse(response.body());
        return element.getAsJsonObject();
    }

    private void AddHeadersToRequestBuilder(HttpRequest.Builder requestBuilder, Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.header(entry.getKey(), entry.getValue());
        }
    }
}
