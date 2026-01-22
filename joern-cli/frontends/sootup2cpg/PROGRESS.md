# sootup2cpg 进度与目标清单（对照 jimple2cpg）

本文档用于规划 `sootup2cpg` 的实现进度，分为两部分：
1) **完整完成需要的数据**（对照 `jimple2cpg` 已有实现）；  
2) **当前已采集的数据**（基于 `sootup2cpg` 当前代码）。  

> 说明：对照项基于 `joern-cli/frontends/jimple2cpg` 的 AST 构建逻辑与 pass（`AstCreator`/`AstCreationPass`/`DeclarationRefPass`/`TypeNodePass`/`MetaDataPass`/`JavaConfigFileCreationPass`）。

---

## 一、完整完成需要的数据（对照 jimple2cpg）

### 1) 元信息与文件层
- `META_DATA`：语言/版本/输入路径（`MetaDataPass`）
- `FILE`：文件路径与内容（如果允许写入 `fileContent`）
- `NAMESPACE_BLOCK`：包/命名空间

### 2) 类型与结构
- `TYPE_DECL`：类/接口/枚举/内部类
- `MEMBER`：字段/成员变量
- 继承关系：`TYPE_DECL.inheritsFromTypeFullName`
- `TYPE` 节点：通过 `TypeNodePass` 生成
 - **JDK 运行时类是否加载**：与 `jimple2cpg` 行为保持一致（默认不加载，可选开启）

### 3) 方法层
- `METHOD`：方法/构造器
  - `fullName`、`signature`、`isExternal`、`filename`
- `METHOD_PARAMETER_IN`：参数（含 `this`/`@parameter`）
- `METHOD_RETURN`：返回类型
- `BLOCK`：方法体

### 4) 语句与表达式（AST 细节）
- 控制结构：`CONTROL_STRUCTURE`（if/else/while/for/switch/try/catch 等）
- 跳转：`RETURN`、`BREAK`、`CONTINUE`、`THROW`
- 语句块：`BLOCK` + 子语句顺序
- 调用与操作符：
  - `CALL`（方法调用、构造器调用、算术/逻辑/比较/赋值等）
  - `DispatchTypes`（静态/动态派发）
  - `signature`、`methodFullName`、`typeFullName`
- 标识符/局部变量：
  - `LOCAL`、`IDENTIFIER`、`REF` 边
  - `FIELD_IDENTIFIER`（字段访问）
- 字面量与类型引用：
  - `LITERAL`
  - `TYPE_REF`

### 5) 引用与绑定
- `REF` 边：标识符指向其声明（`DeclarationRefPass`）

### 6) 配置文件节点
- `CONFIG_FILE` / `JavaConfigFileCreationPass` 生成的相关节点

---

## 二、当前已采集的数据（sootup2cpg 现状）

### 已实现
- **元信息**
  - `META_DATA` 已生成（`MetaDataPass`）
  - `FILE` 节点已生成（`AstCreator` 中创建）
- **运行时/JDK 加载策略**
  - 默认不加载 JDK 运行时类（与 `jimple2cpg` 对齐）
  - 可通过 `--with-jdk-runtime` 开启
- **命名空间**
  - `NAMESPACE_BLOCK`（基于包名，缺省为全局命名空间）
- **类型与结构**
  - `TYPE_DECL`（类节点）
  - `MEMBER`（字段节点）
  - `TYPE` 节点（`TypeNodePass` 生成）
- **方法层**
  - `METHOD`
  - `METHOD_PARAMETER_IN`（含 `this`）
  - `METHOD_RETURN`
  - `BLOCK`
- **语句/表达式（部分）**
  - 赋值语句 → `CALL`（`Operators.assignment`）
  - `IDENTITY` 语句 → `CALL`（参数/this/异常的绑定）
  - 方法调用 → `CALL`（含 `methodFullName` / `signature` / `dispatchType`）
  - `RETURN` / `RETURN_VOID`
  - `THROW`（`<operator>.throw`）
  - `IF` 控制结构（条件 AST）
  - `GOTO`（暂以 `UNKNOWN` 表示）
  - `SWITCH` 控制结构（条件 + case/default 标签）
  - `CATCH` 控制结构（基于异常处理入口识别）
  - `LOCAL` / `IDENTIFIER` 基础绑定（含 `REF`）
  - 二元/一元表达式（算术/比较/位运算/取反/长度/类型判断/类型转换）
  - new/数组创建
  - 字段访问 / 数组访问 / 常量细分（class/null）
  - monitor 语句（`enter/exit`，以 `UNKNOWN` 表示）
- **配置文件节点**
  - `JavaConfigFileCreationPass` 已调用（只要输入路径可用）

### 已有但效果有限
- **`DeclarationRefPass`**
  - pass 已执行，但目前 `IDENTIFIER` 仅覆盖局部/参数相关，复杂场景仍有缺口。

### 未实现（当前缺失）
- **语句与表达式 AST**
  - 控制结构（`for/try`）未实现
- **字段访问/数组访问/类型引用**
  - `FIELD_IDENTIFIER` / `TYPE_REF` / `LITERAL` 已覆盖，`TypeRef/FieldIdentifier` 绑定已补充（仍不完整）
- **继承关系**
  - `TYPE_DECL.inheritsFromTypeFullName` 已填充（基于 `JavaSootClass` 层次）
- **真实 SootUp 加载**
  - 已接入 `JavaView`/`AnalysisInputLocation`，仍需补充更多位置信息与精细绑定

---

## 三、下一步规划建议（对齐 jimple2cpg）

1. **替换加载器为真实 SootUp API**  
   读取类层次、方法签名、字段类型、继承关系、位置等。
2. **补齐语句/表达式 AST**  
   基于 SootUp IR，映射为 `CALL`/`CONTROL_STRUCTURE`/`IDENTIFIER`/`LOCAL` 等节点。
3. **补齐引用关系**  
   生成 `IDENTIFIER` 与 `LOCAL`，让 `DeclarationRefPass` 生效。
4. **完善类型信息**  
   为类型、字段、方法参数/返回值填充准确 `typeFullName`，并注册到 `TypeNodePass`。

## TODO
- 反编译器选型与集成（CFR vs FernFlower/Vineflower），暂缓处理