package com.oidc.core.translation.deepl.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Thin HTTP client that wraps the DeepL v2 /translate endpoint.
 *
 * <p><b>XML tag strategy used for the AEM TIF integration:</b>
 * <ul>
 *   <li>{@code <t>} – plain text values (tag_handling=xml, splitting_tags=t)</li>
 *   <li>{@code <h>} – HTML rich-text values (splitting_tags=h)</li>
 *   <li>{@code <nt>} – values that must not be translated (ignore_tags=nt)</li>
 * </ul>
 *
 * <p>Each element carries {@code idx} as an attribute so translated values can be
 * mapped back to their original position without relying on response ordering.
 */
public class DeepLApiClient implements AutoCloseable {

    private static final String FREE_API_URL = "https://api-free.deepl.com/v2/translate";
    private static final String PAID_API_URL = "https://api.deepl.com/v2/translate";
    private static final String AUTH_HEADER_PREFIX = "DeepL-Auth-Key ";
    private static final Pattern HTML_PATTERN = Pattern.compile("<[^>]+>");

    private final String apiUrl;
    private final String apiKey;
    private final CloseableHttpClient httpClient;

    public DeepLApiClient(final String apiKey,
                          final boolean useFreePlan,
                          final int connectionTimeoutMs,
                          final int socketTimeoutMs) {
        this.apiKey  = apiKey;
        this.apiUrl  = useFreePlan ? FREE_API_URL : PAID_API_URL;

        final RequestConfig reqConfig = RequestConfig.custom()
                .setConnectTimeout(connectionTimeoutMs)
                .setSocketTimeout(socketTimeoutMs)
                .build();

        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(reqConfig)
                .build();
    }

    /**
     * Translates an array of strings in a single DeepL request.
     *
     * @param texts          source strings (may contain HTML or plain text)
     * @param targetLanguage BCP-47 language code in upper-case, e.g. "DE", "FR"
     * @return translated strings in the same order as {@code texts}
     * @throws IOException          on HTTP or I/O failure
     * @throws DeepLApiException    when DeepL returns a non-2xx status or empty translations
     */
    public String[] translateBatch(final String[] texts,
                                   final String targetLanguage) throws IOException {
        if (texts == null || texts.length == 0) {
            return new String[0];
        }

        final String xmlPayload = buildXmlPayload(texts);
        final HttpPost post = new HttpPost(apiUrl);
        post.setHeader("Authorization", AUTH_HEADER_PREFIX + apiKey);
        post.setEntity(new UrlEncodedFormEntity(
                buildFormParams(targetLanguage, xmlPayload), StandardCharsets.UTF_8));

        try (final CloseableHttpResponse response = httpClient.execute(post)) {
            final int status = response.getStatusLine().getStatusCode();
            final String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            if (status < 200 || status >= 300) {
                throw new DeepLApiException(
                        "DeepL returned HTTP " + status + ": " + body, status);
            }
            return parseTranslatedXml(body, texts.length);
        }
    }

    /**
     * Translates a single string. Delegates to {@link #translateBatch}.
     */
    public String translateSingle(final String text,
                                  final String targetLanguage) throws IOException {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        final String[] results = translateBatch(new String[]{text}, targetLanguage);
        return results.length > 0 ? results[0] : text;
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    // -------------------------------------------------------------------------
    // XML payload construction
    // -------------------------------------------------------------------------

    /**
     * Wraps each source string in an XML element with an {@code idx} attribute so
     * translations can be matched back by position after the DeepL round-trip.
     *
     * <pre>{@code
     * <doc>
     *   <t idx="0">Hello world</t>
     *   <h idx="1"><p>Rich <strong>text</strong></p></h>
     * </doc>
     * }</pre>
     */
    private String buildXmlPayload(final String[] texts) {
        final StringBuilder sb = new StringBuilder("<doc>");
        for (int i = 0; i < texts.length; i++) {
            final String value = texts[i];
            if (StringUtils.isNotBlank(value)) {
                final String tag = isHtml(value) ? "h" : "t";
                sb.append('<').append(tag)
                  .append(" idx=\"").append(i).append("\">")
                  .append(escapeNonHtmlAmpersands(value))
                  .append("</").append(tag).append('>');
            }
        }
        sb.append("</doc>");
        return sb.toString();
    }

    private List<NameValuePair> buildFormParams(final String targetLanguage,
                                                 final String xmlContent) {
        final List<NameValuePair> p = new ArrayList<>();
        p.add(new BasicNameValuePair("text",                xmlContent));
        p.add(new BasicNameValuePair("target_lang",         targetLanguage.toUpperCase()));
        p.add(new BasicNameValuePair("tag_handling",        "xml"));
        p.add(new BasicNameValuePair("splitting_tags",      "t,h"));
        p.add(new BasicNameValuePair("ignore_tags",         "nt"));
        p.add(new BasicNameValuePair("preserve_formatting", "1"));
        p.add(new BasicNameValuePair("outline_detection",   "0"));
        return p;
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    /**
     * Parses the DeepL JSON response and extracts translated text, restoring
     * positions using the {@code idx} attributes preserved through the round-trip.
     */
    private String[] parseTranslatedXml(final String responseBody,
                                         final int expectedCount) throws IOException {
        final JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        if (!json.has("translations")) {
            throw new DeepLApiException("No 'translations' key in DeepL response", 200);
        }

        final JsonArray translations = json.getAsJsonArray("translations");
        if (translations.isEmpty()) {
            throw new DeepLApiException("Empty translations array in DeepL response", 200);
        }

        // DeepL returns all tag-split results merged back. The translated XML preserves
        // our <t idx="..."> / <h idx="..."> wrappers, so parse them back.
        final String translatedXml = translations.get(0).getAsJsonObject()
                .get("text").getAsString();

        return extractByIndex(translatedXml, expectedCount);
    }

    /**
     * Extracts translated strings from the returned XML document using {@code idx}
     * attributes, placing each result at its original array index.
     */
    private String[] extractByIndex(final String xml, final int expectedCount) {
        final String[] results = new String[expectedCount];
        // Match both <t idx="N">...</t> and <h idx="N">...</h> (including multi-line)
        final Pattern elemPattern = Pattern.compile(
                "<[th] idx=\"(\\d+)\">(.*?)</[th]>",
                Pattern.DOTALL);
        final java.util.regex.Matcher m = elemPattern.matcher(xml);
        while (m.find()) {
            final int idx = Integer.parseInt(m.group(1));
            if (idx < expectedCount) {
                results[idx] = m.group(2);
            }
        }
        // Fill any untranslated slots with empty string
        for (int i = 0; i < results.length; i++) {
            if (results[i] == null) {
                results[i] = "";
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private boolean isHtml(final String text) {
        return HTML_PATTERN.matcher(text).find();
    }

    /**
     * Escapes bare ampersands that are not already part of an XML entity,
     * preventing XML parse errors when the source text contains "&" characters.
     */
    private String escapeNonHtmlAmpersands(final String text) {
        return text.replaceAll("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)", "&amp;");
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    public static final class DeepLApiException extends IOException {
        private final int httpStatus;

        public DeepLApiException(final String message, final int httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }
}
