# RepoLens

**Understand any Java codebase in minutes.**

Paste a GitHub repository and RepoLens gives you:

- an interactive map of its architecture
- its riskiest code
- the path any API request takes through the code
- a guided reading order
- an assistant that answers questions about the code with links to the exact lines

Every AI claim is checked against the real code.

RepoLens supports **Java only (Maven or Gradle projects)**, so it can go deep and stay accurate rather than give shallow results for every language.

## How it's different

- **GitHub Insights** answers *"how active is this repo?"* It counts commits and contributors but never opens the code. RepoLens answers *"how is this code built, and where is it fragile?"*
- **SonarQube** reports code problems, but doesn't explain the architecture or answer questions.
- **AI "chat with your repo" tools** can invent class names and behaviour.

In RepoLens, **Java does the actual analysis** (parsing, graph algorithms, git history), and Gemini only explains results that have already been verified. Every claim links to its evidence.

## Features

### Architecture map
- Parses every Java file and builds a **dependency graph**: classes are nodes, and imports, method calls and injected dependencies are edges.
- Detects **layers** (controllers, services, repositories, entities) from Spring annotations and groups the graph by package and layer.
- Flags **layer violations**, such as a controller calling a repository directly and skipping the service.
- Finds **circular dependencies** using Tarjan's strongly-connected-components algorithm and highlights them in red.
- Interactive graph with zoom, filters and click-to-inspect.

### Request-flow tracer
- Lists every API endpoint (`@GetMapping`, `@PostMapping`, ...).
- Pick one to see the path it takes through the code, found with **graph search** over the method call graph. For example: `OrderController.create()` → `OrderService.place()` → `PaymentClient.charge()` → `OrderRepository.save()`
- Gemini explains the flow in plain English, based only on the traced path.

### Risk radar
- **Complexity** per method (cyclomatic complexity), long methods and oversized classes.
- **Hotspots:** files that are both complex *and* changed often according to git history. These are where bugs tend to cluster.
- **Bus factor:** files only one person has ever edited.
- **Test gaps:** important classes with no matching test class.
- **Possibly dead code:** classes nothing depends on that aren't entry points.

### Onboarding guide
- A **"read these files in this order"** path, computed with a topological sort of the dependency graph (foundations first), with a short explanation for each file.
- A one-page overview of the architecture, the main modules and where to start.

### Ask the repo
- Code is split into chunks by class and method, embedded with Gemini and stored in **PostgreSQL + pgvector**.
- Questions like *"Where are passwords hashed?"* use **hybrid search** (vector similarity plus keyword matching).
- Every answer cites clickable `file:line` references, and **every citation is verified** against the parsed codebase.

### Fact-checked report
- All numbers (counts, complexity, cycles, hotspots) are computed in Java. Gemini writes the summary from those facts only.
- Any class, file or number that doesn't match the analysis is removed before the report is shown.

### Change tracking
- Every analysis is saved, so you can see trends over time (e.g. "complexity up 12% this month, 1 new cycle").
- Compare two commits or branches to see what a change introduced.

### Shareable results
- A public report page for each analyzed repository.
- A README badge (e.g. `RepoLens: B · 0 cycles`) that links to the report.

## How it works

```
GitHub repo URL
   → clone with JGit (current code + full commit history)
   → parse Java sources with JavaParser
   → build the class and method dependency graphs
   → run analysis: layers, cycles, request flows, complexity, hotspots, bus factor
   → save results to PostgreSQL, embed code chunks into pgvector
   → Gemini writes explanations from verified facts → claims are checked → report
```

Each analysis runs as a **background job** that moves through stages, with live progress streamed to the browser using Server-Sent Events. Jobs survive restarts and retry on failure, and the same repository is never analyzed twice at the same time.

**Safety:**
- Analyzed code is only ever parsed, **never run**.
- Repository size and file counts are capped.
- Archive extraction is protected against path-traversal ("zip slip").
- Webhooks are verified with HMAC signatures.
- Private repositories need GitHub sign-in with read-only access.

## APIs and tools

| Area | Used |
|---|---|
| AI | **Google Gemini API** (text generation and embeddings) |
| Backend | Java 21, **Spring Boot** (Web MVC, Data JPA, Security), Maven |
| Code analysis | **JavaParser** (parsing Java source), **JGit** (git history) |
| Algorithms | Tarjan's SCC (cycles), BFS / graph search (request flows), topological sort (reading order) |
| Database | **PostgreSQL** with **pgvector**, **Flyway** migrations |
| Caching | **Caffeine** |
| GitHub | GitHub REST API with ETag caching and rate-limit backoff, GitHub OAuth login |
| Frontend | HTML, CSS, JavaScript, **Cytoscape.js** (graph visualization), Server-Sent Events |
| Testing | **JUnit 5**, **WireMock** (fake GitHub API), **Testcontainers** (real PostgreSQL) |
| DevOps | **Docker**, **GitHub Actions** CI |

## Roadmap

- [ ] Clone and parse a repo, build the dependency graph, detect layers and cycles, interactive map, Gemini summary
- [ ] Request-flow tracer and risk radar (complexity, hotspots, bus factor)
- [ ] Fact-checked report and onboarding guide
- [ ] Ask the repo (RAG with verified citations)
- [ ] GitHub login, change tracking, badges and deployment
- [ ] Stretch: pull-request bot that comments on new PRs with the modules they touch and any new cycles