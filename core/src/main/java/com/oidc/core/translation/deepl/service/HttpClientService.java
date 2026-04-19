package com.oidc.core.translation.deepl.service;

import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Provides a single shared, pooled {@link CloseableHttpClient} instance.
 *
 * <p>Opening a new HTTP client per request wastes OS ports and connections.
 * This service holds one configured instance that is reused across all
 * translation calls.
 */
public interface HttpClientService {

    /**
     * Returns the shared HTTP client.
     *
     * @return a fully configured, non-null {@link CloseableHttpClient}
     */
    CloseableHttpClient getHttpClient();
}
