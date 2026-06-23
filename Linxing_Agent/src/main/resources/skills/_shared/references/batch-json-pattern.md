# 分批构建 JSON 通用模式

当需要通过工具调用输出包含大量数组元素的 JSON 时，使用分批构建模式替代一次性输出，降低 JSON 语法错误概率。

## 何时使用分批模式

| 条件 | 模式 | 理由 |
|------|------|------|
| 数组元素 ≤ 5，且单元素结构简单 | 一次性调用 save 工具 | JSON 短，出错概率低 |
| 数组元素 > 5，或单元素结构复杂 | 分批模式 | 降低单次输出长度，减少语法错误 |
| 用户明确要求大量输出 | 分批模式 | 明确的大批量场景 |

## 工具列表

| 工具 | 用途 |
|------|------|
| `create_container` | 创建容器，声明元数据和数组路径 |
| `append_to_container` | 向容器数组追加元素（每次 1-3 个） |
| `replace_in_container` | 替换容器数组中指定索引的元素 |
| `replace_container_metadata` | 更新容器元数据字段 |
| `remove_from_container` | 移除容器数组中指定索引的元素 |

## 标准流程

### 正常流程

```
1. create_container(
     container_type="{业务类型}",
     metadata={顶层元数据字段},
     array_paths=["{数组路径}"]
   )
   → {"containerId": "{type}_{random6Hex}"}

2. append_to_container(container_id="...", array_path="{数组路径}", items=[元素1, 元素2, ...])
   → {"currentCount": N, "appendedCount": M}

   ... 重复追加，每次 1-3 个元素 ...

3. {save_tool}(container_id="...")
   → 容器拼装为完整 JSON → 校验 → 持久化
   → 返回业务结果
```

### 校验失败修正流程

当 save 工具返回校验错误（含 `index` + `field` + `message`）时：

```
1. 根据错误信息，使用 replace_in_container 精确替换错误元素:
   replace_in_container(
     container_id="...",
     array_path="{数组路径}",
     index={错误索引},
     item={修正后的完整元素}
   )

2. 重新调用 {save_tool}(container_id="...")
```

### 兜底流程

同一元素修正 3 次仍不通过时，移除该元素：

```
remove_from_container(container_id="...", array_path="{数组路径}", index={错误索引})
→ 移除后后续元素索引前移，注意后续 replace/remove 操作的索引变化

重新调用 {save_tool}(container_id="...")
```

## 各 Skill 引用方式

在 Skill 的步骤指导中引用本模板，补充业务粒度信息：

> 当需要生成超过 5 个数组元素时，使用分批模式（参考 `_shared/references/batch-json-pattern.md`）：
> 先调 `create_container(container_type="{业务类型}", metadata={...}, array_paths=["{数组路径}"])`，
> 再每次 `append_to_container` 追加 2-3 个元素，
> 最后调 `{save_tool}(container_id=...)` 完成保存。
> 若 save 失败，根据错误信息用 `replace_in_container` 精确修正。

## 注意事项

- 容器存储在请求内存中，请求结束即销毁，无需手动清理
- `append_to_container` 每次建议追加 1-3 个元素，避免单次输出过长
- `remove_from_container` 后索引前移，后续操作需注意索引变化
- 容器不做格式校验，校验职责在各 save 工具
- 分批模式是可选的，简单场景仍走一次性调用，不破坏现有流程
