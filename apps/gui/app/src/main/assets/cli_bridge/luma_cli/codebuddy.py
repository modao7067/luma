import json
import re
import secrets
import time
import threading
from .acp import AcpSession
from .base import item, text, image_blocks

from .transport import Rejected, Conflict
from .metrics import acp_usage, cache_ratio, estimate_cost, number

PROTOCOL = "codebuddy-http-acp"
SEMANTIC = {"user_message_chunk": "user", "agent_message_chunk": "assistant", "agent_thought_chunk": "reasoning"}

def request_id():
    value = (int(time.time() * 1000) << 80) | (7 << 76) | (secrets.randbits(12) << 64) | (2 << 62) | secrets.randbits(62)
    return f"{value:032x}"

class CodeBuddyProjection(AcpSession):
    correlated_delivery = True
    input_evidence_protocol = 1

    def call(self, method, params=None, **kwargs):
        submission = self.current_submission if method == "session/prompt" else None
        if submission:
            mapping = self.config.setdefault("codebuddyRequests",{})
            if submission not in mapping: mapping[submission]=request_id(); self.save()
            params = {**params, "_meta": {**params.get("_meta",{}), "codebuddy.ai/conversationRequestId": mapping[submission]}}
        result = super().call(method,params,**kwargs)
        if submission:
            returned = result.get("_meta",{}).get("codebuddy.ai/conversationRequestId")
            if returned and returned != self.config["codebuddyRequests"][submission]: raise ConnectionError("CodeBuddy 回合身份不匹配")
            if result.get("userMessageId"):
                mapping = self.config.setdefault("codebuddyUserMessages",{})
                native = result["userMessageId"]
                if native in mapping and mapping[native] != submission: raise ConnectionError("CodeBuddy 原生消息已绑定其它提交")
                if any(k!=native and v==submission for k,v in mapping.items()): raise ConnectionError("CodeBuddy 提交已绑定其它原生消息")
                mapping[native]=submission; self.save()
        return result

    def drain_permission(self,raw):
        with self.lock:
            rpc=raw.get("_rpcId",raw.get("id"))
            if rpc is None or raw.get("_rpcDrained"):return
            raw["_rpcDrained"]=True
            self.peer.send({"jsonrpc":"2.0","id":rpc,"result":{"outcome":{"outcome":"cancelled"}}})

    def answer(self,payload):
        raw=self.pending_requests.get(payload["id"])
        if raw and (raw.get("_codebuddyAskPermission") or raw.get("_codebuddyInterruption")):
            if raw.get("_answerDispatched"):raise Rejected("此 CodeBuddy 答复已提交，结果待确认")
            option=next((v for v in raw["params"].get("options",[]) if v["optionId"]==payload["choice"]),None)
            allowed=option and option.get("kind") in ("allow_once","allow_always")
            answers={}
            if allowed:
                for index,question in enumerate(raw["params"]["toolCall"].get("rawInput",{}).get("questions",[])):
                    ident=question.get("id") or f"q_{index}"
                    values=payload.get("answers",{}).get(ident,[])
                    if not values or any(not isinstance(v,str) or not v.strip() for v in values):raise Rejected("请回答："+question.get("question",""))
                    if not question.get("multiSelect") and len(values)!=1:raise Rejected("此问题只能提交一个答案")
                    answers[ident]=values
            elif not option and payload["choice"]!=raw.get("_cancelChoice"):raise Rejected("无效 CodeBuddy 答复选项")
            raw["_answerDispatched"]=True
            result=self.call("_codebuddy.ai/resolveInterruption",{"sessionId":self.data["nativeId"],"toolCallId":raw["params"]["toolCall"]["toolCallId"],
                "decision":option.get("_decision",("allowAll" if option.get("kind")=="allow_always" else "allow") if allowed else "deny") if option else "deny",**({"answers":answers} if allowed else {})})
            if result.get("resolved") is not True:raise Rejected("CodeBuddy 问题已失效或尚未确认答复")
            self.drain_permission(raw)
            self.answered(payload["id"]);return {}
        if not raw or not raw.get("_codebuddyQuestion"):return super().answer(payload)
        choice=payload["choice"]
        if choice=="cancel":result={"outcome":"cancelled"}
        elif choice=="submit":
            answers={}
            for question in raw["params"]["schema"]["questions"]:
                values=payload.get("answers",{}).get(question["id"],[])
                if not values or any(not isinstance(v,str) or not v.strip() for v in values):raise Rejected("请回答："+question.get("question",""))
                if not question.get("multiSelect") and len(values)!=1:raise Rejected("此问题只能提交一个答案")
                answers[question["id"]]=values
            result={"outcome":"submitted","answers":answers}
        else:raise Rejected("无效 CodeBuddy 答复选项")
        self.peer.send({"jsonrpc":"2.0","id":raw["id"],"result":result});self.answered(payload["id"]);return {}

    def apply_commands(self,commands):
        super().apply_commands(commands)
        skills=[{"id":c["name"],"name":c["name"],"path":""} for c in commands if c.get("name") and c.get("_meta",{}).get("type")=="skill"]
        self.update(skills=skills,capabilities={**self.data["capabilities"],"skills":bool(skills),"maxSkills":1})

    def prompt(self,payload):
        if payload.get("skills"):payload={**payload,"text":"/"+payload["skills"][0]+" "+payload.get("text","")}
        if not payload.get("steer"): return super().prompt(payload)
        ident=payload["submissionId"]
        self.config.setdefault("codebuddySteers",{})[ident]=True; self.save()
        self.row(item(ident,"user",payload.get("text",""),origin="local"))
        blocks=([{"type":"text","text":payload["text"]}] if payload.get("text") else [])+image_blocks(payload.get("images",[]))
        def dispatch():
            try:
                result=self.call("session/steer",{"sessionId":self.data["nativeId"],"contentBlocks":blocks,"clientUserMessageId":ident,
                    "_meta":{"lumaSubmissionId":ident}},on_uncertain=lambda error:self.diagnostic("codebuddy-steer-result-unknown",error))
                if result.get("steered") is True:self.receipt(ident,"accepted")
                elif result.get("steered") is False:
                    self.receipt(ident,"rejected");self.update(error="CodeBuddy 未接纳插话（"+str(result.get("reason","not-steered"))+"）")
                else:raise ConnectionError("CodeBuddy 插话返回缺少原生受理结果")
            except Exception as error:
                self.diagnostic("codebuddy-steer-result-unknown",error)
                self.update(error="CodeBuddy 插话结果待确认（"+type(error).__name__+"）")
        threading.Thread(target=dispatch,daemon=True,name="LumaCodeBuddySteer").start()
        return False

    def can_steer(self,payload):
        return super().can_steer(payload) and not payload.get("skills") and not re.match(r"^/[a-z][a-z0-9-]*(?:\s|$)",payload.get("text","").strip(),re.I)

    def validate_prompt(self,payload):
        super().validate_prompt(payload)
        if len(payload.get("skills",[]))>1:raise Rejected("CodeBuddy 每条消息最多调用一个原生 Skill")
        if any(k not in {v["id"] for v in self.data["skills"]} for k in payload.get("skills",[])):raise Rejected("Skill 不在 CodeBuddy 原生目录中")
        words=payload.get("text","").strip().split(maxsplit=1)
        if words and words[0].lower() in ("/clear","/new","/reset","/rewind","/fork","/branch","/fork-bg","/bg","/background"):
            raise Rejected("此命令会改变托管会话身份或执行所有者；请使用新建会话")

    def apply_options(self, options):
        super().apply_options(options)
        metadata = getattr(self,"model_metadata",{})
        for model in self.data["models"]:
            meta=metadata.get(model["id"],{})
            model["supportsImages"]=meta.get("supportsImages",False)
            if meta and not meta.get("supportsReasoning"): model["efforts"]=[]
            elif meta:
                available={v.get("value") for v in meta.get("effortOptions",[])}|{"disabled","enabled"}
                model["efforts"]=[v for v in model["efforts"] if v in available]

    def apply_usage(self,update):
        model=self.data.get("model") or self.config.get("model")
        self.acp_metrics=acp_usage(update,model=model)
        usage=update.get("_meta",{}).get("usage")
        if isinstance(usage,dict):
            cached=(usage.get("prompt_tokens_details") or {}).get("cached_tokens")
            prompt_tok=usage.get("prompt_tokens")
            out_tok=usage.get("completion_tokens") or 0
            self.request_metrics=cache_ratio(cached,prompt_tok,"最近请求输入缓存命中")
            if not any(r["label"].startswith(("CLI 记录费用","CLI 累计估算")) for r in self.acp_metrics):
                uncached=max(0,(number(prompt_tok) or 0)-(number(cached) or 0))
                self.request_metrics+=estimate_cost(model,uncached_input=uncached,cache_read=number(cached) or 0,output=number(out_tok) or 0)
        self.update(metrics=self.acp_metrics+getattr(self,"request_metrics",[]))

    def event(self,frame):
        with self.lock:self._event(frame)

    def _event(self,frame):
        params=frame.get("params",{}); expected=self.data["nativeId"]
        if expected and params.get("sessionId") and params["sessionId"] != expected: return
        if frame.get("method")=="_codebuddy.ai/question" and "id" in frame:
            questions=params.get("schema",{}).get("questions",[])
            if params.get("inputType")!="question" or not isinstance(questions,list):raise Rejected("CodeBuddy 未提供可识别的问题表单")
            raw={**frame,"_codebuddyQuestion":True,"_cancelChoice":"cancel"}
            self.request(str(frame["id"]),raw,"Answer","",[{"id":"submit","label":"提交"},{"id":"cancel","label":"取消"}],
                [{"id":q["id"],"header":q.get("header",""),"prompt":q.get("question",""),"options":[v["label"] for v in q.get("options",[])],"multiple":bool(q.get("multiSelect"))} for q in questions],submit_choice="submit",skip_choice="cancel")
            return
        if frame.get("method")=="session/request_permission" and "id" in frame:
            tool_id=params.get("toolCall",{}).get("toolCallId")
            if tool_id in getattr(self,"resolved_interruption_tools",set()):
                self.drain_permission(frame);return
            observed=next((v for v in self.pending_requests.values() if v.get("_codebuddyInterruption") and v["params"]["toolCall"]["toolCallId"]==tool_id),None)
            if observed:
                observed["_rpcId"]=frame["id"];return
            super().event(frame)
            tool=params.get("toolCall",{})
            questions=tool.get("rawInput",{}).get("questions",[])
            if tool.get("_meta",{}).get("codebuddy.ai/toolName")=="AskUserQuestion" and questions:
                key=str(frame["id"]);raw=self.pending_requests[key];raw["_codebuddyAskPermission"]=True
                existing=next(v for v in self.data["requests"] if v["id"]==key)
                submit=next((v["optionId"] for v in params.get("options",[]) if v.get("kind")=="allow_once"),None)
                self.request(key,raw,"Answer","",existing["choices"],
                    [{"id":q.get("id") or f"q_{i}","header":q.get("header",""),"prompt":q.get("question",""),"options":[v["label"] for v in q.get("options",[])],"multiple":bool(q.get("multiSelect"))} for i,q in enumerate(questions)],
                    submit_choice=submit,skip_choice=raw["_cancelChoice"])
            return
        if frame.get("method") != "session/update": return super().event(frame)
        update=params.get("update",{}); kind=update.get("sessionUpdate");meta=update.get("_meta",{})
        interruption=meta.get("codebuddy.ai/interruptionRequest")
        if isinstance(interruption,dict) and interruption.get("toolCallId"):
            tool_id=interruption["toolCallId"]
            if tool_id in getattr(self,"resolved_interruption_tools",set()):return
            if not any(v.get("params",{}).get("toolCall",{}).get("toolCallId")==tool_id for v in self.pending_requests.values()):
                ident=interruption["interruptionId"]
                options=[{"optionId":v,"name":{"allow":"允许一次","allowAll":"始终允许","deny":"拒绝","rejectAndExitPlan":"拒绝并退出计划"}.get(v,v),
                    "kind":{"allow":"allow_once","allowAll":"allow_always"}.get(v,"reject_once"),"_decision":v} for v in interruption.get("options",[])]
                tool={"toolCallId":tool_id,"rawInput":interruption.get("toolInput") or {},"_meta":{"codebuddy.ai/toolName":interruption.get("toolName")}}
                raw={"_codebuddyInterruption":True,"_cancelChoice":"cancel","params":{"toolCall":tool,"options":options}}
                questions=tool["rawInput"].get("questions",[]) if interruption.get("toolName")=="AskUserQuestion" else []
                self.request(ident,raw,interruption.get("toolTitle","工具授权"),json.dumps(tool["rawInput"],ensure_ascii=False),
                    [{"id":v["optionId"],"label":v["name"]} for v in options]+[{"id":"cancel","label":"取消"}],
                    [{"id":q.get("id") or f"q_{i}","header":q.get("header",""),"prompt":q.get("question",""),"options":[v["label"] for v in q.get("options",[])],"multiple":bool(q.get("multiSelect"))} for i,q in enumerate(questions)],
                    submit_choice="allow" if questions and "allow" in interruption.get("options",[]) else None,skip_choice="cancel")
            return
        if meta.get("codebuddy.ai/permissionResolved") is True:
            if not hasattr(self,"resolved_interruption_tools"):self.resolved_interruption_tools=set()
            self.resolved_interruption_tools.add(meta.get("codebuddy.ai/toolCallId"))
            for ident,raw in list(self.pending_requests.items()):
                if raw.get("params",{}).get("toolCall",{}).get("toolCallId")==meta.get("codebuddy.ai/toolCallId"):
                    self.drain_permission(raw)
                    self.answered(ident)
            return
        parent=meta.get("codebuddy.ai/parentToolCallId")
        if parent:
            ident="codebuddy-task:"+parent
            old=self.subagents.get(ident,{})
            detail=old.get("detail","")
            if kind in SEMANTIC: detail+=text(update.get("content"))
            elif kind in ("tool_call","tool_call_update"):
                detail+="\n"+str(meta.get("codebuddy.ai/toolName") or update.get("title", ""))
            self.subagent(ident,task=old.get("task","CodeBuddy 子代理任务"),detail=detail,status=old.get("status","running"),itemId="tool-"+parent)
            return
        team=meta.get("codebuddy.ai/teamUpdate")
        if isinstance(team,dict):
            for member in team.get("members",[]):
                native=member.get("sessionId") or member.get("taskId")
                if not native: continue
                status=member.get("status","unknown")
                self.subagent("codebuddy-member:"+native,name=member.get("name",""),task=member.get("description", ""),
                    nativeStatus=status,status={"completed":"succeeded","failed":"failed","cancelled":"cancelled","running":"running","idle":"unknown"}.get(status,"unknown"))
        snapshot=meta.get("codebuddy.ai/sessionSnapshot")
        if isinstance(snapshot,dict) and isinstance(snapshot.get("busy"),bool) and self.current_submission is None:
            self.update(running=snapshot["busy"])
        if meta.get("codebuddy.ai/isCompactInternal"):
            self.invalidate_context();return
        phase=meta.get("codebuddy.ai/agentPhase",{}).get("phase")
        phase_request=meta.get("codebuddy.ai/requestId")
        if phase and self.current_submission is None:
            if phase=="idle" and (not self.external_request or phase_request==self.external_request):
                self.finish_chunk();self.external_request=None;self.update(running=False)
            elif phase!="idle" and phase_request:
                self.external_request=phase_request;self.update(running=True)
        conversation=meta.get("codebuddy.ai/conversationRequestId")
        active=self.current_submission
        exact=bool(active and conversation and self.config.get("codebuddyRequests",{}).get(active)==conversation)
        if kind in SEMANTIC:
            role=SEMANTIC[kind];native=update.get("messageId")
            submission=self.config.get("codebuddyUserMessages",{}).get(native) if role=="user" else None
            if role=="user" and native in self.config.get("codebuddySteers",{}): submission=native
            if role=="user" and native and not submission and conversation and "codebuddy.ai/steerClientMeta" not in meta:
                candidate=next((k for k,v in self.config.get("codebuddyRequests",{}).items() if v==conversation),None)
                bindings=self.config.setdefault("codebuddyUserMessages",{})
                if candidate and not any(v==candidate and k!=native for k,v in bindings.items()):
                    submission=candidate; bindings[native]=candidate; self._checkpoint_native_evidence("codebuddy-user-binding")
            if not native and not submission:
                self.diagnostic("codebuddy-semantic-identity-missing");return
            ident=submission or f"codebuddy:{role}:{native}"
            if self.chunk_id != ident:self.finish_chunk()
            self.chunk_id,self.chunk_kind=ident,kind
            if self.replaying_history and ident not in self.history_seen:
                self.history_seen.add(ident);self.rows.pop(ident,None)
            old=self.rows.get(ident,item(ident,role,"",kind="reasoning" if role=="reasoning" else ""))
            if role=="user" and submission and old.get("origin")=="local":old={**old,"text":"","media":[]}
            self.row({**old,"text":old["text"]+text(update.get("content")),"media":old.get("media",[])+self.media(update.get("content")),"origin":"native","status":"completed" if self.replaying_history or role=="user" else "running"})
            if submission:self.receipt(submission,"accepted");self.consumed(submission)
            if exact and role!="user":self.receipt(active,"accepted");self.consumed(active)
            if not self.replaying_history and role!="user":
                self.external_request=conversation or self.external_request;self.update(running=True)
            return
        if kind=="session_end":
            native_request=conversation or meta.get("codebuddy.ai/requestId")
            if self.current_submission is None and native_request and native_request==self.external_request:
                self.finish_chunk("cancelled" if update.get("stopReason")=="cancelled" else "completed")
                self.external_request=None;self.update(running=False)
            return
        self.current_submission=None
        try:super().event(frame)
        finally:self.current_submission=active
        if kind in ("tool_call","tool_call_update"):
            tool_name=meta.get("codebuddy.ai/toolName") or update.get("toolName")
            if tool_name:
                ident="tool-"+update["toolCallId"]
                self.row({**self.rows[ident],"toolName":tool_name})
            ident="codebuddy-task:"+update["toolCallId"]
            if meta.get("codebuddy.ai/isSubagent") is True:
                self.subagent(ident,itemId="tool-"+update["toolCallId"],name=meta.get("codebuddy.ai/description", ""),
                    role=meta.get("codebuddy.ai/subagentType", ""),task=update.get("rawInput",{}).get("prompt", ""),
                    background=meta.get("codebuddy.ai/isBackground") is True,status="running")
            agent=self.subagents.get(ident)
            if agent and not agent.get("background") and update.get("status") in ("completed","failed","cancelled"):
                self.subagent(ident,status={"completed":"succeeded","failed":"failed","cancelled":"cancelled"}[update["status"]],
                    detail=self.rows["tool-"+update["toolCallId"]].get("outcome",agent.get("detail","")))
            if exact:self.receipt(active,"accepted");self.consumed(active)

