# RepoLens

## Abstract

RepoLens is a web application that helps developers understand Java codebases. Users provide a GitHub repository URL, and the application analyzes the source code to generate an interactive architecture map, identify potentially risky code, trace API request flows, and answer questions about the codebase using AI with file and line references.

---

## Background

Understanding an unfamiliar codebase can be time-consuming, especially for large projects with many interconnected classes and dependencies. Developers often need to manually explore files, trace method calls, and understand the overall architecture before making changes. Existing tools may provide code quality metrics or AI-generated explanations, but do not always combine architecture analysis with explanations grounded in the actual source code.

---

## Solution

RepoLens simplifies codebase exploration by automatically analyzing Java repositories and presenting the results in an interactive interface. It uses JavaParser and graph algorithms to examine the source code, while Google Gemini provides explanations based on the analysis. RepoLens supports Java projects built with Maven or Gradle.

---

## Features

- **Architecture Map:** Visualizes classes, dependencies, and application layers in an interactive graph.
- **Request Flow Tracing:** Shows how API requests move through controllers, services, and repositories.
- **Risk Analysis:** Identifies complex methods, circular dependencies, frequently modified files, and potential test gaps.
- **Onboarding Guide:** Suggests an order for reading files to help developers understand unfamiliar projects.
- **Ask the Repo:** Allows users to ask questions about the codebase and receive AI-generated answers with file and line references.
- **Analysis Reports:** Generates summaries of the codebase's architecture, complexity, and potential issues.
- **Change Tracking:** Compares repository analyses to identify changes in code quality and structure.

---

## How It Works and Tools Used

1. **Repository Retrieval:** JGit clones the GitHub repository, while the GitHub REST API retrieves repository information.
2. **Source Code Analysis:** JavaParser examines Java files to extract classes, methods, dependencies, and API endpoints.
3. **Graph Analysis:** Graph algorithms identify circular dependencies, trace request flows, and determine a recommended reading order.
4. **Data Storage:** PostgreSQL stores analysis results, while pgvector stores code embeddings for similarity searches.
5. **AI Integration:** Google Gemini generates explanations, reports, and answers to questions using information extracted from the source code.
6. **Frontend:** HTML, CSS, JavaScript, and Cytoscape.js display the analysis results and interactive architecture graphs.

**Technologies Used:** Java,