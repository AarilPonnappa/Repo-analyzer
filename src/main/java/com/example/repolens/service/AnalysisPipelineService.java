package com.example.repolens.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.repolens.dto.AnalysisResult;
import com.example.repolens.dto.AnalysisViews.ProgressEvent;
import com.example.repolens.dto.FileTreeNode;
import com.example.repolens.dto.GraphResult;
import com.example.repolens.dto.HistoryResult;
import com.example.repolens.dto.ParsedCode;
import com.example.repolens.dto.RiskResult;
import com.example.repolens.dto.TraceNode;
import com.example.repolens.model.AnalysisRun.Status;
import com.example.repolens.service.ChunkService.ChunkDraft;

/**
 * Runs one analysis from start to finish, in a background thread started by AnalysisJobService:
 *
 *   clone -> parse -> file tree -> graph -> request flows -> history -> risk -> save
 *         -> index code for "Ask the repo" -> fact-checked report -> done
 *
 * The analysis services only compute and return results. This class calls them in order,
 * records progress (saved in the database and streamed to the browser), and hands results
 * to AnalysisStorageService.
 */
@Service
public class AnalysisPipelineService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipelineService.class);

    /** token is only kept in memory for this run, never saved. */
    public record Request(String ownerRepo, String branch, String token, boolean isPrivate, String defaultBranch) {}

    private final CloneRepoService cloneRepoService;
    private final CodeParserService codeParserService;
    private final RepoTreeService repoTreeService;
    private final GraphService graphService;
    private final RequestFlowService requestFlowService;
    private final HistoryService historyService;
    private final RiskService riskService;
    private final ChunkService chunkService;
    private final GeminiClient gemini;
    private final ReportService reportService;
    private final AnalysisStorageService storage;
    private final ProgressService progress;

    public AnalysisPipelineService(CloneRepoService cloneRepoService, CodeParserService codeParserService,
                                   RepoTreeService repoTreeService, GraphService graphService,
                                   RequestFlowService requestFlowService, HistoryService historyService,
                                   RiskService riskService, ChunkService chunkService, GeminiClient gemini,
                                   ReportService reportService, AnalysisStorageService storage,
                                   ProgressService progress) {
        this.cloneRepoService = cloneRepoService;
        this.codeParserService = codeParserService;
        this.repoTreeService = repoTreeService;
        this.graphService = graphService;
        this.requestFlowService = requestFlowService;
        this.historyService = historyService;
        this.riskService = riskService;
        this.chunkService = chunkService;
        this.gemini = gemini;
        this.reportService = reportService;
        this.storage = storage;
        this.progress = progress;
    }

    public void run(long runId, Request request) {
        long started = System.currentTimeMillis();
        try {
            Analyzed analyzed = analyze(runId, request);
            AnalysisResult result = analyzed.result();

            stage(runId, Status.SAVING);
            storage.saveResults(runId, result);

            // The next two steps add extras; if either fails, the analysis itself is still complete
            stage(runId, Status.INDEXING);
            try {
                storage.saveChunks(runId, analyzed.chunks(), embed(runId, analyzed.chunks()));
            } catch (RuntimeException e) {
                log.warn("Run {}: couldn't save the code index: {}", runId, e.getMessage());
            }

            stage(runId, Status.REPORT);
            try {
                storage.saveReport(runId, reportService.generate(result, request.ownerRepo(), request.branch()));
            } catch (RuntimeException e) {
                log.warn("Run {}: couldn't save the report: {}", runId, e.getMessage());
            }

            storage.markDone(runId);
            progress.publish(new ProgressEvent(runId, Status.DONE.name(), null, true));
            log.info("Run {} ({}@{}) done in {} s: grade {}, {} classes", runId, request.ownerRepo(), request.branch(),
                    (System.currentTimeMillis() - started) / 1000, result.risk().grade(), result.parsed().classes().size());

        } catch (CloneException e) {
            fail(runId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Run {} failed", runId, e);
            fail(runId, "The analysis failed unexpectedly (" + e.getClass().getSimpleName() + ").");
        }
    }

    private record Analyzed(AnalysisResult result, List<ChunkDraft> chunks) {}

    // Everything that needs the cloned files happens inside the try block; the folder is deleted when it ends
    private Analyzed analyze(long runId, Request request) {
        stage(runId, Status.CLONING);
        try (CloneRepoService.ClonedRepo cloned =
                     cloneRepoService.cloneRepo(request.ownerRepo(), request.branch(), request.token())) {

            stage(runId, Status.PARSING);
            ParsedCode parsed = codeParserService.parse(cloned.path());                                         // cloned.path() is the root of the repo. Paresed is the ParsedCode object that contains all the files(path, classes, methods, endpoints, dependencies) in the repo.
            FileTreeNode tree = repoTreeService.build(cloned.path(), parsed);
            if (log.isDebugEnabled()) {
                log.debug("File tree of {}:\n{}", request.ownerRepo(), repoTreeService.toText(tree));
            }
            List<ChunkDraft> chunks = chunkService.build(cloned.path(), parsed);            // The parsed code is passed to the chunkService to build the chunks. The chunks are then used to create embeddings for the code to be sent to Gemini for chatbot questions.  First they are stored in the database later.

            stage(runId, Status.GRAPH);
            GraphResult graph = graphService.build(parsed);
            Map<String, TraceNode> traces = requestFlowService.traceAll(parsed, graph);

            stage(runId, Status.HISTORY);
            HistoryResult history = historyService.read(cloned.path());   // needs .git

            stage(runId, Status.RISK);
            RiskResult risk = riskService.assess(parsed, graph, history);

            return new Analyzed(new AnalysisResult(cloned.commitSha(), parsed, tree, graph, traces, history, risk), chunks);
        }
    }

    // Embeddings are optional: without a Gemini key, or if Gemini fails, search falls back to keywords
    private List<float[]> embed(long runId, List<ChunkDraft> chunks) {
        if (!gemini.enabled() || chunks.isEmpty()) {
            return null;
        }
        try {
            return gemini.embedDocuments(chunks.stream().map(ChunkDraft::embeddingText).toList());
        } catch (RuntimeException e) {
            log.warn("Run {}: embeddings failed, keyword search only: {}", runId, e.getMessage());
            return null;
        }
    }

    private void stage(long runId, Status status) {
        storage.updateStatus(runId, status);
        progress.publish(new ProgressEvent(runId, status.name(), null, false));
    }

    private void fail(long runId, String message) {
        try {
            storage.markFailed(runId, message);
        } finally {
            progress.publish(new ProgressEvent(runId, Status.FAILED.name(), message, true));
        }
    }
}
