package com.aryankanani.repointerviewer.project;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases for projects. Task 1.1 has the read side only; {@code submit} arrives in task 1.2.
 *
 * <p>Takes Spring Data types ({@link ScrollPosition}), never GraphQL types: only the {@code api}
 * module may use {@code org.springframework.graphql} (ArchitectureTest).
 */
@Service
@Transactional(readOnly = true)
public class ProjectService {

	private final ProjectRepository projectRepository;

	ProjectService(ProjectRepository projectRepository) {
		this.projectRepository = projectRepository;
	}

	public Optional<Project> find(UUID id) {
		return projectRepository.findById(id);
	}

	/** Newest first. Start with {@code ScrollPosition.keyset()}, then pass the position of the last item seen. */
	public Window<Project> page(ScrollPosition position, int limit) {
		return projectRepository.findAllByOrderByCreatedAtDescIdDesc(position, Limit.of(limit));
	}

}
