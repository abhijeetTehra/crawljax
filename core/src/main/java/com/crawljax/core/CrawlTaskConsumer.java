package com.crawljax.core;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crawljax.core.state.StateVertex;
import com.crawljax.di.CoreModule.ConsumersDoneLatch;
import com.crawljax.di.CoreModule.RunningConsumers;
import com.google.inject.Inject;

/**
 * Consumes {@link CrawlTask}s it gets from the {@link CrawlQueueManager}. It delegates the actual
 * browser interactions to a {@link NewCrawler} whom it has a 1 to 1 relation with.
 */
public class CrawlTaskConsumer implements Callable<Void> {

	private static final Logger LOG = LoggerFactory.getLogger(CrawlTaskConsumer.class);

	private final AtomicInteger runningConsumers;

	private final CountDownLatch consumersDoneLatch;

	private final NewCrawler crawler;

	private final UnfiredCandidateActions candidates;

	@Inject
	CrawlTaskConsumer(UnfiredCandidateActions candidates,
	        @RunningConsumers AtomicInteger runningConsumers,
	        @ConsumersDoneLatch CountDownLatch consumersDoneLatch, NewCrawler crawler) {
		this.candidates = candidates;
		this.runningConsumers = runningConsumers;
		this.consumersDoneLatch = consumersDoneLatch;
		this.crawler = crawler;

	}

	@Override
	public Void call() {
		try {
			while (!Thread.interrupted()) {
				try {
					CrawlTask crawlTask = candidates.awaitNewTask();
					int activeConsumers = runningConsumers.incrementAndGet();
					LOG.debug("There are {} active consumers", activeConsumers);
					handleTask(crawlTask);
					if (runningConsumers.decrementAndGet() == 0) {
						LOG.debug("No consumers active. Crawl is done. Shutting down...");
						consumersDoneLatch.countDown();
						break;
					}
				} catch (InterruptedException e) {
					throw e;
				} catch (RuntimeException e) {
					LOG.error("Cound not complete state crawl: " + e.getMessage(), e);
				}
			}
			crawler.close();
		} catch (InterruptedException e) {
			LOG.info("Consumer interrupted");
			crawler.close();
		}
		return null;
	}

	private void handleTask(CrawlTask crawlTask) {
		LOG.debug("Handling task {}", crawlTask);
		crawler.execute(crawlTask);
		LOG.debug("Task executed. Returning to queue polling");
	}

	/**
	 * This method calls the index state. It should be called once in order to setup the crawl.
	 * 
	 * @return The initial state.
	 */
	public StateVertex crawlIndex() {
		return crawler.crawlIndex();
	}

}