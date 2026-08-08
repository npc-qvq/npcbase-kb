# AI 通用代码规则

## 一、适用范围

- 本规范适用于所有新建、修改和补全的 Java 源文件。
- 只检查并补齐本次新增或实际修改的类、字段、构造方法和方法的 JavaDoc。
- 未发生实际代码改动的位置，即使缺少 JavaDoc，也不得主动补充。
- 修改范围以本次 Git diff 为准，不得扩大到整个 Java 文件。
- JavaDoc 补全和代码格式整理不属于大范围重构，可以在不改变业务逻辑的前提下完成。
- Java 语法版本以项目 `pom.xml`、Gradle 配置或现有代码使用的版本为准；无法确认时再优先使用 JDK 1.8 可用语法。

## 二、JavaDoc 强制覆盖范围

以下规范适用于本次新建或实际修改的代码元素；未出现在本次 Git diff 中的历史代码不进行 JavaDoc 补充：

- 普通类、抽象类、接口、枚举、Record、注解类型。
- 历史遗留的内部类、内部接口、内部枚举和内部 Record 必须拆分为独立顶级类型；禁止新增任何内部类型。
- 所有成员字段，包括 `private`、`protected`、默认访问级别和 `public` 字段。
- 所有依赖注入字段，包括 Repository、Mapper、Service、Client、Properties、Template、Util 等 `private final` 字段。
- 所有常量和枚举项。
- 所有显式构造方法。
- 所有显式方法，不区分访问级别和方法类型。
- Controller 请求方法、Service 方法、Repository 自定义方法、Mapper 方法、私有辅助方法、静态方法、默认方法、抽象方法、重写方法、转换方法和静态工厂方法。
- 显式编写的 Getter、Setter、Builder 方法和工具方法。

由 Lombok、Record 编译器或框架自动生成、源码中没有显式声明的方法，不要求额外添加 JavaDoc。

## 三、类型 JavaDoc 规范

### 3.1 类、接口、枚举、Record 和注解类型

- 类型定义上方必须添加多行 JavaDoc。
- 第一段直接说明该类型的业务用途，不得只写“某某类”“用于处理业务”等空泛内容。
- 每个类型必须包含 `@author NPC`。
- 每个类型必须包含 `@date`，禁止写成 `@data`。
- `@date` 格式统一为 `yyyy-MM-dd HH:mm:ss`。
- 新建文件时，`@date` 使用文件实际创建时间。
- 已有文件缺少 `@date` 时，优先通过 Git 历史获取文件首次提交时间；无法获取时，使用本次补全 JavaDoc 的当前时间，不得虚构历史创建时间。

标准格式：

```java
/**
 * 提供知识库关键词检索和语义检索接口。
 *
 * @author NPC
 * @date yyyy-MM-dd HH:mm:ss
 */
public class SearchController {
}
```

### 3.2 Record JavaDoc

- Record 类型本身必须添加 JavaDoc。
- Record 的每个组件必须使用 `@param` 说明字段含义。
- Record 中显式声明的静态工厂方法、转换方法和其他方法必须单独添加方法 JavaDoc。

标准格式：

```java
/**
 * 关键词检索结果。
 *
 * @param chunkId 文档分块主键
 * @param documentId 文档主键
 * @param chunkNo 分块序号
 * @param excerpt 内容摘要
 * @author NPC
 * @date yyyy-MM-dd HH:mm:ss
 */
public record Result(String chunkId, String documentId, int chunkNo, String excerpt) {
}
```

## 四、字段和常量 JavaDoc 规范

- 每个字段必须在字段声明正上方添加多行 JavaDoc。
- 禁止使用单行 JavaDoc，例如 `/** 文档仓储。 */`。
- 注释必须说明字段在当前类中的实际用途。
- 相邻字段之间保留一个空行。
- `private final` 依赖字段同样必须添加 JavaDoc，不能因为构造器注入而省略。

标准格式：

```java
/**
 * 文档分块数据访问仓储。
 */
private final DocumentChunkRepository chunks;

/**
 * OpenAI 兼容接口客户端。
 */
private final OpenAiCompatibleClient ai;

/**
 * Qdrant 向量检索服务。
 */
private final QdrantVectorService vectors;
```

## 五、构造方法 JavaDoc 规范

- 所有显式构造方法必须添加 JavaDoc。
- 必须说明构造方法用途。
- 每个参数都必须使用 `@param` 说明。
- 构造方法不得压缩成单行代码。

标准格式：

```java
/**
 * 创建搜索接口控制器。
 *
 * @param chunks 文档分块数据访问仓储
 * @param ai OpenAI 兼容接口客户端
 * @param vectors Qdrant 向量检索服务
 */
public SearchController(DocumentChunkRepository chunks,
                        OpenAiCompatibleClient ai,
                        QdrantVectorService vectors) {
    this.chunks = chunks;
    this.ai = ai;
    this.vectors = vectors;
}
```

