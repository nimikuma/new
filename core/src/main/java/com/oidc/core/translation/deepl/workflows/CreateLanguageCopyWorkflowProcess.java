package com.oidc.core.translation.deepl.workflows;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.WCMException;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Workflow process step that creates a language copy of the payload page before translation.
 *
 * <p>Given a source page at e.g. {@code /content/mysite/de/page}, this step:
 * <ol>
 *   <li>Determines the target language path ({@code /content/mysite/en/page}) by replacing
 *       the language segment (depth 2) with the configured target language.</li>
 *   <li>Ensures all ancestor nodes exist, creating shallow copies where absent.</li>
 *   <li>Deep-copies the source page to the target path (skipped if already exists).</li>
 *   <li>Stores the target path in workflow metadata as {@link #META_TARGET_PATH} so the
 *       subsequent {DeepLWorkflowProcess} step translates the copy, not the source.</li>
 * </ol>
 *
 * <p>Process Args: {@code targetLanguage=EN} (or just {@code EN})
 */
@Component(
        service = WorkflowProcess.class,
        property = {
            "process.label=Create Language Copy",
            "process.description=Creates a structural language copy of the selected page before translation."
        }
)
public class CreateLanguageCopyWorkflowProcess implements WorkflowProcess {

    private static final Logger LOG = LoggerFactory.getLogger(CreateLanguageCopyWorkflowProcess.class);

    /** Workflow metadata key written by this step and consumed by DeepLWorkflowProcess. */
    static final String META_TARGET_PATH = "translationTargetPath";

    /**
     * Depth (0-indexed) at which the language segment sits.
     * For {@code /content(0)/site(1)/lang(2)/page} this is 2.
     */
    private static final int LANGUAGE_DEPTH = 2;

    @Override
    public void execute(final WorkItem workItem,
                        final WorkflowSession workflowSession,
                        final MetaDataMap args) throws WorkflowException {

        final String sourcePath  = workItem.getWorkflowData().getPayload().toString();
        final String processArgs = StringUtils.defaultString(args.get("PROCESS_ARGS", String.class));
        final String targetLang  = parseTargetLanguage(processArgs).toLowerCase(); // e.g. "en"

        final ResourceResolver resolver = workflowSession.adaptTo(ResourceResolver.class);
        if (resolver == null) {
            throw new WorkflowException("Cannot adapt WorkflowSession to ResourceResolver");
        }

        final PageManager pm = resolver.adaptTo(PageManager.class);
        if (pm == null) {
            throw new WorkflowException("Cannot adapt ResourceResolver to PageManager");
        }

        final Page sourcePage = pm.getPage(sourcePath);
        if (sourcePage == null) {
            throw new WorkflowException("Payload is not a valid page: " + sourcePath);
        }

        // /content/mysite/de  →  name = "de"
        final Page sourceLangRoot = sourcePage.getAbsoluteParent(LANGUAGE_DEPTH);
        if (sourceLangRoot == null) {
            throw new WorkflowException(
                    "Cannot determine language root at depth " + LANGUAGE_DEPTH + " for: " + sourcePath);
        }

        final String sourceLangRootPath = sourceLangRoot.getPath();

        // Replace language segment: /content/mysite/de -> /content/mysite/en
        final String siteRootPath       = sourceLangRootPath.substring(0, sourceLangRootPath.lastIndexOf('/'));
        final String targetLangRootPath = siteRootPath + "/" + targetLang;

        // Full target page path
        final String relativePath = sourcePath.substring(sourceLangRootPath.length()); // e.g. /ratgeber-wissen
        final String targetPath   = targetLangRootPath + relativePath;

        LOG.info("Creating language copy: {} → {}", sourcePath, targetPath);

        try {
            ensureAncestors(resolver, pm, sourceLangRoot, sourceLangRootPath,
                    targetLangRootPath, targetPath);

            final Page existingTarget = pm.getPage(targetPath);
            if (existingTarget == null) {
                pm.copy(sourcePage, targetPath, null, false, false);
                LOG.info("Deep-copied {} to {}", sourcePath, targetPath);
            } else {
                LOG.info("Target page already exists at {} — will translate existing content", targetPath);
            }

            if (resolver.hasChanges()) {
                resolver.commit();
            }
        } catch (WCMException | PersistenceException e) {
            throw new WorkflowException("Failed to create language copy: " + e.getMessage(), e);
        }

        // Pass target path to the next step
        workItem.getWorkflow().getWorkflowData().getMetaDataMap().put(META_TARGET_PATH, targetPath);
        LOG.info("Stored translationTargetPath={}", targetPath);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures that all ancestor pages between targetLangRootPath (inclusive) and
     * the immediate parent of targetPath (inclusive) exist in the repository.
     */
    private void ensureAncestors(final ResourceResolver resolver,
                                  final PageManager pm,
                                  final Page sourceLangRoot,
                                  final String sourceLangRootPath,
                                  final String targetLangRootPath,
                                  final String targetPath)
            throws WCMException, PersistenceException {

        // Ensure language root
        if (resolver.getResource(targetLangRootPath) == null) {
            LOG.info("Creating target language root: {}", targetLangRootPath);
            // Shallow-copy the source language root page (keeps template / jcr:content structure)
            pm.copy(sourceLangRoot, targetLangRootPath, null, true, false);
        }

        // Walk intermediate segments between lang root and the page itself
        final String relPath  = targetPath.substring(targetLangRootPath.length()); // /a/b/c
        final String[] segments = relPath.split("/");
        String currentTarget = targetLangRootPath;
        String currentSource = sourceLangRootPath;

        // segments[0] is "", segments[last] is the target page name — skip both
        for (int i = 1; i < segments.length - 1; i++) {
            if (StringUtils.isEmpty(segments[i])) {
                continue;
            }
            currentTarget += "/" + segments[i];
            currentSource += "/" + segments[i];

            if (resolver.getResource(currentTarget) == null) {
                LOG.info("Creating intermediate page: {}", currentTarget);
                final Page srcIntermediate = pm.getPage(currentSource);
                if (srcIntermediate != null) {
                    pm.copy(srcIntermediate, currentTarget, null, true, false);
                } else {
                    createPageNode(resolver, currentTarget);
                }
            }
        }
    }

    /**
     * Creates a minimal {@code cq:Page} node with a {@code cq:PageContent} child at the given path.
     * Used as a fallback when no source intermediate page is available to shallow-copy.
     */
    private void createPageNode(final ResourceResolver resolver, final String path)
            throws PersistenceException {
        final String parentPath = path.substring(0, path.lastIndexOf('/'));
        final String name       = path.substring(path.lastIndexOf('/') + 1);
        final Resource parent   = resolver.getResource(parentPath);
        if (parent == null) {
            LOG.warn("Cannot create page node — parent missing at {}", parentPath);
            return;
        }
        final Map<String, Object> pageProps = new HashMap<>();
        pageProps.put("jcr:primaryType", "cq:Page");
        final Resource pageRes = resolver.create(parent, name, pageProps);

        final Map<String, Object> contentProps = new HashMap<>();
        contentProps.put("jcr:primaryType", "cq:PageContent");
        contentProps.put("jcr:title", name);
        resolver.create(pageRes, "jcr:content", contentProps);
    }

    private String parseTargetLanguage(final String processArgs) {
        if (StringUtils.isBlank(processArgs)) {
            return "en";
        }
        final String trimmed = processArgs.trim();
        if (trimmed.contains("=")) {
            for (final String token : trimmed.split(",")) {
                final String[] kv = token.trim().split("=", 2);
                if (kv.length == 2 && "targetLanguage".equalsIgnoreCase(kv[0].trim())) {
                    return kv[1].trim();
                }
            }
        }
        return trimmed;
    }
}
