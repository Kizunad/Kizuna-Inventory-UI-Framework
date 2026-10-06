#!/usr/bin/env python3
"""在独立生成目录运行真正的 Fabric 客户端/服务端；不打包测试模块。"""

from pathlib import Path
import os
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "build"
SERVER = BUILD / "wire-server"
CLIENT = BUILD / "wire-client"
TIMEOUT_SECONDS = 150


def main():
    # 两端成功标记必须由本轮重新生成，不能沿用以前的成功记录。
    for directory, marker in ((SERVER, "wire-server-ok.txt"), (CLIENT, "wire-client-ok.txt")):
        directory.mkdir(parents=True, exist_ok=True)
        (directory / marker).unlink(missing_ok=True)
    (SERVER / "fixture.pid").unlink(missing_ok=True)
    (SERVER / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (SERVER / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25576\nonline-mode=false\n"
        "view-distance=2\nsimulation-distance=2\nmax-players=1\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],'
        '"biome":"minecraft:plains","structures":{}}\n',
        encoding="utf-8",
    )
    command = [str(ROOT / "gradlew"), "--offline", "--console=plain"]
    with (BUILD / "wire-server.log").open("w") as server_log, (BUILD / "wire-client.log").open("w") as client_log:
        server = subprocess.Popen(command + ["runWireServer"], cwd=ROOT, stdout=server_log, stderr=subprocess.STDOUT)
        try:
            # 读取真实服务端就绪日志；超时与提前退出都按失败处理。
            deadline = time.monotonic() + TIMEOUT_SECONDS
            while "Done (" not in (BUILD / "wire-server.log").read_text(errors="replace"):
                if server.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("服务端未就绪，检查 build/wire-server.log")
                time.sleep(0.5)
            client_command = command + ["runWireClient"]
            if not os.environ.get("DISPLAY"):
                client_command = ["xvfb-run", "-a", "-s", "-screen 0 1280x720x24"] + client_command
            subprocess.run(client_command, cwd=ROOT, stdout=client_log, stderr=subprocess.STDOUT,
                           timeout=TIMEOUT_SECONDS, check=True)
            if server.wait(timeout=30) != 0:
                raise RuntimeError("服务端非正常退出，检查 build/wire-server.log")
            for directory, marker in ((SERVER, "wire-server-ok.txt"), (CLIENT, "wire-client-ok.txt")):
                if not (directory / marker).is_file():
                    raise RuntimeError(f"验收证据缺失：{directory / marker}")
                print((directory / marker).read_text().strip())
        finally:
            # Gradle 的 Java 子进程可能由 daemon 创建；只清理由本夹具写出的进程。
            if server.poll() is None:
                pid_file = SERVER / "fixture.pid"
                if pid_file.exists():
                    try:
                        os.kill(int(pid_file.read_text()), signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                server.terminate()
                try:
                    server.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()


if __name__ == "__main__":
    main()
