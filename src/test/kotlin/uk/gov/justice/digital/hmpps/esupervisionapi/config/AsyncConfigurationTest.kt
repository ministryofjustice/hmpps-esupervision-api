package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncConfigurationTest {

  @Test
  fun `task executor drains running and queued tasks during shutdown`() {
    val contextRunner = ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration::class.java))
      .withUserConfiguration(AsyncConfiguration::class.java)
      .withPropertyValues(
        "spring.task.execution.pool.core-size=1",
        "spring.task.execution.pool.max-size=1",
        "spring.task.execution.pool.queue-capacity=10",
        "spring.task.execution.shutdown.await-termination=true",
        "spring.task.execution.shutdown.await-termination-period=5s",
      )

    contextRunner.run { context ->
      val executor = context.getBean("taskExecutor", ThreadPoolTaskExecutor::class.java)
      val runningTaskStarted = CountDownLatch(1)
      val queuedTaskStarted = CountDownLatch(1)
      val finishTask = CountDownLatch(1)
      val tasksCompleted = CountDownLatch(2)
      executor.execute {
        runningTaskStarted.countDown()
        try {
          finishTask.await()
        } finally {
          tasksCompleted.countDown()
        }
      }
      executor.execute {
        queuedTaskStarted.countDown()
        tasksCompleted.countDown()
      }

      val closeThread = Thread(context::close)
      try {
        assertThat(runningTaskStarted.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(queuedTaskStarted.count).isEqualTo(1)
        closeThread.start()
        closeThread.join(100)
        assertThat(closeThread.isAlive).isTrue()
      } finally {
        finishTask.countDown()
        if (closeThread.isAlive) closeThread.join(2_000)
      }

      assertThat(closeThread.isAlive).isFalse()
      assertThat(queuedTaskStarted.count).isEqualTo(0)
      assertThat(tasksCompleted.count).isEqualTo(0)
    }
  }
}
