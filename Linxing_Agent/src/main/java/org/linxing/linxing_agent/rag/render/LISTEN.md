为简化代码复杂度，此包下的所有类不再被外界使用，已标记废弃


## 废弃原因
推荐：这次重构里直接删除 render/ 包。

下面说为什么不复用。

复用的前提不成立
复用要成立，得满足"抽出去的抽象比内联更值"。但看现状：

1. 三处渲染逻辑完全等价，复用必须改逻辑，不是纯移动

位置	做的事
NodeBasedChunkBuilder.buildChunkFromNodes 一个循环里同时拼 displayText + indexText
NodeBasedChunkBuilder.renderForIndex	单拼 indexText
render/ 三个类	同样逻辑，封装成策略接口
三处的拼接规则完全一致（NODE_SEPARATOR = "\n\n"、调 originalContent() / semanticText()）。要让 render 包被"复用"，就得把 buildChunkFromNodes 里的内联循环替换成 displayRenderer.render(nodes) + indexRenderer.render(nodes)，把 renderForIndex 替换成 indexRenderer.render(nodes)。这是逻辑改动，不是包移动——会混进本次"纯结构重构"的 diff，破坏"只移动不改逻辑"的边界。

2. 接口设计本身没站住

ChunkRenderer 是个策略接口，但全局只有两个实现、零个注入点、零个调用点。它是"为未来可能扩展第三种渲染方式"而提前设计的抽象——典型的 YAGNI。在没有第三个实现之前，接口不提供任何多态价值，只提供间接成本。一个没人调用的策略接口，不是"待激活的架构"，是"未经验证的猜测"。

3. Display/Index 双轨是架构决策，但它的载体应该是 Node 本身，不是 Renderer

双轨的真正落点在 DocumentNode 的两个方法：originalContent()（Display）和 semanticText()（Index）。多态已经发生在 Node 层——ImageNode/CodeNode/TableNode 各自实现这两个方法。Renderer 只是把"调这两个方法 + 拼字符串"包了一层，没有引入新的多态维度。换句话说：渲染的差异已经被 Node 的多态吃掉了，Renderer 是一层没有差异的策略壳。

什么情况下才该复用
只有当出现下面任一信号，才值得把渲染从 ChunkBuilder 里抽出来：

出现第三种渲染用途（如 BM25 专用分词文本、QA 抽取专用文本），且拼接规则与 display/index 不同
多个不同类需要独立调用渲染（目前只有 NodeBasedChunkBuilder 一处）
渲染逻辑变复杂（如需要按 chunkLevel 差异化拼接、需要注入 titlePath 前缀）
目前三个条件一个都不满足。需求没出现就抽接口，抽出来的接口又没人用，反而成了这次要清理的对象——这正是 render 包的现状。