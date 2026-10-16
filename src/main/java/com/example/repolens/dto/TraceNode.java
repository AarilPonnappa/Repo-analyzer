package com.example.repolens.dto;

import java.util.List;

/**
 * One step in a traced request flow, e.g. OwnerController.processCreationForm() -> OwnerRepository.save().
 * Produced by RequestFlowService.
 */
public record TraceNode(
        String className,         // qualified name
        String simpleClassName,
        String method,            // "processCreationForm(Owner, BindingResult)"
        String layer,
        String filePath,          // null for external (framework) methods
        Integer line,
        boolean external,         // true when the method isn't in the repo, e.g. JpaRepository.findById
        boolean truncated,        // true when the trace stopped here (recursion or depth limit)
        List<TraceNode> children
) {}
