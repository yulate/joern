# CodeQL Summary 集成与流传播原理

本文档详细介绍了 CodeQL Summary Model (MaD) 的格式规范、目前支持的流传播模式，以及在 Joern `sootup2cpg` 前端中的底层适配实现。通过引入 CodeQL Summary，我们能够在**没有源码**的情况下，通过加载预定义的数据流模型（Model-as-Data），为闭源第三方库生成精准的 CPG 模型，从而解决跨库分析时数据流断裂的核心难题。

## 1. CodeQL Summary Model 格式

SootUp2CPG 支持加载标准的 CSV/YAML 格式的 CodeQL Summary 模型。每一行代表一条数据流规则（Flow Rule）。

### 1.1 字段定义

模型文件的每一行通常包含以下字段：

| 字段 | 说明 | 示例 |
| :--- | :--- | :--- |
| **Input** | 污点来源 (Source) | `Argument[0]`, `Argument[-1]` |
| **Output** | 污点去向 (Sink/Out) | `ReturnValue`, `Argument[-1]` |
| **Kind** | 传播类型 | `taint`, `value` |

支持的 Access Path 语法：
- `Argument[n]`: 第 `n` 个显式参数（索引从 0 开始）。
- `Argument[-1]`: 方法的 Base 对象（即 `this` 指针）。
- `ReturnValue`: 方法的返回值。
- `Argument[0..2]`: 范围索引，表示第 0 到第 2 个参数。

## 2. 支持的流传播模式

我们目前实现了对以下核心流向模式的完整支持。实现策略是基于 **AstCreator** 的**合成 AST (Synthetic AST)** 技术。

### 2.1 Arg2Ret (参数流向返回值)
* **描述**: 输入参数的数据影响返回值。
* **规则**: `Input=Argument[i]`, `Output=ReturnValue`
* **常见场景**: `String.valueOf(i)`, `Math.max(a, b)`
* **CPG 实现**:
  生成包含特殊的 `<operator>.taintMerge` 调用的返回语句：
  ```java
  return <operator>.taintMerge(arg_i);
  ```

### 2.2 Base2Ret (对象流向返回值)
* **描述**: 调用对象自身的数据影响返回值。
* **规则**: `Input=Argument[-1]`, `Output=ReturnValue`
* **常见场景**: `StringBuilder.toString()`, `List.get(i)`
* **CPG 实现**:
  生成将 `this` 作为输入的返回语句：
  ```java
  return <operator>.taintMerge(this);
  ```

### 2.3 Arg2Base (参数流向对象/副作用)
* **描述**: 参数数据写入调用对象（Base），这是一种依赖于副作用（Side-Effect）的流。
* **规则**: `Input=Argument[i]`, `Output=Argument[-1]`
* **常见场景**: `List.add(item)`, `StringBuilder.append(str)`
* **CPG 实现**:
  由于 Joern 的数据流引擎需要显式的赋值来建立依赖，我们生成一个能够自我更新的赋值语句：
  ```java
  this = <operator>.taintMerge(this, arg_i);
  ```
  *注意*: 将 `this` 也作为输入放入 `taintMerge` 是为了保留对象原有的污点状态（Weak Update），避免覆盖。

### 2.4 Arg2Arg (参数互流)
* **描述**: 一个参数的数据流入另一个参数。
* **规则**: `Input=Argument[i]`, `Output=Argument[j]`
* **常见场景**: `Collections.copy(dest, src)`, `System.arraycopy(...)`
* **CPG 实现**:
  ```java
  arg_j = <operator>.taintMerge(arg_j, arg_i);
  ```

## 3. 实现细节

### 3.1 核心组件

*   **SootUpProjectLoader**: 负责解析 CodeQL CSV/YAML 文件，提取 `SummaryEntry` 并将其注入到 `SootUpMethod` 对象中。
*   **AstCreator**: 在遍历方法时，若发现方法标记为 `isExternal` 且包含 Summary 信息，则跳过空的 Loop 生成逻辑，转而调用 `createSyntheticBody` 生成上述的虚拟 AST。

### 3.2 验证案例

我们在 `benchmark/summary_test` 下提供了一个完整的验证套件，包含上述所有流向的测试代码：

*   **源代码**: `benchmark/summary_test/src/Test.java`
*   **模型定义**: `benchmark/summary_test/summary/models.yml`
*   **运行验证**: `tests/test-sootup-summary.sc`

该 Benchmark 证明了我们的实现能够正确处理复杂的跨过程数据流。

## 4. 未来计划

*   支持更细粒度的 Access Path (如 `Argument[0].Field[name]`)。
*   支持 `SyntheticField` 虚拟字段以模拟更复杂的库内部状态。
