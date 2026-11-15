package com.aryankanani.repointerviewer;

import com.aryankanani.repointerviewer.project.AnalysisPipeline;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;

/**
 * Test {@link AnalysisPipeline} that records every start, and whether the project row was already
 * committed at that moment.
 *
 * <p>The check uses a fresh pooled connection, not the transaction's connection. A transaction always
 * sees its own uncommitted rows; another connection only sees committed ones. So {@code rowCommitted}
 * proves that {@code start} ran after the commit.
 */
public class RecordingAnalysisPipeline implements AnalysisPipeline {

	public record Start(UUID projectId, boolean rowCommitted) {
	}

	private final DataSource dataSource;

	private final List<Start> starts = new CopyOnWriteArrayList<>();

	public RecordingAnalysisPipeline(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public void reset() {
		starts.clear();
	}

	public List<Start> starts() {
		return List.copyOf(starts);
	}

	@Override
	public void start(UUID projectId) {
		starts.add(new Start(projectId, isVisibleFromAnotherConnection(projectId)));
	}

	private boolean isVisibleFromAnotherConnection(UUID projectId) {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM project WHERE id = ?")) {
			statement.setObject(1, projectId);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next();
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
