package com.aryankanani.repointerviewer.project;

import com.aryankanani.repointerviewer.codebase.fetch.GithubUrl;
import com.aryankanani.repointerviewer.codebase.fetch.RemoteHead;
import com.aryankanani.repointerviewer.codebase.fetch.RepoFetcher;
import com.aryankanani.repointerviewer.common.error.DomainException;
import com.aryankanani.repointerviewer.common.error.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Use cases for projects.
 *
 * <p>Takes Spring Data types ({@link ScrollPosition}), never GraphQL types: only the {@code api}
 * module may use {@code org.springframework.graphql} (ArchitectureTest).
 */
@Service
public class ProjectService {

	private final ProjectRepository projectRepository;

	private final RepoFetcher repoFetcher;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transaction;

	ProjectService(ProjectRepository projectRepository, RepoFetcher repoFetcher, ApplicationEventPublisher events,
			PlatformTransactionManager transactionManager) {
		this.projectRepository = projectRepository;
		this.repoFetcher = repoFetcher;
		this.events = events;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@Transactional(readOnly = true)
	public Optional<Project> find(UUID id) {
		return projectRepository.findById(id);
	}

	/** Newest first. Start with {@code ScrollPosition.keyset()}, then pass the position of the last item seen. */
	@Transactional(readOnly = true)
	public Window<Project> page(ScrollPosition position, int limit) {
		return projectRepository.findAllByOrderByCreatedAtDescIdDesc(position, Limit.of(limit));
	}

	/**
	 * Registers a repository at its current commit, or returns the existing project for that commit
	 * (any status, FAILED included; the client retries with reanalyzeProject).
	 *
	 * <p>Deliberately not {@code @Transactional}: {@code resolveHead} is a network call (up to 15 s), and a
	 * transaction must never stay open across one. Order: parse (no I/O), ls-remote (no transaction), then
	 * one short transaction. Analysis starts after that transaction commits ({@link AnalysisStarter}).
	 *
	 * @throws com.aryankanani.repointerviewer.common.error.DomainException INVALID_URL or REPO_NOT_FOUND
	 */
	public Project submit(SubmitProjectCommand command) {
		GithubUrl url = GithubUrl.parse(command.githubUrl());
		RemoteHead head = repoFetcher.resolveHead(url);
		try {
			return transaction.execute(status -> findOrInsert(url, head, command));
		}
		catch (DataIntegrityViolationException ex) {
			// Another request inserted the same (url, commit) between our check and our insert. Postgres made
			// our insert wait for that transaction, so the winner is committed: read it and return it.
			return transaction.execute(status -> projectRepository
					.findByGithubUrlAndCommitSha(url.normalized(), head.commitSha())
					.orElseThrow(() -> ex));
		}
	}

	/**
	 * {@code reanalyzeProject}: a FAILED project goes back to SUBMITTED and its analysis starts again after the
	 * commit, from the clone onwards. A project in any other status is returned unchanged: it is running, done,
	 * or already queued.
	 *
	 * @throws DomainException NOT_FOUND
	 */
	public Project reanalyze(UUID projectId) {
		try {
			return transaction.execute(status -> {
				Project project = projectRepository.findById(projectId).orElseThrow(
						() -> new DomainException(ErrorCode.NOT_FOUND, "Project not found.", "projectId"));
				if (project.getStatus() == AnalysisStatus.FAILED) {
					project.reanalyze();
					events.publishEvent(new ProjectSubmittedEvent(project.getId()));
				}
				return project;
			});
		}
		catch (OptimisticLockingFailureException ex) {
			// Two reanalyze calls at once: the other one won (@Version). Return what it committed.
			return projectRepository.findById(projectId).orElseThrow(() -> ex);
		}
	}

	private Project findOrInsert(GithubUrl url, RemoteHead head, SubmitProjectCommand command) {
		Optional<Project> existing = projectRepository.findByGithubUrlAndCommitSha(url.normalized(), head.commitSha());
		if (existing.isPresent()) {
			return existing.get();
		}
		// saveAndFlush: a unique-constraint violation surfaces here, inside the try, not later at commit.
		Project project = projectRepository.saveAndFlush(new Project(url, head, command));
		events.publishEvent(new ProjectSubmittedEvent(project.getId()));
		return project;
	}

}
