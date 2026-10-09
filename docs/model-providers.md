# Model providers

Petri has a provider-neutral model-client registry. Each provider configuration stores its own endpoint and defaults.

| Provider | Default base URL | Request API | Key policy |
| --- | --- | --- | --- |
| `OPENAI` | `https://api.openai.com/v1` | Responses | Required |
| `LITELLM` | `http://localhost:4000/v1` | Responses | Optional |
| `OLLAMA` | `http://localhost:11434/v1` | Chat Completions | Optional |
| `OPENAI_COMPATIBLE` | `http://localhost:8000/v1` | Chat Completions | Optional |

Petri appends `/responses` or `/chat/completions` to the configured base URL. Include the server's API prefix, usually `/v1`, but not the final resource path.

## Ollama

Configure:

```text
Provider: OLLAMA
Base URL: http://localhost:11434/v1
Model ID: <installed Ollama model>
API key: empty
```

Petri uses Chat Completions for Ollama. Every continuation contains the complete persisted user/assistant/tool history, so it does not depend on provider-side response state.

If Petri is containerized, replace `localhost` with a hostname reachable from the Petri container.

## LiteLLM

Configure the LiteLLM proxy URL ending in `/v1` and use a configured model alias as the model ID. Add a LiteLLM virtual key when proxy authentication is enabled.

LiteLLM uses the Responses request format, including response IDs for tool-result continuations.

## Generic compatible endpoint

Use `OPENAI_COMPATIBLE` for servers implementing `/v1/chat/completions`. Petri sends conservative fields for broad compatibility:

- model
- system and conversation messages
- `max_tokens`
- function tool definitions
- assistant tool calls and tool results

Provider-specific reasoning and verbosity fields are omitted from generic Chat Completions requests.

## Generation settings

- **Model ID** is passed unchanged to the provider.
- **Maximum output tokens** must be between 16 and 1,000,000.
- **Context window tokens** defaults to 32,768. It must retain at least 2,048 input tokens after
  the output budget and Petri's 15% safety reserve.
- **Reasoning effort** is optional and model-dependent.
- **Text verbosity** is optional and mainly relevant to Responses-compatible models.

The chat can override these defaults for one task. The selected provider, model and generation values are stored on the run so later default changes do not alter its model identity or generation limits. Endpoint URLs and secrets remain provider-level configuration and are resolved when a request executes; changing them therefore affects resumed runs for that provider.

## Streaming compatibility

Petri attempts provider streaming first. Responses-compatible providers consume text deltas and the
completed response event; Chat Completions providers consume content/tool-call deltas and finish
events. If an endpoint explicitly rejects streaming before producing a delta, Petri retries that
request once without streaming. It never retries after text has reached the browser.

## Endpoint security

Base URLs must be HTTP(S) URLs without embedded credentials, query strings or fragments. API keys are sent as bearer tokens only when configured. Use HTTP only for trusted local/container networks; use HTTPS across untrusted networks.
