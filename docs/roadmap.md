# 实施与验收记录

2026-10-06，公共框架已补充窗口表现、HUD 固定、SVG、模型相机和本地背景能力。2026-10-07，通信契约迁移到 Protobuf wire v2，并补充生成、分发和跨语言验证。当前是可构建的预发布实现，尚无稳定版本资产；接入和扩展 API 的现行说明维护在 [Wiki](wiki/Home.md)，框架与子 JAR 职责见[迁移清单](wiki/Framework-Boundaries.md)。

## 已实现

- Java 17 独立构建，框架、源码及独立 demo JAR；主框架不依赖 demo。
- 模块依赖、命名空间、注册原子校验；窗口／功能／HUD／栏位／主题／背景注册及消息契约绑定。
- 工作台搜索／分页／固定入口，动态窗口栏，单例及对象实例，拖动／文本尺寸调整／最小化与恢复动画／固定到 HUD，scope 清理。
- 不可变库存快照、共享状态、稀疏占格校验、原子增量和完整重同步回调。
- 跨窗口／容器拖放、旋转、分堆数量选择、取消与版本失效；独立装备槽来源、通用自定义落点。
- 可扩展图标／边框／tooltip／右键菜单／双击详情／快速操作，通用数值条、效果条和模型预览画布（水平／俯仰、自动旋转、聚焦过渡）。
- 动态栏位内容／拖放／清除回调、同源 HUD 投影、HUD 锚点／拖动／缩放／显隐／复位。
- 灰绿渐变与浅金主题、图标控制、本地背景目录与缩略图、SVG 注册／解析／绘制缓存；窗口、HUD、固定入口、动画和外观偏好保存。
- Protobuf wire v2、公共 `.proto` 与代码生成、独立协议包、Fabric 统一通道、类型协商、模块路由、结构化错误、请求结果／超时和连接代数隔离；替代原 JSON wire v1。

## 当前验证

Java 17：

- `./gradlew test build compileWireSmokeJava --offline`：2026-10-07 以 Java 17 验证，68 个测试通过，零失败／错误／跳过，框架／源码／demo／proto ZIP 可构建。
- `xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runSmoke --offline`：真实客户端加载、接受／拒绝移动、跨窗投递、分堆总数守恒、底栏绑定不改库存、主题／HUD、公共组件／模型绘制、关闭取消拖动与 scope 清理；新增尺寸输入、标题栏固定、最小化隐藏 HUD、恢复保留实例及背景卡片。
- `python3 scripts/wire_smoke.py`：2026-10-07 重新验证 Protobuf v2，独立 Fabric 服务端实测协商、状态事件、请求接受／拒绝、MODULE_MISSING 回包和关闭工作台后的 HUD 绘制。
- 同份公共 proto 生成 Python 消息：Python REQUEST → Java `UiWire` / `InventoryWire` → Python 解析 Java RESULT 的双向互通通过；同时验证增量目录 presence 和描述符包内容。这是本地跨语言编解码验证，不代表 Python 生产服务器已接入。
- `python3 scripts/wiki.py check`：Wiki 页面和内部链接检查。
- JAR 内容核验：框架产物不含 demo 类，公共源码不引用接入项目的包、业务模型或资源。

截图及日志位于 `build/smoke/` 和 `build/framework-smoke.log`，不纳入源码。此次客户端验证为 Linux Xvfb；先前的 Windows Native 演示属于旧构建，不作为本次新增能力的运行证据。

通信单测使用编码后的内存传输，验证协商、事件、请求结果、坏载荷、缺失模块、超时和旧连接隔离。实际 Fabric 通道往返证据位于 `build/wire-server/` 和 `build/wire-client/`，日志为 `build/wire-server.log`、`build/wire-client.log`。这不代替接入项目的生产业务联机验收。

## 后续范围

- 接入项目在子 JAR 内实现具体库存／装备／技能／制作等窗口和服务器事务，并完成端到端联调。
- 正式发布前冻结公共 API 和 wire，核对源码／资源授权、版本资产、干净安装及兼容矩阵。
- 可注册布局尺寸／动画曲线、独立外观预览事务属于后续增强；当前采用共享尺寸常量与动画开关。
- 服务器动态增删已注册槽位及运行中加载／卸载 JAR 不属于当前 API。

上述后续项不通过把业务实现移入框架来解决。没有业务子 JAR 时，框架提供可用的工作台外壳、管理入口和扩展 API。
