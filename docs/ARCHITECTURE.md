# Architecture

```text
ChatGPT Android app
       |
       | Accessibility events / visible node tree
       v
ConversationAccessibilityService
       |
       v
ConversationExtractor
       |
       v
CaptureStore
  |       |
  |       +--> SHA-256 dedupe / update timestamps
  |
  +--> sessions/*.json
       |
       +--> export Markdown
       +--> export JSON
```

## Modes

### New conversation

1. Start a new session in Context Keeper.
2. Enable recording.
3. Use ChatGPT normally.
4. Scroll as needed; capture is triggered on content/scroll changes.

### Existing conversation

1. Open the old conversation.
2. Scroll to its first message manually.
3. Enable recording.
4. Scroll through the conversation. Each visible segment is hashed and stored once.

## Dedupe

The prototype uses `SHA-256(role + NUL + normalizedText)`. Re-seeing the exact same segment updates its `lastSeenAt` rather than creating another copy.

## Next implementation phase

- Add an in-app floating overlay with Start / Pause / Stop.
- Track message bounds so a large assistant reply is treated as one message instead of several text nodes.
- Detect streaming replies and replace the previous partial version instead of keeping multiple revisions.
- Persist conversation title/app package and a local export manifest.
- Add GitHub sync via a user-provided token or GitHub App/Device Flow, storing secrets in Android Keystore rather than source code.
- Add an integration test harness with synthetic Accessibility trees.
