# TextFilter - A Simple Text Filter System
This plugin supports a wide range of detecting methods, including chat messages, anvil renamings, sign texts, writable book texts, PlayerChat messages, CMI messages, and ServerShout messages (Velocity version only)[servershout-momo](https://modrinth.com/plugin/servershout-momo).

## 🚀 Latest Updates
- **Completely refactored core logic.**
- **Added i18n (internationalization) support.**
- **Added cross-message detection with a configurable detection window.**
- **Added mapping tables for confusable characters**, including Traditional Chinese, Japanese, English, numbers, and punctuation.
- **Introduced a penalty mechanism for prohibited words.**
- **Allowed customized chat bar alerts for different levels of prohibited words.**
- **Rewrote the command listener logic**, making it easier to add custom commands.
- **Removed character limits for anvil naming.**

## ✨ Core Features
- **Advanced Matching Algorithm:** Supports cross-character matching with a configurable range.
- **Extra Utilities:** Comes with built-in whitelist and inverse detection.
- **Customizable Feedback:** The feedback text triggered by command executions and banned-words detections can be customized as needed.

## 🛡️ Permissions
- `textfilter.admin` - Receive prohibited word warning notifications.
- `textfilter.command` - Use the plugin command (`/tf reload`).
- `textfilter.anvil.bypass` - Bypass anvil naming detection.

---

# TextFilter - 一个简单的屏蔽词过滤系统

本插件支持多种文本检测方式，包括聊天消息、铁砧命名、告示牌文本、书与笔文本、PlayerChat 文本、CMI 文本以及 ServerShout 文本（仅限 Velocity 版本）[momo的改版servershout](https://modrinth.com/plugin/servershout-momo)。

## 🚀 最新更新
- **基本重构了所有核心逻辑。**
- **新增了 i18n（国际化）支持。**
- **新增了跨消息检测**，且检测范围可自主配置。
- **新增了形近字符映射表**，支持简体/繁体中文、日文、英文、数字及标点符号。
- **新增了违禁词惩罚机制。**
- **允许为各个等级的违禁词提供不同的聊天栏提醒。**
- **重写了命令监听逻辑**，现在可以更加轻松地自主添加自定义命令。
- **移除了铁砧命名的字符长度限制。**

## ✨ 核心特性
- **高级匹配算法：** 支持跨字符匹配，且匹配范围可灵活调整。
- **实用扩展功能：** 内置白名单、反向检测等额外功能。
- **自定义反馈：** 可自由定制命令执行和违禁词检测触发的提示文本。

## 🛡️ 权限节点
- `textfilter.admin` - 接收违禁词警告通知。
- `textfilter.command` - 使用插件命令（`/tf reload`）。
- `textfilter.anvil.bypass` - 绕过铁砧命名检测。
