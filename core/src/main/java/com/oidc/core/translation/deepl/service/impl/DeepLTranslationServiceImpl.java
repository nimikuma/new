package com.oidc.core.translation.deepl.service.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oidc.core.translation.deepl.config.DeepLTranslationConfig;
import com.oidc.core.translation.deepl.service.DeepLTranslationService;
import com.oidc.core.translation.deepl.service.HttpClientService;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Translates JCR page content using the DeepL v2 API.
 *
 * <p>Follows the Meticulous Digital approach:
 * <ol>
 *   <li>Load AEM's Translation Rules XML
 *       ({@code /conf/global/settings/translation/rules/translation_rules.xml}) to
 *       determine which properties are translatable per resource type.</li>
 *   <li>Walk the {@code jcr:content} tree and match node properties against those rules.</li>
 *   <li>Build an XML payload ({@code <doc><t jcrPath="..." jcrProperty="...">value</t>...</doc>})
 *       and POST it to DeepL using form params with {@code tag_handling=xml}.</li>
 *   <li>Parse the translated XML response using {@code jcrPath}/{@code jcrProperty}
 *       attributes to write values back to JCR via {@link ModifiableValueMap}.</li>
 * </ol>
 *
 * @see <a href="https://meticulous.digital/blog/f/leveraging-ai-translation-tools-in-aem-example-using-deepl">
 *     Meticulous Digital – Leveraging AI Translation Tools in AEM (DeepL)</a>
 */
@Component(
        service = DeepLTranslationService.class,
        immediate = true,
        configurationPid = "com.oidc.core.translation.deepl.config.DeepLTranslationConfig"
)
@Designate(ocd = DeepLTranslationConfig.class)
public class DeepLTranslationServiceImpl implements DeepLTranslationService {

    private static final Logger LOG = LoggerFactory.getLogger(DeepLTranslationServiceImpl.class);

    private static final String FREE_API_URL = "https://api-free.deepl.com/v2/translate";
    private static final String PAID_API_URL = "https://api.deepl.com/v2/translate";
    private static final String JCR_CONTENT  = "jcr:content";

    /** Max characters per DeepL request (free plan limit is ~130 000; stay well under it). */
    private static final int BATCH_MAX_CHARS = 50_000;

    private static final Pattern HTML_PATTERN = Pattern.compile("<[^>]+>");

    // Properties that are never human-readable text and should be skipped
    private static final Set<String> SKIP_PROPERTIES = new HashSet<>(Arrays.asList(
            "jcr:primaryType", "jcr:mixinTypes", "jcr:uuid", "jcr:created", "jcr:createdBy",
            "jcr:lastModified", "jcr:lastModifiedBy", "jcr:frozen",
            // JCR versioning — reference properties, protected, cannot be written
            "jcr:versionHistory", "jcr:baseVersion", "jcr:predecessors",
            "jcr:isCheckedOut", "jcr:mergeFailed", "jcr:activity",
            "cq:lastModified", "cq:lastModifiedBy", "cq:lastReplicated",
            "cq:lastReplicatedBy", "cq:lastReplicationAction", "cq:template",
            "cq:designPath", "cq:responsive",
            "sling:resourceType", "sling:resourceSuperType",
            "fileReference", "dam:assetLastModified",
            // Layout/config properties (short enum-like values)
            "layout", "type", "variant", "size", "alignment", "position", "rank",
            "colorScheme", "bgcolorselection", "colorshade", "stageHeight", "stageVariant",
            "headlineType", "seotag", "ctaType", "ctatype", "ctastyle", "indicatorposition",
            "controlstyle", "contentPosition", "contentBoxAlignment", "offsetTeaserType"
    ));

    // Property name prefixes/suffixes that indicate non-text fields
    private static final Set<String> SKIP_SUFFIXES = new HashSet<>(Arrays.asList(
            "Id", "Ref", "Path", "Type", "Style", "Class", "Color", "Scheme",
            "Enabled", "Tab", "Loop", "Target", "Autoplay", "Icon"
    ));

    @Reference
    private HttpClientService httpClientService;

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    private DeepLTranslationConfig config;

