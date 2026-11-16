package com.suraj.candidates.candidate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Never call {@code delete*} / {@code deleteAll*} methods; deletion is soft and must go through
 * {@link Candidate#markDeleted()} in the service.
 */
public interface CandidateRepository extends JpaRepository<Candidate, Long>, JpaSpecificationExecutor<Candidate> {
}
