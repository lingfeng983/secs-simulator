/*
 * 首帧主题：在样式应用前把 light/dark 与 density 定下来，避免深色用户先看到一帧白屏。
 *
 * 单独成文件（而不是 index.html 里的内联 <script>）是为了能配 CSP
 * script-src 'self'：内联脚本会让严格的 CSP 直接拦掉它，首帧主题就失效。
 */
(function () {
  try {
    var stored = JSON.parse(localStorage.getItem('secs-sim-ui:preferences') || '{}');
    var theme = stored.theme === 'light' || stored.theme === 'dark'
      ? stored.theme
      : (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
    if (stored.density === 'compact' || stored.density === 'comfortable') {
      document.documentElement.dataset.density = stored.density;
    }
  } catch (error) {
    document.documentElement.dataset.theme = 'light';
  }
})();
