package tech.wenisch.petri.git.provider;

/**
 * The text output of one job.
 *
 * @param truncated true when only the tail of a larger log is kept
 */
public record PipelineLog(String text, boolean truncated) {}
