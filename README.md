# Streak 每日打卡习惯追踪APP

`Streak` 是一个基于 Android Studio Panda 兼容工程结构开发的每日打卡习惯追踪应用，支持本地登录、记住密码、习惯事件的增删改查、每日打卡、相机拍照和相册选图。

## 功能清单

1. 登录功能
   - 支持本地账号登录
   - 支持“记住密码”
   - 支持注册新账号
   - 内置演示账号：`student / 123456`

2. 习惯管理
   - 新增习惯
   - 编辑习惯
   - 删除习惯
   - 查看标题、内容、提醒时间、创建时间

3. 打卡追踪
   - 支持今日打卡/取消打卡
   - 自动统计今日已打卡数量
   - 自动统计单个习惯连续打卡天数

4. 图片功能
   - 调用摄像头拍照
   - 从系统相册选择图片
   - 图片保存到应用私有目录

5. 页面表现
   - Material 3 风格界面
   - 登录页、概览卡片、习惯卡片、编辑底部弹层
   - 适合课程演示的完整操作流程

## 工程信息

- 项目路径：`D:\Desktop\develop\Android Studio\Streak`
- 包名：`com.streak.app`
- 最低版本：`Android 8.0 (API 26)`
- 目标版本：`Android 14 (API 34)`
- 构建系统：`Gradle Kotlin DSL`
- UI：`Jetpack Compose + Material 3`

## 运行方式

1. 使用 Android Studio 打开项目根目录。
2. 等待 Gradle 同步完成。
3. 连接真机或启动模拟器。
4. 运行 `app` 模块。

也可在命令行使用：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

## 构建结果

- Debug APK：`D:\Desktop\develop\Android Studio\Streak\app\build\outputs\apk\debug\app-debug.apk`
- 单元测试报告：
  - `D:\Desktop\develop\Android Studio\Streak\app\build\reports\tests\testDebugUnitTest\index.html`
  - `D:\Desktop\develop\Android Studio\Streak\app\build\reports\tests\testReleaseUnitTest\index.html`

## 建议交付目录

建议最终提交时整理为：

1. `源代码/`：完整 Android Studio 工程
2. `apk/`：放置 `app-debug.apk`
3. `作品报告.docx` 或 `作品报告.pdf`
4. `答辩讲稿.md` 或打印稿

压缩包命名格式：

`姓名+学号.zip`