    @Activate
    @Modified
    protected void activate(final DeepLTranslationConfig config) {
        this.config = config;
        LOG.info("DeepLTranslationServiceImpl activated.");
    }

    // -------------------------------------------------------------------------
    // DeepLTranslationService
    // -------------------------------------------------------------------------

    @Override
    public boolean translate(final String path, final String targetLanguage) {
        if (!config.enabled()) {
            LOG.debug("DeepL translation service is disabled.");
            return false;
        }
        if (StringUtils.isBlank(path)) {
            LOG.warn("translate() called with blank path");
            return false;
        }

        try (final ResourceResolver resolver = getServiceResourceResolver()) {
            if (resolver == null) {
                return false;
            }

            final String resolvedLang = resolveTargetLanguage(path, targetLanguage, resolver);
            if (StringUtils.isBlank(resolvedLang)) {
                LOG.warn("Cannot determine target language for path={}. " +
                        "Provide a language code via the Translate dialog, workflow comment, or PROCESS_ARGS.", path);
                return false;
            }

            LOG.debug("Starting translation: path={}, targetLang={}", path, resolvedLang);

            // Step 1 – walk jcr:content tree, collect all human-readable String properties
            final List<TranslatablePropertyValue> properties =
                    collectTranslatableContent(path, resolver);

            if (properties.isEmpty()) {
                LOG.warn("No translatable content found at {}", path);
                return false;
            }

            LOG.info("Collected {} translatable properties for path={}", properties.size(), path);

            // Steps 3 & 4 – batch translate and write back to JCR
            final List<List<TranslatablePropertyValue>> batches = partition(properties);
            LOG.info("Sending {} batch(es) to DeepL for path={}", batches.size(), path);
            for (int b = 0; b < batches.size(); b++) {
                final String translatedXml =
                        requestDeepLTranslation(batches.get(b), resolvedLang);
                if (StringUtils.isBlank(translatedXml)) {
                    LOG.error("Batch {}/{} failed — aborting translation of {}",
                            b + 1, batches.size(), path);
                    return false;
                }
                applyTranslation(translatedXml, resolver);
                LOG.debug("Batch {}/{} applied", b + 1, batches.size());
            }

        } catch (Exception e) {
            LOG.error("Translation failed for path={}: {}", path, e.getMessage(), e);
            return false;
        }

        LOG.info("Translation complete for path={}", path);
        return true;
    }

    @Override
    public boolean translate(final String path, final String targetLanguage,
                             final ResourceResolver resolver) {
        if (!config.enabled()) {
            LOG.debug("DeepL translation service is disabled.");
            return false;
        }
        if (StringUtils.isBlank(path)) {
            LOG.warn("translate() called with blank path");
            return false;
        }
        try {
            final String resolvedLang = resolveTargetLanguage(path, targetLanguage, resolver);
            if (StringUtils.isBlank(resolvedLang)) {
                LOG.warn("Cannot determine target language for path={}", path);
                return false;
            }
            final List<TranslatablePropertyValue> properties =
                    collectTranslatableContent(path, resolver);
            if (properties.isEmpty()) {
                LOG.warn("No translatable content found at {}", path);
                return false;
            }
            LOG.info("Collected {} translatable properties for path={}", properties.size(), path);
            final List<List<TranslatablePropertyValue>> batches = partition(properties);
            LOG.info("Sending {} batch(es) to DeepL for path={}", batches.size(), path);
            for (int b = 0; b < batches.size(); b++) {
                final String translatedXml = requestDeepLTranslation(batches.get(b), resolvedLang);
                if (StringUtils.isBlank(translatedXml)) {
                    LOG.error("Batch {}/{} failed — aborting translation of {}",
                            b + 1, batches.size(), path);
                    return false;
                }
                applyTranslation(translatedXml, resolver);
                LOG.debug("Batch {}/{} applied", b + 1, batches.size());
            }
        } catch (Exception e) {
            LOG.error("Translation failed for path={}: {}", path, e.getMessage(), e);
            return false;
        }
        LOG.info("Translation complete for path={}", path);
        return true;
    }

