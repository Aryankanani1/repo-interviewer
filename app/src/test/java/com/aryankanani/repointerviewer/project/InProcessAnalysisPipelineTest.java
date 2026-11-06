package com.aryankanani.repointerviewer.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Task 1.9: one background job per project at a time. The job itself is a mock that waits for a signal. */
class InProcessAnalysisPipelineTest {

	private final AnalysisJob job = mock(AnalysisJob.class);

	private final InProcessAnalysisPipeline pipeline = new InProcessAnalysisPipeline(job);

	private final CountDownLatch release = new CountDownLatch(1);

	@AfterEach
	void stop() {
		release.countDown();
		pipeline.stop();
	}

	@Test
	void startReturnsAtOnceAndASecondStartWhileRunningIsIgnored() throws InterruptedException {
		UUID project = UUID.randomUUID();
		CountDownLatch started = new CountDownLatch(1);
		doAnswer(invocation -> {
			started.countDown();
			release.await(10, TimeUnit.SECONDS);
			return null;
		}).when(job).run(project);

		pipeline.start(project);   // the job blocks, yet start returns
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
		pipeline.start(project);
		pipeline.start(project);

		assertThat(pipeline.isRunning(project)).isTrue();
		release.countDown();
		await().atMost(Duration.ofSeconds(5)).until(() -> !pipeline.isRunning(project));
		verify(job, times(1)).run(project);
	}

	@Test
	void theSameProjectCanRunAgainAfterItFinished() {
		UUID project = UUID.randomUUID();

		pipeline.start(project);
		await().atMost(Duration.ofSeconds(5)).until(() -> !pipeline.isRunning(project));
		pipeline.start(project);
		await().atMost(Duration.ofSeconds(5)).until(() -> !pipeline.isRunning(project));

		verify(job, times(2)).run(project);
	}

	@Test
	void differentProjectsRunAtTheSameTime() throws InterruptedException {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		CountDownLatch bothStarted = new CountDownLatch(2);
		doAnswer(invocation -> {
			bothStarted.countDown();
			release.await(10, TimeUnit.SECONDS);
			return null;
		}).when(job).run(any());

		pipeline.start(first);
		pipeline.start(second);

		assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
	}

}
