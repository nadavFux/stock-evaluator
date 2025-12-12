package common.DTO;

import java.util.Arrays;
import java.util.List;

public class StocksSearchRequestBody {

    private final String searchString;
    private final List<String> categories = Arrays.asList("Economic", "Forex", "Mutual Funds", "Model Portfolio", "Client Portfolio", "Futures", "ETF", "Equity", "Index", "Bonds", "Other", "SMA", "Bond", "Closed-End Funds", "Preferred Security", "Warrants", "A-B", "Crypto");
    private final List<String> domains = Arrays.asList("NONE", "UserSecurity");
    private final boolean primaryOnly = true;

    public StocksSearchRequestBody(String searchString) {
        this.searchString = searchString;
    }
}
