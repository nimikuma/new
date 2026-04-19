# AEM DeepL Translator

A plug-in for **Adobe Experience Manager (AEM)** that adds a **"Translate with DeepL"** button to the Sites console. When triggered, it:

1. Creates a language copy of the selected page at the target language path
2. Translates all text/rich-text properties using the [DeepL v2 API](https://www.deepl.com/docs-api)
3. Writes translations back to JCR and commits — no workflow engine required

---

## File Structure

```
core/src/main/java/com/oidc/core/translation/deepl/
├── client/          DeepLApiClient — low-level HTTP wrapper
├── config/          DeepLTranslationConfig — OSGi config interface
├── jobs/            TranslationJob — background job support
├── service/         DeepLTranslationService interface + HttpClientService interface
│   └── impl/        DeepLTranslationServiceImpl + HttpClientServiceImpl
├── servlet/         TranslationStartServlet — POST /bin/oidc/translation/start
└── workflows/       CreateLanguageCopyWorkflowProcess + DeepLWorkflowProcess

ui.apps/
└── apps/
    ├── oidc/clientlibs/deepl-translation/
    │   ├── .content.xml        clientlib node (category: cq.wcm.sites)
    │   ├── js.txt
    │   └── translate-action.js Coral 3 dialog + Sites console button handler
    └── wcm/core/content/sites/jcr:content/actions/selection/deepl-translate/
        └── .content.xml        Sites console action bar button overlay

ui.config/
└── apps/oidc/osgiconfig/
    ├── config/       ServiceUserMapper amendment (deepl-translation-service)
    └── config.author/ DeepLTranslationConfig.cfg.json (API key, language, etc.)

ui.content/
└── conf/global/settings/translation/rules/translation_rules.xml
└── var/workflow/models/deepl-translation-workflow/.content.xml
```

---

## How It Works

### Sites Console Button
The clientlib (`cq.wcm.sites`) injects `translate-action.js` which listens for clicks on `.cq-siteadmin-admin-actions-deepl-translate-activator`. A Coral 3 dialog opens with a language selector.

### Servlet (`POST /bin/oidc/translation/start`)
Parameters: `path` (repeatable), `targetLanguage` (e.g. `EN`, `DE`, `FR`)

For each path:
1. **Language copy** — resolves language root at depth 2 (e.g. `/content/site/de` → `/content/site/en`), shallow-copies ancestor pages, deep-copies source page to target path
2. **Translation** — `DeepLTranslationService.translate(targetPath, targetLanguage, resolver)` uses the caller's resolver directly (no service user required)

### Translation Service
- Walks `jcr:content` tree, skips non-text properties via `SKIP_PROPERTIES` + suffix rules
- Batches up to 50,000 chars per DeepL request
- Builds XML payload `<doc><t jcrPath="..." jcrProperty="...">value</t>...</doc>`
- Sanitizes HTML values to valid XML before sending (`<br>` → `<br/>`, `&nbsp;` → `&#160;`, etc.)
- Parses response and writes translated values back via `ModifiableValueMap`

---

## Configuration

Deploy `ui.config/src/main/content/jcr_root/apps/oidc/osgiconfig/config.author/com.oidc.core.translation.deepl.config.DeepLTranslationConfig.cfg.json`:

```json
{
  "enabled": true,
  "apiKey": "YOUR_DEEPL_API_KEY",
  "useFreePlan": true,
  "serviceUser": "deepl-translation-service",
  "translate_child_pages": false
}
```

For AEM as a Cloud Service, use Cloud Manager secret variables:
```json
{
  "apiKey": "$[secret:DEEPL_API_KEY]"
}
```

---

## Supported Target Languages

`EN`, `DE`, `FR`, `ES`, `IT`, `NL`, `PL`, `PT`

DeepL's free plan supports all of these. See [DeepL language codes](https://www.deepl.com/docs-api/translate-text/request/) for the full list.

---

## Dependencies

Add to your `core/pom.xml`:
- `com.adobe.aem:aem-sdk-api` (provided)
- `org.apache.httpcomponents:httpclient`
- `com.google.code.gson:gson`
