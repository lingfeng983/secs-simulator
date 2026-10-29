package com.shimizukenta.secs.hsmsss;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.shimizukenta.secs.SecsException;

/**
 * This abstract class is implementation of HSMS-SS-Active-Communicator(SEMI-E37.1).
 * 
 * <p>
 * This class is called from {@link HsmsSsCommunicator#newInstance(HsmsSsCommunicatorConfig)<br />
 * </p>
 * 
 * @author kenta-shimizu
 *
 */
public abstract class AbstractHsmsSsActiveCommunicator extends AbstractHsmsSsCommunicator {

	public AbstractHsmsSsActiveCommunicator(HsmsSsCommunicatorConfig config) {
		super(Objects.requireNonNull(config));
	}
	
	@Override
	public void open() throws IOException {
		
		if ( hsmsSsConfig().protocol().get() != HsmsSsProtocol.ACTIVE ) {
			throw new IOException("HsmsSsCommunicatorConfig#protocol is not ACTIVE");
		}
		
		super.open();
		
		executeLoopTask(() -> {
			
			activeCircuit();
			
			hsmsSsConfig().timeout().t5().sleep();
		});
	}
	
	@Override
	public void close() throws IOException {
		super.close();
	}
	
	protected void activeCircuit() throws InterruptedException {
		
		try (
				AsynchronousSocketChannel channel = AsynchronousSocketChannel.open();
				) {
			
			final SocketAddress socketAddr = hsmsSsConfig().socketAddress().getSocketAddress();
			
			final AtomicBoolean circuitClosed = new AtomicBoolean(false);
			
			try {
				notifyLog(HsmsSsConnectionLog.tryConnect(socketAddr));

				channel.connect(socketAddr, null, new CompletionHandler<Void, Void>(){
					
					@Override
					public void completed(Void none, Void attachment) {
						
						SocketAddress local = null;
						SocketAddress remote = null;
						
						try {
							local = channel.getLocalAddress();
							remote = channel.getRemoteAddress();
							
							notifyLog(HsmsSsConnectionLog.connected(local, remote));
							
							completionAction(channel);
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
						catch (IOException giveup) {
						}
						
						synchronized ( channel ) {
							circuitClosed.set(true);
							channel.notifyAll();
						}
						
						notifyLog(HsmsSsConnectionLog.closed(local, remote));
						}
					}
					
					@Override
					public void failed(Throwable t, Void attachment) {
						
						if ( ! (t instanceof ClosedChannelException) ) {
							notifyLog(t);
						}
						
						synchronized ( channel ) {
							circuitClosed.set(true);
							channel.notifyAll();
						}
					}
				});
				
			synchronized ( channel ) {
				
				/* Wait until the whole circuit finishes (completionAction returns),
				 * waking up every second to check isClosed(), so that close()
				 * never hangs this loop even if the notifyAll is missed.
				 * 
				 * NOTE: the flag is set when the connection ENDS, not when the
				 * connect completes: keying off connect-completion would return
				 * from here while completionAction is still running the live
				 * connection, firing NOT_CONNECTED prematurely and tearing the
				 * connection down (SessionTraceTest +1000ms 掉线回归的根因). */
				while ( ! circuitClosed.get() && ! isClosed() ) {
					channel.wait(1000L);
				}
			}
			}
			finally {
				notifyHsmsSsCommunicateStateChange(HsmsSsCommunicateState.NOT_CONNECTED);
			}
		}
		catch ( IOException e ) {
			notifyLog(e);
		}
	}
	
	private void completionAction(AsynchronousSocketChannel channel) throws InterruptedException {
		
		final ActiveInnerConnection conn = new ActiveInnerConnection(channel);
		
		try {
			
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
					});
			
