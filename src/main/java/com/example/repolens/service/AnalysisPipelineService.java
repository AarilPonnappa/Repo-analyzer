package com.example.repolens.service;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.repolens.dto.AnalysisResult;
import com.example.repolens.dto.FileTreeNode;
import com.example.repolens.dto.GraphResult;
import com.example.repolens.dto.HistoryResult;
import com.example.repolens.dto.ParsedCode;
import com.example.repolens.dto.RiskResult;
import com.example.repolens.dto.TraceNode;
import com.example.repolens.model.AnalysisRun.Status;

/**
 * Runs one analysis from start to finish:
 *
 *   cache check -> clone -> parse -> file tree -> graph -> request flows -> history -> risk -> save
 *
 * The analysis services only compute and return results. This class calls them in order,
 * records progress on the run, and hands everything to AnalysisStorageService at the end.
 */
@Service
public class AnalysisPipelineService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipelineService.class);

    public record Request(String ownerRepo, String branch, String token, boolean isPrivate, String defaultBranch) {}

    public record Outcome(long runId, boolean cached, Status status, String errorMessage) {}

    private final GitRemoteService gitRemoteService;
    private final CloneRepoService cloneRepoService;
    private final CodeParserService codeParserService;
    private final RepoTreeService repoTreeService;
    private final GraphService graphService;
    private final RequestFlowService requestFlowService;
    private final HistoryService historyService;
    private final RiskService riskService;
    private final AnalysisStorageService storage;

    public AnalysisPipelineService(GitRemoteService gitRemoteService, CloneRepoService cloneRepoService,
                                   CodeParserService codeParserService, RepoTreeService repoTreeService,
                                   GraphService graphService, RequestFlowService requestFlowService,
                                   HistoryService historyService, RiskService riskService,
                                   AnalysisStorageService storage) {
        this.gitRemoteService = gitRemoteService;
        this.cloneRepoService = cloneRepoService;
        this.codeParserService = codeParserService;
        this.repoTreeService = repoTreeService;
        this.graphService = graphService;
        this.requestFlowService = requestFlowService;
        this.historyService = historyService;
        this.riskService = riskService;
        this.storage = storage;
    }

    /**
     * Throws CloneException if the branch can't be found before anything is saved.
     * After that, failures are recorded on the run and returned as a FAILED outcome.
     */
    public Outcome analyze(Request request) {
        // 1. Cache check: already analyzed this exact commit? Then reuse it and skip the clone.
        String sha = gitRemoteService.latestCommitSha(request.ownerRepo(), request.branch(), request.token());
        Optional<Long> existing = storage.findCompletedRun(request.ownerRepo(), request.branch(), sha);
        if (existing.isPresent()) {
            log.info("{}@{} ({}) already analyzed as run {}", request.ownerRepo(), request.branch(), sha, existing.get());
            return new Outcome(existing.get(), true, Status.DONE, null);
        }

        long runId = storage.createRun(request.ownerRepo(), request.isPrivate(), request.defaultBranch(), request.branch());
        try {
            AnalysisResult result = runSteps(runId, request);
            storage.updateStatus(runId, Status.SAVING);
            storage.saveResults(runId, result);
            log.info("Run {} done: grade {}, {} classes", runId, result.risk().grade(), result.parsed().classes().size());
            return new Outcome(runId, false, Status.DONE, null);

        } catch (CloneException e) {
            storage.markFailed(runId, e.getMessage());
            return new Outcome(runId, false, Status.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Run {} failed", runId, e);
            String message = "The analysis failed unexpectedly (" + e.getClass().getSimpleName() + ").";
            storage.markFailed(runId, message);
            return new Outcome(runId, false, Status.FAILED, message);
        }
    }

    private AnalysisResult runSteps(long runId, Request request) {
        storage.updateStatus(runId, Status.CLONING);

        try (CloneRepoService.ClonedRepo cloned =
                     cloneRepoService.cloneRepo(request.ownerRepo(), request.branch(), request.token())) {

            storage.updateStatus(runId, Status.PARSING);
            ParsedCode parsed = codeParserService.parse(cloned.path());
            FileTreeNode tree = repoTreeService.build(cloned.path(), parsed);
            if (log.isDebugEnabled()) {
                log.debug("File tree of {}:\n{}", request.ownerRepo(), repoTreeService.toText(tree));
            }

            storage.updateStatus(runId, Status.GRAPH);
            GraphResult graph = graphService.build(parsed);
            Map<String, TraceNode> traces = requestFlowService.traceAll(parsed, graph);

            storage.updateStatus(runId, Status.HISTORY);
            HistoryResult history = historyService.read(cloned.path());   // needs .git, so before the folder is deleted

            storage.updateStatus(runId, Status.RISK);
            RiskResult risk = riskService.assess(parsed, graph, history);

            return new AnalysisResult(cloned.commitSha(), parsed, tree, graph, traces, history, risk);
        }   // the cloned folder is deleted here
    }
}
