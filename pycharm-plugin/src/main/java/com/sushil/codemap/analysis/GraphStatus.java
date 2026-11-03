package com.sushil.codemap.analysis;

/**
 * state: "idle" (not started), "waiting_for_index", "building", "ready", "error".
 * done/total: files analysed so far during "building" (0/0 otherwise). message: human readable detail.
 */
public record GraphStatus(String state, int done, int total, String message) {}
