# 财联社电报爬虫 🖤

爬取 [财联社电报](https://www.cls.cn/telegraph) 的实时新闻内容。

## 项目结构

```
news/
├── src/
│   └── main/
│       └── java/
│           └── CLSTelegraphCrawler.java    # 主程序
├── pom.xml                          # Maven 配置
├── run.bat                          # Windows 快速运行脚本
└── README.md                        # 说明文档
```

## 运行方式

### 方式一：IntelliJ IDEA（推荐）

1. 打开 IDEA
2. **File -> Open** → 选择 `D:\code\news` 目录（`pom.xml` 所在目录）
3. 选择 **"Open as Project"**
4. 等待 Maven 自动下载依赖（首次需要一点时间）
5. 打开 `src/CLSTelegraphCrawler.java`
6. 右键点击代码区域 → **Run 'CLSTelegraphCrawler.main()'**

或者使用 Maven 工具窗口：
- 右侧 Maven 面板 → `Lifecycle` → `compile` → 运行
- 然后 `Plugins` → `exec` → `java` → 运行

### 方式二：使用 Maven 命令行

```bash
# 进入项目目录
cd D:\code\news

# 编译并运行
mvn clean compile exec:java -Dexec.mainClass=CLSTelegraphCrawler
```

### 方式三：使用批处理脚本

```bash
run.bat
```

## 输出

爬取的内容会保存到：
```
D:\code\news\telegraph_YYYYMMDD_HHMMSS.txt
```

每条记录格式：
```
[序号] 电报内容
----------------------------------------
```

## 注意事项

⚠️ **爬虫礼仪：**
- 请勿高频请求，建议间隔至少 30 秒
- 仅供个人学习/研究使用
- 请遵守网站 robots.txt 和使用条款
- 不要用于商业用途

⚠️ **可能的问题：**
- 网站结构变化可能导致选择器失效
- 可能需要处理反爬机制（如需要可以添加代理、Cookie 等）
- 部分内容可能是动态加载的，需要 Selenium 等工具

## 扩展建议

如果需要更强大的功能，可以考虑：
- 添加定时任务（每天自动爬取）
- 存入数据库（MySQL/SQLite）
- 添加关键词过滤
- 推送通知（微信/邮件）

---

_有问题随时找我_ 🖤
