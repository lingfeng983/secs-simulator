package com.shimizukenta.secs;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.shimizukenta.secs.gem.Gem;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * This abstract class is implementation of SECS-communicate.
 * 
 * @author kenta-shimizu
 *
 */
public abstract class AbstractSecsCommunicator implements SecsCommunicator {
	
	private final ExecutorService execServ = Executors.newCachedThreadPool(r -> {
		Thread th = new Thread(r);
		th.setDaemon(true);
		return th;
	});
	
	protected ExecutorService executorService() {
		return execServ;
	}
	
	/**
	 * 把循环任务包成"不会死"的 Runnable。
	 *
	 * <p>原实现只捕 {@code InterruptedException}：任一监听器抛 RuntimeException
	 * 就会终结这条分发线程。线程一死，队列只进不出——报文不再分派、堆一路涨到
	 * OOM，而界面上看不到任何异常。这里记录后继续，并睡一下避免持续性故障
	 * 变成忙等自旋。
	 * </p>
	 *
	 * @param task 单次任务
	 * @return 循环任务
	 */
	protected Runnable createLoopTask(InterruptableRunnable task) {
		return new Runnable() {
			@Override
			public void run() {
				for ( ;; ) {
					try {
						task.run();
					}
					catch ( InterruptedException e ) {
						Thread.currentThread().interrupt();
						return;
					}
					catch ( RuntimeException e ) {
						notifyLog(e);
						try {
							Thread.sleep(50L);
						}
						catch ( InterruptedException ie ) {
							Thread.currentThread().interrupt();
							return;
						}
					}
				}
			}
		};
	}
	
	protected void executeLoopTask(InterruptableRunnable r) {
		execServ.execute(createLoopTask(r));
	}
	
	protected <T> T executeInvokeAny(Collection<? extends Callable<T>> tasks)
			throws InterruptedException, ExecutionException{
		return execServ.invokeAny(tasks);
	}
	
	protected <T> T executeInvokeAny(Collection<? extends Callable<T>> tasks, ReadOnlyTimeProperty timeout)
			throws InterruptedException, ExecutionException, TimeoutException {
		return execServ.invokeAny(tasks, timeout.getMilliSeconds(), TimeUnit.MILLISECONDS);
	}
	
	
	private final AbstractSecsCommunicatorConfig config;
	private final Gem gem;
	
	private boolean opened;
	private boolean closed;
	
	public AbstractSecsCommunicator(AbstractSecsCommunicatorConfig config) {
		
		this.config = config;
		this.gem = Gem.newInstance(this, config.gem());
		
		opened = false;
		closed = false;
	}
	
	@Override
	public boolean isOpen() {
		synchronized ( this ) {
			return this.opened && ! this.closed;
		}
	}
	
	@Override
	public boolean isClosed() {
		synchronized ( this ) {
			return this.closed;
		}
	}
	
	@Override
	public void open() throws IOException {
		
		synchronized ( this ) {
			
			if ( this.closed ) {
				throw new IOException("Already closed");
			}
			
			if ( this.opened ) {
				throw new IOException("Already opened");
			}
			
			this.opened = true;
		}
		
		executeLogQueueTask();
		executeMsgRecvQueueTask();
		executeTrySendMsgPassThroughQueueTask();
		executeSendedMsgPassThroughQueueTask();
		executeRecvMsgPassThroughQueueTask();
	}
	
	@Override
	public void close() throws IOException {
		
		synchronized ( this ) {
			
			if ( this.closed ) {
				return ;
			}
			
			this.closed = true;
		}
		
		try {
			execServ.shutdownNow();
			if (! execServ.awaitTermination(5L, TimeUnit.SECONDS)) {
				throw new IOException("ExecutorService#shutdown failed");
			}
		}
		catch ( InterruptedException ignore ) {
		}
	}
	
	@Override
	public Gem gem() {
		return gem;
	}
	
	@Override
	public int deviceId() {
		return config.deviceId().intValue();
	}
	
	@Override
	public boolean isEquip() {
		return config.isEquip().booleanValue();
	}
	
	@Override
	public void openAndWaitUntilCommunicating() throws IOException, InterruptedException {
		
		synchronized ( this ) {
			if ( ! isOpen() ) {
				open();
			}
		}
		
		communicatable.waitUntilTrue();
	}
	