			this.executeInvokeAny(tasks);
		}
		catch ( ExecutionException e ) {
			
			Throwable t = e.getCause();
			
			if ( t instanceof RuntimeException ) {
				throw (RuntimeException)t;
			}
			
			this.notifyLog(t);
		}
	}
	
	protected class ActiveInnerConnection extends AbstractInnerConnection {

		protected ActiveInnerConnection(AsynchronousSocketChannel channel) {
			super(channel);
		}
		
		protected void mainTask() throws InterruptedException {
			
			notifyHsmsSsCommunicateStateChange(HsmsSsCommunicateState.NOT_SELECTED);
			
			try {
				HsmsSsMessageSelectStatus ss = send(createSelectRequest())
						.map(HsmsSsMessageSelectStatus::get)
						.orElse(HsmsSsMessageSelectStatus.NOT_SELECT_RSP);
			
				switch ( ss ) {
				case SUCCESS:
				case ACTIVED: {
					
					/* to next Loop */
					break;
				}
				default: {
					return;
				}
				}
			}
			catch ( SecsException e ) {
				notifyLog(e);
				return;
			}
			
			try {
				
				if ( ! addSelectedConnection(this) ) {
					return;
				}
				
				notifyHsmsSsCommunicateStateChange(HsmsSsCommunicateState.SELECTED);
				
				final Collection<Callable<Void>> tasks = Arrays.asList(
						() -> {
							try {
								this.linktesting();
							}
							catch ( InterruptedException ignore ) {
							}
							return null;
						},
						() -> {
							try {
								this.receivingMsgTask();
							}
							catch ( InterruptedException ignore ) {
							}
							catch ( SecsException e ) {
								notifyLog(e);
							}
							return null;
						});
				
				executeInvokeAny(tasks);
			}
			catch ( ExecutionException e ) {
				
				Throwable t = e.getCause();
				
				if ( t instanceof RuntimeException ) {
					throw (RuntimeException)t;
				}
				
				notifyLog(t);
			}
			finally {
				/* 先注销再降级（通知在原子方法内部完成）：反过来会让 send() 在
				   "状态还没降、连接已关"的窗口里报成写失败，而不是语义正确的"未连接"。 */
				if ( ! removeSelectedConnection(this)
						&& hsmsSsCommunicateState() == HsmsSsCommunicateState.NOT_SELECTED ) {
					/* Deselect 之后连接关闭：从 NOT_SELECTED 收尾到 NOT_CONNECTED。
					   选中连接结束时，removeSelectedConnection 内部已经发过 NOT_CONNECTED。 */
					notifyHsmsSsCommunicateStateChange(HsmsSsCommunicateState.NOT_CONNECTED);
				}
			}
			
		}
		
		protected void receivingMsgTask() throws InterruptedException, SecsException {
			
			for ( ;; ) {
				
				final HsmsSsMessage msg = this.takeReceiveMessage();
				
				switch ( HsmsSsMessageType.get(msg) ) {
				case DATA: {
					
					notifyReceiveMessage(msg);
					break;
				}
				case SELECT_REQ: {
					
					/* SELECT.req 是合法的 S-Type：主动端本就已经建立了选中连接，
					   应回 Select.rsp(ACTIVED)，而不是用"不支持的 SType"误导对端排障 */
					send(createSelectResponse(msg, HsmsSsMessageSelectStatus.ACTIVED));
					break;
				}
				case LINKTEST_REQ: {
					
					send(createLinktestResponse(msg));
					break;
				}
				case SEPARATE_REQ: {
					
					return;
					/* break; */
				}
				case SELECT_RSP:
				case DESELECT_RSP:
				case LINKTEST_RSP:
				case REJECT_REQ:
				default: {
					
					/* ignore */
				}
				case DESELECT_REQ: {
					
					/* E37 7.4：收到 Deselect.req 必须应答并离开 SELECTED。
					   主动端随后会关闭连接并按 T5 重连，所以这里只降状态、不拆链路。 */
					send(createDeselectResponse(msg));
					removeSelectedConnection(this, HsmsSsCommunicateState.NOT_SELECTED);
					return;
				}
				}
			}
		}
	}
	
}
