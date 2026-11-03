package com.sushil.codemap.analysis;

import java.util.Collection;

/**
 * Pure (no PSI) step: merges per-file results into one SPEC graph JSON string, running cross-file passes
 * (route prefixes through include_router chains, implements edges, routes edges, dangling-edge removal,
 * deterministic sorting). Must be fast enough to run after every incremental update (gis_api: < 200 ms).
 */
public interface GraphAssembler {
    /**
     * @param files   current results for all project files
     * @param root    absolute project root path
     * @param project project display name (root dir name)
     * @param version graph version to embed
     * @param buildMs analysis time to report in stats.ms
     */
    String assembleJson(Collection<FileResult> files, String root, String project, long version, long buildMs);

    static GraphAssembler create() {
        return new DefaultGraphAssembler();
    }
}
