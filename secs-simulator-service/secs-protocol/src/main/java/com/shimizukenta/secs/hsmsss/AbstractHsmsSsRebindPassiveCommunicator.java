package com.shimizukenta.secs.hsmsss;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.shimizukenta.secs.ReadOnlyTimeProperty;

/**
 * This abstract class is implementation of HSMS-SS-Passive-rebind Communicator(SEMI-E37.1).
 * 
 * <p>
 * This class is called from {@link HsmsSsCommunicator#newInstance(HsmsSsCommunicatorConfig)<br />
 * </p>
 * 
 * @author kenta-shimizu
 *
 */
public abstract class AbstractHsmsSsRebindPassiveCommunicator extends AbstractHsmsSsPassiveCommunicator {
	
	public AbstractHsmsSsRebindPassiveCommunicator(HsmsSsCommunicatorConfig config) {
		super(config);
	}
	
	public void open() throws IOException {
		super.open();
	}
	
	public void close() throws IOException {
		super.close();
	}
	
	/** 首次绑定落定前的等待上限（毫秒）。绑定本身是本地操作，正常在毫秒级完成。 */
	private static final long BIND_SETTLE_TIMEOUT_MS = 5_000L;
	
	/** 首次绑定是否已落定（成功或失败）；后续重绑不再影响 open()。 */
	private final CountDownLatch firstBindSettled = new CountDownLatch(1);
	
	private volatile IOException firstBindError;
	
	@Override
	protected void passiveOpen() throws IOException {
		
		executeLoopTask(() -> {
			
			passiveBind();
			
			ReadOnlyTimeProperty tp = this.hsmsSsConfig().rebindIfPassive();
			if ( tp.gtZero() ) {
				tp.sleep();
			} else {
				return;
			}
		});
		
		/* 关键：首次绑定必须同步确认结果。
		   以前 bind 失败只进日志队列、open() 照常返回，于是会话报 OPEN、端口上却没有监听，
		   主机永远连不上而界面显示健康。后续断线重绑仍然静默重试（那是运行期行为）。 */
		awaitFirstBind();
	}
	
	private void awaitFirstBind() throws IOException {
		
		final boolean settled;
		try {
			settled = this.firstBindSettled.await(BIND_SETTLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while binding", e);
		}
		
		if ( ! settled ) {
			throw new IOException("passive bind did not settle in " + BIND_SETTLE_TIMEOUT_MS + " ms");
		}
		
		final IOException error = this.firstBindError;
		if ( error != null ) {
			throw error;
		}
	}
	
	/** 只在首次尝试时记录结果；之后的重绑失败不影响已经返回的 open()。 */
	private void settleFirstBind(IOException error) {
		
		if ( this.firstBindSettled.getCount() <= 0L ) {
			return;
		}
		if ( error != null ) {
			this.firstBindError = error;
		}
		this.firstBindSettled.countDown();
	}
	
	private void passiveBind() throws InterruptedException {
		
		try (
				AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open();
				) {
			
			final SocketAddress socketAddr = hsmsSsConfig().socketAddress().getSocketAddress();
			
			notifyLog(HsmsSsPassiveBindLog.tryBind(socketAddr));
			
			server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			server.bind(socketAddr);
			
			notifyLog(HsmsSsPassiveBindLog.binded(socketAddr));
			settleFirstBind(null);
			
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
					
					synchronized ( server ) {
						server.notifyAll();
					}
				}
			});
			
			synchronized ( server ) {
				
				try {
					server.wait();
				}
				finally {
					notifyLog(HsmsSsPassiveBindLog.closed(socketAddr));
				}
			}
			
		}
		catch ( IOException e ) {
			notifyLog(e);
			settleFirstBind(e);
		}
		finally {
			/* 被中断等情况下 firstBindSettled 可能还没落定：兜住，避免 open() 白等 5 秒 */
			settleFirstBind(new IOException("passive bind ended before binding"));
		}
	}
	
}
