---
description: 检查 CLAUDE.md / AGENTS.md / lang / README 与代码是否一致
allowed-tools: read, write, edit, search, list, bash
---
对照当前代码逐项检查并列出不一致清单（不直接修改，除非用户确认）：

1. `CLAUDE.md` 红线（§1/§2/§3/§4.4）是否与 crop/CropGrowthTracker、event/CropGrowthHandler、mixin/* 的实际实现一致。
2. `AGENTS.md` 文件地图与类名/职责是否仍准确。
3. `src/main/resources/assets/pastoralcraft/lang/en_us.json` 的配置键是否与 CropGrowthConfig 的 11 个配置项一一对应（已知缺口：2 幽灵键 seasonChangePenaltyDays + 16 缺键，另 cropOverrides tooltip 过时）。
4. `README.md` 项目结构是否反映当前 6 个 Mixin 与新类。

输出格式：每项 = 文件位置 + 不一致描述 + 修改建议。
