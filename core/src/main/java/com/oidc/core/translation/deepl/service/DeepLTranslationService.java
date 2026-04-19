package com.oidc.core.translation.deepl.service;

/**
 * Translates content on a single AEM page (or content fragment) via DeepL.
 *
 * <p>The implementation walks the JCR node tree under the given path, collects
 * all configurable properties, sends them to the DeepL API, and writes the
 * translated values back to the JCR.
 */
public interface DeepLTranslationService {

    /**
     * Translates the translatable properties of the given JCR path.
     *
     * @param path           absolute JCR path of the page or content fragment
     *                       (e.g. {@code /content/mysite/en/home})
     * @param targetLanguage BCP-47 language code in DeepL upper-case format
     *                       (e.g. {@code "DE"}, {@code "FR"})
     * @return {@code true} if the translation was applied successfully,
     *         {@code false} if the service is disabled or an error occurred
     */
    boolean translate(String path, String targetLanguage);

    /**
     * Translates the page at {@code path} using the provided {@link org.apache.sling.api.resource.ResourceResolver}
     * instead of opening an internal service resolver. Use this when the caller already holds a
     * valid, committed session (e.g. from a Sling servlet).
     *
     * @param path           absolute JCR path of the page
     * @param targetLanguage DeepL language code (e.g. {@code "EN"}, {@code "DE"})
     * @param resolver       already-open resolver with read/write access to {@code path}
     * @return {@code true} if translation was applied successfully
     */
    boolean translate(String path, String targetLanguage, org.apache.sling.api.resource.ResourceResolver resolver);

    /**
     * Returns whether the workflow should also translate child pages of the
     * payload node (driven by the OSGi configuration).
     *
     * @return {@code true} when child-page translation is enabled
     */
    boolean translateChildPages();
}
