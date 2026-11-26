package com.example.repolens.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.example.repolens.dto.AnalysisViews.ProgressEvent;
import com.example.repolens.service.AnalysisPipelineService.Request;
import com.example.repolens.service.AnalysisStorageService.RunRef;
import com.example.repolens.service.AnalysisStorageService.UnfinishedRun;

import jakarta.annotation.PreDestroy;

/**
 * Starts analyses as background jobs.
 *
 *  - The same repo and branch is never analyzed twice at the same time: a second request gets the running job.
 *  - A commit that was already analyzed returns the saved result without cloning.
 *  - A small worker pool runs the jobs; the rest wait in a queue.
 *  - After a restart, unfinished public jobs are started again. Private ones are marked failed,
 *    because their token was never stored.
 */
@Service
public class AnalysisJobService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobService.class);

    public record StartResult(long runId, String accessKey, boolean cached, boolean alreadyRunning) {}

    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) { super(message); }
    }

    private final GitRemoteService gitRemoteService;
    private final AnalysisStorageService storage;
    private final AnalysisPipelineService pipeline;
    private final RateLimiter rateLimiter;
    private final ProgressService progress;
    private final ThreadPoolExecutor executor;
    private final int analysesPerHour;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public AnalysisJobService(GitRemoteService gitRemoteService, AnalysisStorageService storage,
                              AnalysisPipelineService pipeline, RateLimiter rateLimiter, ProgressService progress,
                              @Value("${repolens.analyses-per-hour:10}") int analysesPerHour,
                              @Value("${repolens.worker-threads:2}") int workers,
                              @Value("${repolens.queue-size:20}") int queueSize) {
        this.gitRemoteService = gitRemoteService;
        this.storage = storage;
        this.pipeline = pipeline;
        this.rateLimiter = rateLimiter;
        this.progress = progress;
        this.analysesPerHour = analysesPerHour;
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueSize)),
                r -> {
                    Thread t = new Thread(r, "analysis-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Starts (or reuses) an analysis. The caller has already checked the repo with the GitHub API,
     * so the token is known to work.
     *
     * @throws CloneException          if the branch doesn't exist
     * @throws RateLimitedException    if this IP started too many analyses in the last hour
     */
    public StartResult start(Request request, String clientIp) {
        String key = request.ownerRepo() + "@" + request.branch();
        Object lock = locks.computeIfAbsent(key, k -> new Object());

        // Asks GitHub for the branch's latest commit without downloading anything
        String sha = gitRemoteService.latestCommitSha(request.ownerRepo(), request.branch(), request.token());

        synchronized (lock) {
            Optional<RunRef> active = storage.findActiveRun(request.ownerRepo(), request.branch());
            if (active.isPresent()) {
                return new StartResult(active.get().id(), active.get().accessKey(), false, true);
            }
            Optional<RunRef> done = storage.findCompletedRun(request.ownerRepo(), request.branch(), sha);
            if (done.isPresent()) {
                return new StartResult(done.get().id(), done.get().accessKey(), true, false);
            }
            if (!rateLimiter.tryAcquire("analyze", clientIp, analysesPerHour)) {
                long minutes = rateLimiter.minutesUntilFree("analyze", clientIp);
                throw new RateLimitedException("You can start " + analysesPerHour
                        + " new analyses per hour. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".");
            }
            RunRef run = storage.createRun(request.ownerRepo(), request.isPrivate(), request.defaultBranch(),
                    request.branch(), sha);
            submit(run.id(), request);
            return new StartResult(run.id(), run.accessKey(), false, false);
        }
    }

    private void submit(long runId, Request request) {
        try {
            executor.execute(() -> pipeline.run(runId, request));
        } catch (RejectedExecutionException e) {
            String message = "RepoLens is busy with other analyses right now. Try again in a few minutes.";
            storage.markFailed(runId, message);
            progress.publish(new ProgressEvent(runId, "FAILED", message, true));
        }
    }

    public int queuedJobs() {
        return executor.getQueue().size();
    }

    // ---------------------------------------------------------------- restart recovery

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        deleteLeftoverClones();
        for (UnfinishedRun run : storage.findUnfinishedRuns()) {
            if (run.isPrivate()) {
                storage.markFailed(run.id(), "The server restarted while this analysis was running. Analyze the repo again.");
            } else {
                log.info("Restarting analysis {} of {} after a server restart", run.id(), run.ownerRepo());
                submit(run.id(), new Request(run.ownerRepo(), run.branch(), null, false, run.defaultBranch()));
            }
        }
    }

    // Clone folders left behind if the server was killed in the middle of an analysis
    private void deleteLeftoverClones() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        try (Stream<Path> dirs = Files.list(tmp)) {
            dirs.filter(p -> p.getFileName().toString().startsWith("repolens-"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(CloneRepoService::deleteFolder);
        } catch (IOException e) {
            log.debug("Couldn't list {}: {}", tmp, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
