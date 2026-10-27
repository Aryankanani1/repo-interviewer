package com.aryankanani.repointerviewer.codebase.store;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodeFileRepository extends JpaRepository<CodeFile, UUID> {

	List<CodeFile> findByProjectId(UUID projectId);

}
