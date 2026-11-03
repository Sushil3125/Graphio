package com.sushil.codemap.analysis;

/** Immutable published graph. {@code json} is the full SPEC graph JSON including "version". */
public record GraphSnapshot(long version, String json, java.util.List<String> changedFiles) {
    public static GraphSnapshot empty(String root, String project) {
        return new GraphSnapshot(0, GraphAssembler.create().assembleJson(java.util.List.of(), root, project, 0, 0),
                java.util.List.of());
    }
}
