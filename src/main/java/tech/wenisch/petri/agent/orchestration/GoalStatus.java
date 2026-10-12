package tech.wenisch.petri.agent.orchestration;

/** How far an acceptance criterion of the approved plan is backed by evidence. */
public enum GoalStatus {
  /** No review has covered the criterion yet, or the covering review is stale. */
  UNVERIFIED,
  /** The reviewer says it is met but no cited evidence could be confirmed by the server. */
  CLAIMED,
  /** The reviewer says it is partly met. */
  PARTIAL,
  /** The reviewer says it is not met, or the cited tests failed. */
  NOT_MET,
  /** The reviewer says it is met and at least one cited test or file was confirmed. */
  VERIFIED
}
