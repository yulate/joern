# sootup2cpg

`sootup2cpg` 是 Joern 的下一代 Java/JVM 前端，基于 [SootUp](https://github.com/soot-oss/SootUp) 框架构建。目标是提供比 `jimple2cpg` 更高性能、更清晰架构的字节码分析能力，并完美支持“源码 + 字节码”混合分析。

## 主要特性

- **Java 17 支持**: 基于 SootUp 框架，原生支持现代 Java 字节码特性（如 Record, Sealed Class）。
- **混合分析支持**: 原生支持 `.java` 源码和 `.class`/`.jar` 字节码的混合输入。
  - 自动调用 `javac` 编译源码（可选）。
  - 统一的 staging 机制管理多来源输入。
- **精准的 CFG 生成**:
  - **Inter-statement**: 采用改良的 `Root (Exit) -> Entry (Start)` 连接策略，确保语句间的控制流连通性。
  - **Intra-statement**: 正确模拟表达式求值顺序（Argument -> Call -> Assignment），支持深度数据流分析。
- **健壮的异常处理**: 
  - 完整支持 `Try-Catch` 结构生成。
  - 包含 `Throw` 到 `Catch` 的异常控制流边 (Exceptional CFG Edges)。
- **注解完整支持**: 精准提取 Java 注解 (`@Annotation`) 并生成 CPG `ANNOTATION` 节点，支持基于注解的漏洞扫描（如识别 `@RestController`, `@RequestMapping`）。
- **混合库代码分析**: 支持通过 `--include-package` 强制将特定第三方库包视为 Application 代码处理，提取其完整方法体。这对分析 Web 框架（如 Spring, MyBatis, Uusafe Framework）内部的数据流至关重要。
- **反编译支持**: 内置 CFR 反编译器，自动将 Class/JAR 字节码反编译为 Java 源码并存储在 CPG 中，实现无源码环境下的代码审查与定位。
- **自动存根生成**: 内置 `MethodStubCreator`，自动为调用的外部方法（如 JDK API）生成存根。
- **数据流分析就绪**: 生成的 CPG 完全兼容 Joern 的 `Set of Flows` 分析器，已在反序列化漏洞检测中得到验证。

## 原理与设计

### 1. 输入处理 (Staging)
`sootup2cpg` 并非直接处理输入路径，而是通过 `Staging` 机制：
1. **收集**: 扫描输入路径下的所有 `.java`, `.class`, `.jar`。
2. **整理**: 将它们复制/编译到一个统一的临时目录（Staging Directory）。
3. **加载**: `SootUpProjectLoader` 从 Staging 目录加载所有类，构建统一视图。
4. **分类**: 根据包名或输入来源，将类分为 `Application` (提取AST/CFG) 或 `Library` (仅提取签名)。

### 2. CFG 生成策略
为了支持精确的数据流分析，我们在 AST 转换中实施了严格的 CFG 连接规则：
- **Root Node**: 语句执行完成后的节点（如 `<operator>.assignment` 或 `CALL`）。
- **Entry Node**: 语句开始执行时的第一个节点（如第一个参数的 Identifier）。
- **连接规则**: `Root(Stmt A) -> Entry(Stmt B)`。

这避免了传统 `Entry -> Entry` 连接导致的副作用丢失问题。

## Usage

```bash
# 推荐使用方式（通过 joern-parse 包装器）
./joern-parse <input_path> --language java --frontend-args "--no-compile-java"

# 包含特定库代码进行分析（解决框架数据流断裂）
./sootup2cpg <input_path> --include-package com.example.framework --output <cpg.bin>
```

### 常用参数

- `--compile-java`：开启 Java 源码自动编译（默认关闭）。需确保环境中存在 `javac`。
- `--extra-class-path <path>`：提供编译所需的依赖 JAR 路径。
- `--include-package <pkg>`：**[重要]** 强制包含指定的包（即使在依赖 JAR 中）作为应用代码。这对追踪经过框架内部（如 JsonUtil, Controller）的数据流非常有用。
- `--with-jdk-runtime`：加载 JDK 运行时类（默认关闭）。

## 开发状态

| 功能 | 状态 | 备注 |
|------|------|------|
| 基本 AST/类型 | ✅ | 支持 Namespace, TypeDecl, Method, Field |
| 语句级 CFG | ✅ | 支持 If, Goto, Switch, Return, Throw |
| 表达式 CFG | ✅ | 支持 Call, Assign, BinOp 等 |
| 注解支持 | ✅ | 提取注解及其属性，关联到 Method/TypeDecl |
| 数据流追踪 | ✅ | 支持 Taint Analysis, Reachability |
| 异常控制流 | ✅ | Try-Catch, Throw, Exceptional Edges |
| Java 17+ 支持 | ✅ | Record, Switch Expressions, Sealed Classes |
| 动态调用链接 | 🚧 | 需配合 CHA/RTA 算法优化 |
