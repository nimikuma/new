package com.oidc.core.translation.deepl.workflows;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.oidc.core.translation.deepl.jobs.TranslationJob;
import com.oidc.core.translation.deepl.service.DeepLTranslationService;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * AEM Workflow Process that queues DeepL translation jobs for the workflow payload page.
 *
 * <h3>Usage in the Sites console</h3>
 * <ol>
 *   <li>Select a page in the Sites console.</li>
 *   <li>Toolbar → <b>Create → Workflow</b>.</li>
 *   <li>Select model <em>"DeepL AI Translation"</em>.</li>
 *   <li>Click <b>Create</b> — no comment needed; language is auto-detected
 *       from the page path (e.g. {@code /content/mysite/de/...} → DE).</li>
 * </ol>
 *
 * <h3>Optional process argument override</h3>
 * Set <em>Process Arguments</em> on the workflow step (e.g. {@code DE}) to force
 * a specific target language regardless of the page path locale.
 *
 * <p>Architecture reference:
 * <a href="https://github.com/jlanssie/wknd/tree/feature/ai-trainslations">
 * github.com/jlanssie/wknd – feature/ai-trainslations</a>
 */
@Component(
        service = WorkflowProcess.class,
        property = {
            "process.label=DeepL AI Translation Process",
            "process.description=Translates pages using the DeepL v2 API."
        }
)
public class DeepLWorkflowProcess implements WorkflowProcess {

    private static final Logger LOG = LoggerFactory.getLogger(DeepLWorkflowProcess.class);

    @Reference
    private DeepLTranslationService deepLTranslationService;

    @Reference
    private JobManager jobManager;

    @Override
    public void execute(final WorkItem workItem,
                        final WorkflowSession workflowSession,
                        final MetaDataMap metaDataMap) throws WorkflowException {

        final String payloadPath = workItem.getWorkflowData().getPayload().toString();
        final String userId      = workItem.getWorkflowData().getMetaDataMap()
                                           .get("userId", String.class);

        // If a language copy was created by CreateLanguageCopyWorkflowProcess, translate that.
        // Otherwise fall back to the original payload path (backward compat / standalone use).
        final String targetPathFromMeta = workItem.getWorkflow().getWorkflowData().getMetaDataMap()
                .get(CreateLanguageCopyWorkflowProcess.META_TARGET_PATH, String.class);
        final String effectivePath = StringUtils.isNotBlank(targetPathFromMeta)
                ? targetPathFromMeta : payloadPath;

        // Language resolution priority:
        //  1. workflow comment (entered by user in Sites console start dialog, e.g. "FR")
        //  2. PROCESS_ARGS on the workflow step (e.g. "targetLanguage=EN")
        //  3. auto-detected from page.getLanguage() (in DeepLTranslationServiceImpl)
        final String comment = workItem.getWorkflow().getWorkflowData().getMetaDataMap()
                .get("comment", String.class);
        final String processArgs = StringUtils.isNotBlank(comment)
                ? comment.trim().toUpperCase()
                : (metaDataMap.containsKey("PROCESS_ARGS")
                        ? metaDataMap.get("PROCESS_ARGS", String.class)
                        : StringUtils.EMPTY);

        LOG.info("DeepLWorkflowProcess executing: payload={}, effectivePath={}, userId={}",
                payloadPath, effectivePath, userId);

        final ResourceResolver resolver = workflowSession.adaptTo(ResourceResolver.class);
        if (resolver == null) {
            throw new WorkflowException("Cannot adapt WorkflowSession to ResourceResolver");
        }

        if (deepLTranslationService.translateChildPages()) {
            final PageManager pageManager = resolver.adaptTo(PageManager.class);
            if (pageManager != null) {
                final Page rootPage = pageManager.getPage(effectivePath);
                if (rootPage != null) {
                    LOG.debug("Translating tree rooted at {}", effectivePath);
                    translateTree(rootPage, processArgs);
                    LOG.info("DeepLWorkflowProcess finished queuing jobs for tree={}", effectivePath);
                    return;
                }
            }
        }

        translatePage(effectivePath, processArgs);
        LOG.info("DeepLWorkflowProcess finished queuing job for path={}", effectivePath);
    }

    // -------------------------------------------------------------------------

    private void translateTree(final Page page, final String processArgs) {
        translatePage(page.getPath(), processArgs);
        page.listChildren().forEachRemaining(child -> translateTree(child, processArgs));
    }

    private void translatePage(final String path, final String processArgs) {
        final Map<String, Object> jobProps = new HashMap<>();
        jobProps.put(TranslationJob.PROP_PATH,        path);
        jobProps.put(TranslationJob.PROP_PROCESS_ARGS, processArgs);
        jobManager.addJob(TranslationJob.TOPIC, jobProps);
        LOG.debug("Queued translation job: path={}, processArgs={}", path, processArgs);
    }
}
