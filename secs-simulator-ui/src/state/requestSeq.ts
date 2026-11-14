/**
 * 过期响应守卫。
 *
 * <p>轮询、切换审计过滤、切换配置时，先发的请求可能后回来：没有序号，
 * 旧响应就会盖掉新数据（界面上表现为"刚切的过滤又跳回去了"）。
 * 发请求前取号，落地前确认自己仍是最新一期即可。</p>
 *
 * <p>配置草稿还多一层名称校验：序号最新但请求的是上一份配置，照样要丢——
 * 配置名切换与请求返回可能交错。</p>
 */
export interface RequestSeq {
  /** 取一个新号；之后所有更早的号都过期。 */
  begin(): number;
  /** 该号是否仍是最新一期。 */
  isCurrent(token: number): boolean;
}

export function createRequestSeq(): RequestSeq {
  let current = 0;
  return {
    begin(): number {
      current += 1;
      return current;
    },
    isCurrent(token: number): boolean {
      return token === current;
    },
  };
}

/**
 * 响应能否落地。
 *
 * @param token 请求时取的号
 * @param seq 共享的序号器
 * @param requestedName 请求时针对的配置名
 * @param activeName 当前配置名
 */
export function isFreshResponse(
  token: number,
  seq: RequestSeq,
  requestedName: string,
  activeName: string,
): boolean {
  return seq.isCurrent(token) && requestedName === activeName;
}
