# Agent templates

Ready-to-copy files that teach an AI coding assistant to use KmperTrace in your project. They are
based on [AI-assistants.md](../AI-assistants.md) and verified against KmperTrace 0.3.3.

- `claude-skill/SKILL.md`: copy to `.claude/skills/kmpertrace/SKILL.md` in your project (Claude Code).
- `cursor-rule.mdc`: copy to `.cursor/rules/kmpertrace.mdc` in your project (Cursor).
- `copilot-instructions.md`: add its content to `.github/copilot-instructions.md` in your project (GitHub Copilot).
- `agents-snippet.md`: paste into `AGENTS.md` or `CLAUDE.md` at the root of your project.

File locations and formats were checked against the Claude Code, Cursor and GitHub Docs pages on
2026-10-16. `agents-snippet.md` relies on tools reading `AGENTS.md` or `CLAUDE.md`. Only GitHub
Copilot's support for both was confirmed in its docs. Support in Codex and other tools was not checked.
