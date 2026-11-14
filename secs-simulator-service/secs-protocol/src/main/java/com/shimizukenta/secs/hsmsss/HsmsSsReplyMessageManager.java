package com.shimizukenta.secs.hsmsss;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.shimizukenta.secs.ReadOnlyTimeProperty;

public class HsmsSsReplyMessageManager {
	
	private final Map<Integer, Pack> packMap = new HashMap<>();
	
	public HsmsSsReplyMessageManager() {
		/* Nothing */
	}
	
	public void clear() {
		synchronized ( this ) {
			this.packMap.clear();
		}
	}
	
	public void entry(HsmsSsMessage primaryMsg) {
		synchronized ( this ) {
			this.packMap.put(primaryMsg.systemBytesKey(), Pack.of(primaryMsg));
		}
	}
	
	public void exit(HsmsSsMessage primaryMsg) {
		synchronized ( this ) {
			final Integer key = primaryMsg.systemBytesKey();
			final Pack p = this.packMap.get(key);
			/* 只删除自己登记的那一条：SystemBytes 只有 16 位、回绕后两个在途事务会同 key，
			   按 key 直接删会把别人的 Pack 删掉，让那条事务白等一个 T3。 */
			if ( p != null && p.belongsTo(primaryMsg) ) {
				this.packMap.remove(key);
			}
		}
	}
	
	private Pack getPack(HsmsSsMessage msg) {
		synchronized ( this ) {
			return getPack(msg.systemBytesKey());
		}
	}
	
	private Pack getPack(Integer key) {
		synchronized ( this ) {
			return this.packMap.get(key);
		}
	}
	
	public Optional<HsmsSsMessage> reply(HsmsSsMessage primaryMsg, long timeout, TimeUnit unit) throws InterruptedException {
		
		final Pack p = getPack(primaryMsg);
		
		if ( p != null ) {
		
			synchronized ( p ) {
				
				{
					HsmsSsMessage r = p.replyMsg();
					if ( r != null ) {
						return Optional.of(r);
					}
				}
				
				unit.timedWait(p, timeout);
				
				{
					HsmsSsMessage r = p.replyMsg();
					if ( r != null ) {
						return Optional.of(r);
					}
				}
			}
		}
		
		return Optional.empty();
	}
	
	public Optional<HsmsSsMessage> reply(HsmsSsMessage primaryMsg, ReadOnlyTimeProperty timeout) throws InterruptedException {
		return this.reply(primaryMsg, timeout.getMilliSeconds(), TimeUnit.MILLISECONDS);
	}
	
	/**
	 * Return {@code Optional.empty()} if has primaryMsg, otherwise Optional has value.
	 * 
	 * @param msg
	 * @return Return {@code Optional.empty()} if has primaryMsg, otherwise Optional has value
	 */
	public Optional<HsmsSsMessage> put(HsmsSsMessage msg) {
		
		/* 只有 secondary（偶数 function）能当应答消费。SystemBytes 只有 4 字节且由
		   发送端自增，和我们等待中的 W 位请求撞号完全可能；碰撞时只比 key 会
		   把主机的 primary 当成"应答"吞掉。控制帧 getStream()/getFunction() 为 -1，
		   不属于 secondary，直接走原路径。 */
		if ( msg.getStream() >= 0 && msg.getFunction() >= 0 && (msg.getFunction() % 2) != 0 ) {
			return Optional.of(msg);
		}
		
		final Pack p = getPack(msg);
		
		if ( p == null ) {
			
			return Optional.of(msg);
			
		} else if ( ! p.matches(msg) ) {
			
			/* SystemBytes 只有 4 字节、16 位计数器会回绕：key 撞上不等于"这就是
			   我在等的应答"。Stream/Function 对不上的消息必须继续走接收路径，
			   否则一条无关报文会被当成应答吞掉，等待方还会用错报文解析。 */
			return Optional.of(msg);
			
		} else {
			
			synchronized ( p ) {
				
				p.putReplyMsg(msg);
				p.notifyAll();
				
				return Optional.empty();
			}
		}
	}
	
	private static class Pack {
		
		/** 登记这条 Pack 的主报文；exit 用它做引用比对。 */
		private final HsmsSsMessage primary;
		
		/** 控制帧（SELECT/LINKTEST…）没有 stream/function，只能按 SystemBytes 匹配。 */
		private final boolean control;
		
		private final int stream;
		
		/** 期望的应答 function（primary + 1）。 */
		private final int function;
		
		private HsmsSsMessage replyMsg;
		
		static Pack of(HsmsSsMessage primary) {
			final int stream = primary.getStream();
			final int function = primary.getFunction();
			if ( stream < 0 || function < 0 ) {
				return new Pack(primary, true, -1, -1);
			}
			return new Pack(primary, false, stream, function + 1);
		}
		
		boolean belongsTo(HsmsSsMessage msg) {
			return this.primary == msg;
		}
		
		private Pack(HsmsSsMessage primary, boolean control, int stream, int function) {
			this.primary = primary;
			this.control = control;
			this.stream = stream;
			this.function = function;
			this.replyMsg = null;
		}
		
		public boolean matches(HsmsSsMessage msg) {
			if ( this.control ) {
				return true;
			}
			return msg.getStream() == this.stream && msg.getFunction() == this.function;
		}
		
		public void putReplyMsg(HsmsSsMessage msg) {
			synchronized ( this ) {
				this.replyMsg = msg;
				this.notifyAll();
			}
		}
		
		public HsmsSsMessage replyMsg() {
			synchronized ( this ) {
				return this.replyMsg;
			}
		}
	}
}
