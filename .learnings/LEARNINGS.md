# Learnings Log

---

## [LRN-20260601-001] correction

**Logged**: 2026-06-01T16:00:00+08:00
**Priority**: high
**Status**: pending
**Area**: config

### Summary
Skill 的 description 应该是语义意图描述，不是关键词列表。堆砌中文短语无法覆盖用户的所有表达方式。

### Details
用户说"单独出一个计划"时，export-plan skill 没有触发。原因是 description 里列了"给出计划"、"给个方案"等短语，但没有"出一个计划"、"盘一下"、"理一下思路"等变体。Claude Code 的 skill 匹配是基于语义相似度的，应该描述用户意图（"wants to think through design before writing code"）而非列举短语。

### Suggested Action
- 修改 export-plan/SKILL.md 的 description，改为意图描述而非关键词列表（已在本次对话中完成）
- 对所有 skill 的 description 做同样的审查
- 检查 self-improving-agent 的 description 是否也有同样问题

### Metadata
- Source: user_feedback
- Related Files: `.claude/skills/export-plan/SKILL.md`
- Tags: skill-triggering, description-format

---

## [LRN-20260601-002] correction

**Logged**: 2026-06-01T16:00:00+08:00
**Priority**: medium
**Status**: pending
**Area**: config

### Summary
搜索 lint 工具时，grep 模式太窄（只搜了 checkstyle/spotless/pmd），漏掉了 spotbugs。

### Details
用户问"没有Lint工具么"时，我回答没有。实际上 `pom.xml` 中配置了 spotbugs-maven-plugin。grep 命令 `grep -r "checkstyle\|spotless\|pmd\|lint" pom.xml` 漏掉了 spotbugs。应该用更广泛的搜索模式，或者直接查看 pom.xml 中的所有 plugin。

### Suggested Action
- 查找项目工具/依赖时，优先读取 pom.xml 中的 `<plugins>` 和 `<dependencies>` 段，而非靠 grep 猜测
- grep 搜索 lint 相关时至少包含：checkstyle, spotbugs, pmd, spotless, lint, verify, analyze
- 在 CLAUDE.md 中记录项目已配置的静态分析工具

### Metadata
- Source: user_feedback
- Related Files: `pom.xml`
- Tags: grep-scope, static-analysis, tool-discovery

---

## [LRN-20260601-003] correction

**Logged**: 2026-06-01T16:00:00+08:00
**Priority**: medium
**Status**: pending
**Area**: backend

### Summary
用户指出 dispatcher 和 connectionManager 都是单例时，我应该主动确认而非被动接受。

### Details
用户说"dispatcher和connectionManager都是单例的吧，都是单例的给它设置成单例的"。这实际上是用户在告诉我一个我应该自己推断出的结论——NettyTransportClient 是单例（通过 RpcApplication 双重检查锁创建），它的依赖也必然是单例。我应该主动识别这一点并提出改造，而不是等用户指出。

### Suggested Action
- 当分析类之间的依赖关系时，检查依赖链上的实例生命周期
- 如果一个类 X 是单例，它的字段（非原型作用域）本质上也是单例——可以提示用户是否需要显式化

### Metadata
- Source: user_feedback
- Related Files: `NettyTransportClient.java`, `ResponseDispatcher.java`, `ConnectionManager.java`
- Tags: singleton-detection, dependency-analysis

---

## [LRN-20260601-004] correction

**Logged**: 2026-06-01T16:00:00+08:00
**Priority**: high
**Status**: pending
**Area**: config

### Summary
self-improving-agent skill 本身在用户纠正我的错误时没有被触发，这是一个元问题。

### Details
本次对话中用户至少纠正了 3 次错误，但 self-improving-agent skill 一次都没有自动触发。原因可能是：
1. 该 skill 的 description 也没有足够明确地描述触发意图
2. 当用户纠正错误时，我没有停下来检查是否有对应的 skill 应该被调用
3. 纠正通常是即时的、嵌入在对话流中的，不容易被 skill 匹配

### Suggested Action
- 在 CLAUDE.md 中添加行为规则：用户纠正错误后，主动询问是否需要记录到 learnings
- 或者：检查 self-improving-agent/SKILL.md 的 description 是否需要优化
- 考虑是否需要 hook 来自动检测纠正场景

### Metadata
- Source: user_feedback
- Related Files: `.claude/skills/self-improving-agent/SKILL.md`
- Tags: meta-learning, skill-triggering, self-awareness

---
