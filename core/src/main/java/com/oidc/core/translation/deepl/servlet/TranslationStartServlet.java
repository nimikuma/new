package com.oidc.core.translation.deepl.servlet;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.WCMException;
import com.oidc.core.translation.deepl.service.DeepLTranslationService;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Sling servlet that creates a language copy of selected pages and translates
 * them using DeepL — all without requiring the AEM Workflow engine.
 *
 * <ul>
 *   <li>POST {@code /bin/oidc/translation/start}</li>
 *   <li>Parameters: {@code path} (repeatable), {@code targetLanguage} (e.g. EN, FR, DE)</li>
 *   <li>Response: JSON {@code {"results":[{"path":"...","status":"started"|"error"}]}}</li>
 * </ul>
 */
@Component(
        service = Servlet.class,
        property = {
            "sling.servlet.paths=/bin/oidc/translation/start",
            "sling.servlet.methods=POST"
        }
)
public class TranslationStartServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(TranslationStartServlet.class);

    /** Depth (0-indexed) at which the language segment sits: /content(0)/site(1)/lang(2)/... */
    private static final int LANGUAGE_DEPTH = 2;

    @Reference
    private transient DeepLTranslationService translationService;

    @Override
    protected void doPost(final SlingHttpServletRequest request,
                          final SlingHttpServletResponse response)
            throws ServletException, IOException {

        final String[] paths          = request.getParameterValues("path");
        final String   targetLanguage = StringUtils.upperCase(
                StringUtils.defaultIfBlank(request.getParameter("targetLanguage"), "EN"));

        if (paths == null || paths.length == 0) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"error\":\"no path parameter supplied\"}");
            return;
        }

        response.setContentType("application/json;charset=UTF-8");

        final ResourceResolver resolver = request.getResourceResolver();
        final PageManager pm = resolver.adaptTo(PageManager.class);
        if (pm == null) {
            response.setStatus(SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\":\"cannot obtain PageManager\"}");
            return;
        }

        final StringBuilder json = new StringBuilder("{\"results\":[");
        boolean first = true;

        for (final String sourcePath : paths) {
            if (!first) json.append(",");
            first = false;

            try {
                final String targetPath = createLanguageCopy(resolver, pm, sourcePath, targetLanguage);
                LOG.info("Language copy ready at {}; starting translation to {}", targetPath, targetLanguage);

                // Refresh resolver so new nodes from copy are visible
                resolver.refresh();
                // Use caller's resolver directly — avoids dependency on a service user
                final boolean ok = translationService.translate(targetPath, targetLanguage, resolver);
                LOG.info("Translation complete for {}: ok={}", targetPath, ok);

                json.append("{\"path\":\"").append(escape(sourcePath))
                    .append("\",\"status\":\"").append(ok ? "completed" : "error")
                    .append("\",\"targetPath\":\"").append(escape(targetPath)).append("\"}");

            } catch (Exception e) {
                LOG.error("Failed to translate path={}: {}", sourcePath, e.getMessage(), e);
                json.append("{\"path\":\"").append(escape(sourcePath))
                    .append("\",\"status\":\"error\",\"message\":\"")
                    .append(escape(e.getMessage())).append("\"}");
            }
        }

        json.append("]}");
        response.getWriter().write(json.toString());
    }

    // -------------------------------------------------------------------------
    // Language copy logic (mirrors CreateLanguageCopyWorkflowProcess)
    // -------------------------------------------------------------------------

    /**
     * Creates a language copy of {@code sourcePath} at the target language path and
     * returns the target path.
     */
    private String createLanguageCopy(final ResourceResolver resolver,
                                       final PageManager pm,
                                       final String sourcePath,
                                       final String targetLanguage)
            throws WCMException, PersistenceException {

        final Page sourcePage = pm.getPage(sourcePath);
        if (sourcePage == null) {
            throw new IllegalArgumentException("Not a valid page: " + sourcePath);
        }

        final Page sourceLangRoot = sourcePage.getAbsoluteParent(LANGUAGE_DEPTH);
        if (sourceLangRoot == null) {
            throw new IllegalArgumentException(
                    "Cannot determine language root at depth " + LANGUAGE_DEPTH + " for: " + sourcePath);
        }

        final String sourceLangRootPath = sourceLangRoot.getPath();
        final String siteRootPath       = sourceLangRootPath.substring(0, sourceLangRootPath.lastIndexOf('/'));
        final String targetLang         = targetLanguage.toLowerCase();
        final String targetLangRootPath = siteRootPath + "/" + targetLang;
        final String relativePath       = sourcePath.substring(sourceLangRootPath.length());
        final String targetPath         = targetLangRootPath + relativePath;

        LOG.info("Language copy: {} → {}", sourcePath, targetPath);

        // Ensure language root exists
        if (resolver.getResource(targetLangRootPath) == null) {
            LOG.info("Creating language root: {}", targetLangRootPath);
            pm.copy(sourceLangRoot, targetLangRootPath, null, true, false);
        }

        // Ensure intermediate ancestors exist
        final String[] segments = relativePath.split("/");
        String currentTarget = targetLangRootPath;
        String currentSource = sourceLangRootPath;
        for (int i = 1; i < segments.length - 1; i++) {
            if (StringUtils.isEmpty(segments[i])) continue;
            currentTarget += "/" + segments[i];
            currentSource += "/" + segments[i];
            if (resolver.getResource(currentTarget) == null) {
                final Page srcIntermediate = pm.getPage(currentSource);
                if (srcIntermediate != null) {
                    pm.copy(srcIntermediate, currentTarget, null, true, false);
                } else {
                    createMinimalPage(resolver, currentTarget);
                }
            }
        }

        // Copy the target page itself
        if (pm.getPage(targetPath) == null) {
            pm.copy(sourcePage, targetPath, null, false, false);
            LOG.info("Copied {} to {}", sourcePath, targetPath);
        } else {
            LOG.info("Target already exists at {} — translating existing content", targetPath);
        }

        if (resolver.hasChanges()) {
            resolver.commit();
        }

        return targetPath;
    }

    private static void createMinimalPage(final ResourceResolver resolver, final String path)
            throws PersistenceException {
        final String parentPath = path.substring(0, path.lastIndexOf('/'));
        final String name       = path.substring(path.lastIndexOf('/') + 1);
        final Resource parent   = resolver.getResource(parentPath);
        if (parent == null) return;
        final Map<String, Object> pageProps = new HashMap<>();
        pageProps.put("jcr:primaryType", "cq:Page");
        final Resource pageRes = resolver.create(parent, name, pageProps);
        final Map<String, Object> contentProps = new HashMap<>();
        contentProps.put("jcr:primaryType", "cq:PageContent");
        contentProps.put("jcr:title", name);
        resolver.create(pageRes, "jcr:content", contentProps);
    }

    private static String escape(final String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
