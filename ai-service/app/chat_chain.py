"""带工具的对话。

这里**不实现 Agent 循环**——只做一件事：把对话和工具清单交给模型，返回"模型下一步想干什么"。
循环、工具执行、终止判断都在 Java 侧。

为什么这么切：工具要查数据库，而 AI 服务刻意不碰库（见 main.py 的定位）。
如果把循环放这边，就得让 Python 回调 Java 的接口，链路绕且难排查。
放业务侧之后，每一轮的工具调用都能落日志、能写进 trace、能被单测覆盖。
"""

import logging

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from .llm import get_llm
from .schemas import ChatMessage, ChatRequest, ChatResponse, ToolSpec

logger = logging.getLogger("ai.chat")


def _to_lc_messages(messages: list[ChatMessage]):
    """把跨语言的扁平结构翻译成 LangChain 的消息对象。"""
    out = []
    for m in messages:
        role = (m.role or "").lower()
        if role == "system":
            out.append(SystemMessage(content=m.content or ""))
        elif role == "user":
            out.append(HumanMessage(content=m.content or ""))
        elif role == "assistant":
            if m.tool_calls:
                out.append(AIMessage(content=m.content or "", tool_calls=m.tool_calls))
            else:
                out.append(AIMessage(content=m.content or ""))
        elif role == "tool":
            out.append(ToolMessage(content=m.content or "", tool_call_id=m.tool_call_id or ""))
        else:
            logger.warning("忽略未知角色 role=%s", m.role)
    return out


def _to_lc_tools(tools: list[ToolSpec]) -> list[dict]:
    return [
        {
            "type": "function",
            "function": {
                "name": t.name,
                "description": t.description,
                "parameters": t.parameters or {"type": "object", "properties": {}},
            },
        }
        for t in tools
    ]


def chat_once(req: ChatRequest) -> ChatResponse:
    """跑一轮。模型要么给出最终回答，要么发起工具调用，二选一。"""
    llm: BaseChatModel = get_llm()
    if req.tools:
        lc_tools = _to_lc_tools(req.tools)
        if req.force_tool:
            llm = llm.bind_tools(lc_tools, tool_choice="required")
        else:
            llm = llm.bind_tools(lc_tools)

    msg = llm.invoke(_to_lc_messages(req.messages))

    tool_calls = []
    for tc in getattr(msg, "tool_calls", None) or []:
        tool_calls.append({
            "id": tc.get("id") or "",
            "name": tc.get("name") or "",
            # LangChain 已经把 args 解析成 dict，这里转回 JSON 字符串交给 Java 统一处理
            "arguments": tc.get("args") or {},
        })

    content = msg.content if isinstance(msg.content, str) else str(msg.content or "")
    finish = "tool_calls" if tool_calls else "stop"
    logger.info("对话一轮 tools=%s finish=%s", len(tool_calls), finish)

    return ChatResponse(content=content or None, tool_calls=tool_calls, finish_reason=finish)
