# Streak · 每日打卡习惯追踪 APP

`Streak` 是一款基于原生 Android（Java）开发的每日打卡习惯追踪应用。它在本地完成账号管理与全部数据存储，支持习惯的增删改查、每日打卡、连续天数统计、日历回顾、数据可视化、勋章激励、二维码分享（可保存到相册 / 从相册识别）、备份导入导出与定时提醒，并提供拍照／相册配图与个性化资料设置。

- 项目包名：`com.streak.app`
- 内置演示账号：`student / 123456`

## 功能特性

<img src="https://gitee.com/CBD-A/typora-cloud/raw/master/image-20260713120133671.png" alt="image-20260713120133671" style="zoom: 33%;" /><img src="https://gitee.com/CBD-A/typora-cloud/raw/master/image-20260713120257725.png" alt="image-20260713120257725" style="zoom: 33%;" /><img src="https://gitee.com/CBD-A/typora-cloud/raw/master/image-20260713120334861.png" alt="image-20260713120334861" style="zoom:33%;" />

<img src="https://gitee.com/CBD-A/typora-cloud/raw/master/image-20260713120456522.png" alt="image-20260713120456522" style="zoom:33%;" /><img src="https://gitee.com/CBD-A/typora-cloud/raw/master/image-20260713120509468.png" alt="image-20260713120509468" style="zoom:33%;" /><img src="https://gitee.com/CBD-A/typora-cloud/raw/master/image-20260713120527368.png" alt="image-20260713120527368" style="zoom:33%;" />

<img src="https://gitee.com/CBD-A/typora-cloud/raw/master/image-20260713120545844.png" alt="image-20260713120545844" style="zoom:33%;" /><img src="https://gitee.com/CBD-A/typora-cloud/raw/master/image-20260713120606733.png" alt="image-20260713120606733" style="zoom: 33%;" /><img src="https://gitee.com/CBD-A/typora-cloud/raw/master/image-20260713120631957.png" alt="image-20260713120631957" style="zoom:33%;" />



### 账号与安全
- 本地注册、登录、退出，支持「记住用户名」
- 密码采用 PBKDF2 加盐哈希存储；兼容旧明文账号并在首次登录时自动迁移
- 仅在本地记住用户名，绝不持久化明文密码（避免被 root / 备份提取）
- 删除账号会同时清除该账号的习惯、打卡记录与照片，并清空已保存的登录信息

### 习惯管理
- 新增 / 编辑 / 删除习惯，支持标题、内容、分类、标签、提醒时间
- 内置习惯模板，快速创建常见习惯
- 习惯列表支持按关键字搜索、按分类筛选，并按打卡状态分组展示

### 打卡与追踪
- 一键今日打卡 / 取消打卡
- 自动统计今日完成数、单个习惯连续打卡天数、累计打卡次数（按日期去重）
- 日历页按月查看打卡分布，支持连续补卡

### 数据可视化
- 统计页展示习惯总数、累计打卡、完成率
- 分类分布饼图（自绘 `CategoryPieChart`）
- 周 / 月打卡量统计

### 个性化资料
- 设置昵称、个性签名、头像
- 头像支持 6 款预置图案，或拍照 / 相册自定义

### 勋章墙
- 内置 11 枚成就勋章（创建习惯、连续打卡 3/7/30 天、累计打卡 50/100 次、全勤、影像记录等）
- 根据当前数据自动点亮，个人主页展示进度

### 二维码分享
- 将习惯生成二维码分享给同学（自定义 `streak-habit/v1` 协议），支持一键保存二维码到系统相册
- 扫码「加同款」习惯，扫码界面锁定竖屏，并内置从相册选图离线识别二维码
- 对扫到的二维码内容做长度上限与控制字符清洗，防止不可信输入注入编辑器

### 备份与恢复
- 一键导出 ZIP 备份（含习惯、账号资料与全部图片），通过系统分享发送
- 导入备份恢复数据；导入采用「习惯与账号先全量校验、全部通过再落地」策略，避免中途失败破坏现有数据
- 本地数据采用原子写入（写临时文件 + 落盘同步 + 原子重命名），写入中断绝不会留下半截文件
- 解析到损坏的数据文件时自动改名备份，绝不静默覆盖用户数据

### 定时提醒
- 为习惯设置每日提醒，到点本地通知
- 开机后自动重建全部提醒；精确闹钟不可用时自动降级为非精确闹钟

### 界面与体验
- Material 3 风格界面，浅色视觉设计，夜间模式镜像浅色主题保持一致表现
- 本地图片统一降采样加载，避免大图导致的卡顿与内存溢出

## 技术栈

| 项目 | 说明 |
|------|------|
| 开发语言 | Java 17 |
| 最低 / 目标版本 | Android 8.0 (API 26) / Android 14 (API 34) |
| 构建系统 | Gradle（Groovy DSL） |
| UI | XML 布局 + ViewBinding + Material Components 1.12.0 |
| 数据序列化 | Gson 2.11.0（JSON 文件存储） |
| 二维码 | ZXing core 3.5.3 + zxing-android-embedded 4.3.0 |
| 本地存储 | 应用私有目录 JSON 文件 + SharedPreferences + 私有图片目录 |

## 工程结构

```
app/src/main/java/com/streak/app/
├── model/        数据模型（HabitItem、UserAccount、Badge、HabitTemplate 等）
├── reminder/     提醒调度与广播接收（ReminderScheduler、BootReceiver、ReminderReceiver）
├── storage/      数据仓库 AppRepository（账号、习惯、备份、图片的读写中枢）
├── ui/           界面层（MainActivity、各 Activity、适配器、自绘视图）
└── util/         工具类（PasswordHasher、HabitUtils、BadgeUtils、HabitQrCodec、ImageLoader 等）
```

## 构建与运行

1. 使用 Android Studio 打开项目根目录，等待 Gradle 同步完成。
2. 连接真机或启动模拟器（Android 8.0 及以上）。
3. 运行 `app` 模块。

命令行构建：

```bash
./gradlew assembleDebug      # macOS / Linux
gradlew.bat assembleDebug    # Windows
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

## 权限说明

| 权限 | 用途 |
|------|------|
| `CAMERA` | 拍照配图、扫码 |
| `POST_NOTIFICATIONS` | 习惯提醒通知 |
| `SCHEDULE_EXACT_ALARM` | 精确定时提醒（不可用时自动降级） |
| `RECEIVE_BOOT_COMPLETED` | 开机后重建提醒 |
| `WRITE_EXTERNAL_STORAGE` | 保存二维码到相册（仅 Android 9 / API 28 及以下；API 29+ 走 MediaStore 免此权限） |

## 许可

本项目用于课程作业 / 学习演示。
