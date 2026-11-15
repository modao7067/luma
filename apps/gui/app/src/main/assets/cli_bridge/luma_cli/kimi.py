from .acp import AcpSession
from .i18n import tr
from .transport import Rejected

class KimiSession(AcpSession):
    replay_release = True
    argv = ["kimi", "acp"]
    supports_forms = True
    builtins = {"compact", "status", "usage", "mcp", "tasks", "help"}

    def apply_commands(self, commands):
        super().apply_commands(commands)
        skills = [{"id": c["name"], "name": c["name"], "path": ""}
                  for c in commands if c.get("name") and c["name"] not in self.builtins]
        self.update(skills=skills, capabilities={**self.data["capabilities"], "skills": bool(skills), "maxSkills": 1},
                    notice=tr("使用 Kimi Code 原生 ACP 模型、思考、权限和审批；原生 load 重放历史。每条消息最多调用一个已发现 Skill。无待发队列或实时 TUI 接管；MCP 配置保留在原生 CLI。", "Uses native Kimi Code ACP model, reasoning, permissions, and approvals; replays history via native load. Up to one discovered Skill per message. No pending queue or live TUI handoff; MCP configuration stays in native CLI."))

    def validate_prompt(self, p):
        super().validate_prompt(p)
        if len(p.get("skills", [])) > 1: raise Rejected(tr("Kimi Code 每条消息最多选一个 Skill", "Kimi Code allows at most one Skill per message"))
        if any(k not in {s["id"] for s in self.data["skills"]} for k in p.get("skills", [])):
            raise Rejected(tr("Skill 不在原生目录中", "Skill is not in the native catalog"))

    def prompt(self, p):
        if p.get("skills"):
            p = {**p, "text": "/" + p["skills"][0] + " " + p["text"]}
        return super().prompt(p)