## 六、方法 JavaDoc 规范

- 所有显式方法都必须添加 JavaDoc，不能只给接口方法和实现类重写方法添加。
- 普通类、Controller、DTO、工具类中的方法同样适用。
- JavaDoc 必须说明方法的业务用途。
- 每个入参必须使用 `@param` 说明。
- 非 `void` 方法必须使用 `@return` 说明返回内容。
- 方法明确抛出受检异常，或主动抛出对调用方有重要影响的运行时异常时，应使用 `@throws` 说明触发条件。
- 重写方法不得只写 `{@inheritDoc}`；应根据当前实现补充明确的业务说明、入参和返回值。
- `from`、`of`、`convert`、`build`、`replaceDocument` 等静态工厂方法、转换方法和业务辅助方法必须添加 JavaDoc。
- 私有方法不能因为只在类内部调用而省略 JavaDoc。

标准格式：

```java
/**
 * 根据关键词查询匹配的文档分块。
 *
 * @param q 搜索关键词
 * @return 关键词检索结果列表
 * @throws IllegalArgumentException 当搜索关键词为空时抛出
 */
@GetMapping("/keyword")
public List<Result> keyword(@RequestParam String q) {
    // 方法实现
}
```

## 七、接口和实现类规范

- 接口本身必须添加 JavaDoc，说明接口职责。
- 接口中的每个方法必须添加完整 JavaDoc，包含用途、`@param`、`@return` 和必要的 `@throws`。
- 实现类本身必须添加类型 JavaDoc、`@author NPC` 和 `@date`。
- 实现类中的重写方法必须添加完整 JavaDoc，不能因为接口已有注释而省略。
- 实现类新增的公开方法、受保护方法、默认访问级别方法和私有方法也必须添加完整 JavaDoc。

## 八、方法调用处注释规范

- JavaDoc 只用于类、字段、构造方法和方法声明，不能写在普通方法调用语句上方充当 JavaDoc。
- 对 Service、Repository、Mapper、Feign、Redis、MQ、远程接口、文件操作和第三方客户端等关键业务调用，应在调用语句上一行添加普通行注释 `//`，说明调用目的。
- Getter、Setter、判空、字符串处理、集合基础操作、日志打印等含义明确的通用调用，不强制逐行添加注释，避免产生无意义注释。
- 调用处注释必须说明业务目的，不能简单重复方法名。
- 对封装了数据库写入、远程调用、缓存修改、消息发送、文件操作等副作用的私有方法，在业务编排调用处也必须添加普通行注释，说明该调用产生的业务影响。

示例：

```java
// 查询包含关键词的前十条文档分块，并按照分块序号升序排列
List<DocumentChunk> chunkList = chunks.findTop10ByContentContainingOrderByChunkNoAsc(keyword);
```

## 九、注释内容要求

- 注释应直接说明代码含义、业务用途、参数含义或返回结果。
- 禁止使用“处理数据”“执行逻辑”“获取信息”“调用方法”等空泛描述。
- 禁止使用 `1、2、3` 等步骤式注释。
- 不确定业务含义时，应根据类名、方法名、字段名和现有上下文进行保守描述，不得猜测不存在的业务规则。
- 不得删除已有有效注释；已有注释不完整时，在保留原意的基础上补充。

## 十、Java 代码格式

- 使用清晰的普通写法，不要为了缩短代码而压缩成单行。
- 一个语句单独占一行。
- 类、构造方法、方法和 Record 方法体不得写成单行形式。
- 所有 `if`、`for`、`while` 和 `switch` 分支必须使用大括号。
- 相邻字段之间保留一个空行。
- import 区域与类型 JavaDoc 之间保留一个空行。
- 优先判空并提前返回，避免多层嵌套。
- 涉及可能为空的对象时，必须先判空再执行业务处理。
- 方法返回集合时优先返回空集合，不返回 `null`，除非项目原有约定明确要求返回 `null`。
- 方法返回对象时，如果无法构造有效结果可以返回 `null`，但调用方使用前必须判空。
- 不要随意改变已有方法入参、返回值、异常类型和调用链。

## 十一、基本修改原则

- 不要随意大范围重构，除非用户明确要求新建类、拆分类或重构。
- 不要删除已有业务逻辑和已有注释。
- 修改代码优先只处理当前问题相关逻辑。
- 补齐本次新增或实际修改代码的 JavaDoc，以及对该范围进行必要格式整理，不视为无关修改。
- 不得补齐当前文件中未发生实际修改代码的历史遗留 JavaDoc。
- 不得对 Git diff 范围外的代码进行换行、格式化或注释整理。
- 不确定业务含义时先保守处理，不得虚构业务规则。
- 生成代码时优先保证可读性和可维护性，不要为了简短牺牲清晰度。

