package com.chubb.claims.claim;

import com.chubb.claims.common.ApiException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Optional allow-list of market codes ({@code claims.markets.allowed}, comma separated). The brief mentions six APAC
 * markets but does not name them, so the default is empty, which keeps accepting any 2-letter upper-case code.
 */
@Component
public class MarketPolicy {

    private final List<String> allowed;

    public MarketPolicy(@Value("${claims.markets.allowed:}") List<String> allowed) {
        this.allowed = allowed.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public void requireAllowed(String market) {
        if (!allowed.isEmpty() && !allowed.contains(market)) {
            throw ApiException.badRequest("INVALID_MARKET", "Market " + market + " is not supported; allowed markets: " + allowed);
        }
    }
}
