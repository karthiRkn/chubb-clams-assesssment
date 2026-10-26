package com.chubb.claims.assessment.config;

import com.chubb.claims.assessment.client.ClaimClient;
import com.chubb.claims.common.DownstreamClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ClientConfig {

    @Bean
    public ClaimClient claimClient(RestClient.Builder builder,
                                   @Value("${claims.services.claim.url}") String url,
                                   @Value("${claims.downstream.connect-timeout:2s}") Duration connectTimeout,
                                   @Value("${claims.downstream.read-timeout:10s}") Duration readTimeout) {
        return new ClaimClient(new DownstreamClient(builder, "Claim Service", url, connectTimeout, readTimeout));
    }
}
