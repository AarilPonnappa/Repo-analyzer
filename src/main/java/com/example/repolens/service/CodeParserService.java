package com.example.repolens.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.repolens.dto.ParsedCode;
import com.example.repolens.dto.ParsedCode.CallInfo;
import com.example.repolens.dto.ParsedCode.ClassInfo;
import com.example.repolens.dto.ParsedCode.EndpointInfo;
import com.example.repolens.dto.ParsedCode.MethodInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

/**
 * Reads every .java file with JavaParser and turns it into ParsedCode:
 * classes, layers, dependencies between classes, methods, complexity, method calls and HTTP endpoints.
 *
 * It never compiles or runs the code. Calls are resolved with simple rules (imports, same package,
 * field/parameter/local variable types), which handles typical Spring code without needing the
 * project's dependency jars.
 */
@Service
public class CodeParserService {

    private static final Logger log = LoggerFactory.getLogger(CodeParserService.class);

    // Folders that never contain source we want
    static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "build", "out", "node_modules", ".gradle", ".idea", ".mvn");

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("RestController", "Controller");
    private static final Set<String> MAPPING_ANNOTATIONS =
            Set.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping");
    private static final Set<String> INJECT_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");
    private static final Set<String> LOMBOK_CONSTRUCTORS = Set.of("RequiredArgsConstructor", "AllArgsConstructor");

    // Classes with these annotations are created and called by the framework
    private static final Set<String> FRAMEWORK_ANNOTATIONS = Set.of(
            "SpringBootApplication", "Configuration", "Component", "Service", "Repository", "Controller",
            "RestController", "ControllerAdvice", "RestControllerAdvice", "Entity", "Embeddable",
            "MappedSuperclass", "Document", "Aspect", "WebFilter", "WebServlet", "WebListener",
            "ConfigurationProperties", "Endpoint", "FeignClient", "Converter");

    // Methods with these annotations are called by the framework
    private static final Set<String> FRAMEWORK_METHOD_ANNOTATIONS = Set.of(
            "Bean", "Scheduled", "EventListener", "KafkaListener", "RabbitListener", "JmsListener",
            "PostConstruct", "PreDestroy", "ExceptionHandler", "ModelAttribute", "InitBinder");

    // Library types whose implementations are usually registered with the framework
    private static final Set<String> FRAMEWORK_SUPERTYPES = Set.of(
            "CommandLineRunner", "ApplicationRunner", "WebMvcConfigurer", "Filter", "OncePerRequestFilter",
            "HandlerInterceptor", "Formatter", "Converter", "Validator", "ConstraintValidator",
            "ApplicationListener", "AuthenticationProvider", "UserDetailsService", "AttributeConverter");

    private static final Set<String> REPOSITORY_SUPERTYPES = Set.of(
            "Repository", "CrudRepository", "JpaRepository", "PagingAndSortingRepository",
            "ListCrudRepository", "MongoRepository", "ReactiveCrudRepository", "R2dbcRepository");

    private static final List<String> KIND_PRIORITY = List.of("EXTENDS", "IMPLEMENTS", "INJECTS", "CALLS", "USES");

    public ParsedCode parse(Path repoRoot) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

        // Pass 1: parse every file and index the types it declares
        List<SourceFile> sources = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        TypeIndex index = new TypeIndex();

        for (Path file : javaFiles(repoRoot)) {
            String relPath = relative(repoRoot, file);
            try {
                ParseResult<CompilationUnit> result = parser.parse(file);
                Optional<CompilationUnit> cu = result.getResult();
                if (!result.isSuccessful() || cu.isEmpty()) {
                    String reason = result.getProblems().isEmpty() ? "unknown" : result.getProblems().get(0).getMessage();
                    errors.add(relPath + ": " + firstLine(reason));
                    continue;
                }
                SourceFile source = new SourceFile(relPath, cu.get());
                sources.add(source);
                for (TypeDeclaration<?> type : cu.get().getTypes()) {
                    index.add(source.packageName(), type.getNameAsString());
                }
            } catch (IOException | RuntimeException e) {
                errors.add(relPath + ": " + firstLine(String.valueOf(e.getMessage())));
            }
        }

        // Pass 2: now that every repo type is known, describe each class
        List<ClassInfo> classes = new ArrayList<>();
        for (SourceFile source : sources) {
            TypeResolver resolver = new TypeResolver(source.cu(), source.packageName(), index);
            for (TypeDeclaration<?> type : source.cu().getTypes()) {
                try {
                    classes.add(describe(type, source, resolver));
                } catch (RuntimeException e) {
                    errors.add(source.path() + ": couldn't analyze " + type.getNameAsString() + " (" + e.getClass().getSimpleName() + ")");
                }
            }
        }

        log.info("Parsed {} classes from {} files ({} errors)", classes.size(), sources.size() + errors.size(), errors.size());
        return new ParsedCode(classes, errors, sources.size() + errors.size());
    }

    // ---------------------------------------------------------------- class level

    private ClassInfo describe(TypeDeclaration<?> type, SourceFile source, TypeResolver resolver) {
        String simpleName = type.getNameAsString();
        String qualifiedName = qualify(source.packageName(), simpleName);
        List<String> annotations = annotationNames(type.getAnnotations());
        boolean test = isTestFile(source.path(), simpleName);

        // extends / implements
        List<ClassOrInterfaceType> extended = new ArrayList<>();
        if (type.isClassOrInterfaceDeclaration()) {
            extended.addAll(type.asClassOrInterfaceDeclaration().getExtendedTypes());
        }
        List<ClassOrInterfaceType> implemented = new ArrayList<>();
        if (type instanceof NodeWithImplements<?> withImplements) {
            implemented.addAll(withImplements.getImplementedTypes());
        }
        List<String> rawSuperTypes = new ArrayList<>();
        extended.forEach(t -> rawSuperTypes.add(t.getNameAsString()));
        implemented.forEach(t -> rawSuperTypes.add(t.getNameAsString()));

        Map<String, String> dependencies = new LinkedHashMap<>();
        List<String> superTypes = new ArrayList<>();
        boolean isInterface = type.isClassOrInterfaceDeclaration() && type.asClassOrInterfaceDeclaration().isInterface();
        for (ClassOrInterfaceType t : extended) {
            String target = resolver.resolve(t.getNameWithScope());
            if (target != null) {
                superTypes.add(target);
                // an interface extending an interface is still "implements"-like
                addDependency(dependencies, qualifiedName, target, isInterface ? "IMPLEMENTS" : "EXTENDS");
            }
        }
        for (ClassOrInterfaceType t : implemented) {
            String target = resolver.resolve(t.getNameWithScope());
            if (target != null) {
                superTypes.add(target);
                addDependency(dependencies, qualifiedName, target, "IMPLEMENTS");
            }
        }

        // fields: name -> type, and which fields are injected
        Map<String, String> fieldTypes = new HashMap<>();
        boolean lombokConstructor = annotations.stream().anyMatch(LOMBOK_CONSTRUCTORS::contains);
        List<MethodDeclaration> methodDecls = new ArrayList<>();
        boolean hasMain = false;
        boolean hasFrameworkMethod = false;

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                boolean injected = !field.isStatic() && (
                        annotationNames(field.getAnnotations()).stream().anyMatch(INJECT_ANNOTATIONS::contains)
                        || (lombokConstructor && field.isFinal()));
                for (VariableDeclarator var : field.getVariables()) {
                    String target = resolver.resolveType(var.getType());
                    if (target != null) {
                        fieldTypes.put(var.getNameAsString(), target);
                        addDependency(dependencies, qualifiedName, target, injected ? "INJECTS" : "USES");
                    }
                }
            } else if (member instanceof ConstructorDeclaration constructor) {
                // constructor injection: every repo type in a constructor's parameters
                for (Parameter p : constructor.getParameters()) {
                    String target = resolver.resolveType(p.getType());
                    if (target != null) {
                        addDependency(dependencies, qualifiedName, target, "INJECTS");
                    }
                }
            } else if (member instanceof MethodDeclaration method) {
                methodDecls.add(method);
                if (method.getNameAsString().equals("main") && method.isStatic()) {
                    hasMain = true;
                }
                if (annotationNames(method.getAnnotations()).stream().anyMatch(FRAMEWORK_METHOD_ANNOTATIONS::contains)) {
                    hasFrameworkMethod = true;
                }
            }
        }

        // every other repo type mentioned anywhere in the class (generics, new X(), casts, ...)
        for (ClassOrInterfaceType t : type.findAll(ClassOrInterfaceType.class)) {
            String target = resolver.resolve(t.getNameWithScope());
            if (target != null) {
                addDependency(dependencies, qualifiedName, target, "USES");
            }
        }

        // methods, with complexity and calls
        List<MethodInfo> methods = new ArrayList<>();
        for (MethodDeclaration method : methodDecls) {
            MethodInfo info = describeMethod(method, qualifiedName, fieldTypes, superTypes, resolver);
            methods.add(info);
            for (CallInfo call : info.calls()) {
                addDependency(dependencies, qualifiedName, call.targetClass(), "CALLS");
            }
        }

        String kind = kindOf(type);
        String layer = detectLayer(annotations, rawSuperTypes, simpleName, test);
        List<EndpointInfo> endpoints = "CONTROLLER".equals(layer) ? endpoints(type, methodDecls) : List.of();

        boolean entryPoint = hasMain || hasFrameworkMethod
                || annotations.stream().anyMatch(FRAMEWORK_ANNOTATIONS::contains)
                || rawSuperTypes.stream().anyMatch(FRAMEWORK_SUPERTYPES::contains)
                || rawSuperTypes.stream().anyMatch(REPOSITORY_SUPERTYPES::contains);

        return new ClassInfo(
                qualifiedName, simpleName, source.packageName(), kind, layer, source.path(),
                type.getBegin().map(p -> p.line).orElse(0),
                type.getEnd().map(p -> p.line).orElse(0),
                annotations, superTypes, dependencies, methods, endpoints, test, entryPoint);
    }

    private static String kindOf(TypeDeclaration<?> type) {
        if (type.isClassOrInterfaceDeclaration()) {
            return type.asClassOrInterfaceDeclaration().isInterface() ? "INTERFACE" : "CLASS";
        }
        if (type.isEnumDeclaration()) return "ENUM";
        if (type.isRecordDeclaration()) return "RECORD";
        if (type.isAnnotationDeclaration()) return "ANNOTATION";
        return "CLASS";
    }

    // Layer from annotations first, then supertypes, then naming conventions
    static String detectLayer(List<String> annotations, List<String> rawSuperTypes, String simpleName, boolean test) {
        if (test) return "TEST";
        if (annotations.stream().anyMatch(a -> a.equals("RestController") || a.equals("Controller")
                || a.equals("ControllerAdvice") || a.equals("RestControllerAdvice"))) return "CONTROLLER";
        if (annotations.contains("Service")) return "SERVICE";
        if (annotations.contains("Repository") || rawSuperTypes.stream().anyMatch(REPOSITORY_SUPERTYPES::contains)) return "REPOSITORY";
        if (annotations.stream().anyMatch(a -> a.equals("Entity") || a.equals("Embeddable")
                || a.equals("MappedSuperclass") || a.equals("Document") || a.equals("Table"))) return "ENTITY";
        if (annotations.contains("Configuration") || annotations.contains("SpringBootApplication")) return "CONFIG";
        if (annotations.contains("Component")) return "COMPONENT";
        if (simpleName.endsWith("Controller") || simpleName.endsWith("Resource")) return "CONTROLLER";
        if (simpleName.endsWith("Service") || simpleName.endsWith("ServiceImpl")) return "SERVICE";
        if (simpleName.endsWith("Repository") || simpleName.endsWith("Dao")) return "REPOSITORY";
        return "OTHER";
    }

    static boolean isTestFile(String path, String simpleName) {
        return path.contains("/src/test/") || path.startsWith("src/test/")
                || simpleName.endsWith("Test") || simpleName.endsWith("Tests") || simpleName.endsWith("IT");
    }

    private static void addDependency(Map<String, String> deps, String self, String target, String kind) {
        if (target == null || target.equals(self)) return;
        String existing = deps.get(target);
        if (existing == null || KIND_PRIORITY.indexOf(kind) < KIND_PRIORITY.indexOf(existing)) {
            deps.put(target, kind);
        }
    }

    // ---------------------------------------------------------------- method level

    private MethodInfo describeMethod(MethodDeclaration method, String owner, Map<String, String> fieldTypes,
                                      List<String> superTypes, TypeResolver resolver) {
        // variable name -> repo type, for parameters and local variables
        Map<String, String> localTypes = new HashMap<>();
        List<String> paramTypes = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            paramTypes.add(stripGenerics(p.getType().asString()));
            String target = resolver.resolveType(p.getType());
            if (target != null) localTypes.put(p.getNameAsString(), target);
        }
        for (VariableDeclarator var : method.findAll(VariableDeclarator.class)) {
            Type varType = var.getType();
            String target;
            if (varType.isVarType()) {
                // var x = new Foo(); -> Foo
                target = var.getInitializer()
                        .filter(Expression::isObjectCreationExpr)
                        .map(e -> resolver.resolveType(((ObjectCreationExpr) e).getType()))
                        .orElse(null);
            } else {
                target = resolver.resolveType(varType);
            }
            if (target != null) localTypes.put(var.getNameAsString(), target);
        }

        List<CallInfo> calls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String target = callTarget(call, owner, localTypes, fieldTypes, superTypes, resolver);
            if (target == null) continue;
            String key = target + "#" + call.getNameAsString() + "/" + call.getArguments().size();
            if (seen.add(key)) {
                calls.add(new CallInfo(target, call.getNameAsString(), call.getArguments().size(),
                        call.getBegin().map(p -> p.line).orElse(0)));
            }
        }

        String signature = method.getNameAsString() + "(" + String.join(", ", paramTypes) + ")";
        return new MethodInfo(
                method.getNameAsString(), signature, method.getParameters().size(),
                method.getBegin().map(p -> p.line).orElse(0),
                method.getEnd().map(p -> p.line).orElse(0),
                complexity(method), annotationNames(method.getAnnotations()), calls);
    }

    // Works out which repo class a call goes to, or null if it's a library call or can't be resolved
    private static String callTarget(MethodCallExpr call, String owner, Map<String, String> localTypes,
                                     Map<String, String> fieldTypes, List<String> superTypes, TypeResolver resolver) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return owner;                                   // foo() -> a method in this class
        }
        Expression s = scope.get();
        if (s.isThisExpr()) {
            return owner;                                   // this.foo()
        }
        if (s.isSuperExpr()) {
            return superTypes.isEmpty() ? null : superTypes.get(0);
        }
        if (s instanceof NameExpr name) {
            String n = name.getNameAsString();
            if (localTypes.containsKey(n)) return localTypes.get(n);    // param.foo() / local.foo()
            if (fieldTypes.containsKey(n)) return fieldTypes.get(n);    // orderService.place()
            return resolver.resolve(n);                                 // Utils.foo() (static call)
        }
        if (s instanceof FieldAccessExpr field && field.getScope().isThisExpr()) {
            return fieldTypes.get(field.getNameAsString());             // this.orderService.place()
        }
        return null;                                        // chained calls etc. are skipped
    }

    // Cyclomatic complexity: 1 + number of decision points
    static int complexity(Node method) {
        int count = 1;
        count += method.findAll(IfStmt.class).size();
        count += method.findAll(ForStmt.class).size();
        count += method.findAll(ForEachStmt.class).size();
        count += method.findAll(WhileStmt.class).size();
        count += method.findAll(DoStmt.class).size();
        count += method.findAll(CatchClause.class).size();
        count += method.findAll(ConditionalExpr.class).size();
        count += (int) method.findAll(SwitchEntry.class).stream().filter(e -> !e.getLabels().isEmpty()).count();
        count += (int) method.findAll(BinaryExpr.class).stream()
                .filter(b -> b.getOperator() == BinaryExpr.Operator.AND || b.getOperator() == BinaryExpr.Operator.OR)
                .count();
        return count;
    }

    // ---------------------------------------------------------------- endpoints

    private List<EndpointInfo> endpoints(TypeDeclaration<?> type, List<MethodDeclaration> methods) {
        String basePath = type.getAnnotations().stream()
                .filter(a -> simple(a.getNameAsString()).equals("RequestMapping"))
                .findFirst()
                .map(a -> first(annotationValues(a, "value", "path")))
                .orElse("");

        List<EndpointInfo> result = new ArrayList<>();
        for (MethodDeclaration method : methods) {
            for (AnnotationExpr a : method.getAnnotations()) {
                String name = simple(a.getNameAsString());
                if (!MAPPING_ANNOTATIONS.contains(name)) continue;

                String httpMethod = switch (name) {
                    case "GetMapping" -> "GET";
                    case "PostMapping" -> "POST";
                    case "PutMapping" -> "PUT";
                    case "DeleteMapping" -> "DELETE";
                    case "PatchMapping" -> "PATCH";
                    default -> requestMappingMethod(a);
                };
                List<String> paths = annotationValues(a, "value", "path");
                if (paths.isEmpty()) paths = List.of("");

                List<String> paramTypes = method.getParameters().stream()
                        .map(p -> stripGenerics(p.getType().asString())).toList();
                String signature = method.getNameAsString() + "(" + String.join(", ", paramTypes) + ")";

                for (String path : paths) {
                    result.add(new EndpointInfo(httpMethod, joinPaths(basePath, path), method.getNameAsString(),
                            signature, method.getBegin().map(p -> p.line).orElse(0)));
                }
            }
        }
        return result;
    }

    private static String requestMappingMethod(AnnotationExpr a) {
        if (a instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    Expression v = pair.getValue();
                    if (v instanceof ArrayInitializerExpr array && !array.getValues().isEmpty()) {
                        v = array.getValues().get(0);
                    }
                    String text = v.toString();
                    return text.substring(text.lastIndexOf('.') + 1);   // RequestMethod.GET -> GET
                }
            }
        }
        return "ANY";
    }

    // Reads annotation values like @GetMapping("/a"), @GetMapping(path = {"/a", "/b"}), @GetMapping(value = BASE + "/x")
    private static List<String> annotationValues(AnnotationExpr a, String... keys) {
        Expression value = null;
        if (a instanceof SingleMemberAnnotationExpr single) {
            value = single.getMemberValue();
        } else if (a instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (List.of(keys).contains(pair.getNameAsString())) {
                    value = pair.getValue();
                    break;
                }
            }
        }
        if (value == null) return List.of();
        if (value instanceof ArrayInitializerExpr array) {
            return array.getValues().stream().map(CodeParserService::stringValue).toList();
        }
        return List.of(stringValue(value));
    }

    private static String stringValue(Expression e) {
        if (e instanceof StringLiteralExpr s) return s.asString();
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            return stringValue(b.getLeft()) + stringValue(b.getRight());
        }
        return "{" + e + "}";   // a constant we can't see the value of
    }

    static String joinPaths(String base, String path) {
        String joined = ("/" + base + "/" + path).replaceAll("/+", "/");
        if (joined.length() > 1 && joined.endsWith("/")) joined = joined.substring(0, joined.length() - 1);
        return joined;
    }

    // ---------------------------------------------------------------- helpers

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        for (Path part : root.relativize(p)) {
                            if (SKIPPED_DIRS.contains(part.toString())) return false;
                        }
                        return true;
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static List<String> annotationNames(List<AnnotationExpr> annotations) {
        return annotations.stream().map(a -> simple(a.getNameAsString())).toList();
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String qualify(String pkg, String name) {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    private static String stripGenerics(String type) {
        return type.replaceAll("<.*>", "");
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? "" : values.get(0);
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private record SourceFile(String path, CompilationUnit cu) {
        String packageName() {
            return cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        }
    }

    /** All top-level types declared in the repo. */
    private static final class TypeIndex {
        private final Set<String> qualifiedNames = new HashSet<>();

        void add(String pkg, String simpleName) {
            qualifiedNames.add(qualify(pkg, simpleName));
        }

        boolean contains(String qualifiedName) {
            return qualifiedNames.contains(qualifiedName);
        }
    }

    /**
     * Turns a type name as written in one file into the qualified name of a repo class,
     * following Java's rules: explicit import, then same package, then wildcard imports.
     * Returns null for library types (String, List, JpaRepository, ...).
     */
    private static final class TypeResolver {
        private final String pkg;
        private final TypeIndex index;
        private final Map<String, String> explicitImports = new HashMap<>();
        private final List<String> wildcardPackages = new ArrayList<>();

        TypeResolver(CompilationUnit cu, String pkg, TypeIndex index) {
            this.pkg = pkg;
            this.index = index;
            for (ImportDeclaration imp : cu.getImports()) {
                if (imp.isStatic()) continue;
                if (imp.isAsterisk()) {
                    wildcardPackages.add(imp.getNameAsString());
                } else {
                    explicitImports.put(simple(imp.getNameAsString()), imp.getNameAsString());
                }
            }
        }

        String resolveType(Type type) {
            if (type.isClassOrInterfaceType()) {
                return resolve(type.asClassOrInterfaceType().getNameWithScope());
            }
            if (type.isArrayType()) {
                return resolveType(type.asArrayType().getComponentType());
            }
            return null;   // primitives, var, etc.
        }

        String resolve(String name) {
            if (name == null || name.isEmpty()) return null;
            if (name.contains(".")) {
                if (index.contains(name)) return name;                  // fully qualified
                return resolve(name.substring(0, name.indexOf('.')));   // Outer.Inner -> Outer
            }
            String imported = explicitImports.get(name);
            if (imported != null) {
                return index.contains(imported) ? imported : null;      // an import always wins
            }
            String samePackage = qualify(pkg, name);
            if (index.contains(samePackage)) return samePackage;
            for (String wildcard : wildcardPackages) {
                String candidate = wildcard + "." + name;
                if (index.contains(candidate)) return candidate;
            }
            return null;
        }
    }
}
