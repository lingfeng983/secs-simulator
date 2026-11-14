import { useEffect, useState } from 'react';

/**
 * 主题版本号：主题/密度一切换就 +1。
 *
 * <p>canvas 里的曲线是用 getComputedStyle 取色画的，CSS 令牌换了颜色
 * 它不会自动重绘——订阅这个版本号，把主题变化变成一次显式重画。</p>
 */
export function useThemeVersion(): number {
  const [version, setVersion] = useState(0);

  useEffect(() => {
    const bump = () => setVersion((previous) => previous + 1);
    const observer = new MutationObserver(bump);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme', 'data-density'],
    });
    return () => observer.disconnect();
  }, []);

  return version;
}
