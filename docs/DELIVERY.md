# 首版交付与验证记录

## 交付

- 应用：小小点读 0.1.0，家庭试用版。
- Android 8.0+，ARM64，面向 OPPO Find X；未在用户的实际手机上安装。
- 已实现拍照 / 选图入口、英文识别、句子与单词点读、慢速示范、录音与对照回放、最近书页、家长修改与删除。
- 本轮交付本地 OCR / 系统离线英语 TTS 版本，无后台部署、无付费服务密钥。原先讨论的云端方案未部署。

## 通过的检查

1. `testDebugUnitTest`：9 项通过，覆盖跨行合并、段落分离、标点分句、单词提取、录音键隔离、存储重开、坏文件隔离、删除与路径校验。
2. `lintDebug`：0 个错误。仍有非阻断警告，包括目标 API、依赖更新提示、竖屏和自定义 View 构造器。
3. `assembleDebug` / `assembleDebugAndroidTest`：通过。最终交付 APK 仅包含 ARM64 原生库。
4. Android 11 / API 30 ARM64 AOSP 模拟器，无 Google Play 服务：6 项 instrumentation 测试通过。
   - 真实绘本照片的离线 OCR。
   - 相册结果返回后的识别、保存与点读界面。
   - 图片 EXIF 旋转归一化。
   - 跟读录音生成可读取的 AAC 音频，带有效时长。
   - 打开书页、单词按钮、Activity 重建恢复。
   - 数据重开及删除关联文件。
5. 实际查看首页、点读页、识别导入后的截图，确认大按钮、文字与点击框无明显裁切；主要播放和录音按钮在首屏可见。
6. APK 签名校验通过；安装权限列表没有 INTERNET，仅需要麦克风运行时权限（拍照由系统相机完成）。

## 测试运行方式

Gradle 的 `connectedDebugAndroidTest` 被 UTP 测试工具的远程下载 TLS 错误阻断，没有将这条命令记为通过。
已安装同一套测试 APK，改用 Android 官方 instrumentation runner 执行相同测试：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r cn.family.littlereader.test/androidx.test.runner.AndroidJUnitRunner
```

最终输出：`OK (6 tests)`。测试脚本为 `ReadingFlowTest.kt`；导入测试从图片返回回调进入真实识别流程，未替换 OCR 或存储为模拟对象。

## 尚未通过的真机门禁

- **系统相册选择器**：AOSP 模拟器的 DocumentsUI 能展示图片，但点选后未返回应用；尚未定位为模拟器、系统选择器还是兼容性问题。图片返回后的应用导入流程已通过，不能据此宣称系统选图交互通过。需在 OPPO 上验证。
- **实际拍照**：相机启动与照片返回的 ColorOS 行为未验证，按 DEVICE-CHECKLIST.md 实测。
- **英语语音包**：体验页内置声音；新句子的声音取决于设备安装的离线英语引擎与语音包。OPPO 上尚未验证引擎可用性和音质。
- **真实录音和播放音量**：模拟器录音编码通过；未测试用户手机的实际麦克风、扬声器与儿童声音。
- 没有自动发音评测、打分或纠音。跟读功能是录音对照。

本阶段完成了可安装试用版与本地自动检查。家庭使用验收仍待用户在实际手机上完成；不标记为生产发布就绪。
