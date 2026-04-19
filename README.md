# AEM DeepL Translator

A plug-in for **Adobe Experience Manager (AEM)** that integrates [DeepL v2 AI translation](https://www.deepl.com/docs-api) into the Sites console.

Two independent trigger paths are supported — both create a language copy and translate it, but differ in how they are started and how the target language is chosen:

| # | Trigger | Language selection | Execution model |
|---|---|---|---|
| 1 | **Sites console button** → "Translate with DeepL" | Coral 3 browser dialog | Synchronous (servlet) |
| 2 | **Sites console** → "Create Workflow" → "DeepL AI Translation" | Dialog Participant Step in AEM Inbox | Asynchronous (workflow) |

---

## How It Works

### Path 1 — Sites Console Button (Servlet)

1. A clientlib (`cq.wcm.sites`) injects `translate-action.js` into the Sites console.
2. The script adds a **"Translate with DeepL"** button to the action bar via a Sling overlay at  
   `/apps/wcm/core/content/sites/jcr:content/actions/selection/deepl-translate`.
3. Clicking the button opens a **Coral 3 dialog** with a language drop-down (EN / DE / FR / ES / IT / NL / PL / PT).
4. On confirm, the browser POSTs to `POST /bin/oidc/translation/start` with `path[]` and `targetLanguage`.
5. The servlet (`TranslationStartServlet`) uses the **caller's ResourceResolver** (no service user needed):
   - Resolves the language root at depth 2 (e.g. `/content/site/en` → `/content/site/de`).
   - Shallow-copies ancestor pages, then deep-copies the source page to the target path.
   - Calls `DeepLTranslationService.translate(targetPath, targetLanguage, resolver)`.
6. Returns JSON: `{"results":[{"path":"...","status":"completed","targetPath":"..."}]}`

### Path 2 — Workflow (AEM Inbox)

1. From the Sites console, choose **Create → Workflow** and select **"DeepL AI Translation"**.
2. The workflow pauses at a **Dialog Participant Step** — an Inbox task appears for the `administrators` group.
3. The assignee opens the task, picks the target language from a Granite UI select (`/apps/oidc/workflow/dialog/deepl-language-select`), and completes the step.
4. The chosen language is stored in the **workflow metadata map** (`targetLanguage` key).
5. **Step 2 — CreateLanguageCopyWorkflowProcess**: reads `targetLanguage` from the metadata map (falls back to `PROCESS_ARGS` if not set), creates the language copy.
6. **Step 3 — DeepLWorkflowProcess**: reads the target path written by step 2, queues a `TranslationJob` via Sling Job Manager.

### Translation Service (shared by both paths)

- Walks the `jcr:content` tree; skips structural/config properties via `SKIP_PROPERTIES` and suffix rules.
- Batches up to 50 000 characters per DeepL request.
- Builds an XML payload: `<doc><t jcrPath="..." jcrProperty="...">value</t>...</doc>`
- Sanitizes HTML values before sending (`<br>` → `<br/>`, `&nbsp;` → `&#160;`, etc.) to prevent DeepL XML parse errors.
- Writes translated values back via `ModifiableValueMap`.

---

## File Structure

```
core/src/main/java/com/oidc/core/translation/deepl/
├── config/          DeepLTranslationConfig — OSGi config interface
├── jobs/            TranslationJob — Sling background job (workflow path)
├── service/         DeepLTranslationService (interface) + HttpClientService (interface)
│   └── impl/        DeepLTranslationServiceImpl + HttpClientServiceImpl
├── servlet/         TranslationStartServlet — POST /bin/oidc/translation/start (button path)
└── workflows/       CreateLanguageCopyWorkflowProcess + DeepLWorkflowProcess (workflow path)

ui.apps/src/main/content/jcr_root/apps/
├── oidc/
│   ├── clientlibs/deepl-translation/
│   │   ├── .content.xml          clientlib node (category: cq.wcm.sites)
│   │   ├── js.txt
│   │   └── translate-action.js   Sites console button + Coral 3 dialog
│   └── workflow/dialog/deepl-language-select/
│       └── .content.xml          Granite UI dialog for the Inbox Participant Step
└── wcm/core/content/sites/jcr:content/actions/selection/deepl-translate/
    └── .content.xml              Sites console action bar button overlay

ui.config/src/main/content/jcr_root/apps/oidc/osgiconfig/
├── config/          ServiceUserMapper amendment (deepl-translation-service)
└── config.author/   DeepLTranslationConfig.cfg.json

ui.content/src/main/content/jcr_root/var/workflow/models/
└── deepl-translation-workflow/.content.xml   5-node workflow model
```

---

## Workflow Model

```
START → [Participant: Select Language] → [Process: CreateLanguageCopy] → [Process: DeepL] → END
```

| Node | Type | Detail |
|---|---|---|
| `node0` | START | — |
| `node1` | PARTICIPANT | Dialog: `/apps/oidc/workflow/dialog/deepl-language-select`, assignee: `administrators` |
| `node2` | PROCESS | `CreateLanguageCopyWorkflowProcess` — reads `targetLanguage` from metadata |
| `node3` | PROCESS | `DeepLWorkflowProcess` — queues translation job |
| `node4` | END | — |

---

## Configuration

`ui.config/.../config.author/com.oidc.core.translation.deepl.config.DeepLTranslationConfig.cfg.json`:

```json
{
  "enabled": true,
  "apiKey": "YOUR_DEEPL_API_KEY",
  "useFreePlan": true,
  "serviceUser": "deepl-translation-service",
  "translate.child.pages": true
}
```

For AEM as a Cloud Service, use Cloud Manager secret variables instead of a hard-coded key:

```json
{
  "apiKey": "$[secret:DEEPL_API_KEY]"
}
```

---

## Supported Target Languages

`EN` `DE` `FR` `ES` `IT` `NL` `PL` `PT`

DeepL's free plan supports all of these. See the [DeepL language code reference](https://www.deepl.com/docs-api/translate-text/request/) for the full list.

---

## Dependencies

Add to `core/pom.xml`:

```xml
<!-- AEM SDK (provided) -->
<dependency>
  <groupId>com.adobe.aem</groupId>
  <artifactId>aem-sdk-api</artifactId>
  <scope>provided</scope>
</dependency>
<!-- HTTP client for DeepL API calls -->
<dependency>
  <groupId>org.apache.httpcomponents</groupId>
  <artifactId>httpclient</artifactId>
</dependency>
<!-- JSON parsing -->
<dependency>
  <groupId>com.google.code.gson</groupId>
  <artifactId>gson</artifactId>
</dependency>
```
