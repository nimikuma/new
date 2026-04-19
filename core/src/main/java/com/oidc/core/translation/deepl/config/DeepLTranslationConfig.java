package com.oidc.core.translation.deepl.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi configuration for the DeepL Translation Service.
 *
 * <p>Store the API key securely via Cloud Manager:
 * <ul>
 *   <li>Secret variable: {@code DEEPL_API_KEY}</li>
 *   <li>In cfg.json: {@code "apiKey": "$[secret:DEEPL_API_KEY]"}</li>
 * </ul>
 */
@ObjectClassDefinition(
        name = "DeepL Translation Service Configuration",
        description = "Configuration for the DeepL AI translation workflow integration"
)
public @interface DeepLTranslationConfig {

    @AttributeDefinition(
            name = "Enabled",
            description = "Enable or disable the translation service.",
            type = AttributeType.BOOLEAN
    )
    boolean enabled() default true;

    @AttributeDefinition(
            name = "Translate Child Pages",
            description = "When true, the workflow translates the payload page and all its child pages.",
            type = AttributeType.BOOLEAN
    )
    boolean translate_child_pages() default true;

    @AttributeDefinition(
            name = "DeepL API Key",
            description = "DeepL v2 API authentication key. Use $[secret:DEEPL_API_KEY] in production.",
            type = AttributeType.PASSWORD
    )
    String apiKey() default "";

    @AttributeDefinition(
            name = "Use Free Plan",
            description = "true → api-free.deepl.com ; false → api.deepl.com (Pro plan)"
    )
    boolean useFreePlan() default false;

    @AttributeDefinition(
            name = "Service User",
            description = "Sling sub-service name used to obtain a ResourceResolver."
    )
    String serviceUser() default "deepl-translation-service";
}