## 十二、代码分层

### Controller 层

- Controller 必须保持轻薄，只负责接收请求、参数校验、调用 Service 和返回结果。
- Controller 不直接编写复杂业务判断、数据库操作或多个外部接口的业务编排。
- Controller 原有代码已经直接调用 Repository 或外部客户端时，除非用户明确要求重构，否则先保持原调用关系，只补充必要注释和当前问题相关修改。

### Service 层

- 业务逻辑应写在 Service 层。
- Service 负责业务编排、数据处理、状态判断和调用下游组件。
- DTO 可以直接透传到 Service，无必要不要在 Service 中重复创建 DTO。
- Service 应保持高内聚、低耦合，按功能或领域组织代码。
- 单个 Service 类通常控制在 200 至 400 行，原则上不超过 800 行；超过时优先按业务职责拆分，但不要只为满足行数强行拆分。
- 单个方法应职责清晰；逻辑复杂时可以通过私有方法拆分业务步骤，拆出的私有方法同样必须添加完整 JavaDoc。

## 十三、MyBatis-Plus 与实体类规范

- 本项目数据访问层统一使用 MyBatis-Plus，禁止使用 JPA、Hibernate 和 Spring Data JPA。
- 禁止引入或继续使用 `JpaRepository`、`@Entity`、`@Table`、`@Column`、`@Enumerated` 等 JPA 类型。
- 实体类必须使用 MyBatis-Plus 的 `@TableName` 标注表名；主键必须使用 `@TableId` 标注。
- 实体类统一使用 Lombok 的 `@Data`，禁止手写 Getter、Setter、`equals`、`hashCode` 和 `toString` 方法。
- 实体类除 `@Data` 外，禁止为了生成访问器、构造方法或 `toString` 再叠加其他 Lombok 注解；框架需要无参构造方法时，可显式声明无参构造方法并添加完整 JavaDoc。
- 实体类字段必须保留多行 JavaDoc；`@Data` 自动生成的方法不要求额外添加 JavaDoc。
- 数据访问接口必须继承 `BaseMapper<T>`，命名为 `XxxMapper`，并为接口和自定义方法添加完整 JavaDoc。
- Service 层必须通过 Mapper 操作数据库；Controller 不得直接调用 Mapper。

## 十四、禁止内部类型规范

- 禁止在普通类、接口、枚举、Record、注解类型中声明内部类、静态内部类、内部接口、内部枚举、内部 Record。
- 请求 DTO、响应 DTO、异常类、配置类、转换对象、查询结果对象必须拆分为独立顶级 Java 文件。
- 禁止使用 `Controller.Request`、`Service.Result`、`Config.Properties` 等嵌套类型写法。
- 每个公开顶级类、接口、枚举或 Record 必须单独保存为一个 `.java` 文件，文件名必须与公开类型名称一致。
- 拆分历史遗留内部类型时，必须保持原有接口字段名、JSON 字段名、方法签名、返回值和业务逻辑不变。

## 十五、提交前检查清单

在输出或提交 Java 代码前，只针对本次 Git diff 中新增或实际修改的代码元素进行以下检查：

- 本次新增或实际修改的类型定义是否有业务用途说明、`@author NPC` 和 `@date`。
- 是否误写成了 `@data`。
- 本次新增或实际修改的字段和常量是否都有多行 JavaDoc。
- 本次新增或实际修改的 `private final` 依赖字段是否都有 JavaDoc。
- 本次新增或实际修改的显式构造方法是否都有 JavaDoc 和完整 `@param`。
- 本次新增或实际修改的方法是否都有 JavaDoc，包括 Controller 方法、私有方法、静态方法、转换方法、工厂方法和重写方法。
- 本次新增或实际修改的非 `void` 方法是否都有 `@return`。
- 本次新增或实际修改的方法参数是否都有对应的 `@param`。
- 本次新增或实际修改的重要异常是否有 `@throws`。
- 本次新增或实际修改的 Record 是否有类型 JavaDoc，以及每个组件对应的 `@param`。
- 本次新增或实际修改的关键下游调用前是否有说明业务目的的普通行注释。
- 本次新增或实际修改的代码是否存在单行字段 JavaDoc、单行构造方法或单行方法体。
- 是否改变了与本次需求无关的业务逻辑。
- 是否修改了 Git diff 范围外的代码、注释或格式。
- 用户要求完整代码时，输出是否完整且没有使用省略号。
- 本次新增或实际修改的数据访问代码是否使用 MyBatis-Plus，且未引入 JPA 相关类型。
- 本次新增或实际修改的实体类是否使用且仅使用 `@Data` 管理访问器。
- 本次是否新增了内部类、内部接口、内部枚举或内部 Record；如有，必须拆分为独立顶级文件。
- 对本次需求无关的历史遗留内部类型，不得主动拆分或修改，除非用户明确要求。
