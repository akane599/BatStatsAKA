[English](README.md) | [简体中文](README.zh-CN.md) | [日本語](README.ja.md)

# BatStats

![横幅](fastlane/metadata/android/en-US/images/banner.svg)

BatStats 显示 Android 报告的电池读数，并记录实际观察到的充放电过程。普通读数无需特权；高级统计需要 Shizuku、Root 或通过 ADB 授予的权限。无法获取的读数不会被当作零耗电。

此开发分支面向 Android 16/API 36。实现进度及实际测试结果见 [PROGRESS.md](PROGRESS.md) 和 [验证记录](docs/VALIDATION.md)。Samsung 实机行为尚未验证。

## 访问方式与读数含义

启动 Shizuku 后，在应用内授权 BatStats。Shizuku 正在运行且已授权时会优先使用它；连接中断后仍可查看普通电池信息。shell 模式不保证能读取需要 Root 的受保护内核文件。ADB 设置命令见 [英文说明](README.md#advanced-access)。

- 本地观察从开始监测或重置观察时起算，不会补算开始前或缺失区间的耗电。
- 息屏包含非交互状态的常亮显示（AOD）。息屏、CPU 挂起和 Android Doze 是不同的指标。
- 电荷变化来自 Android 电荷计数器的差值。由电压计算的能量及剩余时间都是估算，受设备实现与校准情况影响。
- 每个应用的 mAh 是 Android 统计区间内的估算电荷消耗，不是精确的能量测量。共享 UID 的应用无法准确拆分耗电；活动次数多或持续时间长本身不能证明某个应用导致异常耗电。

## 监测、历史与安装

监测通知保留实用的详细信息，并保持安静。普通采样默认为30秒，高级统计默认为5分钟。缩短间隔可提高响应速度，也会增加采集工作量。尚未测得实际节电百分比。电池提醒仅在监测期间运行，声音和振动由 Android 通知设置控制。

JSON/CSV 导出包含单位、UTC 时间、数据来源和统计区间。清空历史会停止监测，但不会重置 Android 系统电池统计。自动备份只包含设置，保留历史需要手动导出。报告仅在你主动选择目标或分享后离开设备。

手机构建下载及签名兼容说明见 [构建与安装指南](docs/BUILD_AND_INSTALL.md)。Preview 使用独立包名，可与原版共存。覆盖更新需要相同包名及兼容的签名；不同工作流运行生成的临时签名可能不兼容。

## 参与贡献
Issue 和 PR 应提供 Android 版本、访问模式、统计区间、复现步骤及已执行的测试。请勿无意中分享个人应用使用数据。

## 许可证
有关详细信息，请参阅 [LICENSE](LICENSE) 文件。
