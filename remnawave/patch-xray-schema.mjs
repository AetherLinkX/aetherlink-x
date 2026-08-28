import { readFileSync, writeFileSync } from 'node:fs';

const alxSettings = {
  title: 'AetherLink X inbound settings',
  description: 'Static AetherLink X users and protocol tuning.',
  type: 'object',
  required: ['users', 'security'],
  properties: {
    users: {
      type: 'array',
      minItems: 1,
      items: {
        type: 'object',
        required: ['id', 'secret'],
        properties: {
          id: { type: 'string' },
          secret: { type: 'string' },
          email: { type: 'string' },
          level: { type: 'integer', minimum: 0 },
        },
        additionalProperties: false,
      },
    },
    clients: { type: 'array' },
    allowInsecureTransport: { type: 'boolean' },
    handshakeTimeoutSeconds: { type: 'integer', minimum: 0, maximum: 60 },
    turbo: {
      type: 'object',
      properties: {
        enabled: { type: 'boolean' },
        maxDatagramAgeMs: { type: 'integer', minimum: 0, maximum: 65535 },
        destinationCacheSize: { type: 'integer', minimum: 0, maximum: 1024 },
        maxUdpPayload: { type: 'integer', minimum: 512, maximum: 32768 },
        tcpKeepAliveIdle: { type: 'integer', minimum: 0 },
        tcpKeepAliveInterval: { type: 'integer', minimum: 0 },
        tcpUserTimeout: { type: 'integer', minimum: 0 },
        congestion: { type: 'string' },
        multipathTcp: { type: 'boolean' },
        muxConcurrency: { type: 'integer', minimum: 0, maximum: 1024 },
        xudpConcurrency: { type: 'integer', minimum: 0, maximum: 1024 },
        xudpProxyUdp443: { enum: ['reject', 'allow', 'skip'] },
      },
      additionalProperties: false,
    },
    security: {
      type: 'object',
      properties: {
        pqMode: { enum: ['off', 'prefer', 'required'] },
        xwingPublicKey: { type: 'string' },
        xwingPrivateKey: { type: 'string' },
        innerAead: { type: 'boolean' },
      },
      additionalProperties: false,
    },
    stealth: {
      type: 'object',
      properties: {
        enabled: { type: 'boolean' },
        minChunkSize: { type: 'integer', minimum: 0 },
        maxChunkSize: { type: 'integer', minimum: 0 },
        maxPaddingBytes: { type: 'integer', minimum: 0, maximum: 4096 },
        paddingProbabilityPercent: { type: 'integer', minimum: 0, maximum: 100 },
      },
      additionalProperties: false,
    },
  },
  additionalProperties: false,
};

for (const file of process.argv.slice(2)) {
  const schema = JSON.parse(readFileSync(file, 'utf8'));
  const protocols = schema.definitions.InboundObject.properties.protocol.anyOf;
  if (!protocols.some((item) => item.const === 'aetherlinkx')) {
    protocols.push({ const: 'aetherlinkx' });
  }
  const settings = schema.definitions.InboundConfigurationObject.anyOf;
  if (!settings.some((item) => item.title === alxSettings.title)) {
    settings.push(alxSettings);
  }
  writeFileSync(file, `${JSON.stringify(schema)}\n`);
}
