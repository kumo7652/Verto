# Errors Log

---

## [ERR-20260601-001] grep_lint_tools

**Logged**: 2026-06-01T16:00:00+08:00
**Priority**: high
**Status**: pending
**Area**: config

### Summary
grep 搜索 lint 工具时遗漏了 spotbugs，导致错误回答"没有 lint 工具"

### Error
```
用户: "没有Lint工具么，我这有一个spotbugs啊"
→ 此前我回答"没有 lint 工具配置"，但 pom.xml 中实际有 spotbugs-maven-plugin
```

### Context
- 命令: `grep -r "checkstyle\|spotless\|pmd\|lint" pom.xml **/pom.xml`
- 遗漏: `spotbugs` 不在搜索模式中
- 正确做法: 读取 pom.xml 的 `<build><plugins>` 段，或搜索 `maven-plugin`

### Suggested Fix
1. 查找构建工具/插件时，直接读取 pom.xml 的 `<plugins>` 段
2. 如用 grep，至少包含: `checkstyle|spotbugs|pmd|spotless|error-prone|forbidden-apis|jacoco`
3. 在 CLAUDE.md 中记录已配置的静态分析工具列表

### Metadata
- Reproducible: yes
- Related Files: `pom.xml`, `**/pom.xml`
- Tags: grep, static-analysis, pom.xml

---

## [ERR-20260601-002] skill_not_triggered

**Logged**: 2026-06-01T16:00:00+08:00
**Priority**: high
**Status**: pending
**Area**: config

### Summary
export-plan 和 self-improving-agent 两个 skill 都未被自动触发，需要用户手动调用

### Error
```
用户: "你为什么没有使用skill" (对 export-plan)
用户: "你有没有触发self-improving-agent这个skill吗"
→ 两个 skill 都未被自动触发，需要用户手动提醒
```

### Context
- export-plan: description 中缺少用户使用的表达方式（语义不匹配）
- self-improving-agent: 用户纠正错误时未触发，可能是 description 不够明确或纠正场景嵌入对话流中不易匹配
- 根本原因: skill description 应该描述意图而非列举关键词

### Suggested Fix
1. export-plan description 已修复（改为意图描述）
2. self-improving-agent description 待审查和优化
3. 考虑在 CLAUDE.md 中添加规则：当用户说"你为什么没有..."时，立即检查相关 skill

### Metadata
- Reproducible: yes (可能在其他 skill 上也会出现)
- Related Files: `.claude/skills/*/SKILL.md`
- Tags: skill-triggering, description

---
