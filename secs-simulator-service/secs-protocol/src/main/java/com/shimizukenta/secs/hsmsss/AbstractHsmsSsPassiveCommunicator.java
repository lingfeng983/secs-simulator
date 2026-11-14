package com.shimizukenta.secs.hsmsss;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import com.shimizukenta.secs.SecsException;

/**
 * This abstract class is implementation of HSMS-SS-Passive Communicator(SEMI-E37.1).
 * 
 * <p>
 * This class is called from {@link HsmsSsCommunicator#newInstance(HsmsSsCommunicatorConfig)<br />
 * </p>
 * 
 * @author kenta-shimizu
 *
 */
public abstract class AbstractHsmsSsPassiveCommunicator extends AbstractHsmsSsCommunicator {

	private AsynchronousServerSocketChannel server;
	
	public AbstractHsmsSsPassiveCommunicator(HsmsSsCommunicatorConfig config) {
		super(Objects.requireNonNull(config));
		
		server = null;
	}
	
	@Override
	public void open() throws IOException {
		
		if ( hsmsSsConfig().protocol().get() != HsmsSsProtocol.PASSIVE ) {
			throw new IOException("HsmsSsCommunicatorConfig#protocol is not PASSIVE");
		}
		
		super.open();
		
		try {
			passiveOpen();
		}
		catch ( IOException e ) {
			/* super.open() 已经置 opened 并起了 5 条分发线程：绑定失败必须自己回滚。
			   否则调用方拿到异常，却留下一个“半个打开”的通信器（线程与 channel 泄漏）。 */
			try {
				close();
			}
			catch ( IOException giveup ) {
			}
			throw e;
		}
	}
	
	@Override
	public void close() throws IOException {
		
		IOException ioExcept = null;
		
		try {
			synchronized ( this ) {
				if ( isClosed() ) {
					return;
				}
				
				super.close();
			}
		}
		catch ( IOException e ) {
			ioExcept = e;
		}
		
		synchronized ( this ) {
			
			if ( server != null ) {
				
				SocketAddress addr = null;
				
				try {
					addr = server.getLocalAddress();
				}
				catch ( IOException giveup ) {
				}
				
				try {
					server.close();
					
					if ( addr != null ) {
						notifyLog(HsmsSsPassiveBindLog.closed(addr));
					}
				}
				catch ( IOException e ) {
					ioExcept = e;
				}
			}
		}
		
		if ( ioExcept != null) {
			throw ioExcept;
		}
	}
	
