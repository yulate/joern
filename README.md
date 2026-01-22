Joern - 增强型代码属性图分析平台
===

> [!NOTE]
> 本项目是 Joern 的深度定制分支。
> 我们致力于引入包括 **SootUp2CPG** 在内的先进静态分析能力，并计划持续对核心架构进行优化与扩展，以满足更复杂的代码分析需求。

[![release](https://github.com/joernio/joern/actions/workflows/release.yml/badge.svg)](https://github.com/joernio/joern/actions/workflows/release.yml)

Joern 是一个用于分析源代码、字节码和二进制可执行文件的平台。它生成代码属性图 (CPG)——一种跨语言的代码分析图表示，并将其存储在自定义的图数据库中。

## 核心贡献与特性

本项目在官方 Joern 的基础上，重点进行了以下扩展：

### 1. 全新 SootUp2CPG 前端
集成基于 **SootUp** 框架的全新前端，为 Java 和 Android 分析带来质的飞跃：
- **Jimple IR 精确映射**: 能够准确地将 Soot 的 Jimple 中间表示转换为 CPG，保留更多语义细节。
- **Android 深度支持**: 针对 APK 和 Android 字节码进行了专门优化。
- **现代化架构**: 利用 SootUp 的现代化 API，提升分析的稳定性和扩展性。

### 2. 核心功能增强 (开发中)
我们正在对 Joern 的底层设施进行一系列改造，计划涵盖：
- 数据流分析引擎的增强
- 对 LLM 辅助修复流程的支持
- 更灵活的图查询语言扩展

## 快速开始

### 编译项目
本项目使用 sbt 构建。请确保您的环境已安装 JDK 11+ (推荐 JDK 17/21)。

```bash
sbt stage
```

### 使用 SootUp2CPG
构建完成后，直接运行以下命令即可分析目标（Jar 包或 APK）：

```bash
./joern-cli/frontends/sootup2cpg/bin/sootup2cpg <path-to-jar-or-apk>
```

## 官方文档
关于 Joern 的标准功能、安装及通用查询语法，请参考 [Joern 官方文档](https://docs.joern.io/)。

---
*本项目基于 [ShiftLeft/Joern](https://github.com/joernio/joern) 开发。感谢原作者团队的杰出工作。*
