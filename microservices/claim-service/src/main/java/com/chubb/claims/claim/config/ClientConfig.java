package com.chubb.claims.claim.config;

import com.chubb.claims.claim.client.UserClient;
import com.chubb.claims.common.DownstreamClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ClientConfig {

    @Bean
    public UserClient userClient(RestClient.Builder builder,
                                 @Value("${claims.services.user.url}") String url,
                                 @Value("${claims.downstream.connect-timeout:2s}") Duration connectTimeout,
                                 @Value("${claims.downstream.read-timeout:10s}") Duration readTimeout) {
        return new UserClient(new DownstreamClient(builder, "User Service", url, connectTimeout, readTimeout));
    }
}
