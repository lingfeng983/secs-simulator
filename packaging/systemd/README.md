# Linux systemd 部署

这个目录配合 `scripts/package-linux.sh` 生成的 Linux 包使用：

```bash
# 1. 在 Linux 构建（需要 JDK 25 + Node 22）
./scripts/package-linux.sh

# 2. 解压到 /opt
sudo tar -xzf package/secs-sim-2.0.0-SNAPSHOT-linux-x64.tar.gz -C /opt
sudo ln -sfn /opt/secs-sim-2.0.0-SNAPSHOT-linux-x64 /opt/secs-sim

# 3. 用户与配置
sudo useradd --system --home /opt/secs-sim --shell /usr/sbin/nologin secs
sudo cp /opt/secs-sim/secs-sim.env.example /etc/secs-sim.env
sudo editor /etc/secs-sim.env          # 改 SECS_TOKEN

# 4. systemd
sudo cp /opt/secs-sim/secs-sim.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now secs-sim
sudo systemctl status secs-sim
```

- 默认 HTTP 8080、HSMS 5000（demo 配置）。
- 日志在 `/var/lib/secs-sim/logs/`。
- `SECS_TOKEN` 必填；systemd 从 `/etc/secs-sim.env` 读。
- 换配置：改 `SECS_CONFIG` 指向 `/opt/secs-sim` 下的 JSON；重载用
  `sudo systemctl restart secs-sim`。
