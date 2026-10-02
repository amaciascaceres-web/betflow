package com.alejandromacias.betflow.betting.config;

import com.alejandromacias.betflow.betting.remote.SportsbookClient;
import com.alejandromacias.betflow.betting.remote.WalletClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The two synchronous calls the saga depends on, with the setting that is easiest to forget and
 * most expensive to omit: timeouts.
 *
 * <p>A client with no read timeout waits forever. One slow downstream then holds a request thread
 * per caller until the pool is empty, and a service that was merely slow has taken down one that
 * was healthy. The numbers here are deliberately short — this is a price check and a balance
 * update, not a report — and they are what makes a stuck dependency a failure this service can
 * decide about rather than a hang it cannot.
 */
@Configuration
public class RemoteClientConfig {

    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 3_000;

    @Bean
    SportsbookClient sportsbookClient(RestClient.Builder builder,
            @Value("${betflow.sportsbook.base-url}") String baseUrl) {
        return new SportsbookClient(client(builder, baseUrl));
    }

    @Bean
    WalletClient walletClient(RestClient.Builder builder,
            @Value("${betflow.wallet.base-url}") String baseUrl) {
        return new WalletClient(client(builder, baseUrl));
    }

    private RestClient client(RestClient.Builder builder, String baseUrl) {
        return builder.baseUrl(baseUrl).requestFactory(timeoutingRequestFactory()).build();
    }

    private ClientHttpRequestFactory timeoutingRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        factory.setReadTimeout(READ_TIMEOUT_MILLIS);
        return factory;
    }
}
