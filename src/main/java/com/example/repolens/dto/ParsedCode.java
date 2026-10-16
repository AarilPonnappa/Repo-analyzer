package com.example.repolens.dto;

import java.util.List;
import java.util.Map;

/**
 * Everything JavaParser found in the repo. Produced by CodeParserService.
 * Only classes that live in this repo are kept; library classes (Spring, JDK, ...) are left out.
 */
public record ParsedCode(
        List<ClassInfo> classes,
        List<String> parseErrors,     // "path: reason" for files JavaParser couldn't read
        int javaFileCount
) {

    public record ClassInfo(
            String qualifiedName,     // "org.example.owner.OwnerController"
            String simpleName,        // "OwnerController"
            String packageName,
            String kind,              // CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
            String layer,             // CONTROLLER, SERVICE, REPOSITORY, ENTITY, CONFIG, COMPONENT, TEST, OTHER
            String filePath,          // relative to the repo root, e.g. "src/main/java/org/example/Owner.java"
            int startLine,
            int endLine,
            List<String> annotations, // "RestController", "Service", ...
            List<String> superTypes,  // repo classes this one extends or implements (qualified names)
            Map<String, String> dependencies, // repo class -> strongest kind: EXTENDS, IMPLEMENTS, INJECTS, CALLS, USES
            List<MethodInfo> methods,
            List<EndpointInfo> endpoints,
            boolean test,
            boolean entryPoint        // started by the framework (Spring bean, main method, ...), not by other code
    ) {
        public int lineCount() {
            return Math.max(0, endLine - startLine + 1);
        }

        public int totalComplexity() {
            return methods.stream().mapToInt(MethodInfo::complexity).sum();
        }
    }

    public record MethodInfo(
            String name,              // "place"
            String signature,         // "place(OrderRequest)"
            int paramCount,
            int startLine,
            int endLine,
            int complexity,           // cyclomatic complexity
            List<String> annotations,
            List<CallInfo> calls      // calls to methods of repo classes
    ) {
        public int lineCount() {
            return Math.max(0, endLine - startLine + 1);
        }
    }

    public record CallInfo(
            String targetClass,       // qualified name of the repo class being called (may be an interface)
            String methodName,
            int argCount,
            int line
    ) {}

    public record EndpointInfo(
            String httpMethod,        // GET, POST, ..., or ANY
            String path,              // "/owners/{ownerId}"
            String methodName,
            String methodSignature,
            int line
    ) {}
}
