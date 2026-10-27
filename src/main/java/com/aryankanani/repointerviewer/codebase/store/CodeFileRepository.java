package com.aryankanani.repointerviewer.codebase.store;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodeFileRepository extends JpaRepository<CodeFile, UUID> {

	List<CodeFile> findByProjectId(UUID projectId);

	@Query("select f.path from CodeFile f where f.projectId = :projectId")
	List<String> findPathsByProjectId(@Param("projectId") UUID projectId);

	/**
	 * One DELETE statement, not one per row (a derived {@code deleteBy...} would load every entity first). The
	 * project's chunks go with it through {@code ON DELETE CASCADE}. Must run inside a transaction.
	 */
	@Modifying
	@Query("delete from CodeFile f where f.projectId = :projectId")
	int deleteAllByProjectId(@Param("projectId") UUID projectId);

}
