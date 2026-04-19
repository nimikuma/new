package com.oidc.core.translation.deepl.service.impl;

import com.oidc.core.translation.deepl.service.HttpClientService;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * OSGi service that provides a single pooled {@link CloseableHttpClient}.
 *
 * <p>One client is shared across all translation calls to avoid exhausting
 * available OS ports and to benefit from connection pooling.
 */
@Component(service = HttpClientService.class, immediate = true)
@Designate(ocd = HttpClientServiceImpl.HttpClientServiceConf.class)
public class HttpClientServiceImpl implements HttpClientService {

    @ObjectClassDefinition(
            name = "DeepL HTTP Client Service",
            description = "Pooled HTTP client used by the DeepL translation service."
    )
    public @interface HttpClientServiceConf {

        @AttributeDefinition(name = "Time To Live (ms)",
                description = "Connection time-to-live in milliseconds.",
                type = AttributeType.INTEGER)
        int time_to_live() default 50_000;

        @AttributeDefinition(name = "Connection Timeout (ms)",
                description = "Connect and socket timeout in milliseconds.",
                type = AttributeType.INTEGER)
        int connection_timeout() default 30_000;

        @AttributeDefinition(name = "Max Connections Per Route",
                type = AttributeType.INTEGER)
        int max_per_route() default 10;

        @AttributeDefinition(name = "Max Total Connections",
                type = AttributeType.INTEGER)
        int max_total() default 20;

        @AttributeDefinition(name = "Keep-Alive",
                description = "Honour Keep-Alive response headers; fall back to time_to_live.")
        boolean keep_alive() default true;
    }

    private static final Logger LOG = LoggerFactory.getLogger(HttpClientServiceImpl.class);

    private HttpClientServiceConf configuration;
    private HttpClientConnectionManager connectionManager;
    private CloseableHttpClient httpClient;

    @Activate
    protected void activate(final HttpClientServiceConf config) {
        this.configuration = config;
        buildConnectionManager();
        buildHttpClient();
        LOG.info("HttpClientServiceImpl activated.");
    }

    @Modified
    protected void modified(final HttpClientServiceConf config) {
        this.configuration = config;
        shutdownClient();
        buildConnectionManager();
        buildHttpClient();
        LOG.info("HttpClientServiceImpl modified.");
    }

    @Deactivate
    protected void deactivate() {
        shutdownClient();
        LOG.info("HttpClientServiceImpl deactivated.");
    }

    @Override
    public CloseableHttpClient getHttpClient() {
        return httpClient;
    }

    // -------------------------------------------------------------------------

    private void buildConnectionManager() {
        final PoolingHttpClientConnectionManager pm = new PoolingHttpClientConnectionManager();
        pm.setMaxTotal(configuration.max_total());
        pm.setDefaultMaxPerRoute(configuration.max_per_route());
        this.connectionManager = pm;
    }

    private void buildHttpClient() {
        final RequestConfig requestConfig = RequestConfig.custom()
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .setConnectTimeout(configuration.connection_timeout())
                .setSocketTimeout(configuration.connection_timeout())
                .setConnectionRequestTimeout(configuration.connection_timeout())
                .build();

        final HttpClientBuilder builder = HttpClientBuilder.create()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(connectionManager);

        if (configuration.keep_alive()) {
            builder.setKeepAliveStrategy(buildKeepAliveStrategy());
        }

        this.httpClient = builder.build();
    }

    private ConnectionKeepAliveStrategy buildKeepAliveStrategy() {
        return (response, context) -> {
            final HeaderElementIterator it = new BasicHeaderElementIterator(
                    response.headerIterator("Keep-Alive"));
            while (it.hasNext()) {
                final HeaderElement el = it.nextElement();
                if ("timeout".equalsIgnoreCase(el.getName()) && el.getValue() != null) {
                    try {
                        return Long.parseLong(el.getValue()) * 1000L;
                    } catch (NumberFormatException ignored) {
                        // fall through to default
                    }
                }
            }
            return configuration.time_to_live();
        };
    }

    private void shutdownClient() {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOG.warn("Error closing HTTP client", e);
            }
        }
        if (connectionManager != null) {
            connectionManager.shutdown();
        }
    }
}