    @Override
    public boolean translateChildPages() {
        return config.translate_child_pages();
    }

    // -------------------------------------------------------------------------
    // Language resolution
    // -------------------------------------------------------------------------

    private String resolveTargetLanguage(final String path,
                                          final String processArgs,
                                          final ResourceResolver resolver) {
        if (StringUtils.isNotBlank(processArgs)) {
            final String trimmed = processArgs.trim();
            if (trimmed.contains("=")) {
                for (final String token : trimmed.split(",")) {
                    final String[] kv = token.trim().split("=", 2);
                    if (kv.length == 2 && "targetLanguage".equalsIgnoreCase(kv[0].trim())) {
                        return kv[1].trim().toUpperCase();
                    }
                }
            } else {
                return trimmed.toUpperCase();
            }
        }
        final PageManager pageManager = resolver.adaptTo(PageManager.class);
        if (pageManager != null) {
            final Page page = pageManager.getPage(path);
            if (page != null) {
                return page.getLanguage().getLanguage().toUpperCase();
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Step 1 – walk JCR tree, collect all human-readable String properties
    // -------------------------------------------------------------------------

    private List<TranslatablePropertyValue> collectTranslatableContent(
            final String pagePath, final ResourceResolver resolver) {
        final List<TranslatablePropertyValue> result = new ArrayList<>();
        final Resource jcrContent = resolver.getResource(pagePath + "/" + JCR_CONTENT);
        if (jcrContent == null) {
            LOG.warn("No jcr:content found at {}", pagePath);
            return result;
        }
        collectFromResource(jcrContent, result);
        return result;
    }

    private void collectFromResource(final Resource resource,
                                      final List<TranslatablePropertyValue> result) {
        final ValueMap vm = resource.getValueMap();
        final String resourcePath = resource.getPath();

        for (final Map.Entry<String, Object> entry : vm.entrySet()) {
            final String key = entry.getKey();
            if (!(entry.getValue() instanceof String)) {
                continue;
            }
            if (isSkipped(key)) {
                continue;
            }
            final String value = (String) entry.getValue();
            // Must be non-blank, longer than 2 chars, not a path or URL
            if (StringUtils.isBlank(value) || value.length() <= 2
                    || value.startsWith("/") || value.startsWith("http")) {
                continue;
            }
            result.add(new TranslatablePropertyValue(resourcePath, key, value));
            LOG.debug("Collected {}@{}: {}", resourcePath, key,
                    StringUtils.abbreviate(value, 60));
        }

        for (final Resource child : resource.getChildren()) {
            collectFromResource(child, result);
        }
    }

    /**
     * Splits {@code all} into sub-lists where the approximate XML character count of
     * each sub-list does not exceed {@link #BATCH_MAX_CHARS}.
     */
    private List<List<TranslatablePropertyValue>> partition(
            final List<TranslatablePropertyValue> all) {
        final List<List<TranslatablePropertyValue>> batches = new ArrayList<>();
        List<TranslatablePropertyValue> current = new ArrayList<>();
        int currentChars = 0;
        for (final TranslatablePropertyValue p : all) {
            // Estimate XML overhead per element: tags + attributes + value
            final int estimated = p.value.length() + p.jcrPath.length()
                    + p.propertyName.length() + 60;
            if (!current.isEmpty() && currentChars + estimated > BATCH_MAX_CHARS) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(p);
            currentChars += estimated;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private boolean isSkipped(final String key) {
        if (SKIP_PROPERTIES.contains(key)) {
            return true;
        }
        for (final String suffix : SKIP_SUFFIXES) {
            if (key.endsWith(suffix)) {
                return true;
            }
        }
        // Skip namespace system properties (contain ":" and namespace is not jcr:title etc.)
        if (key.contains(":") && !key.startsWith("jcr:") && !key.startsWith("cq:")) {
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Step 3 – build XML payload and POST to DeepL via form params
    // -------------------------------------------------------------------------

    private String requestDeepLTranslation(final List<TranslatablePropertyValue> properties,
                                            final String targetLanguage) throws IOException {
        final String xmlPayload = buildXml(properties);
        final String apiUrl = config.useFreePlan() ? FREE_API_URL : PAID_API_URL;
        final HttpPost post = new HttpPost(apiUrl);
        post.setHeader("Authorization", "DeepL-Auth-Key " + config.apiKey());

        final List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("text",                xmlPayload));
        params.add(new BasicNameValuePair("target_lang",         targetLanguage.toUpperCase()));
        params.add(new BasicNameValuePair("tag_handling",        "xml"));
        params.add(new BasicNameValuePair("splitting_tags",      "t,h"));
        params.add(new BasicNameValuePair("ignore_tags",         "nt"));
        params.add(new BasicNameValuePair("preserve_formatting", "1"));
        params.add(new BasicNameValuePair("outline_detection",   "false"));
        post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

        try (final CloseableHttpResponse response = httpClientService.getHttpClient().execute(post)) {
            final int status = response.getStatusLine().getStatusCode();
            final String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            LOG.debug("DeepL response status={}", status);
            if (status < 200 || status >= 300) {
                LOG.error("DeepL API error HTTP {}: {}", status, body);
                return null;
            }
            final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("translations") || json.getAsJsonArray("translations").isEmpty()) {
                LOG.error("No translations in DeepL response: {}", body);
                return null;
            }
            return json.getAsJsonArray("translations")
                       .get(0).getAsJsonObject()
                       .get("text").getAsString();
        }
    }

    private String buildXml(final List<TranslatablePropertyValue> properties) {
        final StringBuilder sb = new StringBuilder("<doc>");
        for (final TranslatablePropertyValue p : properties) {
            if (StringUtils.isNotBlank(p.value)) {
                final boolean htmlVal = isHtml(p.value);
                final String  tag     = htmlVal ? "h" : "t";
                final String  safe    = htmlVal ? fixHtmlForXml(p.value) : escapeXmlText(p.value);
                sb.append(String.format("<%s jcrPath=\"%s\" jcrProperty=\"%s\">%s</%s>",
                        tag, p.jcrPath, p.propertyName, safe, tag));
            }
        }
        sb.append("</doc>");
        return sb.toString();
    }

    /**
     * XML-escapes plain text so it is safe to embed inside an XML element.
     * Handles {@code &}, {@code <}, {@code >}.
     */
    private static String escapeXmlText(final String text) {
        return text
            .replaceAll("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    /**
     * Makes an AEM RTE HTML string safe to embed inside an XML document by:
     * <ol>
     *   <li>Self-closing common void elements ({@code <br>}, {@code <hr>}, {@code <img>}, etc.)</li>
     *   <li>Replacing HTML-only named entities with numeric XML character references.</li>
     *   <li>Escaping any remaining bare {@code &} that are not already entity references.</li>
     * </ol>
     */
    private static String fixHtmlForXml(String html) {
        // Self-close <br> and <hr> (with optional whitespace/attrs)
        html = html.replaceAll("(?i)<br\\s*/?>", "<br/>");
        html = html.replaceAll("(?i)<hr\\s*/?>", "<hr/>");
        // Self-close other void elements that have attributes
        html = html.replaceAll(
            "(?i)<(img|input|area|base|col|embed|link|meta|param|source|track)(\\s[^>]*?)(?<!/)>",
            "<$1$2/>");
        // Replace HTML-only named entities with XML-safe numeric character references
        html = html
            .replace("&nbsp;",   "&#160;")
            .replace("&ndash;",  "&#8211;")
            .replace("&mdash;",  "&#8212;")
            .replace("&laquo;",  "&#171;")
            .replace("&raquo;",  "&#187;")
            .replace("&copy;",   "&#169;")
            .replace("&reg;",    "&#174;")
            .replace("&trade;",  "&#8482;")
            .replace("&euro;",   "&#8364;")
            .replace("&hellip;", "&#8230;")
            .replace("&bull;",   "&#8226;")
            .replace("&middot;", "&#183;")
            .replace("&times;",  "&#215;")
            .replace("&divide;", "&#247;")
            .replace("&szlig;",  "&#223;")
            .replace("&auml;",   "&#228;")
            .replace("&ouml;",   "&#246;")
            .replace("&uuml;",   "&#252;")
            .replace("&Auml;",   "&#196;")
            .replace("&Ouml;",   "&#214;")
            .replace("&Uuml;",   "&#220;");
        // Escape bare & not already part of an entity reference
        html = html.replaceAll("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)", "&amp;");
        return html;
    }

    // -------------------------------------------------------------------------
    // Step 4 – parse XML response, write back to JCR
    // -------------------------------------------------------------------------

    private void applyTranslation(final String translatedXml,
                                   final ResourceResolver resolver) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            final Document doc = builder.parse(new InputSource(new StringReader(translatedXml)));

            processTranslationNodeList(doc.getElementsByTagName("t"), resolver);
            processTranslationNodeList(doc.getElementsByTagName("h"), resolver);

            if (resolver.hasChanges()) {
                resolver.commit();
            }
        } catch (Exception e) {
            LOG.error("Failed to apply translation: {}", e.getMessage(), e);
        }
    }

    private void processTranslationNodeList(final NodeList nodeList,
                                             final ResourceResolver resolver) {
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (nodeList.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                continue;
            }
            final Element element = (Element) nodeList.item(i);
            final String jcrPath     = element.getAttribute("jcrPath");
            final String jcrProperty = element.getAttribute("jcrProperty");
            final String translated  = innerXml(element);

            if (StringUtils.isAnyBlank(jcrPath, jcrProperty)) {
                continue;
            }
            final Resource resource = resolver.getResource(jcrPath);
            if (resource == null) {
                LOG.warn("Resource not found for write-back: {}", jcrPath);
                continue;
            }
            final ModifiableValueMap vm = resource.adaptTo(ModifiableValueMap.class);
            if (vm == null) {
                LOG.warn("ModifiableValueMap is null at {}", jcrPath);
                continue;
            }
            try {
                vm.put(jcrProperty, translated);
                LOG.debug("Wrote {}@{}", jcrPath, jcrProperty);
            } catch (IllegalArgumentException e) {
                LOG.warn("Skipped protected/incompatible property {}@{}: {}",
                        jcrPath, jcrProperty, e.getMessage());
            }
        }
    }

    /**
     * Returns the inner XML/text content of a DOM element, preserving any
     * child HTML tags (e.g. {@code <strong>}, {@code <p>}) for rich-text fields.
     */
    private String innerXml(final Element element) {
        final StringBuilder sb = new StringBuilder();
        final NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
                sb.append(child.getNodeValue());
            } else if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                try {
                    final TransformerFactory tf = TransformerFactory.newInstance();
                    tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                    final Transformer transformer = tf.newTransformer();
                    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                    final StringWriter sw = new StringWriter();
                    transformer.transform(new DOMSource(child), new StreamResult(sw));
                    sb.append(sw.toString());
                } catch (Exception e) {
                    LOG.warn("Failed to serialize inner XML element: {}", e.getMessage());
                }
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private boolean isHtml(final String text) {
        return HTML_PATTERN.matcher(text).find();
    }

    private String escapeAmpersands(final String text) {
        return text.replaceAll("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)", "&amp;");
    }

    // -------------------------------------------------------------------------
    // Service user resolver
    // -------------------------------------------------------------------------

    private ResourceResolver getServiceResourceResolver() {
        try {
            return resourceResolverFactory.getServiceResourceResolver(
                    Collections.singletonMap(
                            ResourceResolverFactory.SUBSERVICE, config.serviceUser()));
        } catch (LoginException e) {
            LOG.error("Cannot obtain service resource resolver for sub-service '{}': {}",
                    config.serviceUser(), e.getMessage(), e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Inner model class
    // -------------------------------------------------------------------------

    private static final class TranslatablePropertyValue {
        final String jcrPath;
        final String propertyName;
        final String value;

        TranslatablePropertyValue(final String jcrPath,
                                   final String propertyName,
                                   final String value) {
            this.jcrPath      = jcrPath;
            this.propertyName = propertyName;
            this.value        = value;
        }
    }
}