	@Override
	public Optional<SecsMessage> send(int strm, int func, boolean wbit)
			throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException
			, InterruptedException {
		
		return send(strm, func, wbit, Secs2.empty());
	}
	
	@Override
	public Optional<SecsMessage> send(SecsMessage primary, int strm, int func, boolean wbit)
			throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException
			, InterruptedException {
		
		return send(primary, strm, func, wbit, Secs2.empty());
	}
	
	
	/* Secs-Message Receive Listener */
	private final Collection<SecsMessageReceiveListener> msgRecvListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addSecsMessageReceiveListener(SecsMessageReceiveListener l) {
		return msgRecvListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeSecsMessageReceiveListener(SecsMessageReceiveListener l) {
		return msgRecvListeners.remove(Objects.requireNonNull(l));
	}
	
	/**
	 * 报文队列上限。
	 *
	 * <p>无界队列在下游变慢或分发线程异常时只会把堆吃光（单条 8 MB 上限挡不住
	 * 累积）。超限时丢最旧的一条：宁可少几条历史，也不能让设备 OOM。
	 * W 位请求被丢时用户会看到 T3 超时，这是过载下应有的降级。
	 * </p>
	 */
	private static final int MSG_QUEUE_CAPACITY = 8192;

	/** 日志队列上限：日志比报文更可丢。 */
	private static final int LOG_QUEUE_CAPACITY = 4096;

	/** 入队；满了丢最旧再来一次。 */
	private static <T> boolean offerDroppingOldest(BlockingQueue<T> queue, T value) {
		if ( queue.offer(value) ) {
			return true;
		}
		queue.poll();
		return queue.offer(value);
	}

	private final BlockingQueue<SecsMessage> msgRecvQueue = new LinkedBlockingQueue<>(MSG_QUEUE_CAPACITY);
	
	private void executeMsgRecvQueueTask() {
		executeLoopTask(() -> {
			SecsMessage msg = msgRecvQueue.take();
			msgRecvListeners.forEach(l -> {
				/* 单个监听器的异常不能连累其它监听器与后续报文 */
				try {
					l.received(msg);
				}
				catch ( RuntimeException e ) {
					notifyLog(e);
				}
			});
		});
	}
	
	protected final boolean offerMsgRecvQueue(SecsMessage msg) {
		return offerDroppingOldest(msgRecvQueue, msg);
	}
	
	protected void notifyReceiveMessage(SecsMessage msg) {
		offerMsgRecvQueue(msg);
	}
	
	
	/* Secs-Log Receive Listener */
	private final Collection<SecsLogListener> logListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addSecsLogListener(SecsLogListener l) {
		return logListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeSecsLogListener(SecsLogListener l) {
		return logListeners.remove(Objects.requireNonNull(l));
	}
	
	private final BlockingQueue<SecsLog> logQueue = new LinkedBlockingQueue<>(LOG_QUEUE_CAPACITY);
	
	private void executeLogQueueTask() {
		
		this.executorService().execute(() -> {
			
			try {
				for ( ;; ) {
					final SecsLog log = logQueue.take();
					dispatchLog(log);
				}
			}
			catch ( InterruptedException ignore ) {
			}
			
			try {
				for ( ;; ) {
					
					final SecsLog log = logQueue.poll(100L, TimeUnit.MILLISECONDS);
					if ( log == null ) {
						break;
					}
					dispatchLog(log);
				}
			}
			catch ( InterruptedException ignore ) {
			}
		});
	}
	
	/** 逐条投递日志；单个监听器抛异常不能被当成"没写日志"而写入日志队列（自我递归）。 */
	private void dispatchLog(SecsLog log) {
		logListeners.forEach(l -> {
			try {
				l.received(log);
			}
			catch ( RuntimeException ignore ) {
				/* 记录日志的监听器自己出错时没有更安全的去处，忽略以保住分发线程 */
			}
		});
	}
	
	protected final boolean offerLogQueue(AbstractSecsLog log) {
		return offerDroppingOldest(logQueue, log);
	}
	
	protected void notifyLog(AbstractSecsLog log) {
		log.subjectHeader(this.config.logSubjectHeader().get());
		offerLogQueue(log);
	}
	
	protected void notifyLog(Throwable t) {
		
		notifyLog(new AbstractSecsThrowableLog(t) {
			
			private static final long serialVersionUID = -1271705310309086030L;
		});
	}
	
	
	/* Secs-Communicatable-State-Changed-Listener */
	private final BooleanProperty communicatable = BooleanProperty.newInstance(false);
	
	@Override
	public boolean addSecsCommunicatableStateChangeListener(SecsCommunicatableStateChangeListener l) {
		return this.communicatable.addChangeListener(l::changed);
	}
	
	@Override
	public boolean removeSecsCommunicatableStateChangeListener(SecsCommunicatableStateChangeListener l) {
		return this.communicatable.removeChangeListener(l::changed);
	}
	
	protected void notifyCommunicatableStateChange(boolean communicatable) {
		this.communicatable.set(communicatable);
	}
	
	
	/* Try-Send Secs-Message Pass-through Listener */
	private final Collection<SecsMessagePassThroughListener> trySendMsgPassThroughListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addTrySendMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return trySendMsgPassThroughListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeTrySendMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return trySendMsgPassThroughListeners.remove(Objects.requireNonNull(l));
	}
	
	private final BlockingQueue<SecsMessage> trySendMsgPassThroughQueue = new LinkedBlockingQueue<>(MSG_QUEUE_CAPACITY);
	
	private void executeTrySendMsgPassThroughQueueTask() {
		executeLoopTask(() -> {
			SecsMessage msg = trySendMsgPassThroughQueue.take();
			trySendMsgPassThroughListeners.forEach(l -> {
				try {
					l.passThrough(msg);
				}
				catch ( RuntimeException e ) {
					notifyLog(e);
				}
			});
		});
	}
	
	protected final boolean offerTrySendMsgPassThroughQueue(SecsMessage msg) {
		return offerDroppingOldest(trySendMsgPassThroughQueue, msg);
	}
	
	protected void notifyTrySendMessagePassThrough(SecsMessage msg) {
		offerTrySendMsgPassThroughQueue(msg);
	}
	
	
	/* Sended Secs-Message Pass-through Listener */
	private final Collection<SecsMessagePassThroughListener> sendedMsgPassThroughListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addSendedMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return sendedMsgPassThroughListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeSendedMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return sendedMsgPassThroughListeners.remove(Objects.requireNonNull(l));
	}
	
