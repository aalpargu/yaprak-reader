import { createAssistantServer } from './assistant.mjs';

const port = Number(process.env.PORT ?? 8787);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid listening port');
createAssistantServer({ apiKey: process.env.OPENAI_API_KEY, model: process.env.OPENAI_MODEL,
  accessToken: process.env.YAPRAK_ACCESS_TOKEN }).listen(port, '127.0.0.1');