class CodeBuddySession(CodeBuddyProjection):
    argv = ["codebuddy", "--serve", "--host", "127.0.0.1", "--auth", "password"]
    desired_model_on_create = True
    replay_release = True
    release_empty = True

    def connect(self):
        self.external_request = None; self.replaying_history = True; self.history_seen = set()
        if self.config.setdefault("nativeProtocol","codebuddy-http-acp") != "codebuddy-http-acp":raise Rejected("CodeBuddy 原生协议身份不匹配")
        self.save()
        self.model_metadata = {}
        try: AcpSession.connect(self)
        finally: self.replaying_history = False
        self.finish_chunk(); self.chunk_kind = self.chunk_id = None
        self.update(nativeProtocol="codebuddy-http-acp", canOpenTui=False, notice="CodeBuddy 使用安全 GUI 接入，不提供同会话原生终端。",
            capabilities={**self.data["capabilities"],"steer":True})
        controls=self.config.get("codebuddyControls",{})
        if any(self.data.get(k)!=v for k,v in controls.items()):raise Rejected("CodeBuddy 尚未恢复已确认的会话设置")
        self.update_model_capabilities()
        self.refresh()

    def start(self, argv, env_overrides=None):
        from .codebuddy_owner import CodeBuddyOwner
        self.owner=CodeBuddyOwner(self)
        self.owner.launch(env_overrides)
        self.peer=self.owner.connect_http(self.event,self.exited,lambda:bool(self.pending_requests))
        self.peer.start_read()

    def call(self,method,params=None,**kwargs):
        if method=="initialize":
            if getattr(self.peer,"resumed_connection",False) and self.config.get("codebuddyInitialize"):
                return self.config["codebuddyInitialize"]
            capabilities=params.get("clientCapabilities",{})
            params={**params,"clientCapabilities":{**capabilities,"_meta":{**capabilities.get("_meta",{}),"codebuddy.ai":{"question":True}}}}
        result=super().call(method,params,**kwargs)
        if method=="initialize":
            self.config["codebuddyInitialize"]={k:result[k] for k in ("protocolVersion","agentCapabilities") if k in result}
            self.config["codebuddyInitialize"]["_meta"]={"availableCommands":result.get("_meta",{}).get("availableCommands",[])}
            self.save()
        if method in ("session/new","session/load"):
            self.model_metadata={m["modelId"]:m.get("_meta",{}) for m in result.get("models",{}).get("availableModels",[]) if m.get("modelId")}
        return result

    def configure(self,payload):
        effort=payload.get("effort")
        current=next((m for m in self.data["models"] if m["id"]==payload.get("model",self.data["model"])),None)
        if effort and current is not None and effort not in current.get("efforts",[]):raise Rejected("当前 CodeBuddy 模型未声明此思考强度")
        result=AcpSession.configure(self,payload)
        self.update_model_capabilities()
        self.config["codebuddyControls"]={k:self.data[k] for k in ("permission","effort") if self.data.get(k)}
        self.save()
        return result

    def update_model_capabilities(self):
        current=next((m for m in self.data["models"] if m["id"]==self.data["model"]),{})
        self.update(capabilities={**self.data["capabilities"],"images":bool(current.get("supportsImages"))})

    def release_mode(self): return AcpSession.release_mode(self)
    def refresh(self):
        result=self.call("_codebuddy.ai/goalControl",{"sessionId":self.data["nativeId"],"action":"snapshot"})
        if result.get("ok") is not True or result.get("applied")!="snapshot" or not isinstance(result.get("runInFlight"),bool):
            raise Rejected("CodeBuddy 未返回可确认的原生运行状态")
        if self.current_submission is None:self.update(running=result["runInFlight"])
        connection=None
        try:
            connection,response=self.peer.request("GET","/api/v1/mcp/servers")
            if response.status!=200:raise Rejected("无法获取 MCP")
            data=json.loads(response.read(1024*1024+1)).get("data",[])
            if not isinstance(data,list):raise Rejected("无法获取 MCP")
            self.update(mcp=[{"name":v["name"],"detail":" · ".join(str(v[k]) for k in ("transport","scope","status") if v.get(k))} for v in data if isinstance(v,dict) and isinstance(v.get("name"),str)],mcpAvailable=True,mcpError=None)
        except Exception:
            self.update(mcpAvailable=True,mcpError="无法获取 MCP")
        finally:
            if connection:self.peer.finish(connection)
        return {}
    def terminal(self): raise Rejected("CodeBuddy 当前未提供安全的同执行所有者交互终端")
