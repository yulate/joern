# SootUp2CPG 使用指南

SootUp2CPG 是 Joern 的一个新实验性前端，基于 [SootUp](https://github.com/SootUp/SootUp) 框架构建。它能够将 Java 字节码（.class, .jar）和 Android 字节码（.apk, .dex）转换为代码属性图（CPG）。

## 1. 安装与构建

在使用之前，您需要从源码构建项目。请确保已安装 JDK 11+ (推荐 JDK 17/21)。

```bash
# 在 Joern 根目录下运行
sbt stage
```

构建成功后，可执行文件位于：`joern-cli/frontends/sootup2cpg/target/universal/stage/bin/sootup2cpg`。

## 2. 基础用法

### 命令行语法

```bash
./joern-cli/frontends/sootup2cpg/bin/sootup2cpg [options] <input-file>
```

- `<input-file>`: 待分析的目标文件，支持 Jar 包、WAR 包、APK 文件或包含 class 文件的目录。

### 常用选项

| 选项 | 说明 | 默认值 |
| :--- | :--- | :--- |
| `--output <file>` | 输出 CPG 文件的路径 | `cpg.bin` |
| `--android-jar <path>` | Android 平台 JAR (android.jar) 的路径，分析 APK 时必须 | 自动从通过环境变量查找 |
| `--summary-model-dir <dir>` | CodeQL Summary 模型 (`.yml`) 所在的目录 | 默认为空 |
| `--strip-bodies-for-summary` | 是否在应用 Summary 时剥离原方法体 (实验性) | `none` (不剥离) |
| `--help` | 显示帮助信息 | - |

## 3. 使用示例

### 示例 1: 分析标准 Java Jar 包

假设有一个名为 `app.jar` 的应用程序。

```bash
# 生成 CPG
./joern-cli/frontends/sootup2cpg/bin/sootup2cpg --output app.cpg.bin app.jar

# 使用 Joern 分析
./joern
joern> importCpg("app.cpg.bin")
joern> cpg.method.name("main").l
```

### 示例 2: 分析 Android APK

需要提供 Android SDK 中的 `android.jar` (通常在 `$ANDROID_SDK_ROOT/platform/android-xx/android.jar`)。

```bash
export ANDROID_HOME=/path/to/sdk

./joern-cli/frontends/sootup2cpg/bin/sootup2cpg \
  --output app.apk.cpg.bin \
  --android-jar $ANDROID_HOME/platforms/android-30/android.jar \
  my-app.apk
```

### 示例 3: 使用 CodeQL Summary 增强分析

如果您想分析的代码依赖了闭源的第三方库（例如 `com.example.lib`），而该库的方法会通过参数传播污点。

1. **准备模型**: 创建 `models.yml` 描述库方法的行为。
   ```yaml
   # models.yml
   models:
     - name: "com.example.lib.StringUtil.concat"
       input: "Argument[0]"
       output: "ReturnValue"
       kind: "taint"
   ```

2. **运行分析**: 指定模型目录。
   ```bash
   ./joern-cli/frontends/sootup2cpg/bin/sootup2cpg \
     --output enriched.cpg.bin \
     --summary-model-dir ./path/to/summary-dir \
     app-using-lib.jar
   ```

3. **效果**: 生成的 CPG 中，`StringUtil.concat` 方法将包含一个虚拟的方法体，连接了参数到返回值的数据流，使得 Joern 的污点分析能够“穿透”该方法。

## 4. 技术文档

- [CodeQL Summary 集成与流传播原理](codeql_summary.md): 深入了解 Summary 适配的底层实现细节。