	private final BlockingQueue<SecsMessage> sendedMsgPassThroughQueue = new LinkedBlockingQueue<>(MSG_QUEUE_CAPACITY);
	
	private void executeSendedMsgPassThroughQueueTask() {
		executeLoopTask(() -> {
			SecsMessage msg = sendedMsgPassThroughQueue.take();
			sendedMsgPassThroughListeners.forEach(l -> {
				try {
					l.passThrough(msg);
				}
				catch ( RuntimeException e ) {
					notifyLog(e);
				}
			});
		});
	}
	
	protected final boolean offerSendedMsgPassThroughQueue(SecsMessage msg) {
		return offerDroppingOldest(sendedMsgPassThroughQueue, msg);
	}
	
	protected void notifySendedMessagePassThrough(SecsMessage msg) {
		offerSendedMsgPassThroughQueue(msg);
	}
	
	
	/* Receive Secs-Message Pass-through Listener */
	private final Collection<SecsMessagePassThroughListener> recvMsgPassThroughListeners = new CopyOnWriteArrayList<>();
	
	@Override
	public boolean addReceiveMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return recvMsgPassThroughListeners.add(Objects.requireNonNull(l));
	}
	
	@Override
	public boolean removeReceiveMessagePassThroughListener(SecsMessagePassThroughListener l) {
		return recvMsgPassThroughListeners.remove(Objects.requireNonNull(l));
	}
	
	private final BlockingQueue<SecsMessage> recvMsgPassThroughQueue = new LinkedBlockingQueue<>(MSG_QUEUE_CAPACITY);
	
	private void executeRecvMsgPassThroughQueueTask() {
		executeLoopTask(() -> {
			SecsMessage msg = recvMsgPassThroughQueue.take();
			recvMsgPassThroughListeners.forEach(l -> {
				try {
					l.passThrough(msg);
				}
				catch ( RuntimeException e ) {
					notifyLog(e);
				}
			});
		});
	}
	
	protected final boolean offerRecvMsgPassThroughQueue(SecsMessage msg) {
		return offerDroppingOldest(recvMsgPassThroughQueue, msg);
	}
	
	protected void notifyReceiveMessagePassThrough(SecsMessage msg) {
		offerRecvMsgPassThroughQueue(msg);
	}
	
}