	protected void passiveOpen() throws IOException {
		
		synchronized ( this ) {
			this.server = AsynchronousServerSocketChannel.open();
		}
		
		final SocketAddress socketAddr = hsmsSsConfig().socketAddress().getSocketAddress();
		
		notifyLog(HsmsSsPassiveBindLog.tryBind(socketAddr));
		
		server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		server.bind(socketAddr);
		
		notifyLog(HsmsSsPassiveBindLog.binded(socketAddr));
		
		server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
			
			@Override
			public void completed(AsynchronousSocketChannel channel, Void attachment) {
				server.accept(attachment, this);
				
				SocketAddress local = null;
				SocketAddress remote = null;
				
				try {
					local = channel.getLocalAddress();
					remote = channel.getRemoteAddress();
					
					notifyLog(HsmsSsConnectionLog.accepted(local, remote));
					
					completedAction(channel);
				}
				catch ( IOException e ) {
					notifyLog(e);
				}
				catch ( InterruptedException ignore ) {
				}
				finally {
					
					try {
						channel.shutdownOutput();
					}
					catch ( IOException ignore ) {
					}
					
					try {
						channel.close();
					}
					catch ( IOException e ) {
						notifyLog(e);
					}
					
					notifyLog(HsmsSsConnectionLog.closed(local, remote));
				}
			}
			
			@Override
			public void failed(Throwable t, Void attachment) {
				
				if ( ! (t instanceof ClosedChannelException) ) {
					notifyLog(t);
				}
			}
		});
	}
	
	protected void completedAction(AsynchronousSocketChannel channel) throws InterruptedException {
		
		try {
			final PassiveInnerConnection conn = new PassiveInnerConnection(channel);

			final Collection<Callable<Void>> tasks = Arrays.asList(
					() -> {
						try {
							conn.reading();
						}
						catch ( InterruptedException ignore ) {
						}
						return null;
					},
					() -> {
						try {
							conn.mainTask();
						}
						catch ( InterruptedException ignore ) {
						}
						return null;
					}
					);
			
			this.executeInvokeAny(tasks);
		}
		catch ( ExecutionException e ) {

			Throwable t = e.getCause();

			if ( t instanceof RuntimeException ) {
				throw (RuntimeException)t;
			}

			notifyLog(t);
		}
	}
	
	protected class PassiveInnerConnection extends AbstractInnerConnection {

		protected PassiveInnerConnection(AsynchronousSocketChannel channel) {
			super(channel);
		}
		
		protected void mainTask() throws InterruptedException {
			
			try {
				for ( ;; ) {
					
					if ( ! awaitSelect() ) {
						return;
					}
					
					notifyHsmsSsCommunicateStateChange(HsmsSsCommunicateState.SELECTED);
					
					if ( ! runSelectedTasks() ) {
						return;
					}
					/* Deselect：已回 Deselect.rsp 并降为 NOT_SELECTED，TCP 保持不动，
					   回到等 SELECT——主机经常在 Deselect 后立刻重新 Select。 */
				}
			}
			finally {
				/* 状态由"当前选中连接"派生：只有选中连接自己结束时才降级。
				   第二条 TCP（扫描/重连重叠/空闲探测）结束不能影响已选中的链路。
				   降级与通知在 removeSelectedConnection 里原子完成。 */
				removeSelectedConnection(this);
			}
		}
		
		/** 等到本连接被 SELECT（T7）。返回 {@code false} 表示连接应当关闭。 */
		private boolean awaitSelect() throws InterruptedException {
			
			final Collection<Callable<Boolean>> tasks = Arrays.asList(
					() -> {
						try {
							return connectTask();
						}
						catch ( InterruptedException ignore ) {
						}
						
						return Boolean.FALSE;
					}
					);
			
			try {
				return executeInvokeAny(
						tasks,
						hsmsSsConfig().timeout().t7()
						).booleanValue();
			}
			catch ( TimeoutException e ) {
				notifyLog(new HsmsSsTimeoutT7Exception(e));
				return false;
			}
			catch ( ExecutionException e ) {
				
				Throwable t = e.getCause();
				
				if ( t instanceof RuntimeException ) {
					throw (RuntimeException)t;
				}
				
				notifyLog(t);
				return false;
			}
		}
		
		/**
		 * SELECTED 态的服务循环。
		 *
		 * @return {@code true} 表示收到 Deselect（连接保持、回到等 SELECT）；
		 *         {@code false} 表示连接应当关闭（Separate / 出错）
		 */
		private boolean runSelectedTasks() throws InterruptedException {
			
			final Collection<Callable<Boolean>> tasks = Arrays.asList(
					() -> {
						try {
							return selectedTask();
						}
						catch ( InterruptedException ignore ) {
						}
						catch ( SecsException e ) {
							notifyLog(e);
						}
						return Boolean.FALSE;
					},
					() -> {
						try {
							linktesting();
						}
						catch ( InterruptedException ignore ) {
						}
						return Boolean.FALSE;
					}
					);
			
			try {
				return executeInvokeAny(tasks).booleanValue();
			}
			catch ( ExecutionException e ) {
				
				Throwable t = e.getCause();
				
				if ( t instanceof RuntimeException ) {
					throw (RuntimeException)t;
				}
				
				notifyLog(t);
				return false;
			}
		}
		
		protected Boolean connectTask() throws InterruptedException, SecsException {
			
			for ( ;; ) {
				
				final HsmsSsMessage msg = this.takeReceiveMessage();
				
				switch ( HsmsSsMessageType.get(msg) ) {
				case DATA: {
					
					send(createRejectRequest(msg, HsmsSsMessageRejectReason.NOT_SELECTED));
					break;
				}
				case SELECT_REQ: {
					
					boolean f = addSelectedConnection(this);
					
					if ( f /* success */) {
						
						send(createSelectResponse(msg, HsmsSsMessageSelectStatus.SUCCESS));
						
						return Boolean.TRUE;
						
					} else {
						
						send(createSelectResponse(msg, HsmsSsMessageSelectStatus.ALREADY_USED));
					}
					
					break;
				}
				case LINKTEST_REQ: {
					
					send(createLinktestResponse(msg));
					break;
				}
				case SEPARATE_REQ: {
					
					return Boolean.FALSE;
					/* break; */
				}
				case SELECT_RSP:
				case DESELECT_RSP:
				case LINKTEST_RSP: {
					
					/* 非应答期收到应答类控制帧：E37 要求回 Reject.req(TRANSACTION_NOT_OPEN) */
					send(createRejectRequest(msg, HsmsSsMessageRejectReason.TRANSACTION_NOT_OPEN));
					break;
				}
				case REJECT_REQ: {
					
					/* Reject.req 是请求类消息（SType=7，无 .rsp）且为终点消息：
					   再回 Reject 会因 SystemBytes 回带而对端互回，所以只吸收不作答 */
					break;
				}
				case DESELECT_REQ: {
					
					/* 未选中时收到 Deselect.req：按 E37 回 status=1（通信未建立），连接保持 */
					send(createDeselectResponse(msg, (byte)1));
					break;
				}
				default: {
					
					if ( HsmsSsMessageType.supportSType(msg) ) {
						
						if ( ! HsmsSsMessageType.supportPType(msg) ) {
							
							send(createRejectRequest(msg, HsmsSsMessageRejectReason.NOT_SUPPORT_TYPE_P));
						}
						
					} else {
						
						send(createRejectRequest(msg, HsmsSsMessageRejectReason.NOT_SUPPORT_TYPE_S));
					}
				}
				}
			}
		}
		
		/**
		 * SELECTED 态的收报文循环。
		 *
		 * @return {@code true} 表示收到 Deselect（回 NOT_SELECTED，连接保持）；
		 *         {@code false} 表示连接应当关闭（Separate）
		 */
		protected boolean selectedTask() throws InterruptedException, SecsException {
			
			for ( ;; ) {
				
				final HsmsSsMessage msg = this.takeReceiveMessage();
				
				switch ( HsmsSsMessageType.get(msg) ) {
				case DATA: {
					
					notifyReceiveMessage(msg);
					break;
				}
				case SELECT_REQ: {
					
					send(createSelectResponse(msg, HsmsSsMessageSelectStatus.ACTIVED));
					break;
				}
				case LINKTEST_REQ: {
					
					send(createLinktestResponse(msg));
					break;
				}
				case SEPARATE_REQ: {
					
					return false;
					/* break; */
				}
				case DESELECT_REQ: {
					
					/* E37 7.4：SELECTED 态收到 Deselect.req 必须回 Deselect.rsp(status=0)，
					   并迁移到 NOT_SELECTED。以前这里落到 default 只做 SType 检查、什么也不发，
					   对端等到 T6 超时判通信失败，而本端仍认为自己 SELECTED。 */
					send(createDeselectResponse(msg));
					removeSelectedConnection(this, HsmsSsCommunicateState.NOT_SELECTED);
					return true;
				}
				case SELECT_RSP:
				case DESELECT_RSP:
				case LINKTEST_RSP: {
					
					/* 非应答期收到应答类控制帧：E37 要求回 Reject.req(TRANSACTION_NOT_OPEN) */
					send(createRejectRequest(msg, HsmsSsMessageRejectReason.TRANSACTION_NOT_OPEN));
					break;
				}
				case REJECT_REQ: {
					
					/* Reject.req 是请求类消息（SType=7，无 .rsp）且为终点消息：
					   再回 Reject 会因 SystemBytes 回带而对端互回，所以只吸收不作答 */
					break;
				}
				default: {
					
					if ( HsmsSsMessageType.supportSType(msg) ) {
						
						if ( ! HsmsSsMessageType.supportPType(msg) ) {
							
							send(createRejectRequest(msg, HsmsSsMessageRejectReason.NOT_SUPPORT_TYPE_P));
						}
						
					} else {
						
						send(createRejectRequest(msg, HsmsSsMessageRejectReason.NOT_SUPPORT_TYPE_S));
					}
				}
				}
			}
		}
	}
	
}
