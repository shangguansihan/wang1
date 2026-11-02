# 小仙女要刻录（PhoneCdBurner）

> 作者：王紫靖（小仙女）

一个安卓 App：把手机通过 OTG 直连 USB 光盘刻录机，把手机里的文件/文件夹刻成**资料数据光盘（ISO 9660）**。

支持两种用法（你要的两个都要）：
- **CD-R 一次性刻录**：选好文件一次刻满，封盘后不可追加。
- **CD-RW 擦除并重刻**：反复擦除、重新刻录，用于更新备份盘。

全程不依赖电脑，纯手机控制刻录机。

---

## 技术原理

安卓没有光驱内核驱动，所以不走系统 mount，而是在**用户态**直接跟刻录机对话：

1. 用 **USB Host API** 认领刻录机的 USB 接口（Mass Storage 类）。
2. 用 **Bulk-Only Transport**（CBW → 数据 → CSW）发送裸 **SCSI/MMC 命令**：
   - `INQUIRY` / `TEST UNIT READY` / `READ CAPACITY` / `REQUEST SENSE`：探测设备与状态
   - `MODE SELECT`：设置写参数（SAO、数据轨、2048 字节/扇区）
   - `WRITE(10)`：按 2048 字节/扇区把整张 ISO 灌进光盘
   - `CLOSE TRACK/SESSION`：封盘，使盘放进任意光驱都能读
   - `BLANK`：擦除 CD-RW
3. 手机端先把待刻文件拼成标准的 **ISO 9660** 镜像字节流，再整盘写入。

## 环境要求

- **Android Studio**（用于构建与签名）
- 手机：Android 5.0+（minSdk 21），且支持 **USB OTG**
- 硬件：**带独立供电**（或 Y 线双 USB 头）的外置 USB 刻录机；OTG 转接线
- 光盘：CD-R（一次刻满）或 CD-RW（可擦写重刻）

> ⚠️ 手机 OTG 口供电通常带不动刻录机，必须外接供电，否则读盘都勉强，更别提刻录。

## 导入与构建

### 方式一：云端自动出 APK（不用装 Android Studio）⭐
本项目已内置 GitHub Actions 流水线（`.github/workflows/build-apk.yml`）：
1. 把本仓库推到 GitHub（远端已设为 `https://github.com/shangguansihan/wang1.git`）。
2. 进仓库 **Actions** 标签，首次会自动运行（或手动 Run workflow）。
3. 运行结束后在 **Artifacts** 里下载 `PhoneCdBurner-debug-apk`，装到手机即可。

### 方式二：本地用 Android Studio
1. 用 Android Studio **打开本目录**（根目录含 `settings.gradle`）。
2. 首次打开会自动补全 Gradle wrapper（联网下载 Gradle 8.4 + AGP 8.1.4）。
3. 连接手机（开启 USB 调试），`Run`；或 `Build → Build Bundle(s) / APK(s)` 后安装。

## 使用步骤

1. OTG 接上**带供电**的刻录机，插入手机，App 会自动请求 USB 权限。
2. 点「选择文件 / 文件夹」，选一个要备份的文件夹。
3. 放盘：
   - CD-R：点「刻录（CD-R，一次性）」
   - CD-RW：点「擦除并重刻（CD-RW）」
4. 进度到 100% 且提示「刻录完成」，封盘后取出，放进任意电脑光驱可读。

## 分阶段调试（强烈建议按顺序验证）

真机 OTG 通信很难一次跑通，请逐步验证、各自定位：

1. **链路打通**：`INQUIRY` 能返回刻录机型号（`MainActivity` 里 `dev.productName` 或 `UsbMassStorage` 加日志）。
2. **只读验证**：`READ` 读一张现成资料 CD，确认收发框架稳定。
3. **造 ISO**：镜像结构已用 `tools/iso9660_ref.py` 自检通过；手机端 `Iso9660Builder.plan` 也可单独单测。
4. **真正写盘**：先用 **CD-RW** 练手（刻坏擦掉重来不心疼）。
5. **封盘**：`CLOSE TRACK/SESSION` 后，盘放进电脑光驱应可读。

## 排错清单

- **检测不到设备**：确认 OTG 正常、刻录机是 Mass Storage 类（`device_filter.xml` 里已按 subclass 0x02/0x06 过滤；若仍不行，放宽过滤或在 `UsbMassStorage.findDrive` 里去掉类限制手动枚举）。
- **供电不足 / 写盘中断**：务必用带独立供电或 Y 线的刻录机。
- **SAO 失败 / 报 status 错误**：部分刻录机对 SAO 不友好，把 `ScsiCommands.modeSelectWriteParams` 中 `page[2] = 0x12` 改成 `0x11`（改用 TAO）。
- **封盘后老光驱读不出**：老设备对 CD-RW / 多 session 兼容差；资料盘用 CD-R、一次性封盘最稳。
- **写命令返回失败**：用 `REQUEST SENSE` 取详细错误码（已在代码注释中提示）。

## 已知限制 / 待增强

- **文件名仅支持 ASCII**：非 ASCII（如中文）会被清理成 `_`。完整中文文件名需要 **Joliet** 补充卷描述符扩展（结构已预留，可加）。
- **内存版读取**：`MainActivity.loadFolder` 把整个文件夹读进内存，超大文件夹可能 OOM。可改为流式读取（`IsoPlan` 接收 Uri + 按块读接口）。
- **无法在此开发环境编译安卓代码**：USB/SCSI 逻辑需在真机调。ISO 9660 布局已用 Python 参考实现（`tools/iso9660_ref.py`）自检通过，并产出 `tools/demo.iso` 供第三方工具校验。

## 文件结构

| 文件 | 作用 |
|------|------|
| `app/.../usb/UsbMassStorage.kt` | USB Host 通信层（CBW/CSW、端点收发） |
| `app/.../usb/ScsiCommands.kt`  | SCSI/MMC 命令构造 |
| `app/.../iso/Iso9660Builder.kt`| ISO 9660 镜像生成 |
| `app/.../burn/CdBurner.kt`     | 刻录 / 擦除流程编排 |
| `app/.../MainActivity.kt`      | UI / 权限 / 文件选择 |
| `tools/iso9660_ref.py`         | Python 参考实现 + 自检 + 生成 demo.iso |
| `tools/demo.iso`               | 示例 ISO（可用 isoinfo / OSFMount / 虚拟光驱校验结构） |

## 参考规范

- ECMA-119（ISO 9660）
- SCSI Primary Commands（SPC-3）
- SCSI Multimedia Commands（MMC-3）
