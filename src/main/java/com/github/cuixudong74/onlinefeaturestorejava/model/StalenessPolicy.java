package com.github.cuixudong74.onlinefeaturestorejava.model;

/**
 * What to do when the freshest value at-or-before the requested timestamp is
 * older than the configured TTL.
 */
public enum StalenessPolicy {
    /** Return the value anyway, flagged as stale. */
    MARK_STALE,
    /** Do not return the stale value; fall back to the last non-expired version if any. */
    FALLBACK_TO_PREVIOUS,
    /** Treat as missing. */
    FAIL
}
