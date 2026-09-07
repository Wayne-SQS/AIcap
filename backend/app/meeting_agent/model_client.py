"""Small, explicit Chat Completions transport; no vendor SDK dependency."""
from urllib.parse import urlparse
import httpx
from .. import config
from .schemas import AgentError


def settings_ready():
    return bool(config.AICAP_LLM_API_KEY and config.AICAP_LLM_MODEL and config.AICAP_LLM_BASE_URL)


class ModelClient:
    def __init__(self, model=None, *, transport=None):
        self.model = model or config.AICAP_LLM_MODEL
        self.transport = transport

    def complete(self, messages, tools, *, final=False):
        if not settings_ready():
            raise AgentError("not_configured", "服务端尚未配置模型密钥/模型名称")
        base = config.AICAP_LLM_BASE_URL.rstrip('/')
        parsed = urlparse(base)
        if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('localhost', '127.0.0.1')):
            raise AgentError("invalid_config", "模型地址需要 HTTPS（本机测试服务除外）")
        body = {"model": self.model, "messages": messages,
                "stream": False, "max_tokens": 6000}
        # DeepSeek's current default is thinking mode; this loop uses non-thinking responses.
        if parsed.hostname == 'api.deepseek.com':
            body['thinking'] = {"type": "disabled"}
        if final:
            body['response_format'] = {"type": "json_object"}
        else:
            body.update(tools=tools, tool_choice="auto")
        try:
            with httpx.Client(timeout=config.AICAP_LLM_TIMEOUT, follow_redirects=False, transport=self.transport) as client:
                response = client.post(base + '/chat/completions', json=body,
                                       headers={"Authorization": "Bearer " + config.AICAP_LLM_API_KEY})
            if response.status_code != 200:
                # Never persist upstream bodies/headers; they may echo credentials or private text.
                raise AgentError('provider_error', f'模型服务返回 HTTP {response.status_code}，请检查配置或稍后重试')
            data = response.json()
            choice = data['choices'][0]
            message = choice['message']
            if choice.get('finish_reason') not in ('stop', 'tool_calls'):
                raise AgentError('incomplete_response', '模型输出被截断或中断，未生成任何建议')
            calls = message.get('tool_calls') or []
            if not isinstance(calls, list) or len(calls) > 8:
                raise ValueError('invalid tool calls')
            for call in calls:
                if (not isinstance(call.get('id'), str) or call.get('type') != 'function'
                        or not isinstance(call.get('function', {}).get('name'), str)
                        or not isinstance(call['function'].get('arguments'), str)):
                    raise ValueError('invalid tool call')
            content = message.get('content')
            if content is not None and not isinstance(content, str):
                raise ValueError('invalid content')
            if final and calls:
                raise ValueError('tools in final response')
            usage = data.get('usage') or {}
            return ({'role': 'assistant', 'content': content, **({'tool_calls': calls} if calls else {})},
                    {k: v for k, v in usage.items() if k in ('prompt_tokens', 'completion_tokens', 'total_tokens')
                     and isinstance(v, int)})
        except httpx.TimeoutException:
            raise AgentError('provider_timeout', '模型请求超时，可重试；未自动写入需求池')
        except httpx.HTTPError:
            raise AgentError('provider_unreachable', '无法连接模型服务，请检查服务端网络')
        except (ValueError, KeyError, IndexError, TypeError, AttributeError):
            raise AgentError('invalid_response', '模型服务返回了无法解析的响应')
