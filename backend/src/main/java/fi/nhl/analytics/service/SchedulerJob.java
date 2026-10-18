package fi.nhl.analytics.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single in-process scheduler; RefreshService's lock prevents overlap with manual refreshes. */
@Component
@ConditionalOnProperty(name = "analytics.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerJob {
    private static final Logger log = LoggerFactory.getLogger(SchedulerJob.class);
    private final RefreshService refresh;
    public SchedulerJob(RefreshService r) { this.refresh = r; }

    @Scheduled(initialDelay = 5000, fixedDelayString = "#{${analytics.refresh-minutes:30} * 60000}")
    public void run() {
        try { refresh.refresh(); } catch (IllegalStateException busy) { log.info("Scheduled refresh skipped: {}", busy.getMessage()); }
    }

    /** Only does anything when CLOSING_CAPTURE=true; polls odds every 5 min (throttled to 10 min near game start). */
    @Scheduled(initialDelay = 60000, fixedDelay = 300000)
    public void closingTick() { refresh.oddsTick(); }
}
