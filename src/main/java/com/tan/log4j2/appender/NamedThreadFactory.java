package com.tan.log4j2.appender;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

class NamedThreadFactory implements ThreadFactory {

	private final String prefix;
	private final ThreadFactory threadFactory;
	private final boolean daemonThread;
	private final AtomicInteger counter = new AtomicInteger();

	public NamedThreadFactory(final String prefix, final boolean daemonThread) {
		this(prefix, daemonThread, Executors.defaultThreadFactory());
	}

	public NamedThreadFactory(final String prefix, final boolean daemonThread, final ThreadFactory threadFactory) {
		this.prefix = prefix;
		this.threadFactory = threadFactory;
		this.daemonThread = daemonThread;
	}

	public Thread newThread(Runnable r) {
		Thread t = this.threadFactory.newThread(r);
		t.setDaemon(this.daemonThread);
		t.setName(this.prefix + "-Thread-" + this.counter.incrementAndGet());
		return t;
	}

}
