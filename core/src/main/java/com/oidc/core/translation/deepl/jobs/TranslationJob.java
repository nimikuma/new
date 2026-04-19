package com.oidc.core.translation.deepl.jobs;

import com.oidc.core.translation.deepl.service.DeepLTranslationService;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sling Job Consumer that executes a single-page DeepL translation asynchronously.
 *
 * <p>Jobs are created by {@link com.oidc.core.translation.deepl.workflows.DeepLWorkflowProcess}
 * — one job per page. The Sling Job Manager dispatches and retries them automatically,
 * keeping the main workflow thread free.
 *
 * <p>Job properties:
 * <ul>
 *   <li>{@link #PROP_PATH} — absolute JCR path of the page to translate</li>
 *   <li>{@link #PROP_TARGET_LANGUAGE} — DeepL target language code (e.g. "DE")</li>
 * </ul>
 */
@Component(
        service = {JobConsumer.class, TranslationJob.class},
        immediate = true,
        property = {JobConsumer.PROPERTY_TOPICS + "=" + TranslationJob.TOPIC}
)
public class TranslationJob implements JobConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TranslationJob.class);

    /** Sling Job topic for DeepL translation jobs. */
    public static final String TOPIC = "com/oidc/translation/deepl";

    /** Job property: absolute JCR path of the page or content fragment. */
    public static final String PROP_PATH = "path";

    /**
     * Job property: raw process arguments string from the workflow step.
     * May be a bare language code (e.g. {@code "DE"}), blank (service auto-detects
     * language from the page path), or any other string passed by the workflow.
     */
    public static final String PROP_PROCESS_ARGS = "processArgs";

    @Reference
    private DeepLTranslationService deepLTranslationService;

    @Override
    public JobResult process(final Job job) {
        final String path       = (String) job.getProperty(PROP_PATH);
        final String processArgs = (String) job.getProperty(PROP_PROCESS_ARGS);

        LOG.info("TranslationJob started: path={}, processArgs={}", path, processArgs);

        if (path == null) {
            LOG.error("TranslationJob missing required property: path");
            return JobResult.CANCEL;
        }

        // processArgs may be blank — the service auto-detects target language from page path
        final boolean success = deepLTranslationService.translate(path, processArgs);

        if (success) {
            LOG.info("TranslationJob completed: path={}", path);
            return JobResult.OK;
        } else {
            LOG.warn("TranslationJob failed: path={}", path);
            return JobResult.FAILED;
        }
    }
}
