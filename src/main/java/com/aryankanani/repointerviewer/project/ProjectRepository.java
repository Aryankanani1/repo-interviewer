package com.aryankanani.repointerviewer.project;

import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectRepository extends JpaRepository<Project, UUID> {

	/**
	 * One page of projects, newest first, using keyset scrolling.
	 *
	 * <p>The sort is fixed in the method name on purpose: it must match the index
	 * {@code ix_project_created_at (created_at DESC, id DESC)}, and {@code id} breaks ties between
	 * projects created at the same moment, so no row is skipped or repeated between pages.
	 * With a keyset position, Spring Data adds {@code WHERE (created_at, id) < (last seen values)}
	 * instead of {@code OFFSET n}.
	 */
	Window<Project> findAllByOrderByCreatedAtDescIdDesc(ScrollPosition position, Limit limit);

}
