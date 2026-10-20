自动更新
========

更新器读取一个 JSON 清单：

    {
      "version": "2.1.0",
      "url": "https://.../secs-sim-2.1.0-win-x64-setup.zip",
      "sha256": "...",
      "notes": "本次更新说明"
    }

只检查：

    .\check-update.cmd

设置 SECS_UPDATE_MANIFEST 后运行：

    set SECS_UPDATE_MANIFEST=https://.../latest.json
    .\check-update.cmd -Apply

安全措施
--------
- 下载后校验 SHA256；不匹配直接拒绝。
- 先备份旧安装目录，安装器失败会自动回滚。
- 新版本号必须大于当前 VERSION（除非 -Force）。
- 更新包必须是本项目生成的 setup zip（内含 install.ps1）。
- 清单与包建议走 HTTPS；代码签名仍未做。
