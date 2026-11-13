package com.example.repolens.dto;

import java.util.List;
import java.util.Map;

/**
 * Class representing the parsed code data of a repository, including classes, methods, endpoints, and dependencies.
 * Everything JavaParser found in the repo. Produced by CodeParserService.
 */
public record ParsedCode(
        List<ClassInfo> classes,      // List of all java files/ classes in the repo, each represented by a ClassInfo object
        List<String> parseErrors,     // "path: reason" for files JavaParser couldn't read
        int javaFileCount
) {

    public record ClassInfo(
            String qualifiedName,     // This is the fully qualified name of the class, including the package name. For example, "com.example.OwnerController".
            String simpleName,        // The class name itself. "OwnerController" or "FileClassName"
            String packageName,       // The package name of the class. "com.example"
            String kind,              // CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
            String layer,             // CONTROLLER, SERVICE, REPOSITORY, ENTITY, CONFIG, COMPONENT, TEST, OTHER
            String filePath,          // relative to the repo root, e.g. "src/main/java/org/example/Owner.java"
            int startLine,
            int endLine,
            List<String> annotations,       // The annotations on the class, e.g. ["@RestController", "@RequestMapping("/owners")"]. javaparser returns the annotations as they appear in the source code, including the "@" symbol and any parameters.
            List<String> superTypes,        // The superclasses and interfaces that this class extends(inheritance) or implements(interface). Say there is a class "BaseController" that this class extends by "BaseClass extends BaseController" or "BaseClass implements BaseController". For example, ["BaseController", "CrudRepository<Owner, Long>"].
            Map<String, String> dependencies,   // The dependencies of this class on other classes in the repo. The key is the fully qualified name of the class being depended on, and the value is the type of dependency (FIELD, PARAMETER, LOCAL_VARIABLE, RETURN_TYPE, SUPERCLASS, INTERFACE). For example, {"com.example.Owner": "FIELD", "com.example.Pet": "PARAMETER"}.
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
