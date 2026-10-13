"""Model-agnostic chat interface. Agents and the loop only see these types, so a different
provider can be added without touching them (spec §4)."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol


@dataclass(frozen=True)
class ToolCall:
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_ms: float = 0.0
    load_ms: float = 0.0
    prompt_eval_ms: float = 0.0
    eval_ms: float = 0.0


@dataclass(frozen=True)
class ModelResponse:
    content: str
    tool_calls: list[ToolCall]
    thinking: str = ""
    done_reason: str = "stop"
    usage: Usage = field(default_factory=Usage)
    model: str = ""
    latency_ms: float = 0.0
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass
class ChatRequest:
    """messages use the Ollama shape: {"role", "content", ["tool_calls"], ["tool_name"]}."""
    messages: list[dict[str, Any]]
    tools: list[dict[str, Any]] | None = None
    format: dict[str, Any] | None = None
    temperature: float = 0.2
    think: bool = False
    num_predict: int | None = None


class ModelError(Exception):
    def __init__(self, message: str, retryable: bool, status: int | None = None):
        super().__init__(message)
        self.retryable = retryable
        self.status = status


class ModelAdapter(Protocol):
    @property
    def model_id(self) -> str: ...

    async def chat(self, request: ChatRequest) -> ModelResponse: ...


def assistant_message(response: ModelResponse) -> dict[str, Any]:
    """The assistant turn to append to the conversation after a response."""
    msg: dict[str, Any] = {"role": "assistant", "content": response.content}
    if response.tool_calls:
        msg["tool_calls"] = [{"type": "function", "function": {"name": c.name, "arguments": c.arguments}}
                             for c in response.tool_calls]
    return msg


def tool_message(tool_name: str, content: str) -> dict[str, Any]:
    return {"role": "tool", "tool_name": tool_name, "content": content}
